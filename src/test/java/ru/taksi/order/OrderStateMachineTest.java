package ru.taksi.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.taksi.order.OrderStatus.CANCELLED;
import static ru.taksi.order.OrderStatus.COMPLETED;
import static ru.taksi.order.OrderStatus.DRIVER_ARRIVED;
import static ru.taksi.order.OrderStatus.DRIVER_ASSIGNED;
import static ru.taksi.order.OrderStatus.DRIVER_EN_ROUTE;
import static ru.taksi.order.OrderStatus.IN_PROGRESS;
import static ru.taksi.order.OrderStatus.SEARCHING;

/**
 * Риск: в машину состояний «просочился» недопустимый переход (например, отмена идущей поездки
 * или завершение без начала). Таблица ниже — эталон из контракта этапа 3.
 */
class OrderStateMachineTest {

    private static final Map<OrderAction, Set<OrderStatus>> EXPECTED_FROM = Map.of(
            OrderAction.CREATE, EnumSet.noneOf(OrderStatus.class),
            OrderAction.ASSIGN, EnumSet.of(SEARCHING),
            OrderAction.ACCEPT, EnumSet.of(DRIVER_ASSIGNED),
            OrderAction.DECLINE, EnumSet.of(DRIVER_ASSIGNED),
            OrderAction.ARRIVE, EnumSet.of(DRIVER_EN_ROUTE),
            OrderAction.START, EnumSet.of(DRIVER_ARRIVED),
            OrderAction.COMPLETE, EnumSet.of(IN_PROGRESS),
            OrderAction.OFFER_TIMEOUT, EnumSet.of(DRIVER_ASSIGNED),
            OrderAction.DRIVER_CANCEL, EnumSet.of(DRIVER_EN_ROUTE, DRIVER_ARRIVED),
            OrderAction.PASSENGER_CANCEL, EnumSet.of(SEARCHING, DRIVER_ASSIGNED, DRIVER_EN_ROUTE, DRIVER_ARRIVED));

    @ParameterizedTest
    @EnumSource(OrderAction.class)
    void transitionTableMatchesContract(OrderAction action) {
        for (OrderStatus status : OrderStatus.values()) {
            assertThat(action.allowedFrom(status))
                    .as("%s from %s", action, status)
                    .isEqualTo(EXPECTED_FROM.get(action).contains(status));
        }
    }

    @Test
    void finalStatusesHaveNoExits() {
        for (OrderAction action : OrderAction.values()) {
            assertThat(action.allowedFrom(COMPLETED)).as(action.name()).isFalse();
            assertThat(action.allowedFrom(CANCELLED)).as(action.name()).isFalse();
        }
    }

    @Test
    void passengerCannotCancelRideInProgress() {
        assertThat(OrderAction.PASSENGER_CANCEL.allowedFrom(IN_PROGRESS)).isFalse();
    }

    @Test
    void declineAndDriverCancelReturnOrderToSearch() {
        assertThat(OrderAction.DECLINE.target()).isEqualTo(SEARCHING);
        assertThat(OrderAction.DRIVER_CANCEL.target()).isEqualTo(SEARCHING);
    }

    @Test
    void applyRejectsForbiddenTransition() {
        RideOrder order = new RideOrder(UUID.randomUUID(), 1L, null, "k", Instant.EPOCH);
        assertThatThrownBy(() -> order.apply(OrderAction.COMPLETE, null, Instant.EPOCH))
                .isInstanceOf(IllegalStateException.class);
        assertThat(order.getStatus()).isEqualTo(SEARCHING);
    }

    @Test
    void passengerCancelRecordsWhoAndWhen() {
        RideOrder order = new RideOrder(UUID.randomUUID(), 1L, null, "k", Instant.EPOCH);
        Instant at = Instant.parse("2026-11-24T10:00:00Z");
        OrderStatus before = order.apply(OrderAction.PASSENGER_CANCEL, null, at);

        assertThat(before).isEqualTo(SEARCHING);
        assertThat(order.getStatus()).isEqualTo(CANCELLED);
        assertThat(order.getCancelledAt()).isEqualTo(at);
        assertThat(order.getCancelledBy()).isEqualTo("PASSENGER");
    }

    @Test
    void activeStatusesMatchDatabaseIndexes() {
        // V4: ride_order_one_active_per_passenger / ..._per_driver используют эти же наборы
        assertThat(OrderStatus.ACTIVE).containsExactlyInAnyOrder(
                SEARCHING, DRIVER_ASSIGNED, DRIVER_EN_ROUTE, DRIVER_ARRIVED, IN_PROGRESS);
        assertThat(OrderStatus.WITH_DRIVER).containsExactlyInAnyOrder(
                DRIVER_ASSIGNED, DRIVER_EN_ROUTE, DRIVER_ARRIVED, IN_PROGRESS);
    }
}
