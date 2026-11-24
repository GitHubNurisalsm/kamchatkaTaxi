package ru.taksi.tariff;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.taksi.api.DomainException;
import ru.taksi.city.City;
import ru.taksi.city.CityRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/tariffs")
@Tag(name = "Тарифы")
public class TariffController {

    private final TariffRepository tariffs;
    private final TariffOptionRepository options;
    private final CityRepository cities;

    public TariffController(TariffRepository tariffs, TariffOptionRepository options, CityRepository cities) {
        this.tariffs = tariffs;
        this.options = options;
        this.cities = cities;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "Активные тарифы и опции",
            description = "Без параметра city — все активные города. Цены указаны в валюте города (поле currency).")
    @ApiResponse(responseCode = "200", description = "Справочник тарифов и опций")
    @ApiResponse(responseCode = "404", description = "Город не найден или неактивен",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    public TariffCatalog catalog(
            @Parameter(description = "Код города, например BISHKEK") @RequestParam(required = false) String city) {
        Map<String, City> active = cities.findAllByActiveTrueOrderByCode().stream()
                .collect(Collectors.toMap(City::getCode, Function.identity()));

        List<Tariff> tariffList;
        List<TariffOption> optionList;
        if (city == null) {
            tariffList = tariffs.findAllByActiveTrueOrderByCityCodeAscCodeAsc();
            optionList = options.findAllByActiveTrueOrderByCityCodeAscCodeAsc();
        } else {
            if (!active.containsKey(city)) {
                throw new DomainException(HttpStatus.NOT_FOUND, "CITY_NOT_FOUND", "Город не найден: " + city);
            }
            tariffList = tariffs.findAllByCityCodeAndActiveTrueOrderByCode(city);
            optionList = options.findAllByCityCodeAndActiveTrueOrderByCode(city);
        }

        return new TariffCatalog(
                tariffList.stream()
                        .filter(t -> active.containsKey(t.getCityCode()))
                        .map(t -> new TariffDto(t.getCityCode(), t.getCode(), t.getName(),
                                active.get(t.getCityCode()).getCurrency(), t.getSupplyPrice(),
                                t.getPricePerMin(), t.getPricePerKm(), t.getLongPickupFee()))
                        .toList(),
                optionList.stream()
                        .filter(o -> active.containsKey(o.getCityCode()))
                        .map(o -> new OptionDto(o.getCityCode(), o.getCode(), o.getName(),
                                active.get(o.getCityCode()).getCurrency(), o.getPrice()))
                        .toList());
    }

    public record TariffCatalog(List<TariffDto> tariffs, List<OptionDto> options) {
    }

    public record TariffDto(String city, String code, String name, String currency, BigDecimal supplyPrice,
                            BigDecimal pricePerMin, BigDecimal pricePerKm, BigDecimal longPickupFee) {
    }

    public record OptionDto(String city, String code, String name, String currency, BigDecimal price) {
    }
}
