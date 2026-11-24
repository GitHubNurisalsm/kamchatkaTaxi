package ru.taksi.driver;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.taksi.api.DomainException;
import ru.taksi.city.City;
import ru.taksi.city.CityRepository;
import ru.taksi.geo.GeoPoint;

/**
 * Выход на линию, уход с линии и координаты водителя. Все изменения — под блокировкой строки водителя,
 * поэтому уход офлайн не может «проскочить» одновременно с назначением заказа.
 */
@Service
public class DriverService {

    private final DriverRepository drivers;
    private final CityRepository cities;

    public DriverService(DriverRepository drivers, CityRepository cities) {
        this.drivers = drivers;
        this.cities = cities;
    }

    /** Выйти на линию в точке {@code location}. Если водитель уже везёт заказ — только обновить координаты. */
    @Transactional
    public DriverResponse goOnline(Long driverId, GeoPoint location) {
        Driver d = lockDriver(driverId);
        requireInsideCity(d, location);
        if (d.getStatus() == DriverStatus.BUSY) {
            d.moveTo(location);
        } else {
            d.goOnline(location);
        }
        return DriverResponse.of(d);
    }

    /** Уйти с линии. С активным заказом нельзя: сначала завершить или отменить его. */
    @Transactional
    public DriverResponse goOffline(Long driverId) {
        Driver d = lockDriver(driverId);
        if (d.getStatus() == DriverStatus.BUSY) {
            throw new DomainException(HttpStatus.CONFLICT, "DRIVER_HAS_ACTIVE_ORDER",
                    "Нельзя уйти с линии с активным заказом");
        }
        d.goOffline();
        return DriverResponse.of(d);
    }

    @Transactional
    public DriverResponse updateLocation(Long driverId, GeoPoint location) {
        Driver d = lockDriver(driverId);
        if (d.getStatus() == DriverStatus.OFFLINE) {
            throw new DomainException(HttpStatus.CONFLICT, "DRIVER_OFFLINE",
                    "Водитель не на линии — сначала POST /api/v1/driver/online");
        }
        requireInsideCity(d, location);
        d.moveTo(location);
        return DriverResponse.of(d);
    }

    @Transactional(readOnly = true)
    public DriverResponse me(Long driverId) {
        return DriverResponse.of(drivers.findById(driverId).orElseThrow(DriverService::unknownDriver));
    }

    private Driver lockDriver(Long driverId) {
        if (driverId == null) {
            throw unknownDriver();
        }
        return drivers.findForUpdate(driverId).orElseThrow(DriverService::unknownDriver);
    }

    /** Водитель работает только в зоне своего города — иначе он искажает спрос и подачу чужого города. */
    private void requireInsideCity(Driver d, GeoPoint location) {
        City city = cities.findById(d.getCityCode()).filter(City::isActive)
                .orElseThrow(() -> new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "OUTSIDE_SERVICE_AREA",
                        "Город водителя не обслуживается"));
        if (!city.contains(location)) {
            throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "OUTSIDE_SERVICE_AREA",
                    "Координаты вне зоны обслуживания города " + city.getName());
        }
    }

    private static DomainException unknownDriver() {
        return new DomainException(HttpStatus.UNAUTHORIZED, "UNKNOWN_DRIVER", "Водитель не найден");
    }

    public record DriverResponse(Long id, String name, String city, String tariff, DriverStatus status,
                                 Double lat, Double lon) {
        static DriverResponse of(Driver d) {
            return new DriverResponse(d.getId(), d.getFullName(), d.getCityCode(), d.getTariffCode(),
                    d.getStatus(), d.getLat(), d.getLon());
        }
    }
}
