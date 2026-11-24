package ru.taksi.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;

/** Общие управляемые часы для всех интеграционных тестов (один кэшированный Spring-контекст). */
@TestConfiguration
public class TestClockConfig {

    public static final Instant T0 = Instant.parse("2026-11-24T09:00:00Z");

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock(T0);
    }
}
