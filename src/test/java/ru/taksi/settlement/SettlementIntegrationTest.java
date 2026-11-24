package ru.taksi.settlement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import ru.taksi.order.OrderAction;
import ru.taksi.order.OrderService;
import ru.taksi.support.MutableClock;
import ru.taksi.support.TestClockConfig;
import ru.taksi.support.TestDb;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Этап 5 на реальной PostgreSQL: расчёт при завершении поездки, доступ, итоги, калькулятор, атомарность. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class SettlementIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;
    @Autowired
    OrderService orders;
    @Autowired
    MutableClock clock;

    long park;
    int seq;

    @BeforeEach
    void setUp() {
        clock.set(TestClockConfig.T0);
        TestDb.clean(jdbc);
        jdbc.update("DELETE FROM taxi_park WHERE name = 'Тест-парк'");
        park = jdbc.queryForObject(
                "INSERT INTO taxi_park (name, commission_rate) VALUES ('Тест-парк', 0.03) RETURNING id", Long.class);
        seq = 0;
    }

    @AfterEach
    void restoreRates() {
        jdbc.update("UPDATE tariff SET service_commission_rate = 0.1600 WHERE city_code = 'BISHKEK' AND code = 'ECONOM'");
    }

    private long driver(String city, double lat, double lon, Long parkId) {
        long id = TestDb.insertDriver(jdbc, city, "ECONOM", "FREE", lat, lon);
        jdbc.update("UPDATE driver SET park_id = ? WHERE id = ?", parkId, id);
        return id;
    }

    /** Заказ через настоящий quote (цена посчитана сервисом) и проведение до IN_PROGRESS. */
    private UUID rideInProgress(long driverId, String fromTo) throws Exception {
        long passenger = TestDb.insertPassenger(jdbc, "+99655580000" + (seq++));
        String body = mvc.perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON).content(fromTo))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID quote = UUID.fromString(json.readTree(body).get("id").asText());
        UUID order = orders.createOrder(passenger, "k", quote).order().id();
        orders.assignDriver(order, driverId);
        orders.driverAction(driverId, order, OrderAction.ACCEPT);
        orders.driverAction(driverId, order, OrderAction.ARRIVE);
        orders.driverAction(driverId, order, OrderAction.START);
        return order;
    }

    private static final String BISHKEK_RIDE = """
            {"from":{"lat":42.8765,"lon":74.6037},"to":{"lat":42.8559,"lon":74.5860},"tariff":"ECONOM"}""";
    private static final String MOSCOW_RIDE = """
            {"from":{"lat":55.7558,"lon":37.6173},"to":{"lat":55.7299,"lon":37.6036},"tariff":"ECONOM"}""";

    private JsonNode settlement(long driverId, UUID order) throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/driver/orders/" + order + "/settlement")
                        .header("X-Driver-Id", driverId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static BigDecimal item(JsonNode s, String name) {
        for (JsonNode i : s.get("items")) {
            if (i.get("name").asText().equals(name)) {
                return i.get("amount").decimalValue();
            }
        }
        return null;
    }

    /** Шаг 8 в Бишкеке: сервис 16%, парк 3%, подоходный 1%, взносы 1%, топливо по км подачи + поездки. */
    @Test
    void completedBishkekRideIsSettled() throws Exception {
        long d = driver("BISHKEK", 42.8800, 74.6037, park);   // ~0,39 км до точки А по прямой
        UUID order = rideInProgress(d, BISHKEK_RIDE);
        orders.driverAction(d, order, OrderAction.COMPLETE);

        JsonNode s = settlement(d, order);
        BigDecimal amount = s.get("amount").decimalValue();
        BigDecimal price = jdbc.queryForObject(
                "SELECT q.total FROM ride_order o JOIN price_quote q ON q.id = o.quote_id WHERE o.id = ?",
                BigDecimal.class, order);

        assertThat(s.get("currency").asText()).isEqualTo("KGS");
        assertThat(amount).isEqualByComparingTo(price);
        assertThat(item(s, "Сервис")).isEqualByComparingTo(amount.multiply(new BigDecimal("0.16")).setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(item(s, "Таксопарк")).isEqualByComparingTo(amount.multiply(new BigDecimal("0.03")).setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(item(s, "Подоходный налог")).isEqualByComparingTo(amount.multiply(new BigDecimal("0.01")).setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(item(s, "Страховые взносы")).isNotNull();

        BigDecimal sum = s.get("driverNet").decimalValue();
        for (JsonNode i : s.get("items")) {
            sum = sum.add(i.get("amount").decimalValue());
        }
        assertThat(sum).isEqualByComparingTo(amount);   // все деньги разложены, копейка в копейку

        BigDecimal fuelKm = jdbc.queryForObject("SELECT fuel_km FROM ride_settlement WHERE order_id = ?", BigDecimal.class, order);
        BigDecimal expectedKm = jdbc.queryForObject(
                "SELECT q.distance_km + o.pickup_km FROM ride_order o JOIN price_quote q ON q.id = o.quote_id WHERE o.id = ?",
                BigDecimal.class, order);
        assertThat(fuelKm).isEqualByComparingTo(expectedKm);
        assertThat(jdbc.queryForObject("SELECT pickup_km FROM ride_order WHERE id = ?", BigDecimal.class, order))
                .isGreaterThan(BigDecimal.ZERO);
    }

    /** Москва: НПД 4%, взносов нет — строки «Страховые взносы» в детализации нет. Без парка ставка 0. */
    @Test
    void moscowRideWithoutPark() throws Exception {
        long d = driver("MOSCOW", 55.7560, 37.6170, null);
        UUID order = rideInProgress(d, MOSCOW_RIDE);
        orders.driverAction(d, order, OrderAction.COMPLETE);

        JsonNode s = settlement(d, order);
        assertThat(s.get("currency").asText()).isEqualTo("RUB");
        assertThat(item(s, "НПД")).isEqualByComparingTo(
                s.get("amount").decimalValue().multiply(new BigDecimal("0.04")).setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(item(s, "Страховые взносы")).isNull();
        assertThat(item(s, "Таксопарк")).isEqualByComparingTo("0");
    }

    /** Ставки копируются в расчёт: смена комиссии потом не меняет историю. */
    @Test
    void laterRateChangeDoesNotAffectSettlement() throws Exception {
        long d = driver("BISHKEK", 42.8800, 74.6037, park);
        UUID order = rideInProgress(d, BISHKEK_RIDE);
        orders.driverAction(d, order, OrderAction.COMPLETE);
        BigDecimal before = item(settlement(d, order), "Сервис");

        jdbc.update("UPDATE tariff SET service_commission_rate = 0.5 WHERE city_code = 'BISHKEK' AND code = 'ECONOM'");

        assertThat(item(settlement(d, order), "Сервис")).isEqualByComparingTo(before);
    }

    /** Повтор «завершить» (обрыв сети) не создаёт второй расчёт. */
    @Test
    void repeatedCompleteKeepsOneSettlement() throws Exception {
        long d = driver("BISHKEK", 42.8800, 74.6037, park);
        UUID order = rideInProgress(d, BISHKEK_RIDE);
        orders.driverAction(d, order, OrderAction.COMPLETE);
        orders.driverAction(d, order, OrderAction.COMPLETE);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ride_settlement WHERE order_id = ?", Long.class, order))
                .isEqualTo(1);
    }

    /**
     * Атомарность: если расчёт невозможен (сумма ставок > 100%), поездка не завершается —
     * заказ остаётся IN_PROGRESS, водитель BUSY, расчёта нет. «Завершён без расчёта» не бывает.
     */
    @Test
    void failedSettlementRollsBackCompletion() throws Exception {
        long d = driver("BISHKEK", 42.8800, 74.6037, park);
        UUID order = rideInProgress(d, BISHKEK_RIDE);
        jdbc.update("UPDATE tariff SET service_commission_rate = 0.99 WHERE city_code = 'BISHKEK' AND code = 'ECONOM'");

        assertThatThrownBy(() -> orders.driverAction(d, order, OrderAction.COMPLETE))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(jdbc.queryForObject("SELECT status FROM ride_order WHERE id = ?", String.class, order))
                .isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT status FROM driver WHERE id = ?", String.class, d)).isEqualTo("BUSY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ride_settlement", Long.class)).isZero();
    }

    /** Детализацию видит только водитель поездки; незавершённая поездка расчёта не имеет. */
    @Test
    void settlementIsVisibleOnlyToItsDriver() throws Exception {
        long d = driver("BISHKEK", 42.8800, 74.6037, park);
        long other = driver("BISHKEK", 42.8700, 74.6000, park);
        UUID order = rideInProgress(d, BISHKEK_RIDE);

        mvc.perform(get("/api/v1/driver/orders/" + order + "/settlement").header("X-Driver-Id", d))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SETTLEMENT_NOT_FOUND"));

        orders.driverAction(d, order, OrderAction.COMPLETE);
        mvc.perform(get("/api/v1/driver/orders/" + order + "/settlement").header("X-Driver-Id", other))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/driver/orders/" + order + "/settlement"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void earningsSumsCompletedRidesInPeriod() throws Exception {
        long d = driver("BISHKEK", 42.8800, 74.6037, park);
        UUID o1 = rideInProgress(d, BISHKEK_RIDE);
        orders.driverAction(d, o1, OrderAction.COMPLETE);
        clock.advance(Duration.ofMinutes(10));
        UUID o2 = rideInProgress(d, BISHKEK_RIDE);
        orders.driverAction(d, o2, OrderAction.COMPLETE);

        BigDecimal net = jdbc.queryForObject("SELECT sum(driver_net) FROM ride_settlement", BigDecimal.class);
        mvc.perform(get("/api/v1/driver/earnings").header("X-Driver-Id", d)
                        .param("from", "2026-11-24T00:00:00Z").param("to", "2026-11-25T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.length()").value(1))
                .andExpect(jsonPath("$.totals[0].currency").value("KGS"))
                .andExpect(jsonPath("$.totals[0].rides").value(2))
                .andExpect(jsonPath("$.totals[0].driverNet").value(net.doubleValue()));

        mvc.perform(get("/api/v1/driver/earnings").header("X-Driver-Id", d)
                        .param("from", "2026-11-25T00:00:00Z").param("to", "2026-11-26T00:00:00Z"))
                .andExpect(jsonPath("$.totals.length()").value(0));
    }

    @Test
    void earningsPeriodIsBounded() throws Exception {
        long d = driver("BISHKEK", 42.8800, 74.6037, park);
        mvc.perform(get("/api/v1/driver/earnings").header("X-Driver-Id", d)
                        .param("from", "2026-01-01T00:00:00Z").param("to", "2026-11-25T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PERIOD"));
        mvc.perform(get("/api/v1/driver/earnings").header("X-Driver-Id", d)
                        .param("from", "2026-11-25T00:00:00Z").param("to", "2026-11-24T00:00:00Z"))
                .andExpect(status().isBadRequest());
    }

    /** Калькулятор воспроизводит слайд 10 по ставкам Москвы из БД. */
    @Test
    void previewReproducesSlide10() throws Exception {
        mvc.perform(get("/api/v1/settlements/preview").param("city", "MOSCOW").param("tariff", "ECONOM")
                        .param("price", "699").param("km", "13.5").param("parkRate", "0.03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.name=='Сервис')].amount").value(156.37))
                .andExpect(jsonPath("$.items[?(@.name=='Таксопарк')].amount").value(20.97))
                .andExpect(jsonPath("$.items[?(@.name=='НПД')].amount").value(27.96))
                .andExpect(jsonPath("$.items[?(@.name=='Топливо')].amount").value(70.00))
                .andExpect(jsonPath("$.driverNet").value(423.70))
                .andExpect(jsonPath("$.driverSharePercent").value(60.6));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ride_settlement", Long.class)).isZero();
    }

    @Test
    void previewValidation() throws Exception {
        mvc.perform(get("/api/v1/settlements/preview").param("city", "ATLANTIS").param("tariff", "ECONOM")
                        .param("price", "100").param("km", "1"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/settlements/preview").param("city", "BISHKEK").param("tariff", "SPACE")
                        .param("price", "100").param("km", "1"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/settlements/preview").param("city", "BISHKEK").param("tariff", "ECONOM")
                        .param("price", "-5").param("km", "1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/settlements/preview").param("city", "BISHKEK").param("tariff", "ECONOM")
                        .param("price", "100").param("km", "1").param("parkRate", "0.9"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_RATES"));
    }
}
