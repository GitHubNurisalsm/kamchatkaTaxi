package ru.taksi.settlement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.taksi.settlement.SettlementCalculator.Breakdown;
import ru.taksi.settlement.SettlementCalculator.Input;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Расчёт по завершённой поездке (шаг 8). Неизменяем; ставки — копия на момент завершения.
 */
@Entity
@Table(name = "ride_settlement")
public class RideSettlement {

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "driver_id", nullable = false, updatable = false)
    private Long driverId;
    @Column(name = "park_id", updatable = false)
    private Long parkId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "service_rate", nullable = false, updatable = false)
    private BigDecimal serviceRate;
    @Column(name = "service_fee", nullable = false, updatable = false)
    private BigDecimal serviceFee;

    @Column(name = "park_rate", nullable = false, updatable = false)
    private BigDecimal parkRate;
    @Column(name = "park_fee", nullable = false, updatable = false)
    private BigDecimal parkFee;

    @Column(name = "income_tax_name", nullable = false, updatable = false)
    private String incomeTaxName;
    @Column(name = "income_tax_rate", nullable = false, updatable = false)
    private BigDecimal incomeTaxRate;
    @Column(name = "income_tax", nullable = false, updatable = false)
    private BigDecimal incomeTax;

    @Column(name = "social_rate", nullable = false, updatable = false)
    private BigDecimal socialRate;
    @Column(name = "social_fee", nullable = false, updatable = false)
    private BigDecimal socialFee;

    @Column(name = "fuel_km", nullable = false, updatable = false)
    private BigDecimal fuelKm;
    @Column(name = "fuel_cost_per_km", nullable = false, updatable = false)
    private BigDecimal fuelCostPerKm;
    @Column(name = "fuel_cost", nullable = false, updatable = false)
    private BigDecimal fuelCost;

    @Column(name = "driver_net", nullable = false, updatable = false)
    private BigDecimal driverNet;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RideSettlement() {
    }

    public RideSettlement(UUID orderId, Long driverId, Long parkId, String currency, String incomeTaxName,
                          Input in, Breakdown b, Instant createdAt) {
        this.orderId = orderId;
        this.driverId = driverId;
        this.parkId = parkId;
        this.currency = currency;
        this.amount = b.amount();
        this.serviceRate = in.serviceRate();
        this.serviceFee = b.serviceFee();
        this.parkRate = in.parkRate();
        this.parkFee = b.parkFee();
        this.incomeTaxName = incomeTaxName;
        this.incomeTaxRate = in.incomeTaxRate();
        this.incomeTax = b.incomeTax();
        this.socialRate = in.socialRate();
        this.socialFee = b.socialFee();
        this.fuelKm = in.fuelKm();
        this.fuelCostPerKm = in.fuelCostPerKm();
        this.fuelCost = b.fuelCost();
        this.driverNet = b.driverNet();
        this.createdAt = createdAt;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public Long getDriverId() {
        return driverId;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getServiceRate() {
        return serviceRate;
    }

    public BigDecimal getServiceFee() {
        return serviceFee;
    }

    public BigDecimal getParkRate() {
        return parkRate;
    }

    public BigDecimal getParkFee() {
        return parkFee;
    }

    public String getIncomeTaxName() {
        return incomeTaxName;
    }

    public BigDecimal getIncomeTaxRate() {
        return incomeTaxRate;
    }

    public BigDecimal getIncomeTax() {
        return incomeTax;
    }

    public BigDecimal getSocialRate() {
        return socialRate;
    }

    public BigDecimal getSocialFee() {
        return socialFee;
    }

    public BigDecimal getFuelKm() {
        return fuelKm;
    }

    public BigDecimal getFuelCostPerKm() {
        return fuelCostPerKm;
    }

    public BigDecimal getFuelCost() {
        return fuelCost;
    }

    public BigDecimal getDriverNet() {
        return driverNet;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
