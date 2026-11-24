package ru.taksi.demand;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface DemandPinRepository extends JpaRepository<DemandPin, Long> {

    /** Пины в квадрате ячеек [minLat..maxLat] × [minLon..maxLon] не старше {@code since}. */
    @Query("""
            select count(p) from DemandPin p
            where p.cellLat between :minLat and :maxLat
              and p.cellLon between :minLon and :maxLon
              and p.createdAt >= :since
            """)
    long countInCells(@Param("minLat") int minLat, @Param("maxLat") int maxLat,
                      @Param("minLon") int minLon, @Param("maxLon") int maxLon,
                      @Param("since") Instant since);

    @Modifying
    @Query("delete from DemandPin p where p.createdAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}
