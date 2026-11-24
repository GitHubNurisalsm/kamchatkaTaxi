package ru.taksi.tariff;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TariffRepository extends JpaRepository<Tariff, CityScopedId> {

    Optional<Tariff> findByCityCodeAndCodeAndActiveTrue(String cityCode, String code);

    List<Tariff> findAllByActiveTrueOrderByCityCodeAscCodeAsc();

    List<Tariff> findAllByCityCodeAndActiveTrueOrderByCode(String cityCode);
}
