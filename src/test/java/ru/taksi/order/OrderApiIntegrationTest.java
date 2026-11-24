package ru.taksi.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.ResultActions;
import ru.taksi.api.DomainException;
import ru.taksi.support.MutableClock;
import ru.taksi.support.TestClockConfig;
import ru.taksi.support.TestDb;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Этап 3 на реальной PostgreSQL: заказ по quote, идемпотентность, права доступа, машина состояний,
 * согласованность статусов заказа и водителя.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class OrderApiIntegrationTest {

    // Бишкек: площадь Ала-Тоо → Asia Mall
    private static final double A_LAT = 42.8765;
    private static final double A_LON = 74.6037;
    private static final double B_LAT = 42.8559;
    private static final double B_LON = 74.5860;

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;
    @Autowired
    MutableClock clock;
    @Autowired
    OrderService orderService;

    long passenger;
    long otherPassenger;
    long driver;

    @BeforeEach
    void setUp() {
        clock.set(TestClockConfig.T0);
        TestDb.clean(jdbc);
        passenger = TestDb.insertPassenger(jdbc, "+996555000001");
        otherPassenger = TestDb.insertPassenger(jdbc, "+996555000002");
        driver = TestDb.insertDriver(jdbc, "BISHKEK", "ECONOM", "FREE", 42.8760, 74.6040);
    }

    // ------------------------------------------------------------------ helpers

    private String newQuote() throws Exception {
        String body = mvc.perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON).content("""
                        {"from":{"lat":%s,"lon":%s},"to":{"lat":%s,"lon":%s},"tariff":"ECONOM"}"""
                        .formatted(A_LAT, A_LON, B_LAT, B_LON)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asText();
    }

    private ResultActions postOrder(Long passengerId, String key, String quoteId) throws Exception {
        var req = post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"quoteId\":\"" + quoteId + "\"}");
        if (passengerId != null) {
            req.header("X-Passenger-Id", passengerId);
        }
        if (key != null) {
            req.header("Idempotency-Key", key);
        }
        return mvc.perform(req);
    }

    private UUID createOrder() throws Exception {
        String body = postOrder(passenger, UUID.randomUUID().toString(), newQuote())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asText());
    }

    private ResultActions driverAction(long driverId, UUID orderId, String action) throws Exception {
        return mvc.perform(post("/api/v1/driver/orders/" + orderId + "/" + action).header("X-Driver-Id", driverId));
    }

    private String orderStatus(UUID id) {
        return jdbc.queryForObject("SELECT status FROM ride_order WHERE id = ?", String.class, id);
    }

    private String driverStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM driver WHERE id = ?", String.class, id);
    }

    private List<String> eventActions(UUID orderId) {
        return jdbc.queryForList("SELECT action FROM order_event WHERE order_id = ? ORDER BY id", String.class, orderId);
    }

    private long orderCount() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM ride_order", Long.class);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ создание заказа

    /** Happy path шага 3: цена и валюта заказа — из quote, статус SEARCHING, событие CREATE в журнале. */
    @Test
    void createsOrderFromQuote() throws Exception {
        String quoteId = newQuote();
        JsonNode quote = json.readTree(mvc.perform(get("/api/v1/quotes/" + quoteId))
                .andReturn().getResponse().getContentAsString());

        String body = postOrder(passenger, "key-1", quoteId)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/orders/")))
                .andExpect(jsonPath("$.status").value("SEARCHING"))
                .andExpect(jsonPath("$.city").value("BISHKEK"))
                .andExpect(jsonPath("$.currency").value("KGS"))
                .andExpect(jsonPath("$.price").value(quote.get("total").decimalValue()))
                .andExpect(jsonPath("$.driver").isEmpty())
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(json.readTree(body).get("id").asText());
        assertThat(eventActions(id)).containsExactly("CREATE");
    }

    /** Повтор с тем же ключом (обрыв сети) не создаёт второй заказ, а возвращает первый с кодом 200. */
    @Test
    void repeatedRequestWithSameKeyReturnsSameOrder() throws Exception {
        String quoteId = newQuote();
        String first = postOrder(passenger, "key-1", quoteId).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = postOrder(passenger, "key-1", quoteId).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(second).get("id")).isEqualTo(json.readTree(first).get("id"));
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentQuoteIsConflict() throws Exception {
        postOrder(passenger, "key-1", newQuote()).andExpect(status().isCreated());
        postOrder(passenger, "key-1", newQuote())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    /** Один quote — один заказ: чужой пассажир не может «переиспользовать» зафиксированную цену. */
    @Test
    void quoteCannotBeUsedTwice() throws Exception {
        String quoteId = newQuote();
        postOrder(passenger, "key-1", quoteId).andExpect(status().isCreated());
        postOrder(otherPassenger, "key-2", quoteId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QUOTE_ALREADY_USED"));
    }

    @Test
    void expiredQuoteIsRejected() throws Exception {
        String quoteId = newQuote();
        clock.advance(Duration.ofMinutes(5));
        postOrder(passenger, "key-1", quoteId)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("QUOTE_EXPIRED"));
        assertThat(orderCount()).isZero();
    }

    @Test
    void unknownQuoteIsRejected() throws Exception {
        postOrder(passenger, "key-1", UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("QUOTE_NOT_FOUND"));
    }

    @Test
    void secondActiveOrderIsRejected() throws Exception {
        createOrder();
        postOrder(passenger, "key-2", newQuote())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_ORDER_EXISTS"));
    }

    @Test
    void afterCancelPassengerCanOrderAgain() throws Exception {
        UUID first = createOrder();
        mvc.perform(post("/api/v1/orders/" + first + "/cancel").header("X-Passenger-Id", passenger))
                .andExpect(status().isOk());
        postOrder(passenger, "key-2", newQuote()).andExpect(status().isCreated());
    }

    @Test
    void missingPassengerHeaderIs401() throws Exception {
        postOrder(null, "key-1", newQuote())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void unknownPassengerIs401() throws Exception {
        postOrder(999_999L, "key-1", newQuote())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNKNOWN_PASSENGER"));
    }

    @Test
    void missingIdempotencyKeyIs400() throws Exception {
        postOrder(passenger, null, newQuote())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
    }

    @Test
    void invalidIdempotencyKeyIs400() throws Exception {
        postOrder(passenger, "ключ с пробелами", newQuote())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    // ------------------------------------------------------------------ права доступа (IDOR)

    /** Чужой заказ — 404, а не 403: не раскрываем факт его существования. */
    @Test
    void passengerCannotSeeOrCancelForeignOrder() throws Exception {
        UUID id = createOrder();

        mvc.perform(get("/api/v1/orders/" + id).header("X-Passenger-Id", otherPassenger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        mvc.perform(post("/api/v1/orders/" + id + "/cancel").header("X-Passenger-Id", otherPassenger))
                .andExpect(status().isNotFound());

        assertThat(orderStatus(id)).isEqualTo("SEARCHING");
    }

    @Test
    void driverCannotActOnOrderNotAssignedToHim() throws Exception {
        UUID id = createOrder();
        orderService.assignDriver(id, driver);
        long stranger = TestDb.insertDriver(jdbc, "BISHKEK", "ECONOM", "FREE", 42.8770, 74.6050);

        driverAction(stranger, id, "accept").andExpect(status().isNotFound());
        assertThat(orderStatus(id)).isEqualTo("DRIVER_ASSIGNED");
    }

    @Test
    void historyContainsOnlyOwnOrdersAndIsBounded() throws Exception {
        UUID own = createOrder();
        mvc.perform(get("/api/v1/orders").header("X-Passenger-Id", passenger))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(own.toString()))
                .andExpect(jsonPath("$.totalItems").value(1));
        mvc.perform(get("/api/v1/orders").header("X-Passenger-Id", otherPassenger))
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/orders").param("size", "500").header("X-Passenger-Id", passenger))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));
    }

    // ------------------------------------------------------------------ жизненный цикл

    /** Шаги 4–6 целиком; водитель занят на время заказа и свободен в точке Б после завершения. */
    @Test
    void fullRideLifecycle() throws Exception {
        UUID id = createOrder();
        orderService.assignDriver(id, driver);
        assertThat(driverStatus(driver)).isEqualTo("BUSY");

        mvc.perform(get("/api/v1/driver/orders/current").header("X-Driver-Id", driver))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));

        driverAction(driver, id, "accept").andExpect(jsonPath("$.status").value("DRIVER_EN_ROUTE"));
        driverAction(driver, id, "arrive").andExpect(jsonPath("$.status").value("DRIVER_ARRIVED"));
        driverAction(driver, id, "start").andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        driverAction(driver, id, "complete")
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedAt").exists());

        assertThat(driverStatus(driver)).isEqualTo("FREE");
        Double lat = jdbc.queryForObject("SELECT lat FROM driver WHERE id = ?", Double.class, driver);
        assertThat(lat).isEqualTo(B_LAT);
        assertThat(eventActions(id)).containsExactly("CREATE", "ASSIGN", "ACCEPT", "ARRIVE", "START", "COMPLETE");

        mvc.perform(get("/api/v1/driver/orders/current").header("X-Driver-Id", driver))
                .andExpect(status().isNoContent());
    }

    /** Слайд 4: при отказе водителя поиск повторяется; водитель снова свободен и больше не видит заказ. */
    @Test
    void declineReturnsOrderToSearch() throws Exception {
        UUID id = createOrder();
        orderService.assignDriver(id, driver);

        driverAction(driver, id, "decline")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEARCHING"))
                .andExpect(jsonPath("$.driver").isEmpty());

        assertThat(driverStatus(driver)).isEqualTo("FREE");
        Long recorded = jdbc.queryForObject(
                "SELECT driver_id FROM order_event WHERE order_id = ? AND action = 'DECLINE'", Long.class, id);
        assertThat(recorded).isEqualTo(driver);   // этап 4: диспетчер не предложит заказ ему снова
        driverAction(driver, id, "accept").andExpect(status().isNotFound());
    }

    @Test
    void driverCancelAfterAcceptReturnsOrderToSearch() throws Exception {
        UUID id = createOrder();
        orderService.assignDriver(id, driver);
        driverAction(driver, id, "accept");

        driverAction(driver, id, "cancel").andExpect(jsonPath("$.status").value("SEARCHING"));
        assertThat(driverStatus(driver)).isEqualTo("FREE");
    }

    @Test
    void passengerCancelFreesAssignedDriver() throws Exception {
        UUID id = createOrder();
        orderService.assignDriver(id, driver);
        driverAction(driver, id, "accept");

        mvc.perform(post("/api/v1/orders/" + id + "/cancel").header("X-Passenger-Id", passenger))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledBy").value("PASSENGER"));
        assertThat(driverStatus(driver)).isEqualTo("FREE");
    }

    @Test
    void repeatedCancelIsIdempotent() throws Exception {
        UUID id = createOrder();
        mvc.perform(post("/api/v1/orders/" + id + "/cancel").header("X-Passenger-Id", passenger))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/orders/" + id + "/cancel").header("X-Passenger-Id", passenger))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(eventActions(id)).containsExactly("CREATE", "PASSENGER_CANCEL");
    }

    @Test
    void rideInProgressCannotBeCancelledByPassenger() throws Exception {
        UUID id = createOrder();
        orderService.assignDriver(id, driver);
        driverAction(driver, id, "accept");
        driverAction(driver, id, "arrive");
        driverAction(driver, id, "start");

        mvc.perform(post("/api/v1/orders/" + id + "/cancel").header("X-Passenger-Id", passenger))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
        assertThat(orderStatus(id)).isEqualTo("IN_PROGRESS");
        assertThat(driverStatus(driver)).isEqualTo("BUSY");
    }

    @Test
    void skippingStepIsRejected() throws Exception {
        UUID id = createOrder();
        orderService.assignDriver(id, driver);
        driverAction(driver, id, "accept");

        driverAction(driver, id, "start")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
    }

    /** Повтор «прибыл» (обрыв сети) — 200 без второго события в журнале. */
    @Test
    void repeatedArriveIsNoOp() throws Exception {
        UUID id = createOrder();
        orderService.assignDriver(id, driver);
        driverAction(driver, id, "accept");
        driverAction(driver, id, "arrive").andExpect(status().isOk());
        driverAction(driver, id, "arrive").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRIVER_ARRIVED"));

        assertThat(eventActions(id)).containsExactly("CREATE", "ASSIGN", "ACCEPT", "ARRIVE");
    }

    // ------------------------------------------------------------------ назначение (основа для этапа 4)

    @Test
    void busyDriverCannotGetSecondOrder() throws Exception {
        UUID first = createOrder();
        orderService.assignDriver(first, driver);

        long p2 = TestDb.insertPassenger(jdbc, "+996555000003");
        String body = postOrder(p2, "k", newQuote()).andReturn().getResponse().getContentAsString();
        UUID second = UUID.fromString(json.readTree(body).get("id").asText());

        assertThatThrownBy(() -> orderService.assignDriver(second, driver))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).code()).isEqualTo("DRIVER_NOT_AVAILABLE");
        assertThat(orderStatus(second)).isEqualTo("SEARCHING");
    }

    @Test
    void driverOfOtherCityOrTariffIsNotEligible() throws Exception {
        UUID id = createOrder();
        long comfort = TestDb.insertDriver(jdbc, "BISHKEK", "COMFORT", "FREE", 42.8760, 74.6040);
        long moscow = TestDb.insertDriver(jdbc, "MOSCOW", "ECONOM", "FREE", 55.7560, 37.6170);

        for (long d : new long[]{comfort, moscow}) {
            assertThatThrownBy(() -> orderService.assignDriver(id, d))
                    .isInstanceOf(DomainException.class)
                    .extracting(e -> ((DomainException) e).code()).isEqualTo("DRIVER_NOT_ELIGIBLE");
        }
    }

    // ------------------------------------------------------------------ линия водителя

    @Test
    void driverCannotGoOfflineWithActiveOrder() throws Exception {
        UUID id = createOrder();
        orderService.assignDriver(id, driver);

        mvc.perform(post("/api/v1/driver/offline").header("X-Driver-Id", driver))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRIVER_HAS_ACTIVE_ORDER"));
        assertThat(driverStatus(driver)).isEqualTo("BUSY");
    }

    @Test
    void driverGoesOnlineOnlyInsideOwnCity() throws Exception {
        mvc.perform(post("/api/v1/driver/offline").header("X-Driver-Id", driver))
                .andExpect(jsonPath("$.status").value("OFFLINE"));

        mvc.perform(post("/api/v1/driver/online").header("X-Driver-Id", driver)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lat\":55.7558,\"lon\":37.6173}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OUTSIDE_SERVICE_AREA"));
        assertThat(driverStatus(driver)).isEqualTo("OFFLINE");

        mvc.perform(post("/api/v1/driver/online").header("X-Driver-Id", driver)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lat\":42.8746,\"lon\":74.5698}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FREE"))
                .andExpect(jsonPath("$.lat").value(42.8746));
    }

    @Test
    void offlineDriverCannotSendLocation() throws Exception {
        mvc.perform(post("/api/v1/driver/offline").header("X-Driver-Id", driver));
        mvc.perform(put("/api/v1/driver/location").header("X-Driver-Id", driver)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"lat\":42.87,\"lon\":74.60}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRIVER_OFFLINE"));
    }

    @Test
    void unknownDriverIs401() throws Exception {
        mvc.perform(get("/api/v1/driver/me").header("X-Driver-Id", 999_999L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNKNOWN_DRIVER"));
        mvc.perform(get("/api/v1/driver/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // ------------------------------------------------------------------ регистрация

    @Test
    void passengerRegistration() throws Exception {
        mvc.perform(post("/api/v1/passengers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Айгуль\",\"phone\":\"+996555123456\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber());
        mvc.perform(post("/api/v1/passengers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Другая\",\"phone\":\"+996555123456\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PHONE_TAKEN"));
        mvc.perform(post("/api/v1/passengers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"X\",\"phone\":\"0555123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
