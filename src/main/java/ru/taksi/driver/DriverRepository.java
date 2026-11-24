package ru.taksi.driver;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DriverRepository extends JpaRepository<Driver, Long> {

    /** Свободные машины в прямоугольной области (зона спроса с соседями). */
    @Query("""
            select count(d) from Driver d
            where d.status = ru.taksi.driver.DriverStatus.FREE
              and d.lat >= :minLat and d.lat < :maxLat
              and d.lon >= :minLon and d.lon < :maxLon
            """)
    long countFreeInBox(@Param("minLat") double minLat, @Param("maxLat") double maxLat,
                        @Param("minLon") double minLon, @Param("maxLon") double maxLon);

    /**
     * Ближайший свободный водитель тарифа города внутри прямоугольника поиска.
     * Сортировка по приближённому расстоянию (долгота сжата на cos широты); точное расстояние
     * и радиус проверяются в Java. Выборка ограничена одной строкой.
     */
    @Query(value = """
            select * from driver d
            where d.status = 'FREE'
              and d.city_code = :city
              and d.tariff_code = :tariff
              and d.lat between :minLat and :maxLat
              and d.lon between :minLon and :maxLon
            order by power(d.lat - :lat, 2) + power((d.lon - :lon) * :cosLat, 2), d.id
            limit 1
            """, nativeQuery = true)
    Optional<Driver> findNearestFree(@Param("city") String city, @Param("tariff") String tariff,
                                     @Param("lat") double lat, @Param("lon") double lon, @Param("cosLat") double cosLat,
                                     @Param("minLat") double minLat, @Param("maxLat") double maxLat,
                                     @Param("minLon") double minLon, @Param("maxLon") double maxLon);

    /** Блокировка строки водителя: смена статуса (онлайн/офлайн/назначение) идёт строго по очереди. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Driver d where d.id = :id")
    Optional<Driver> findForUpdate(@Param("id") Long id);

    /** Кандидаты для раунда подбора: свободные водители города и тарифа в прямоугольнике вокруг заказов. */
    @Query("""
            select d from Driver d
            where d.status = ru.taksi.driver.DriverStatus.FREE
              and d.cityCode = :city and d.tariffCode = :tariff
              and d.lat between :minLat and :maxLat
              and d.lon between :minLon and :maxLon
            order by d.id
            """)
    java.util.List<Driver> findFreeForDispatch(@Param("city") String city, @Param("tariff") String tariff,
                                               @Param("minLat") double minLat, @Param("maxLat") double maxLat,
                                               @Param("minLon") double minLon, @Param("maxLon") double maxLon,
                                               org.springframework.data.domain.Pageable pageable);
}
