package ru.taksi.order;

import java.util.EnumSet;
import java.util.Set;

import static ru.taksi.order.OrderStatus.CANCELLED;
import static ru.taksi.order.OrderStatus.COMPLETED;
import static ru.taksi.order.OrderStatus.DRIVER_ARRIVED;
import static ru.taksi.order.OrderStatus.DRIVER_ASSIGNED;
import static ru.taksi.order.OrderStatus.DRIVER_EN_ROUTE;
import static ru.taksi.order.OrderStatus.IN_PROGRESS;
import static ru.taksi.order.OrderStatus.SEARCHING;

/**
 * Машина состояний заказа: каждое действие — кто его делает, из каких статусов и в какой статус.
 * Всё, чего нет в этой таблице, запрещено.
 */
public enum OrderAction {
    CREATE(Actor.PASSENGER, EnumSet.noneOf(OrderStatus.class), SEARCHING),
    ASSIGN(Actor.SYSTEM, EnumSet.of(SEARCHING), DRIVER_ASSIGNED),
    ACCEPT(Actor.DRIVER, EnumSet.of(DRIVER_ASSIGNED), DRIVER_EN_ROUTE),
    DECLINE(Actor.DRIVER, EnumSet.of(DRIVER_ASSIGNED), SEARCHING),
    ARRIVE(Actor.DRIVER, EnumSet.of(DRIVER_EN_ROUTE), DRIVER_ARRIVED),
    START(Actor.DRIVER, EnumSet.of(DRIVER_ARRIVED), IN_PROGRESS),
    COMPLETE(Actor.DRIVER, EnumSet.of(IN_PROGRESS), COMPLETED),
    /** Водитель не ответил на предложение вовремя — заказ снова ищет водителя (этап 4). */
    OFFER_TIMEOUT(Actor.SYSTEM, EnumSet.of(DRIVER_ASSIGNED), SEARCHING),
    /** Водитель отменил после принятия — заказ снова ищет водителя (слайд 4). */
    DRIVER_CANCEL(Actor.DRIVER, EnumSet.of(DRIVER_EN_ROUTE, DRIVER_ARRIVED), SEARCHING),
    /** Пассажир может отменить до начала поездки. */
    PASSENGER_CANCEL(Actor.PASSENGER,
            EnumSet.of(SEARCHING, DRIVER_ASSIGNED, DRIVER_EN_ROUTE, DRIVER_ARRIVED), CANCELLED);

    public enum Actor { PASSENGER, DRIVER, SYSTEM }

    private final Actor actor;
    private final Set<OrderStatus> from;
    private final OrderStatus to;

    OrderAction(Actor actor, Set<OrderStatus> from, OrderStatus to) {
        this.actor = actor;
        this.from = from;
        this.to = to;
    }

    public Actor actor() {
        return actor;
    }

    public OrderStatus target() {
        return to;
    }

    public boolean allowedFrom(OrderStatus status) {
        return from.contains(status);
    }

    /**
     * Повтор действия, которое уже привело заказ в целевой статус, — не ошибка (повторный запрос
     * после обрыва сети). Для отказов это не так: после DECLINE водитель уже не связан с заказом.
     */
    public boolean isRepeatSafe() {
        return this == ACCEPT || this == ARRIVE || this == START || this == COMPLETE || this == PASSENGER_CANCEL;
    }
}
