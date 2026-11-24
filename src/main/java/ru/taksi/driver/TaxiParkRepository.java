package ru.taksi.driver;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TaxiParkRepository extends JpaRepository<TaxiPark, Long> {
}
