package ru.taksi.quote;

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
import ru.taksi.support.MutableClock;
import ru.taksi.support.TestClockConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Интеграционные тесты на реальной PostgreSQL (БД taxi_test): Flyway-схема, CHECK-ограничения,
 * запросы подсчёта пинов и машин, формат ошибок API.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class QuoteApiIntegrationTest {

    private static final Instant T0 = TestClockConfig.T0;

    // Точка А — центр Москвы; ячейка 0,01° и её соседи: lat [55.74; 55.77), lon [37.60; 37.63)
    private static final String ROUTE_ECONOM = """
            {"from":{"lat":55.7558,"lon":37.6173},"to":{"lat":55.7299,"lon":37.6036},"tariff":"ECONOM"}""";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;
    @Autowired
    MutableClock clock;

    @BeforeEach
    void cleanState() {
        clock.set(T0);
        ru.taksi.support.TestDb.clean(jdbc);
    }

    private void freeDriver(String tariff, double lat, double lon) {
        freeDriver("MOSCOW", tariff, lat, lon);
    }

    private void freeDriver(String city, String tariff, double lat, double lon) {
        jdbc.update("INSERT INTO driver (full_name, city_code, tariff_code, status, lat, lon) "
                + "VALUES ('Тест', ?, ?, 'FREE', ?, ?)", city, tariff, lat, lon);
    }

    private ResultActions postQuote(String body) throws Exception {
        return mvc.perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode createQuote(String body) throws Exception {
        String response = postQuote(body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private long pinCount() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM demand_pin", Long.class);
        return n == null ? 0 : n;
    }

    /** Happy path: цена считается по формуле из частей, которые вернул сам сервис, и сохраняется в БД. */
    @Test
    void createsQuoteWithConsistentBreakdown() throws Exception {
        freeDriver("ECONOM", 55.7560, 37.6170);
        freeDriver("ECONOM", 55.7610, 37.6090);

        String body = postQuote(ROUTE_ECONOM)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/quotes/")))
                .andExpect(jsonPath("$.surge.pins").value(1))
                .andExpect(jsonPath("$.surge.freeCars").value(2))
                .andExpect(jsonPath("$.surge.k").value(1.00))   // r = 0,5 → k = 1
                .andExpect(jsonPath("$.pickup.longPickup").value(false))
                .andExpect(jsonPath("$.breakdown.supply").value(189.00))
                .andExpect(jsonPath("$.breakdown.longPickupFee").value(0))
                .andExpect(jsonPath("$.city").value("MOSCOW"))
                .andExpect(jsonPath("$.currency").value("RUB"))
                .andExpect(jsonPath("$.expired").value(false))
                .andReturn().getResponse().getContentAsString();
        JsonNode q = json.readTree(body);

        BigDecimal t = q.get("durationMin").decimalValue();
        BigDecimal d = q.get("distanceKm").decimalValue();
        BigDecimal k = q.get("surge").get("k").decimalValue();
        BigDecimal expectedRide = k.multiply(new BigDecimal("13").multiply(t).add(new BigDecimal("13").multiply(d)))
                .setScale(2, RoundingMode.HALF_UP);
        assertThat(q.get("breakdown").get("ride").decimalValue()).isEqualByComparingTo(expectedRide);
        BigDecimal exact = new BigDecimal("189").add(expectedRide);
        assertThat(q.get("total").decimalValue()).isEqualByComparingTo(exact.setScale(0, RoundingMode.HALF_UP));

        // Маршрут ~2,9 км по прямой × 1,3 ≈ 3,8 км; время = D / 24 км/ч
        assertThat(d.doubleValue()).isBetween(3.5, 4.2);
        assertThat(t.doubleValue()).isCloseTo(d.doubleValue() / 24 * 60, org.assertj.core.api.Assertions.within(0.1));

        Integer saved = jdbc.queryForObject("SELECT count(*) FROM price_quote WHERE id = ?", Integer.class,
                UUID.fromString(q.get("id").asText()));
        assertThat(saved).isEqualTo(1);
    }

    /** Риск: спрос не растёт с числом пинов. 3 пина / 1 машина → r = 3 → k = 1,80. */
    @Test
    void surgeGrowsWithPinsInZone() throws Exception {
        freeDriver("ECONOM", 55.7560, 37.6170);

        createQuote(ROUTE_ECONOM);
        createQuote(ROUTE_ECONOM);
        JsonNode third = createQuote(ROUTE_ECONOM);

        assertThat(third.get("surge").get("pins").asLong()).isEqualTo(3);
        assertThat(third.get("surge").get("k").decimalValue()).isEqualByComparingTo("1.80");
    }

    /** Риск: старые пины продолжают поднимать цену. Пин старше окна (10 мин) не учитывается. */
    @Test
    void pinsOutsideWindowAreIgnored() throws Exception {
        freeDriver("ECONOM", 55.7560, 37.6170);
        jdbc.update("INSERT INTO demand_pin (cell_lat, cell_lon, created_at) VALUES (5575, 3761, ?)",
                Timestamp.from(T0.minus(Duration.ofMinutes(11))));
        jdbc.update("INSERT INTO demand_pin (cell_lat, cell_lon, created_at) VALUES (5575, 3761, ?)",
                Timestamp.from(T0.minus(Duration.ofMinutes(5))));

        JsonNode q = createQuote(ROUTE_ECONOM);

        assertThat(q.get("surge").get("pins").asLong()).isEqualTo(2);   // свежий + текущий
    }

    /** Риск: пины соседних зон не сглаживаются / далёкие зоны влияют на цену. */
    @Test
    void neighbourCellsCountButDistantCellsDoNot() throws Exception {
        freeDriver("ECONOM", 55.7560, 37.6170);
        jdbc.update("INSERT INTO demand_pin (cell_lat, cell_lon, created_at) VALUES (5576, 3762, ?)",
                Timestamp.from(T0));   // соседняя ячейка
        jdbc.update("INSERT INTO demand_pin (cell_lat, cell_lon, created_at) VALUES (5590, 3761, ?)",
                Timestamp.from(T0));   // далеко

        JsonNode q = createQuote(ROUTE_ECONOM);

        assertThat(q.get("surge").get("pins").asLong()).isEqualTo(2);
    }

    /** Граница: свободных машин нет → k = k_max, прогноза подачи нет. */
    @Test
    void noFreeCarsGivesMaxSurgeAndNoPickup() throws Exception {
        JsonNode q = createQuote(ROUTE_ECONOM);

        assertThat(q.get("surge").get("freeCars").asLong()).isZero();
        assertThat(q.get("surge").get("k").decimalValue()).isEqualByComparingTo("3.00");
        assertThat(q.get("pickup").isNull()).isTrue();
        assertThat(q.get("breakdown").get("longPickupFee").decimalValue()).isEqualByComparingTo("0");
    }

    /** Риск: водитель OFFLINE или другого тарифа учитывается в подаче. */
    @Test
    void pickupUsesOnlyFreeDriversOfRequestedTariff() throws Exception {
        freeDriver("COMFORT", 55.7560, 37.6170);
        jdbc.update("INSERT INTO driver (full_name, city_code, tariff_code, status, lat, lon) "
                + "VALUES ('Офлайн', 'MOSCOW', 'ECONOM', 'OFFLINE', 55.7559, 37.6172)");

        JsonNode q = createQuote(ROUTE_ECONOM);

        assertThat(q.get("pickup").isNull()).isTrue();
        assertThat(q.get("surge").get("freeCars").asLong()).isEqualTo(1);   // COMFORT-машина — часть предложения в зоне
    }

    /** Слайд 6: дальняя подача добавляет доплату тарифа (150 ₽). */
    @Test
    void longPickupAddsFee() throws Exception {
        freeDriver("ECONOM", 55.7900, 37.6173);   // ≈ 3,8 км по прямой → ≈ 4,9 км по дороге

        JsonNode q = createQuote(ROUTE_ECONOM);

        assertThat(q.get("pickup").get("longPickup").asBoolean()).isTrue();
        assertThat(q.get("breakdown").get("longPickupFee").decimalValue()).isEqualByComparingTo("150");
    }

    /** Опции: цена добавляется один раз, дубли схлопываются. */
    @Test
    void optionsAreAddedOnceEach() throws Exception {
        freeDriver("ECONOM", 55.7560, 37.6170);
        JsonNode q = createQuote("""
                {"from":{"lat":55.7558,"lon":37.6173},"to":{"lat":55.7299,"lon":37.6036},
                 "tariff":"ECONOM","options":["PET","CHILD_SEAT","CHILD_SEAT"]}""");

        assertThat(q.get("breakdown").get("options").decimalValue()).isEqualByComparingTo("300");
        assertThat(q.get("options").toString()).isEqualTo("[\"CHILD_SEAT\",\"PET\"]");
    }

    /** Отказ в запросе не должен оставлять пин (иначе мусорные запросы раздувают спрос). */
    @Test
    void unknownOptionIsRejectedWithoutPin() throws Exception {
        postQuote("""
                {"from":{"lat":55.7558,"lon":37.6173},"to":{"lat":55.7299,"lon":37.6036},
                 "tariff":"ECONOM","options":["JACUZZI"]}""")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNKNOWN_OPTION"));
        assertThat(pinCount()).isZero();
    }

    @Test
    void unknownTariffIsRejected() throws Exception {
        postQuote("""
                {"from":{"lat":55.7558,"lon":37.6173},"to":{"lat":55.7299,"lon":37.6036},"tariff":"SPACE"}""")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNKNOWN_TARIFF"));
        assertThat(pinCount()).isZero();
    }

    @Test
    void tooShortRouteIsRejected() throws Exception {
        postQuote("""
                {"from":{"lat":55.7558,"lon":37.6173},"to":{"lat":55.7558,"lon":37.6173},"tariff":"ECONOM"}""")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ROUTE_TOO_SHORT"));
        assertThat(pinCount()).isZero();
    }

    /** Междугородняя поездка (Москва → Санкт-Петербург): точка Б вне зоны города А. */
    @Test
    void intercityRouteIsRejected() throws Exception {
        postQuote("""
                {"from":{"lat":55.7558,"lon":37.6173},"to":{"lat":59.9343,"lon":30.3351},"tariff":"ECONOM"}""")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OUTSIDE_SERVICE_AREA"));
        assertThat(pinCount()).isZero();
    }

    @Test
    void invalidCoordinatesReturnValidationError() throws Exception {
        postQuote("""
                {"from":{"lat":91,"lon":37.6173},"to":{"lat":55.7299,"lon":37.6036},"tariff":"ECONOM"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields['from.lat']").exists());
    }

    @Test
    void malformedJsonReturnsBadRequest() throws Exception {
        postQuote("{not json").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    /** Цена фиксирована: повторное чтение возвращает те же числа, после TTL — expired = true. */
    @Test
    void quoteIsReadableAndExpires() throws Exception {
        JsonNode q = createQuote(ROUTE_ECONOM);
        String id = q.get("id").asText();

        mvc.perform(get("/api/v1/quotes/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(q.get("total").decimalValue()))
                .andExpect(jsonPath("$.expired").value(false));

        clock.advance(Duration.ofMinutes(5));
        mvc.perform(get("/api/v1/quotes/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expired").value(true));
    }

    @Test
    void unknownQuoteReturns404() throws Exception {
        mvc.perform(get("/api/v1/quotes/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUOTE_NOT_FOUND"));
    }

    @Test
    void invalidQuoteIdReturns400() throws Exception {
        mvc.perform(get("/api/v1/quotes/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void tariffCatalogListsSeededTariffs() throws Exception {
        mvc.perform(get("/api/v1/tariffs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tariffs[?(@.city=='MOSCOW' && @.code=='ECONOM')].pricePerMin").value(13.0))
                .andExpect(jsonPath("$.tariffs[?(@.city=='BISHKEK' && @.code=='ECONOM')].currency").value("KGS"))
                .andExpect(jsonPath("$.options.length()").value(4));
    }
}
