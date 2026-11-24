package ru.taksi.tariff;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface TariffOptionRepository extends JpaRepository<TariffOption, CityScopedId> {

    List<TariffOption> findAllByCityCodeAndCodeInAndActiveTrue(String cityCode, Collection<String> codes);

    List<TariffOption> findAllByActiveTrueOrderByCityCodeAscCodeAsc();

    List<TariffOption> findAllByCityCodeAndActiveTrueOrderByCode(String cityCode);
}
