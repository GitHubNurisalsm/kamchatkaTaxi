package ru.taksi.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface RideOrderRepository extends JpaRepository<RideOrder, UUID> {

    Optional<RideOrder> findByPassengerIdAndIdempotencyKey(Long passengerId, String idempotencyKey);

    boolean existsByQuoteId(UUID quoteId);

    boolean existsByPassengerIdAndStatusIn(Long passengerId, Collection<OrderStatus> statuses);

    /** Заказ пассажира — с проверкой владельца прямо в запросе (защита от IDOR). */
    @Query("""
            select o from RideOrder o join fetch o.quote left join fetch o.driver
            where o.id = :id and o.passengerId = :passengerId
            """)
    Optional<RideOrder> findOwned(@Param("id") UUID id, @Param("passengerId") Long passengerId);

    @Query(value = """
            select o from RideOrder o join fetch o.quote left join fetch o.driver
            where o.passengerId = :passengerId
            order by o.createdAt desc, o.id
            """,
            countQuery = "select count(o) from RideOrder o where o.passengerId = :passengerId")
    Page<RideOrder> findPageByPassenger(@Param("passengerId") Long passengerId, Pageable pageable);

    /** Блокировка строки заказа на время перехода статуса: переходы одного заказа идут строго по очереди. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from RideOrder o where o.id = :id")
    Optional<RideOrder> findForUpdate(@Param("id") UUID id);

    @Query("""
            select o from RideOrder o join fetch o.quote join fetch o.driver d
            where d.id = :driverId and o.status in :statuses
            """)
    Optional<RideOrder> findActiveByDriver(@Param("driverId") Long driverId,
                                           @Param("statuses") Collection<OrderStatus> statuses);

    /** Буфер диспетчера: самые старые заказы в поиске, ограниченная пачка. */
    @Query("""
            select o from RideOrder o join fetch o.quote
            where o.status = ru.taksi.order.OrderStatus.SEARCHING
            order by o.createdAt, o.id
            """)
    java.util.List<RideOrder> findSearchingBatch(Pageable pageable);

    /** Предложения без ответа дольше таймаута (updated_at в DRIVER_ASSIGNED — момент назначения). */
    @Query("""
            select o.id from RideOrder o
            where o.status = ru.taksi.order.OrderStatus.DRIVER_ASSIGNED and o.updatedAt < :before
            order by o.updatedAt
            """)
    java.util.List<UUID> findExpiredOfferIds(@Param("before") java.time.Instant before, Pageable pageable);
}
