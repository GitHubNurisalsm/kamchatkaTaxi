package ru.taksi.city;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CityRepository extends JpaRepository<City, String> {

    /** Активный город, в зону обслуживания которого попадает точка. Зоны не пересекаются (EXCLUDE в БД). */
    @Query("""
            select c from City c
            where c.active = true
              and :lat between c.minLat and c.maxLat
              and :lon between c.minLon and c.maxLon
            order by c.code
            limit 1
            """)
    Optional<City> findActiveContaining(@Param("lat") double lat, @Param("lon") double lon);

    List<City> findAllByActiveTrueOrderByCode();
}
