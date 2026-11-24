package ru.taksi.tariff;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@IdClass(CityScopedId.class)
@Table(name = "tariff")
public class Tariff {

    @Id
    @Column(name = "city_code")
    private String cityCode;

    @Id
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "supply_price", nullable = false)
    private BigDecimal supplyPrice;

    @Column(name = "price_per_min", nullable = false)
    private BigDecimal pricePerMin;

    @Column(name = "price_per_km", nullable = false)
    private BigDecimal pricePerKm;

    @Column(name = "long_pickup_fee", nullable = false)
    private BigDecimal longPickupFee;

    @Column(name = "service_commission_rate", nullable = false)
    private BigDecimal serviceCommissionRate;

    @Column(nullable = false)
    private boolean active;

    protected Tariff() {
    }

    public String getCityCode() {
        return cityCode;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getSupplyPrice() {
        return supplyPrice;
    }

    public BigDecimal getPricePerMin() {
        return pricePerMin;
    }

    public BigDecimal getPricePerKm() {
        return pricePerKm;
    }

    public BigDecimal getLongPickupFee() {
        return longPickupFee;
    }

    /** Комиссия сервиса с цены поездки (доля, 0.2237 = 22,37%). */
    public BigDecimal getServiceCommissionRate() {
        return serviceCommissionRate;
    }

    public boolean isActive() {
        return active;
    }
}
