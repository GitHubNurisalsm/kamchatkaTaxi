package ru.taksi.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Запись журнала переходов заказа (аудит). Только добавляется, не изменяется. */
@Entity
@Table(name = "order_event")
public class OrderEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private OrderAction action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private OrderAction.Actor actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", updatable = false)
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false)
    private OrderStatus toStatus;

    @Column(name = "driver_id", updatable = false)
    private Long driverId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected OrderEvent() {
    }

    public OrderEvent(UUID orderId, OrderAction action, OrderStatus fromStatus, OrderStatus toStatus,
                      Long driverId, Instant createdAt) {
        this.orderId = orderId;
        this.action = action;
        this.actor = action.actor();
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.driverId = driverId;
        this.createdAt = createdAt;
    }

    public OrderAction getAction() {
        return action;
    }

    public OrderStatus getToStatus() {
        return toStatus;
    }

    public Long getDriverId() {
        return driverId;
    }
}
