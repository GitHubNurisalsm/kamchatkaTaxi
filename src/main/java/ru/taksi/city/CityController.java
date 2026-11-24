package ru.taksi.city;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/cities")
@Tag(name = "Города")
public class CityController {

    private final CityRepository cities;

    public CityController(CityRepository cities) {
        this.cities = cities;
    }

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "Города, где работает сервис, и их зоны обслуживания")
    @ApiResponse(responseCode = "200", description = "Список активных городов")
    public List<CityDto> list() {
        return cities.findAllByActiveTrueOrderByCode().stream()
                .map(c -> new CityDto(c.getCode(), c.getName(), c.getCurrency(),
                        new AreaDto(c.getMinLat(), c.getMaxLat(), c.getMinLon(), c.getMaxLon())))
                .toList();
    }

    public record CityDto(String code, String name, String currency, AreaDto serviceArea) {
    }

    public record AreaDto(double minLat, double maxLat, double minLon, double maxLon) {
    }
}
