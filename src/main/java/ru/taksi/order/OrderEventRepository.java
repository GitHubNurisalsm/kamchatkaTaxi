package ru.taksi.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrderEventRepository extends JpaRepository<OrderEvent, Long> {

    List<OrderEvent> findAllByOrderIdOrderById(UUID orderId);

    /** Пары «заказ — водитель», которые больше не предлагаем: водитель отказался или пропустил предложение. */
    @org.springframework.data.jpa.repository.Query("""
            select e.orderId, e.driverId from OrderEvent e
            where e.orderId in :orderIds
              and e.action in (ru.taksi.order.OrderAction.DECLINE, ru.taksi.order.OrderAction.OFFER_TIMEOUT)
            """)
    List<Object[]> findRejectedPairs(@org.springframework.data.repository.query.Param("orderIds")
                                     java.util.Collection<UUID> orderIds);
}
