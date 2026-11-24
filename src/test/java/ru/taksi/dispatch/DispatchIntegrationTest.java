package ru.taksi.dispatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import ru.taksi.dispatch.DispatchService.DispatchReport;
import ru.taksi.dispatch.DispatchService.GroupReport;
import ru.taksi.order.OrderAction;
import ru.taksi.order.OrderService;
import ru.taksi.support.MutableClock;
import ru.taksi.support.TestClockConfig;
import ru.taksi.support.TestDb;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Этап 4 на реальной PostgreSQL: раунд подбора, оптимальность против жадного выбора,
 * фильтры пар (город, тариф, дальность, отказ) и таймаут предложения.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class DispatchIntegrationTest {

    // Бишкек, вдоль одной параллели: 1 км ≈ 0,012255° долготы на широте 42,87°
    private static final double LAT = 42.87;
    private static final double LON0 = 74.60;
    private static final double KM = 1 / (111.32 * Math.cos(Math.toRadians(LAT)));

    @Autowired
    DispatchService dispatch;
    @Autowired
    OrderService orders;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MutableClock clock;
    @Autowired
    MockMvc mvc;
    @Autowired
    ApplicationContext context;

    private int passengerSeq;

    @BeforeEach
    void setUp() {
        clock.set(TestClockConfig.T0);
        TestDb.clean(jdbc);
        passengerSeq = 0;
    }

    /** Заказ из точки (LAT, LON0 + kmFromOrigin км); часы сдвигаются, чтобы порядок создания был однозначным. */
    private UUID order(double kmFromOrigin) {
        return order("BISHKEK", "ECONOM", LAT, LON0 + kmFromOrigin * KM);
    }

    private UUID order(String city, String tariff, double lat, double lon) {
        clock.advance(Duration.ofSeconds(1));
        long passenger = TestDb.insertPassenger(jdbc, "+99655590000" + (passengerSeq++));
        UUID quote = TestDb.insertQuote(jdbc, city, tariff, lat, lon, clock.instant());
        return orders.createOrder(passenger, "k", quote).order().id();
    }

    private long driver(double kmFromOrigin) {
        return TestDb.insertDriver(jdbc, "BISHKEK", "ECONOM", "FREE", LAT, LON0 + kmFromOrigin * KM);
    }

    private Long assignedDriver(UUID order) {
        return jdbc.queryForObject("SELECT driver_id FROM ride_order WHERE id = ?", Long.class, order);
    }

    private String orderStatus(UUID order) {
        return jdbc.queryForObject("SELECT status FROM ride_order WHERE id = ?", String.class, order);
    }

    /**
     * Слайд 8 на карте. Водитель 1 в 0 км, водитель 2 в 2,5 км; заказ А в 1 км (создан первым), заказ Б в −1,5 км.
     * Жадно: А–1 (1 км) и Б–2 (4 км). Оптимально: А–2 (1,5 км) и Б–1 (1,5 км). Подача = км × 1,3 / 24 км/ч × 60.
     */
    @Test
    void assignsByMinimumTotalPickupNotNearestDriver() {
        long d1 = driver(0);
        long d2 = driver(2.5);
        UUID a = order(1);
        UUID b = order(-1.5);

        DispatchReport report = dispatch.runRound();

        assertThat(assignedDriver(a)).isEqualTo(d2);
        assertThat(assignedDriver(b)).isEqualTo(d1);
        GroupReport g = report.groups().get(0);
        assertThat(g.totalPickupMin()).isCloseTo(9.75, org.assertj.core.api.Assertions.within(0.2));         // 4,875 × 2
        assertThat(g.greedyTotalPickupMin()).isCloseTo(16.25, org.assertj.core.api.Assertions.within(0.2));  // 3,25 + 13
        assertThat(report.assigned()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM driver WHERE status = 'BUSY'", Long.class)).isEqualTo(2);
    }

    /**
     * Сильнее слайда: при жадном выборе заказ Б остался бы вовсе без водителя (водитель 2 дальше лимита 10 км),
     * а венгерский алгоритм обслуживает оба заказа.
     */
    @Test
    void servesMoreOrdersThanGreedy() {
        long d1 = driver(0);
        long d2 = driver(5);
        UUID a = order(2);
        UUID b = order(-3);   // до водителя 2: 8 км × 1,3 = 10,4 км > 10 км

        GroupReport g = dispatch.runRound().groups().get(0);

        assertThat(g.greedyMatches()).isEqualTo(1);
        assertThat(g.optimalMatches()).isEqualTo(2);
        assertThat(assignedDriver(a)).isEqualTo(d2);
        assertThat(assignedDriver(b)).isEqualTo(d1);
    }

    @Test
    void moreOrdersThanDriversLeavesRestSearching() {
        driver(0);
        UUID o1 = order(1);
        UUID o2 = order(4);
        UUID o3 = order(-6);

        DispatchReport report = dispatch.runRound();

        assertThat(report.assigned()).isEqualTo(1);
        assertThat(orderStatus(o1)).isEqualTo("DRIVER_ASSIGNED");   // ближайший (подача 1 км) — минимум суммы
        assertThat(orderStatus(o2)).isEqualTo("SEARCHING");
        assertThat(orderStatus(o3)).isEqualTo("SEARCHING");
    }

    /** Водитель другого города или тарифа не получает заказ, даже если стоит рядом. */
    @Test
    void respectsCityAndTariff() {
        TestDb.insertDriver(jdbc, "BISHKEK", "COMFORT", "FREE", LAT, LON0);
        TestDb.insertDriver(jdbc, "MOSCOW", "ECONOM", "FREE", LAT, LON0);   // ошибка данных: «московский» в Бишкеке
        UUID o = order(0.5);

        DispatchReport report = dispatch.runRound();

        assertThat(report.assigned()).isZero();
        assertThat(orderStatus(o)).isEqualTo("SEARCHING");
    }

    @Test
    void driverFartherThanMaxPickupIsNotAssigned() {
        driver(11);   // max-pickup-km = 10 (по дороге: 11 × 1,3 ≈ 14 км)
        UUID o = order(0);

        dispatch.runRound();

        assertThat(orderStatus(o)).isEqualTo("SEARCHING");
    }

    @Test
    void offlineAndBusyDriversAreIgnored() {
        TestDb.insertDriver(jdbc, "BISHKEK", "ECONOM", "OFFLINE", LAT, LON0);
        UUID o = order(0.5);

        dispatch.runRound();

        assertThat(orderStatus(o)).isEqualTo("SEARCHING");
    }

    /** Слайд 4: после отказа поиск повторяется, но тому же водителю заказ больше не предлагается. */
    @Test
    void declinedDriverIsNotOfferedSameOrderAgain() {
        long d1 = driver(0);
        UUID o = order(1);
        dispatch.runRound();
        assertThat(assignedDriver(o)).isEqualTo(d1);
        orders.driverAction(d1, o, OrderAction.DECLINE);

        dispatch.runRound();
        assertThat(orderStatus(o)).isEqualTo("SEARCHING");   // единственный водитель отказался

        long d2 = driver(3);
        dispatch.runRound();
        assertThat(assignedDriver(o)).isEqualTo(d2);
    }

    /** Водитель молчит дольше offer-timeout (60 с): заказ снова в поиске, водитель свободен. */
    @Test
    void unansweredOfferExpires() {
        long d1 = driver(0);
        UUID o = order(1);
        dispatch.runRound();
        assertThat(orderStatus(o)).isEqualTo("DRIVER_ASSIGNED");

        clock.advance(Duration.ofSeconds(59));
        assertThat(dispatch.runRound().offersExpired()).isZero();
        assertThat(orderStatus(o)).isEqualTo("DRIVER_ASSIGNED");

        clock.advance(Duration.ofSeconds(2));
        DispatchReport report = dispatch.runRound();

        assertThat(report.offersExpired()).isEqualTo(1);
        assertThat(orderStatus(o)).isEqualTo("SEARCHING");    // и тому же водителю не предложен снова
        assertThat(jdbc.queryForObject("SELECT status FROM driver WHERE id = ?", String.class, d1)).isEqualTo("FREE");
        assertThat(jdbc.queryForList("SELECT action FROM order_event WHERE order_id = ? ORDER BY id", String.class, o))
                .containsExactly("CREATE", "ASSIGN", "OFFER_TIMEOUT");
    }

    /** Гонка «водитель принял ↔ истёк таймаут»: принятое предложение не отзывается. */
    @Test
    void acceptedOfferIsNotExpired() {
        long d1 = driver(0);
        UUID o = order(1);
        dispatch.runRound();
        orders.driverAction(d1, o, OrderAction.ACCEPT);

        clock.advance(Duration.ofMinutes(5));
        assertThat(orders.expireOffer(o, clock.instant())).isFalse();
        assertThat(orderStatus(o)).isEqualTo("DRIVER_EN_ROUTE");
    }

    @Test
    void emptyRoundIsHarmless() {
        DispatchReport report = dispatch.runRound();
        assertThat(report.ordersConsidered()).isZero();
        assertThat(report.groups()).isEmpty();
    }

    @Test
    void schedulerIsDisabledInTests() {
        assertThat(context.getBeanNamesForType(DispatchScheduler.class)).isEmpty();
    }

    @Test
    void manualRunEndpoint() throws Exception {
        driver(0);
        order(1);
        mvc.perform(post("/api/v1/dispatch/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigned").value(1))
                .andExpect(jsonPath("$.groups[0].city").value("BISHKEK"))
                .andExpect(jsonPath("$.groups[0].assignments[0].pickupMin").isNumber());
    }
}
