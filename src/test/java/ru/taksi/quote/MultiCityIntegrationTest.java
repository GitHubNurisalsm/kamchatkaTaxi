package ru.taksi.quote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Мультигородская модель: город по точке А, цены и валюта города, границы зоны обслуживания.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(ru.taksi.support.TestClockConfig.class)
class MultiCityIntegrationTest {

    // Бишкек: площадь Ала-Тоо → Asia Mall
    private static final String BISHKEK_ROUTE = """
            {"from":{"lat":42.8765,"lon":74.6037},"to":{"lat":42.8559,"lon":74.5860},"tariff":"ECONOM"}""";

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;

    @BeforeEach
    void cleanState() {
        ru.taksi.support.TestDb.clean(jdbc);
        jdbc.update("DELETE FROM city WHERE code NOT IN ('MOSCOW', 'BISHKEK')");
    }

    private void freeDriver(String city, String tariff, double lat, double lon) {
        jdbc.update("INSERT INTO driver (full_name, city_code, tariff_code, status, lat, lon) "
                + "VALUES ('Тест', ?, ?, 'FREE', ?, ?)", city, tariff, lat, lon);
    }

    private ResultActions postQuote(String body) throws Exception {
        return mvc.perform(post("/api/v1/quotes").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long pinCount() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM demand_pin", Long.class);
        return n == null ? 0 : n;
    }

    /** Цена в Бишкеке считается по тарифу Бишкека (48 сом + 3,7 сом/мин + 10 сом/км) и в сомах. */
    @Test
    void bishkekQuoteUsesBishkekTariffAndSom() throws Exception {
        freeDriver("BISHKEK", "ECONOM", 42.8760, 74.6040);
        freeDriver("BISHKEK", "ECONOM", 42.8770, 74.6050);

        String body = postQuote(BISHKEK_ROUTE)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.city").value("BISHKEK"))
                .andExpect(jsonPath("$.currency").value("KGS"))
                .andExpect(jsonPath("$.breakdown.supply").value(48.00))
                .andExpect(jsonPath("$.surge.k").value(1.00))
                .andReturn().getResponse().getContentAsString();
        JsonNode q = json.readTree(body);

        BigDecimal t = q.get("durationMin").decimalValue();
        BigDecimal d = q.get("distanceKm").decimalValue();
        BigDecimal expectedRide = new BigDecimal("3.70").multiply(t).add(BigDecimal.TEN.multiply(d))
                .setScale(2, RoundingMode.HALF_UP);
        assertThat(q.get("breakdown").get("ride").decimalValue()).isEqualByComparingTo(expectedRide);
        assertThat(q.get("total").decimalValue())
                .isEqualByComparingTo(new BigDecimal("48").add(expectedRide).setScale(0, RoundingMode.HALF_UP));

        String savedCurrency = jdbc.queryForObject("SELECT currency FROM price_quote", String.class);
        assertThat(savedCurrency).isEqualTo("KGS");
    }

    /** Один и тот же код ECONOM в разных городах — разные цены подачи. */
    @Test
    void sameTariffCodeHasCityPrices() throws Exception {
        postQuote(BISHKEK_ROUTE).andExpect(jsonPath("$.breakdown.supply").value(48.00));
        postQuote("""
                {"from":{"lat":55.7558,"lon":37.6173},"to":{"lat":55.7299,"lon":37.6036},"tariff":"ECONOM"}""")
                .andExpect(jsonPath("$.breakdown.supply").value(189.00))
                .andExpect(jsonPath("$.currency").value("RUB"));
    }

    /** Опции Бишкека — в сомах (50), а не московские 150 ₽. */
    @Test
    void bishkekOptionsUseCityPrices() throws Exception {
        postQuote("""
                {"from":{"lat":42.8765,"lon":74.6037},"to":{"lat":42.8559,"lon":74.5860},
                 "tariff":"ECONOM","options":["CHILD_SEAT"]}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.breakdown.options").value(50.00));
    }

    /** Водитель из другого города не участвует в прогнозе подачи. */
    @Test
    void pickupIgnoresDriversOfOtherCity() throws Exception {
        // Водитель с city_code = MOSCOW, но (по ошибке данных) стоит в Бишкеке
        freeDriver("MOSCOW", "ECONOM", 42.8766, 74.6038);

        postQuote(BISHKEK_ROUTE)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pickup").isEmpty());
    }

    /** Регрессия: точка А в Юго-Восточной Азии, Б — в Москве (запрос из Swagger пользователя). */
    @Test
    void pointOutsideAnyCityIsRejectedWithoutPin() throws Exception {
        postQuote("""
                {"from":{"lat":15.7558,"lon":97.6173},"to":{"lat":55.7299,"lon":37.6036},"tariff":"ECONOM"}""")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OUTSIDE_SERVICE_AREA"));
        assertThat(pinCount()).isZero();
    }

    @Test
    void destinationInOtherCityIsRejected() throws Exception {
        postQuote("""
                {"from":{"lat":42.8765,"lon":74.6037},"to":{"lat":55.7299,"lon":37.6036},"tariff":"ECONOM"}""")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OUTSIDE_SERVICE_AREA"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Бишкек")));
    }

    /** Неактивный город не обслуживается. */
    @Test
    void inactiveCityIsNotServed() throws Exception {
        try {
            jdbc.update("UPDATE city SET active = FALSE WHERE code = 'BISHKEK'");
            postQuote(BISHKEK_ROUTE)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("OUTSIDE_SERVICE_AREA"));
        } finally {
            jdbc.update("UPDATE city SET active = TRUE WHERE code = 'BISHKEK'");
        }
    }

    /** Инвариант БД: зоны городов не пересекаются, иначе город по точке определялся бы неоднозначно. */
    @Test
    void overlappingCityAreaIsRejectedByDatabase() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO city (code, name, currency, min_lat, max_lat, min_lon, max_lon) "
                        + "VALUES ('OVERLAP', 'Пересечение', 'KGS', 42.80, 42.90, 74.50, 74.60)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void citiesEndpointListsBishkekWithSom() throws Exception {
        mvc.perform(get("/api/v1/cities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='BISHKEK')].currency").value("KGS"))
                .andExpect(jsonPath("$[?(@.code=='BISHKEK')].name").value("Бишкек"));
    }

    @Test
    void tariffsCanBeFilteredByCity() throws Exception {
        mvc.perform(get("/api/v1/tariffs").param("city", "BISHKEK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tariffs.length()").value(2))
                .andExpect(jsonPath("$.tariffs[?(@.code=='ECONOM')].supplyPrice").value(48.0))
                .andExpect(jsonPath("$.tariffs[?(@.code=='ECONOM')].pricePerKm").value(10.0))
                .andExpect(jsonPath("$.tariffs[?(@.code=='ECONOM')].pricePerMin").value(3.7))
                .andExpect(jsonPath("$.options[0].currency").value("KGS"));
    }

    @Test
    void unknownCityInTariffFilterReturns404() throws Exception {
        mvc.perform(get("/api/v1/tariffs").param("city", "ATLANTIS"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CITY_NOT_FOUND"));
    }
}
