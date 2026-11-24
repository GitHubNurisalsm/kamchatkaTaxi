package ru.taksi.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Параметры учебной модели. Проверяются при старте: неверный конфиг не даст запустить сервис.
 */
@Validated
@ConfigurationProperties(prefix = "taksi")
public record TaksiProperties(
        @Valid @NotNull Route route,
        @Valid @NotNull Surge surge,
        @Valid @NotNull Pickup pickup,
        @Valid @NotNull Quote quote,
        @Valid @NotNull Dispatch dispatch
) {

    public record Route(
            @Positive double avgSpeedKmh,
            @DecimalMin("1.0") double roadFactor,
            @Positive double minDistanceKm,
            @Positive double maxDistanceKm
    ) {
    }

    public record Surge(
            @NotNull @DecimalMin("0.0") BigDecimal alpha,
            @NotNull @DecimalMin("1.0") BigDecimal kMax,
            @NotNull Duration pinWindow,
            @Positive double cellSizeDeg,
            @Min(0) int neighborRadius,
            @NotNull Duration pinRetention
    ) {
    }

    public record Pickup(
            @Positive double searchRadiusKm,
            @Positive double longPickupMin,
            @Positive double longPickupKm
    ) {
    }

    public record Quote(@NotNull Duration ttl) {
    }

    /** Подбор водителя (слайд 8): буфер заказов и венгерский алгоритм. */
    public record Dispatch(
            boolean enabled,
            @NotNull Duration interval,
            @Min(1) int maxOrdersPerRound,
            @Min(1) int maxDriversPerGroup,
            @Positive double maxPickupKm,
            @NotNull Duration offerTimeout
    ) {
    }
}
