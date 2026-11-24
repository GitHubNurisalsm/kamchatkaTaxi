package ru.taksi.settlement;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RideSettlementRepository extends JpaRepository<RideSettlement, UUID> {

    Optional<RideSettlement> findByOrderIdAndDriverId(UUID orderId, Long driverId);

    /** Итоги водителя за период — суммирование в БД, без загрузки поездок в память. */
    @Query("""
            select new ru.taksi.settlement.EarningsTotals(
                s.currency, count(s), sum(s.amount), sum(s.serviceFee), sum(s.parkFee),
                sum(s.incomeTax), sum(s.socialFee), sum(s.fuelCost), sum(s.driverNet))
            from RideSettlement s
            where s.driverId = :driverId and s.createdAt >= :from and s.createdAt < :to
            group by s.currency
            order by s.currency
            """)
    List<EarningsTotals> totals(@Param("driverId") Long driverId,
                                               @Param("from") Instant from, @Param("to") Instant to);
}
