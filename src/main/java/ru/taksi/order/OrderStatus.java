package ru.taksi.order;

import java.util.EnumSet;
import java.util.Set;

/** Статусы заказа (шаги 4–6 алгоритма, слайд 4). */
public enum OrderStatus {
    SEARCHING,
    DRIVER_ASSIGNED,
    DRIVER_EN_ROUTE,
    DRIVER_ARRIVED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED;

    /** Статусы, в которых заказ «живой» (должны совпадать с частичными индексами в V4). */
    public static final Set<OrderStatus> ACTIVE =
            EnumSet.of(SEARCHING, DRIVER_ASSIGNED, DRIVER_EN_ROUTE, DRIVER_ARRIVED, IN_PROGRESS);

    /** Статусы, в которых водитель занят этим заказом. */
    public static final Set<OrderStatus> WITH_DRIVER =
            EnumSet.of(DRIVER_ASSIGNED, DRIVER_EN_ROUTE, DRIVER_ARRIVED, IN_PROGRESS);

    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    public boolean holdsDriver() {
        return WITH_DRIVER.contains(this);
    }
}
