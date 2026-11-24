package ru.taksi.quote;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.taksi.geo.GeoPoint;
import ru.taksi.pricing.PriceCalculator.PriceBreakdown;
import ru.taksi.pricing.RouteEstimator;
import ru.taksi.pricing.SurgeCalculator.SurgeResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Зафиксированное предложение цены. Неизменяемо после создания: заказ (этап 3)
 * будет ссылаться на quote и брать цену отсюда, а не пересчитывать её.
 */
@Entity
@Table(name = "price_quote")
public class PriceQuote {

    @Id
    private UUID id;

    @Column(name = "city_code", nullable = false, updatable = false)
    private String cityCode;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "tariff_code", nullable = false, updatable = false)
    private String tariffCode;

    @Column(name = "from_lat", nullable = false, updatable = false)
    private double fromLat;
    @Column(name = "from_lon", nullable = false, updatable = false)
    private double fromLon;
    @Column(name = "to_lat", nullable = false, updatable = false)
    private double toLat;
    @Column(name = "to_lon", nullable = false, updatable = false)
    private double toLon;

    @Column(name = "distance_km", nullable = false, updatable = false)
    private BigDecimal distanceKm;
    @Column(name = "duration_min", nullable = false, updatable = false)
    private BigDecimal durationMin;

    @Column(nullable = false, updatable = false)
    private int pins;
    @Column(name = "free_cars", nullable = false, updatable = false)
    private int freeCars;
    @Column(name = "surge_k", nullable = false, updatable = false)
    private BigDecimal surgeK;

    @Column(name = "pickup_km", updatable = false)
    private BigDecimal pickupKm;
    @Column(name = "pickup_min", updatable = false)
    private BigDecimal pickupMin;

    @Column(name = "supply_price", nullable = false, updatable = false)
    private BigDecimal supplyPrice;
    @Column(name = "long_pickup_fee", nullable = false, updatable = false)
    private BigDecimal longPickupFee;
    @Column(name = "ride_price", nullable = false, updatable = false)
    private BigDecimal ridePrice;
    @Column(name = "options_price", nullable = false, updatable = false)
    private BigDecimal optionsPrice;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "options", nullable = false, updatable = false, columnDefinition = "varchar(32)[]")
    private String[] options;

    @Column(name = "total_exact", nullable = false, updatable = false)
    private BigDecimal totalExact;
    @Column(nullable = false, updatable = false)
    private BigDecimal total;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    protected PriceQuote() {
    }

    @SuppressWarnings("java:S107")
    public PriceQuote(UUID id, String cityCode, String currency, String tariffCode, GeoPoint from, GeoPoint to, RouteEstimator.Estimate route,
                      SurgeResult surge, RouteEstimator.Estimate pickup, List<String> options,
                      PriceBreakdown price, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.cityCode = cityCode;
        this.currency = currency;
        this.tariffCode = tariffCode;
        this.fromLat = from.lat();
        this.fromLon = from.lon();
        this.toLat = to.lat();
        this.toLon = to.lon();
        this.distanceKm = route.distanceKm();
        this.durationMin = route.durationMin();
        this.pins = Math.toIntExact(surge.pins());
        this.freeCars = Math.toIntExact(surge.freeCars());
        this.surgeK = surge.k();
        this.pickupKm = pickup == null ? null : pickup.distanceKm();
        this.pickupMin = pickup == null ? null : pickup.durationMin();
        this.supplyPrice = price.supplyPrice();
        this.longPickupFee = price.longPickupFee();
        this.ridePrice = price.ridePrice();
        this.optionsPrice = price.optionsPrice();
        this.options = options.toArray(String[]::new);
        this.totalExact = price.totalExact();
        this.total = price.total();
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public UUID getId() {
        return id;
    }

    public String getCityCode() {
        return cityCode;
    }

    public String getCurrency() {
        return currency;
    }

    public String getTariffCode() {
        return tariffCode;
    }

    public GeoPoint getFrom() {
        return new GeoPoint(fromLat, fromLon);
    }

    public GeoPoint getTo() {
        return new GeoPoint(toLat, toLon);
    }

    public BigDecimal getDistanceKm() {
        return distanceKm;
    }

    public BigDecimal getDurationMin() {
        return durationMin;
    }

    public int getPins() {
        return pins;
    }

    public int getFreeCars() {
        return freeCars;
    }

    public BigDecimal getSurgeK() {
        return surgeK;
    }

    public BigDecimal getPickupKm() {
        return pickupKm;
    }

    public BigDecimal getPickupMin() {
        return pickupMin;
    }

    public BigDecimal getSupplyPrice() {
        return supplyPrice;
    }

    public BigDecimal getLongPickupFee() {
        return longPickupFee;
    }

    public BigDecimal getRidePrice() {
        return ridePrice;
    }

    public BigDecimal getOptionsPrice() {
        return optionsPrice;
    }

    public List<String> getOptions() {
        return List.of(options);
    }

    public BigDecimal getTotalExact() {
        return totalExact;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
