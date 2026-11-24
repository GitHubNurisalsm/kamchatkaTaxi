package ru.taksi.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import ru.taksi.order.OrderService;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Регрессия (ревью этапа 6, находка 2): repeatable-миграция демо-данных перезапускается Flyway при каждом
 * изменении файла. Раньше она делала DELETE демо-водителей, на которых уже ссылаются заказы, — и приложение
 * с профилем demo переставало запускаться (нарушение внешнего ключа).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestClockConfig.class)
class DemoDataScriptTest {

    private static final ClassPathResource SCRIPT = new ClassPathResource("db/demo/R__demo_drivers.sql");

    @Autowired
    DataSource dataSource;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    OrderService orders;

    @BeforeEach
    void setUp() {
        TestDb.clean(jdbc);
    }

    private void runDemoScript() {
        new ResourceDatabasePopulator(SCRIPT).execute(dataSource);
    }

    private long demoDrivers() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM driver WHERE full_name LIKE 'Демо %'", Long.class);
        return n == null ? 0 : n;
    }

    @Test
    void rerunWithDemoDriverReferencedByOrdersSucceeds() {
        runDemoScript();
        long busyDriver = jdbc.queryForObject(
                "SELECT id FROM driver WHERE full_name = 'Демо Бишкек 1'", Long.class);

        // Демо-водитель получает заказ: на него ссылаются ride_order и order_event, он BUSY.
        long passenger = TestDb.insertPassenger(jdbc, "+996555700001");
        UUID quote = TestDb.insertQuote(jdbc, "BISHKEK", "ECONOM", 42.8765, 74.6037, TestClockConfig.T0);
        UUID order = orders.createOrder(passenger, "k", quote).order().id();
        orders.assignDriver(order, busyDriver);

        runDemoScript();   // как Flyway после изменения файла

        assertThat(demoDrivers()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT id FROM driver WHERE full_name = 'Демо Бишкек 1'", Long.class))
                .isEqualTo(busyDriver);                                    // тот же водитель, не пересоздан
        assertThat(jdbc.queryForObject("SELECT status FROM driver WHERE id = ?", String.class, busyDriver))
                .isEqualTo("BUSY");                                        // занятого водителя не «освободили»
        assertThat(jdbc.queryForObject("SELECT driver_id FROM ride_order WHERE id = ?", Long.class, order))
                .isEqualTo(busyDriver);
    }

    @Test
    void scriptIsIdempotent() {
        runDemoScript();
        runDemoScript();
        assertThat(demoDrivers()).isEqualTo(10);
        assertThat(jdbc.queryForObject(
                "SELECT count(DISTINCT full_name) FROM driver WHERE full_name LIKE 'Демо %'", Long.class)).isEqualTo(10);
    }

    /** Свободный демо-водитель, сдвинутый в ходе демонстрации, возвращается на исходную точку из файла. */
    @Test
    void freeDemoDriversAreResetToFilePositions() {
        runDemoScript();
        jdbc.update("UPDATE driver SET lat = 42.9, lon = 74.7 WHERE full_name = 'Демо Бишкек 2'");

        runDemoScript();

        assertThat(jdbc.queryForObject("SELECT lat FROM driver WHERE full_name = 'Демо Бишкек 2'", Double.class))
                .isEqualTo(42.8760);
    }
}
