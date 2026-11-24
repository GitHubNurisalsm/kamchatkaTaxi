package ru.taksi.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import ru.taksi.driver.Driver;
import ru.taksi.quote.PriceQuote;

import java.time.Instant;
import java.util.UUID;

/**
 * Заказ. Цена не хранится отдельно — она берётся из неизменяемого quote, на который ссылается заказ.
 * Менять статус можно только через {@link #apply}: он проверяет таблицу переходов {@link OrderAction}.
 */
@Entity
@Table(name = "ride_order")
public class RideOrder {

    @Id
    private UUID id;

    @Column(name = "passenger_id", nullable = false, updatable = false)
    private Long passengerId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quote_id", nullable = false, updatable = false)
    private PriceQuote quote;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "driver_id")
    private Driver driver;

    /** Км подачи по прогнозу на момент назначения — для расхода топлива (этап 5). */
    @Column(name = "pickup_km")
    private java.math.BigDecimal pickupKm;

    @Column(name = "cancelled_by")
    private String cancelledBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Version
    private long version;

    protected RideOrder() {
    }

    public RideOrder(UUID id, Long passengerId, PriceQuote quote, String idempotencyKey, Instant now) {
        this.id = id;
        this.passengerId = passengerId;
        this.quote = quote;
        this.idempotencyKey = idempotencyKey;
        this.status = OrderAction.CREATE.target();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Переход по машине состояний. Водитель задаётся только для ASSIGN, снимается при DECLINE и DRIVER_CANCEL,
     * а при отмене пассажиром остаётся в истории.
     *
     * @return статус до перехода
     */
    OrderStatus apply(OrderAction action, Driver assignedDriver, Instant now) {
        if (!action.allowedFrom(status)) {
            throw new IllegalStateException(action + " is not allowed from " + status);
        }
        OrderStatus before = status;
        status = action.target();
        updatedAt = now;
        switch (action) {
            case ASSIGN -> driver = assignedDriver;
            case DECLINE, DRIVER_CANCEL, OFFER_TIMEOUT -> {
                driver = null;
                pickupKm = null;
            }
            case COMPLETE -> completedAt = now;
            case PASSENGER_CANCEL -> {
                cancelledAt = now;
                cancelledBy = "PASSENGER";
            }
            default -> {
                // остальные переходы меняют только статус
            }
        }
        return before;
    }

    void recordPickup(java.math.BigDecimal km) {
        this.pickupKm = km;
    }

    public java.math.BigDecimal getPickupKm() {
        return pickupKm;
    }

    public boolean isAssignedTo(Long driverId) {
        return driver != null && driver.getId().equals(driverId);
    }

    public UUID getId() {
        return id;
    }

    public Long getPassengerId() {
        return passengerId;
    }

    public PriceQuote getQuote() {
        return quote;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Driver getDriver() {
        return driver;
    }

    public String getCancelledBy() {
        return cancelledBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }
}
