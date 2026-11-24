package ru.taksi.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Регрессия (ревью этапа 6, находка 3): тесты очищают таблицы. Если по ошибке указать
 * TEST_DB_URL на рабочую базу, очистка должна отказаться работать, а не удалить данные.
 */
class TestDatabaseGuardTest {

    @Test
    void refusesNonTestDatabase() {
        assertThatThrownBy(() -> TestDb.requireTestDatabase("taxi"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("taxi");
        assertThatThrownBy(() -> TestDb.requireTestDatabase("taxi_test_backup"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TestDb.requireTestDatabase(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void allowsTestDatabase() {
        assertThatCode(() -> TestDb.requireTestDatabase("taxi_test")).doesNotThrowAnyException();
    }
}
