package ru.taksi.settlement;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.taksi.api.DomainException;
import ru.taksi.city.City;
import ru.taksi.city.CityRepository;
import ru.taksi.driver.Driver;
import ru.taksi.driver.DriverRepository;
import ru.taksi.driver.TaxiPark;
import ru.taksi.driver.TaxiParkRepository;
import ru.taksi.order.RideOrder;
import ru.taksi.quote.PriceQuote;
import ru.taksi.settlement.SettlementCalculator.Breakdown;
import ru.taksi.settlement.SettlementCalculator.Input;
import ru.taksi.settlement.SettlementDtos.EarningsResponse;
import ru.taksi.settlement.SettlementDtos.SettlementResponse;
import ru.taksi.tariff.CityScopedId;
import ru.taksi.tariff.Tariff;
import ru.taksi.tariff.TariffRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Шаги 7–8 (слайд 4): пассажир платит цену из quote, деньги раскладываются по статьям (слайды 9–10). */
@Service
public class SettlementService {

    public static final Duration MAX_EARNINGS_PERIOD = Duration.ofDays(31);
    private static final BigDecimal MAX_PREVIEW_PRICE = new BigDecimal("1000000");
    private static final BigDecimal MAX_PREVIEW_KM = new BigDecimal("500");

    private final RideSettlementRepository settlements;
    private final CityRepository cities;
    private final TariffRepository tariffs;
    private final TaxiParkRepository parks;
    private final DriverRepository drivers;
    private final Clock clock;

    public SettlementService(RideSettlementRepository settlements, CityRepository cities, TariffRepository tariffs,
                             TaxiParkRepository parks, DriverRepository drivers, Clock clock) {
        this.settlements = settlements;
        this.cities = cities;
        this.tariffs = tariffs;
        this.parks = parks;
        this.drivers = drivers;
        this.clock = clock;
    }

    /**
     * Расчёт при завершении поездки. Работает только внутри транзакции перехода COMPLETE:
     * если расчёт не удался, откатится и завершение — «завершённый заказ без расчёта» невозможен.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public RideSettlement settle(RideOrder order, Driver driver, Instant now) {
        PriceQuote quote = order.getQuote();
        City city = cities.findById(quote.getCityCode()).orElseThrow();
        Tariff tariff = tariffs.findById(new CityScopedId(quote.getCityCode(), quote.getTariffCode())).orElseThrow();
        BigDecimal parkRate = driver.getParkId() == null ? BigDecimal.ZERO
                : parks.findById(driver.getParkId()).map(TaxiPark::getCommissionRate).orElse(BigDecimal.ZERO);
        BigDecimal pickupKm = order.getPickupKm() == null ? BigDecimal.ZERO : order.getPickupKm();

        Input in = new Input(quote.getTotal(), tariff.getServiceCommissionRate(), parkRate,
                city.getIncomeTaxRate(), city.getSocialContributionRate(),
                quote.getDistanceKm().add(pickupKm), city.getFuelCostPerKm());
        Breakdown b = SettlementCalculator.calculate(in);
        return settlements.save(new RideSettlement(order.getId(), driver.getId(), driver.getParkId(),
                quote.getCurrency(), city.getIncomeTaxName(), in, b, now));
    }

    /** Детализация поездки — только водителю этого заказа; чужая — 404. */
    @Transactional(readOnly = true)
    public SettlementResponse forDriver(Long driverId, UUID orderId) {
        requireDriver(driverId);
        return settlements.findByOrderIdAndDriverId(orderId, driverId)
                .map(SettlementResponse::of)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "SETTLEMENT_NOT_FOUND",
                        "Расчёт не найден: поездка не завершена или не ваша"));
    }

    @Transactional(readOnly = true)
    public EarningsResponse earnings(Long driverId, Instant from, Instant to) {
        requireDriver(driverId);
        Instant end = to != null ? to : Instant.now(clock);
        Instant start = from != null ? from : end.minus(Duration.ofDays(7));
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(MAX_EARNINGS_PERIOD) > 0) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_PERIOD",
                    "Период: from < to, не длиннее " + MAX_EARNINGS_PERIOD.toDays() + " дней");
        }
        return new EarningsResponse(driverId, start, end, settlements.totals(driverId, start, end));
    }

    /** Калькулятор «куда идут деньги» по ставкам города и тарифа — без записи в БД. */
    @Transactional(readOnly = true)
    public SettlementResponse preview(String cityCode, String tariffCode, BigDecimal price, BigDecimal km,
                                      BigDecimal parkRate) {
        City city = cities.findById(cityCode).filter(City::isActive)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "CITY_NOT_FOUND", "Город не найден: " + cityCode));
        Tariff tariff = tariffs.findById(new CityScopedId(cityCode, tariffCode)).filter(Tariff::isActive)
                .orElseThrow(() -> new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_TARIFF",
                        "Тариф " + tariffCode + " не найден в городе " + city.getName()));
        if (price.signum() < 0 || price.compareTo(MAX_PREVIEW_PRICE) > 0
                || km.signum() < 0 || km.compareTo(MAX_PREVIEW_KM) > 0
                || parkRate.signum() < 0 || parkRate.compareTo(BigDecimal.ONE) > 0) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                    "price 0…" + MAX_PREVIEW_PRICE + ", km 0…" + MAX_PREVIEW_KM + ", parkRate 0…1");
        }
        Input in;
        try {
            in = new Input(price, tariff.getServiceCommissionRate(), parkRate, city.getIncomeTaxRate(),
                    city.getSocialContributionRate(), km, city.getFuelCostPerKm());
        } catch (IllegalArgumentException e) {
            throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RATES",
                    "Сумма ставок больше 100%");
        }
        return SettlementResponse.of(null, city.getCurrency(), city.getIncomeTaxName(), in,
                SettlementCalculator.calculate(in), null);
    }

    private void requireDriver(Long driverId) {
        if (driverId == null || !drivers.existsById(driverId)) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, "UNKNOWN_DRIVER", "Водитель не найден");
        }
    }
}
