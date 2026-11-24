package ru.taksi.city;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.taksi.geo.GeoPoint;

/**
 * Город и его зона обслуживания (прямоугольник). Зоны разных городов не пересекаются —
 * это гарантирует EXCLUDE-ограничение в БД.
 */
@Entity
@Table(name = "city")
public class City {

    @Id
    private String code;

    @Column(nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "min_lat", nullable = false)
    private double minLat;
    @Column(name = "max_lat", nullable = false)
    private double maxLat;
    @Column(name = "min_lon", nullable = false)
    private double minLon;
    @Column(name = "max_lon", nullable = false)
    private double maxLon;

    @Column(name = "income_tax_name", nullable = false)
    private String incomeTaxName;
    @Column(name = "income_tax_rate", nullable = false)
    private java.math.BigDecimal incomeTaxRate;
    @Column(name = "social_contribution_rate", nullable = false)
    private java.math.BigDecimal socialContributionRate;
    @Column(name = "fuel_cost_per_km", nullable = false)
    private java.math.BigDecimal fuelCostPerKm;

    @Column(nullable = false)
    private boolean active;

    protected City() {
    }

    public boolean contains(GeoPoint p) {
        return p.lat() >= minLat && p.lat() <= maxLat && p.lon() >= minLon && p.lon() <= maxLon;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getCurrency() {
        return currency;
    }

    public double getMinLat() {
        return minLat;
    }

    public double getMaxLat() {
        return maxLat;
    }

    public double getMinLon() {
        return minLon;
    }

    public double getMaxLon() {
        return maxLon;
    }

    public String getIncomeTaxName() {
        return incomeTaxName;
    }

    public java.math.BigDecimal getIncomeTaxRate() {
        return incomeTaxRate;
    }

    public java.math.BigDecimal getSocialContributionRate() {
        return socialContributionRate;
    }

    public java.math.BigDecimal getFuelCostPerKm() {
        return fuelCostPerKm;
    }

    public boolean isActive() {
        return active;
    }
}
