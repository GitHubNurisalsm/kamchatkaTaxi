package ru.taksi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.taksi.pricing.SurgeCalculator;

@Configuration
public class PricingConfig {

    @Bean
    public SurgeCalculator surgeCalculator(TaksiProperties properties) {
        return new SurgeCalculator(properties.surge().alpha(), properties.surge().kMax());
    }
}
