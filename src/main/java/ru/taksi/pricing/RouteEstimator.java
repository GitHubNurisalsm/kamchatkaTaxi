package ru.taksi.pricing;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import ru.taksi.api.DomainException;
import ru.taksi.config.TaksiProperties;
import ru.taksi.geo.GeoPoint;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Прогноз маршрута (слайд 11, наша модель): «расстояние / средняя скорость».
 * Дорожное расстояние = прямая × road-factor. Реальные карты не используются.
 */
@Component
public class RouteEstimator {

    private final TaksiProperties.Route props;

    public RouteEstimator(TaksiProperties properties) {
        this.props = properties.route();
    }

    /** Маршрут поездки A → Б с проверкой границ. */
    public Estimate estimateRide(GeoPoint from, GeoPoint to) {
        Estimate estimate = estimate(from, to);
        double km = estimate.distanceKm().doubleValue();
        if (km < props.minDistanceKm()) {
            throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "ROUTE_TOO_SHORT",
                    "Точки А и Б слишком близко друг к другу");
        }
        if (km > props.maxDistanceKm()) {
            throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "ROUTE_TOO_LONG",
                    "Маршрут длиннее " + props.maxDistanceKm() + " км");
        }
        return estimate;
    }

    /** Оценка пути без проверки границ — используется и для подачи. */
    public Estimate estimate(GeoPoint from, GeoPoint to) {
        double km = from.distanceKm(to) * props.roadFactor();
        double minutes = km / props.avgSpeedKmh() * 60.0;
        return new Estimate(
                BigDecimal.valueOf(km).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(minutes).setScale(1, RoundingMode.HALF_UP));
    }

    public record Estimate(BigDecimal distanceKm, BigDecimal durationMin) {
    }
}
