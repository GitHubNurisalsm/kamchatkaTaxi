package ru.taksi.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import ru.taksi.api.DomainException;
import ru.taksi.support.TestClockConfig;
import ru.taksi.support.TestDb;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Настоящие параллельные транзакции (без @Transactional в тесте): проверяем, что блокировки строк
 * и ограничения БД не дают нарушить инварианты при одновременных запросах.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class OrderConcurrencyTest {

    @Autowired
    OrderService orders;
    @Autowired
    JdbcTemplate jdbc;

    long passenger;
    long driver;

    @BeforeEach
    void setUp() {
        TestDb.clean(jdbc);
        passenger = TestDb.insertPassenger(jdbc, "+996555000010");
        driver = TestDb.insertDriver(jdbc, "BISHKEK", "ECONOM", "FREE", 42.8760, 74.6040);
    }

    /** Quote вставляется напрямую: здесь важна не цена, а гонка вокруг заказа. */
    private UUID insertQuote() {
        UUID id = UUID.randomUUID();
        Instant now = TestClockConfig.T0;
        jdbc.update("""
                INSERT INTO price_quote (id, city_code, currency, tariff_code, from_lat, from_lon, to_lat, to_lon,
                    distance_km, duration_min, pins, free_cars, surge_k, supply_price, long_pickup_fee,
                    ride_price, options_price, options, total_exact, total, created_at, expires_at)
                VALUES (?, 'BISHKEK', 'KGS', 'ECONOM', 42.8765, 74.6037, 42.8559, 74.5860,
                    3.52, 8.8, 1, 1, 1.00, 48.00, 0.00, 67.76, 0.00, '{}', 115.76, 116, ?, ?)
                """, id, Timestamp.from(now), Timestamp.from(now.plusSeconds(300)));
        return id;
    }

    /** Запускает задачи одновременно (общий старт по защёлке) и собирает результат или исключение каждой. */
    private static List<Object> race(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> t : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return t.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long successes(List<Object> results) {
        return results.stream().filter(r -> !(r instanceof Exception)).count();
    }

    /** Двойное нажатие «Заказать»: 4 одновременных запроса с одним ключом → один заказ, все получают его id. */
    @RepeatedTest(3)
    void concurrentDuplicateSubmitCreatesOneOrder() throws Exception {
        UUID quote = insertQuote();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            tasks.add(() -> orders.createOrder(passenger, "double-click", quote));
        }

        List<Object> results = race(tasks);

        assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(OrderDtos.CreateResult.class));
        assertThat(results.stream().map(r -> ((OrderDtos.CreateResult) r).order().id()).distinct()).hasSize(1);
        assertThat(results.stream().filter(r -> ((OrderDtos.CreateResult) r).created())).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ride_order", Long.class)).isEqualTo(1);
    }

    /** Разные ключи, разные quote: у пассажира всё равно окажется не больше одного активного заказа. */
    @RepeatedTest(3)
    void concurrentDifferentOrdersKeepOneActivePerPassenger() throws Exception {
        UUID q1 = insertQuote();
        UUID q2 = insertQuote();

        List<Object> results = race(List.of(
                () -> orders.createOrder(passenger, "k1", q1),
                () -> orders.createOrder(passenger, "k2", q2)));

        assertThat(successes(results)).isEqualTo(1);
        Object failure = results.stream().filter(r -> r instanceof Exception).findFirst().orElseThrow();
        assertThat(failure).isInstanceOf(DomainException.class);
        assertThat(((DomainException) failure).code()).isEqualTo("ACTIVE_ORDER_EXISTS");
    }

    /**
     * Пассажир отменяет ровно в момент, когда водитель начинает поездку. Выигрывает один, и итог согласован:
     * либо CANCELLED и водитель FREE, либо IN_PROGRESS и водитель BUSY.
     */
    @RepeatedTest(5)
    void cancelVersusStartIsSerialized() throws Exception {
        UUID order = orders.createOrder(passenger, "k", insertQuote()).order().id();
        orders.assignDriver(order, driver);
        orders.driverAction(driver, order, OrderAction.ACCEPT);
        orders.driverAction(driver, order, OrderAction.ARRIVE);

        List<Object> results = race(List.of(
                () -> orders.cancelByPassenger(passenger, order),
                () -> orders.driverAction(driver, order, OrderAction.START)));

        assertThat(successes(results)).isEqualTo(1);
        String orderStatus = jdbc.queryForObject("SELECT status FROM ride_order WHERE id = ?", String.class, order);
        String driverStatus = jdbc.queryForObject("SELECT status FROM driver WHERE id = ?", String.class, driver);
        if (orderStatus.equals("CANCELLED")) {
            assertThat(driverStatus).isEqualTo("FREE");
        } else {
            assertThat(orderStatus).isEqualTo("IN_PROGRESS");
            assertThat(driverStatus).isEqualTo("BUSY");
        }
    }

    /** Один свободный водитель, два заказа, одновременное назначение: водитель получит только один. */
    @RepeatedTest(5)
    void sameDriverCannotBeAssignedTwiceConcurrently() throws Exception {
        long p2 = TestDb.insertPassenger(jdbc, "+996555000011");
        UUID o1 = orders.createOrder(passenger, "k1", insertQuote()).order().id();
        UUID o2 = orders.createOrder(p2, "k2", insertQuote()).order().id();

        List<Object> results = race(List.of(
                () -> orders.assignDriver(o1, driver),
                () -> orders.assignDriver(o2, driver)));

        assertThat(successes(results)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ride_order WHERE driver_id = ? AND status = 'DRIVER_ASSIGNED'",
                Long.class, driver)).isEqualTo(1);
    }

    /** Водитель уходит офлайн в момент назначения: либо он получил заказ (и остался BUSY), либо ушёл, а заказ ищет дальше. */
    @RepeatedTest(5)
    void offlineVersusAssignIsSerialized(@Autowired ru.taksi.driver.DriverService driverService) throws Exception {
        UUID order = orders.createOrder(passenger, "k", insertQuote()).order().id();

        race(List.of(
                () -> orders.assignDriver(order, driver),
                () -> driverService.goOffline(driver)));

        String orderStatus = jdbc.queryForObject("SELECT status FROM ride_order WHERE id = ?", String.class, order);
        String driverStatus = jdbc.queryForObject("SELECT status FROM driver WHERE id = ?", String.class, driver);
        if (orderStatus.equals("DRIVER_ASSIGNED")) {
            assertThat(driverStatus).isEqualTo("BUSY");
        } else {
            assertThat(orderStatus).isEqualTo("SEARCHING");
            assertThat(driverStatus).isEqualTo("OFFLINE");
        }
    }
}
