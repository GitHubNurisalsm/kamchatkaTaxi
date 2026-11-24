package ru.taksi.order;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ru.taksi.api.DomainException;
import ru.taksi.driver.Driver;
import ru.taksi.driver.DriverRepository;
import ru.taksi.driver.DriverStatus;
import ru.taksi.order.OrderDtos.CreateResult;
import ru.taksi.order.OrderDtos.OrderPage;
import ru.taksi.order.OrderDtos.OrderResponse;
import ru.taksi.passenger.PassengerRepository;
import ru.taksi.quote.PriceQuote;
import ru.taksi.quote.PriceQuoteRepository;
import ru.taksi.geo.GeoPoint;
import ru.taksi.pricing.RouteEstimator;
import ru.taksi.settlement.SettlementService;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Шаги 3–6 алгоритма (слайд 4): заказ по зафиксированной цене и его жизненный цикл.
 *
 * <p>Порядок блокировок при любом переходе: сначала строка заказа, затем строка водителя.
 * Один и тот же порядок во всех операциях исключает взаимные блокировки (deadlock).
 */
@Service
public class OrderService {

    public static final int MAX_PAGE_SIZE = 50;
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final RideOrderRepository orders;
    private final OrderEventRepository events;
    private final PriceQuoteRepository quotes;
    private final PassengerRepository passengers;
    private final DriverRepository drivers;
    private final RouteEstimator routeEstimator;
    private final SettlementService settlements;
    private final TransactionTemplate tx;
    private final Clock clock;

    public OrderService(RideOrderRepository orders, OrderEventRepository events, PriceQuoteRepository quotes,
                        PassengerRepository passengers, DriverRepository drivers, RouteEstimator routeEstimator,
                        SettlementService settlements, PlatformTransactionManager txManager, Clock clock) {
        this.orders = orders;
        this.events = events;
        this.quotes = quotes;
        this.passengers = passengers;
        this.drivers = drivers;
        this.routeEstimator = routeEstimator;
        this.settlements = settlements;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    // ---------------------------------------------------------------- пассажир

    /**
     * Шаг 3: пассажир соглашается с ценой. Идемпотентно по (пассажир, Idempotency-Key).
     * Если параллельный запрос обогнал проверки, сработает ограничение БД — тогда во второй
     * транзакции читаем победителя и отвечаем так же, как ответили бы на обычный повтор.
     */
    public CreateResult createOrder(Long passengerId, String idempotencyKey, UUID quoteId) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key: 1–64 символа из A–Z, a–z, 0–9, '-', '_'");
        }
        try {
            return tx.execute(s -> doCreate(passengerId, idempotencyKey, quoteId));
        } catch (DataIntegrityViolationException e) {
            return tx.execute(s -> resolveCreateConflict(passengerId, idempotencyKey, quoteId, e));
        }
    }

    private CreateResult doCreate(Long passengerId, String key, UUID quoteId) {
        requirePassenger(passengerId);
        Optional<RideOrder> existing = orders.findByPassengerIdAndIdempotencyKey(passengerId, key);
        if (existing.isPresent()) {
            return replay(existing.get(), quoteId);
        }

        Instant now = Instant.now(clock);
        PriceQuote quote = quotes.findById(quoteId)
                .orElseThrow(() -> new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "QUOTE_NOT_FOUND",
                        "Предложение цены не найдено"));
        if (quote.isExpired(now)) {
            throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "QUOTE_EXPIRED",
                    "Срок действия цены истёк — запросите новую цену");
        }
        if (orders.existsByQuoteId(quoteId)) {
            return replayOrThrow(passengerId, key, quoteId, quoteAlreadyUsed());
        }
        if (orders.existsByPassengerIdAndStatusIn(passengerId, OrderStatus.ACTIVE)) {
            return replayOrThrow(passengerId, key, quoteId, activeOrderExists());
        }

        RideOrder order = new RideOrder(UUID.randomUUID(), passengerId, quote, key, now);
        orders.saveAndFlush(order);
        events.save(new OrderEvent(order.getId(), OrderAction.CREATE, null, order.getStatus(), null, now));
        return new CreateResult(OrderResponse.of(order), true);
    }

    /**
     * В READ COMMITTED каждый запрос видит свежие коммиты. Параллельный повтор с тем же ключом мог
     * зафиксироваться между нашей проверкой ключа и проверкой конфликта — тогда это не конфликт, а повтор.
     */
    private CreateResult replayOrThrow(Long passengerId, String key, UUID quoteId, DomainException conflict) {
        return orders.findByPassengerIdAndIdempotencyKey(passengerId, key)
                .map(existing -> replay(existing, quoteId))
                .orElseThrow(() -> conflict);
    }

    private CreateResult resolveCreateConflict(Long passengerId, String key, UUID quoteId,
                                               DataIntegrityViolationException e) {
        Optional<RideOrder> existing = orders.findByPassengerIdAndIdempotencyKey(passengerId, key);
        if (existing.isPresent()) {
            return replay(existing.get(), quoteId);
        }
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        if (message.contains("ride_order_quote_unique")) {
            throw quoteAlreadyUsed();
        }
        if (message.contains("ride_order_one_active_per_passenger")) {
            throw activeOrderExists();
        }
        throw e;
    }

    private static CreateResult replay(RideOrder existing, UUID quoteId) {
        if (!existing.getQuote().getId().equals(quoteId)) {
            throw new DomainException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "Этот Idempotency-Key уже использован для другого заказа");
        }
        return new CreateResult(OrderResponse.of(existing), false);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long passengerId, UUID orderId) {
        requirePassenger(passengerId);
        return orders.findOwned(orderId, passengerId).map(OrderResponse::of).orElseThrow(OrderService::orderNotFound);
    }

    @Transactional(readOnly = true)
    public OrderPage listOrders(Long passengerId, int page, int size) {
        requirePassenger(passengerId);
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_PAGE",
                    "page >= 0, size от 1 до " + MAX_PAGE_SIZE);
        }
        Page<RideOrder> result = orders.findPageByPassenger(passengerId, PageRequest.of(page, size));
        return new OrderPage(result.map(OrderResponse::of).getContent(), page, size,
                result.getTotalElements(), result.getTotalPages());
    }

    /** Отмена пассажиром. Повторная отмена уже отменённого заказа — не ошибка. */
    @Transactional
    public OrderResponse cancelByPassenger(Long passengerId, UUID orderId) {
        requirePassenger(passengerId);
        RideOrder order = orders.findForUpdate(orderId)
                .filter(o -> o.getPassengerId().equals(passengerId))
                .orElseThrow(OrderService::orderNotFound);
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return OrderResponse.of(order);
        }
        transition(order, OrderAction.PASSENGER_CANCEL, null);
        return OrderResponse.of(order);
    }

    // ---------------------------------------------------------------- водитель

    /**
     * Действие водителя над своим заказом. Чужой заказ — 404, чтобы не раскрывать его существование.
     * Повтор ACCEPT/ARRIVE/START/COMPLETE в уже достигнутом статусе возвращает текущее состояние.
     */
    @Transactional
    public OrderResponse driverAction(Long driverId, UUID orderId, OrderAction action) {
        if (action.actor() != OrderAction.Actor.DRIVER) {
            throw new IllegalArgumentException("not a driver action: " + action);
        }
        requireDriver(driverId);
        RideOrder order = orders.findForUpdate(orderId)
                .filter(o -> o.isAssignedTo(driverId))
                .orElseThrow(OrderService::orderNotFound);
        if (action.isRepeatSafe() && order.getStatus() == action.target()) {
            return OrderResponse.of(order);
        }
        transition(order, action, null);
        return OrderResponse.of(order);
    }

    @Transactional(readOnly = true)
    public Optional<OrderResponse> currentDriverOrder(Long driverId) {
        requireDriver(driverId);
        return orders.findActiveByDriver(driverId, OrderStatus.WITH_DRIVER).map(OrderResponse::of);
    }

    // ---------------------------------------------------------------- система (диспетчер, этап 4)

    /** Шаг 4: назначить свободного водителя на заказ в поиске. Вызывается диспетчером. */
    @Transactional
    public OrderResponse assignDriver(UUID orderId, Long driverId) {
        RideOrder order = orders.findForUpdate(orderId).orElseThrow(OrderService::orderNotFound);
        if (!OrderAction.ASSIGN.allowedFrom(order.getStatus())) {
            throw invalidTransition(OrderAction.ASSIGN, order.getStatus());
        }
        Driver driver = drivers.findForUpdate(driverId)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "DRIVER_NOT_FOUND", "Водитель не найден"));
        if (driver.getStatus() != DriverStatus.FREE) {
            throw new DomainException(HttpStatus.CONFLICT, "DRIVER_NOT_AVAILABLE",
                    "Водитель не свободен: " + driver.getStatus());
        }
        PriceQuote quote = order.getQuote();
        if (!driver.getCityCode().equals(quote.getCityCode()) || !driver.getTariffCode().equals(quote.getTariffCode())) {
            throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "DRIVER_NOT_ELIGIBLE",
                    "Водитель работает в другом городе или тарифе");
        }
        transition(order, OrderAction.ASSIGN, driver);
        // прогноз км подачи — для расхода топлива в расчёте (этап 5)
        order.recordPickup(routeEstimator.estimate(new GeoPoint(driver.getLat(), driver.getLon()), quote.getFrom())
                .distanceKm());
        return OrderResponse.of(order);
    }

    /**
     * Предложение не принято вовремя. Под блокировкой повторно проверяем, что заказ всё ещё ждёт ответа
     * именно с того момента: если водитель успел принять или отказаться, ничего не делаем.
     *
     * @return true, если заказ возвращён в поиск
     */
    @Transactional
    public boolean expireOffer(UUID orderId, Instant offeredBefore) {
        RideOrder order = orders.findForUpdate(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.DRIVER_ASSIGNED
                || !order.getUpdatedAt().isBefore(offeredBefore)) {
            return false;
        }
        transition(order, OrderAction.OFFER_TIMEOUT, null);
        return true;
    }

    // ---------------------------------------------------------------- общее

    /**
     * Единственное место, где меняется статус заказа. Вызывается под блокировкой строки заказа.
     * Водитель блокируется вторым и меняет статус вместе с заказом в одной транзакции.
     */
    private void transition(RideOrder order, OrderAction action, Driver driverToAssign) {
        if (!action.allowedFrom(order.getStatus())) {
            throw invalidTransition(action, order.getStatus());
        }
        Instant now = Instant.now(clock);
        Driver current = order.getDriver() == null ? null
                : drivers.findForUpdate(order.getDriver().getId()).orElseThrow();

        OrderStatus before = order.apply(action, driverToAssign, now);

        switch (action) {
            case ASSIGN -> driverToAssign.markBusy();
            case DECLINE, DRIVER_CANCEL, OFFER_TIMEOUT -> current.markFree();
            case COMPLETE -> {
                current.markFree();
                current.moveTo(order.getQuote().getTo());   // поездка закончилась в точке Б
                settlements.settle(order, current, now);     // шаги 7–8 — в той же транзакции
            }
            case PASSENGER_CANCEL -> {
                if (current != null) {
                    current.markFree();
                }
            }
            default -> {
                // ACCEPT, ARRIVE, START: водитель остаётся BUSY
            }
        }
        Long eventDriver = driverToAssign != null ? driverToAssign.getId() : current != null ? current.getId() : null;
        events.save(new OrderEvent(order.getId(), action, before, order.getStatus(), eventDriver, now));
    }

    private void requirePassenger(Long passengerId) {
        if (passengerId == null || !passengers.existsById(passengerId)) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, "UNKNOWN_PASSENGER", "Пассажир не найден");
        }
    }

    private void requireDriver(Long driverId) {
        if (driverId == null || !drivers.existsById(driverId)) {
            throw new DomainException(HttpStatus.UNAUTHORIZED, "UNKNOWN_DRIVER", "Водитель не найден");
        }
    }

    private static DomainException orderNotFound() {
        return new DomainException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Заказ не найден");
    }

    private static DomainException quoteAlreadyUsed() {
        return new DomainException(HttpStatus.CONFLICT, "QUOTE_ALREADY_USED",
                "По этому предложению цены уже создан заказ");
    }

    private static DomainException activeOrderExists() {
        return new DomainException(HttpStatus.CONFLICT, "ACTIVE_ORDER_EXISTS",
                "У пассажира уже есть активный заказ");
    }

    private static DomainException invalidTransition(OrderAction action, OrderStatus status) {
        return new DomainException(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
                "Действие " + action + " недопустимо в статусе " + status);
    }
}
