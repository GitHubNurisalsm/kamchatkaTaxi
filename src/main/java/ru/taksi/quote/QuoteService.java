package ru.taksi.quote;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.taksi.api.DomainException;
import ru.taksi.city.City;
import ru.taksi.city.CityRepository;
import ru.taksi.config.TaksiProperties;
import ru.taksi.demand.DemandPin;
import ru.taksi.demand.DemandPinRepository;
import ru.taksi.driver.Driver;
import ru.taksi.driver.DriverRepository;
import ru.taksi.geo.GeoPoint;
import ru.taksi.geo.GridCell;
import ru.taksi.pricing.PriceCalculator;
import ru.taksi.pricing.PriceCalculator.PriceBreakdown;
import ru.taksi.pricing.PriceCalculator.PriceInput;
import ru.taksi.pricing.RouteEstimator;
import ru.taksi.pricing.SurgeCalculator;
import ru.taksi.pricing.SurgeCalculator.SurgeResult;
import ru.taksi.quote.QuoteDtos.CreateQuoteRequest;
import ru.taksi.quote.QuoteDtos.QuoteResponse;
import ru.taksi.tariff.Tariff;
import ru.taksi.tariff.TariffOption;
import ru.taksi.tariff.TariffOptionRepository;
import ru.taksi.tariff.TariffRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Шаги 1–2 алгоритма (слайд 4): маршрут → цена. Цена фиксируется в quote до поиска водителя.
 */
@Service
public class QuoteService {

    private static final double KM_PER_DEGREE_LAT = 111.32;

    private final CityRepository cities;
    private final TariffRepository tariffs;
    private final TariffOptionRepository options;
    private final DriverRepository drivers;
    private final DemandPinRepository pins;
    private final PriceQuoteRepository quotes;
    private final RouteEstimator routeEstimator;
    private final SurgeCalculator surgeCalculator;
    private final TaksiProperties props;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public QuoteService(CityRepository cities, TariffRepository tariffs, TariffOptionRepository options, DriverRepository drivers,
                        DemandPinRepository pins, PriceQuoteRepository quotes, RouteEstimator routeEstimator,
                        SurgeCalculator surgeCalculator, TaksiProperties props, Clock clock) {
        this.cities = cities;
        this.tariffs = tariffs;
        this.options = options;
        this.drivers = drivers;
        this.pins = pins;
        this.quotes = quotes;
        this.routeEstimator = routeEstimator;
        this.surgeCalculator = surgeCalculator;
        this.props = props;
        this.clock = clock;
    }

    /**
     * Транзакция: запись пина + запись quote. Если расчёт упадёт, пин тоже откатится,
     * и неудачный запрос не повлияет на спрос.
     */
    @Transactional
    public QuoteResponse createQuote(CreateQuoteRequest request) {
        Instant now = Instant.now(clock);
        GeoPoint from = request.from().toGeoPoint();
        GeoPoint to = request.to().toGeoPoint();

        City city = resolveCity(from, to);
        Tariff tariff = tariffs.findByCityCodeAndCodeAndActiveTrue(city.getCode(), request.tariff())
                .orElseThrow(() -> new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_TARIFF",
                        "Тариф " + request.tariff() + " не найден или неактивен в городе " + city.getName()));
        List<TariffOption> selectedOptions = resolveOptions(city.getCode(), request.options());

        RouteEstimator.Estimate route = routeEstimator.estimateRide(from, to);
        SurgeResult surge = registerPinAndCalculateSurge(from, now);
        RouteEstimator.Estimate pickup = estimatePickup(city.getCode(), tariff.getCode(), from).orElse(null);
        boolean longPickup = pickup != null && isLongPickup(pickup);

        BigDecimal optionsPrice = selectedOptions.stream()
                .map(TariffOption::getPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        PriceBreakdown price = PriceCalculator.calculate(new PriceInput(
                tariff.getSupplyPrice(),
                longPickup ? tariff.getLongPickupFee() : BigDecimal.ZERO,
                surge.k(),
                tariff.getPricePerMin(), route.durationMin(),
                tariff.getPricePerKm(), route.distanceKm(),
                optionsPrice));

        PriceQuote quote = new PriceQuote(UUID.randomUUID(), city.getCode(), city.getCurrency(), tariff.getCode(), from, to, route, surge, pickup,
                selectedOptions.stream().map(TariffOption::getCode).toList(),
                price, now, now.plus(props.quote().ttl()));
        quotes.save(quote);
        return QuoteResponse.from(quote, now);
    }

    @Transactional(readOnly = true)
    public QuoteResponse getQuote(UUID id) {
        PriceQuote quote = quotes.findById(id)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "QUOTE_NOT_FOUND",
                        "Предложение цены не найдено"));
        return QuoteResponse.from(quote, Instant.now(clock));
    }

    /**
     * Город — по точке А. Междугородние поездки не поддерживаются: точка Б должна быть в зоне того же города.
     */
    private City resolveCity(GeoPoint from, GeoPoint to) {
        City city = cities.findActiveContaining(from.lat(), from.lon())
                .orElseThrow(() -> new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "OUTSIDE_SERVICE_AREA",
                        "Точка А вне зоны обслуживания: сервис работает только в городах из GET /api/v1/cities"));
        if (!city.contains(to)) {
            throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "OUTSIDE_SERVICE_AREA",
                    "Точка Б вне зоны обслуживания города " + city.getName());
        }
        return city;
    }

    /** Опции без дублей; неизвестная или неактивная опция — ошибка, а не молчаливый пропуск. */
    private List<TariffOption> resolveOptions(String cityCode, List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        TreeSet<String> codes = new TreeSet<>(requested);
        List<TariffOption> found = options.findAllByCityCodeAndCodeInAndActiveTrue(cityCode, codes);
        if (found.size() != codes.size()) {
            found.forEach(o -> codes.remove(o.getCode()));
            throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_OPTION",
                    "Неизвестные или неактивные опции: " + codes);
        }
        return found.stream().sorted((a, b) -> a.getCode().compareTo(b.getCode())).toList();
    }

    /**
     * Слайд 7: пин ставится до заказа; r = пины / свободные машины в зоне.
     * Сглаживание — зона считается вместе с соседними ячейками (neighbor-radius).
     */
    private SurgeResult registerPinAndCalculateSurge(GeoPoint from, Instant now) {
        TaksiProperties.Surge s = props.surge();
        GridCell cell = GridCell.of(from, s.cellSizeDeg());
        pins.save(new DemandPin(cell.latIdx(), cell.lonIdx(), now));

        int rad = s.neighborRadius();
        long pinCount = pins.countInCells(
                cell.latIdx() - rad, cell.latIdx() + rad,
                cell.lonIdx() - rad, cell.lonIdx() + rad,
                now.minus(s.pinWindow()));
        long freeCars = drivers.countFreeInBox(
                cell.minLat(rad, s.cellSizeDeg()), cell.maxLat(rad, s.cellSizeDeg()),
                cell.minLon(rad, s.cellSizeDeg()), cell.maxLon(rad, s.cellSizeDeg()));
        return surgeCalculator.calculate(pinCount, freeCars);
    }

    /** Прогноз подачи: ближайший свободный водитель тарифа в радиусе поиска. */
    private Optional<RouteEstimator.Estimate> estimatePickup(String cityCode, String tariffCode, GeoPoint from) {
        double radiusKm = props.pickup().searchRadiusKm();
        double cosLat = Math.max(0.01, Math.cos(Math.toRadians(from.lat())));
        double dLat = radiusKm / KM_PER_DEGREE_LAT;
        double dLon = radiusKm / (KM_PER_DEGREE_LAT * cosLat);

        Optional<Driver> nearest = drivers.findNearestFree(cityCode, tariffCode, from.lat(), from.lon(), cosLat,
                from.lat() - dLat, from.lat() + dLat, from.lon() - dLon, from.lon() + dLon);
        return nearest
                .map(d -> new GeoPoint(d.getLat(), d.getLon()))
                .filter(p -> p.distanceKm(from) <= radiusKm)
                .map(p -> routeEstimator.estimate(p, from));
    }

    /** Слайд 6: «дальняя подача — сверх 6,7 мин и 3,5 км». Трактуем как превышение любого порога. */
    private boolean isLongPickup(RouteEstimator.Estimate pickup) {
        TaksiProperties.Pickup p = props.pickup();
        return pickup.durationMin().doubleValue() > p.longPickupMin()
                || pickup.distanceKm().doubleValue() > p.longPickupKm();
    }
}
