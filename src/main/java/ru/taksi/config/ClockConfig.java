package ru.taksi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /** Часы как зависимость — чтобы тесты могли управлять временем (TTL quote, окно пинов). */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
