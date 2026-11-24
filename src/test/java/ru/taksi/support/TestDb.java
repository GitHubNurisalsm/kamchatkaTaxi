package ru.taksi.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Очистка изменяемых таблиц в порядке внешних ключей. Справочники (city, tariff, option) не трогаются. */
public final class TestDb {

    private TestDb() {
    }

    /**
     * Защита от потери данных: очистка работает только в базе, имя которой оканчивается на "_test".
     * Если TEST_DB_URL по ошибке указывает на рабочую базу, тест упадёт, а данные останутся целы.
     */
    static void requireTestDatabase(String databaseName) {
        if (databaseName == null || !databaseName.endsWith("_test")) {
            throw new IllegalStateException("Тесты очищают таблицы и запускаются только в базе *_test, а подключены к '"
                    + databaseName + "'. Проверьте TEST_DB_URL.");
        }
    }

    public static void clean(JdbcTemplate jdbc) {
        requireTestDatabase(jdbc.queryForObject("SELECT current_database()", String.class));
        jdbc.update("DELETE FROM ride_settlement");
        jdbc.update("DELETE FROM order_event");
        jdbc.update("DELETE FROM ride_order");
        jdbc.update("DELETE FROM passenger");
        jdbc.update("DELETE FROM price_quote");
        jdbc.update("DELETE FROM demand_pin");
        jdbc.update("DELETE FROM driver");
    }

    public static long insertPassenger(JdbcTemplate jdbc, String phone) {
        Long id = jdbc.queryForObject(
                "INSERT INTO passenger (full_name, phone, created_at) VALUES ('Тест', ?, now()) RETURNING id",
                Long.class, phone);
        return id == null ? -1 : id;
    }

    public static long insertDriver(JdbcTemplate jdbc, String city, String tariff, String status,
                                    double lat, double lon) {
        Long id = jdbc.queryForObject(
                "INSERT INTO driver (full_name, city_code, tariff_code, status, lat, lon) "
                        + "VALUES ('Тест Водитель', ?, ?, ?, ?, ?) RETURNING id",
                Long.class, city, tariff, status, lat, lon);
        return id == null ? -1 : id;
    }

    /** Предложение цены напрямую в БД: в тестах заказов и подбора важна точка А, а не расчёт цены. */
    public static UUID insertQuote(JdbcTemplate jdbc, String city, String tariff, double fromLat, double fromLon,
                                   Instant createdAt) {
        UUID id = UUID.randomUUID();
        String currency = city.equals("MOSCOW") ? "RUB" : "KGS";
        jdbc.update("""
                INSERT INTO price_quote (id, city_code, currency, tariff_code, from_lat, from_lon, to_lat, to_lon,
                    distance_km, duration_min, pins, free_cars, surge_k, supply_price, long_pickup_fee,
                    ride_price, options_price, options, total_exact, total, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 3.52, 8.8, 1, 1, 1.00, 48.00, 0.00, 67.76, 0.00, '{}', 115.76, 116, ?, ?)
                """, id, city, currency, tariff, fromLat, fromLon, fromLat - 0.02, fromLon - 0.02,
                Timestamp.from(createdAt), Timestamp.from(createdAt.plusSeconds(300)));
        return id;
    }
}
