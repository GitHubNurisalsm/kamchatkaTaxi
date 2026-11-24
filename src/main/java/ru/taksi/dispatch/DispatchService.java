package ru.taksi.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.taksi.api.DomainException;
import ru.taksi.config.TaksiProperties;
import ru.taksi.dispatch.HungarianAlgorithm.Pair;
import ru.taksi.driver.Driver;
import ru.taksi.driver.DriverRepository;
import ru.taksi.geo.GeoPoint;
import ru.taksi.order.OrderEventRepository;
import ru.taksi.order.OrderService;
import ru.taksi.order.RideOrder;
import ru.taksi.order.RideOrderRepository;
import ru.taksi.pricing.RouteEstimator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Шаг 4 (слайд 8): раунд буферного подбора.
 *
 * <ol>
 *   <li>Просроченные предложения возвращаются в поиск.</li>
 *   <li>Снимок: заказы в поиске (пачка) и свободные водители — без блокировок.</li>
 *   <li>Для каждой группы «город + тариф» — матрица времени подачи и венгерский алгоритм.</li>
 *   <li>Каждая пара назначается отдельной транзакцией через {@link OrderService#assignDriver}, которая
 *       под блокировками перепроверяет статусы. Если снимок устарел (отмена, офлайн) — пара пропускается.</li>
 * </ol>
 */
@Service
public class DispatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchService.class);
    private static final double KM_PER_DEGREE_LAT = 111.32;
    private static final int MAX_EXPIRED_PER_ROUND = 100;

    private final RideOrderRepository orders;
    private final OrderEventRepository events;
    private final DriverRepository drivers;
    private final OrderService orderService;
    private final RouteEstimator routeEstimator;
    private final TaksiProperties.Dispatch props;
    private final TransactionTemplate readTx;
    private final Clock clock;

    public DispatchService(RideOrderRepository orders, OrderEventRepository events, DriverRepository drivers,
                           OrderService orderService, RouteEstimator routeEstimator, TaksiProperties properties,
                           PlatformTransactionManager txManager, Clock clock) {
        this.orders = orders;
        this.events = events;
        this.drivers = drivers;
        this.orderService = orderService;
        this.routeEstimator = routeEstimator;
        this.props = properties.dispatch();
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.clock = clock;
    }

    /** Раунды одного экземпляра идут по очереди (ручной запуск ждёт фоновый и наоборот). */
    public synchronized DispatchReport runRound() {
        Instant now = Instant.now(clock);
        int expired = expireOffers(now);

        List<OrderSnapshot> batch = readTx.execute(s -> orders
                .findSearchingBatch(PageRequest.of(0, props.maxOrdersPerRound())).stream()
                .map(OrderSnapshot::of)
                .toList());

        Map<String, List<OrderSnapshot>> groups = new LinkedHashMap<>();
        for (OrderSnapshot o : batch) {
            groups.computeIfAbsent(o.city() + "/" + o.tariff(), k -> new ArrayList<>()).add(o);
        }

        List<GroupReport> reports = new ArrayList<>();
        int driversConsidered = 0;
        for (List<OrderSnapshot> group : groups.values()) {
            GroupReport report = dispatchGroup(group);
            driversConsidered += report.drivers();
            reports.add(report);
        }

        int assigned = reports.stream().mapToInt(r -> r.assignments().size()).sum();
        int conflicts = reports.stream().mapToInt(GroupReport::conflicts).sum();
        if (assigned > 0 || expired > 0 || conflicts > 0) {
            log.info("Раунд подбора: заказов {}, водителей {}, назначено {}, конфликтов {}, просрочено предложений {}",
                    batch.size(), driversConsidered, assigned, conflicts, expired);
        }
        return new DispatchReport(now, expired, batch.size(), driversConsidered, assigned, conflicts, reports);
    }

    private int expireOffers(Instant now) {
        Instant before = now.minus(props.offerTimeout());
        List<UUID> ids = readTx.execute(s -> orders.findExpiredOfferIds(before, PageRequest.of(0, MAX_EXPIRED_PER_ROUND)));
        int expired = 0;
        for (UUID id : ids) {
            if (orderService.expireOffer(id, before)) {
                expired++;
            }
        }
        return expired;
    }

    private GroupReport dispatchGroup(List<OrderSnapshot> group) {
        String city = group.get(0).city();
        String tariff = group.get(0).tariff();

        List<DriverSnapshot> candidates = readTx.execute(s -> loadCandidates(city, tariff, group));
        Set<String> rejected = readTx.execute(s -> loadRejectedPairs(group));

        double[][] eta = new double[group.size()][candidates.size()];
        for (int i = 0; i < group.size(); i++) {
            OrderSnapshot o = group.get(i);
            for (int j = 0; j < candidates.size(); j++) {
                DriverSnapshot d = candidates.get(j);
                RouteEstimator.Estimate pickup = routeEstimator.estimate(d.location(), o.from());
                boolean allowed = pickup.distanceKm().doubleValue() <= props.maxPickupKm()
                        && !rejected.contains(o.id() + "/" + d.id());
                eta[i][j] = allowed ? pickup.durationMin().doubleValue() : Double.POSITIVE_INFINITY;
            }
        }

        List<Pair> optimal = HungarianAlgorithm.solve(eta);
        List<Pair> greedy = HungarianAlgorithm.greedy(eta);

        List<AssignmentReport> done = new ArrayList<>();
        int conflicts = 0;
        for (Pair p : optimal) {
            UUID orderId = group.get(p.row()).id();
            Long driverId = candidates.get(p.col()).id();
            try {
                orderService.assignDriver(orderId, driverId);
                done.add(new AssignmentReport(orderId, driverId, round1(p.cost())));
            } catch (DomainException e) {
                // снимок устарел: заказ отменён или водитель ушёл/занят — заказ попадёт в следующий раунд
                conflicts++;
                log.debug("Пара {} → {} пропущена: {}", orderId, driverId, e.code());
            }
        }
        return new GroupReport(city, tariff, group.size(), candidates.size(), done,
                round1(HungarianAlgorithm.total(optimal)), round1(HungarianAlgorithm.total(greedy)),
                optimal.size(), greedy.size(), conflicts);
    }

    /** Свободные водители в прямоугольнике «заказы группы ± max-pickup-km». */
    private List<DriverSnapshot> loadCandidates(String city, String tariff, List<OrderSnapshot> group) {
        double minLat = group.stream().mapToDouble(o -> o.from().lat()).min().orElseThrow();
        double maxLat = group.stream().mapToDouble(o -> o.from().lat()).max().orElseThrow();
        double minLon = group.stream().mapToDouble(o -> o.from().lon()).min().orElseThrow();
        double maxLon = group.stream().mapToDouble(o -> o.from().lon()).max().orElseThrow();
        double dLat = props.maxPickupKm() / KM_PER_DEGREE_LAT;
        double cos = Math.max(0.01, Math.cos(Math.toRadians(Math.max(Math.abs(minLat), Math.abs(maxLat)))));
        double dLon = props.maxPickupKm() / (KM_PER_DEGREE_LAT * cos);

        return drivers.findFreeForDispatch(city, tariff, minLat - dLat, maxLat + dLat, minLon - dLon, maxLon + dLon,
                        PageRequest.of(0, props.maxDriversPerGroup())).stream()
                .map(DriverSnapshot::of)
                .toList();
    }

    private Set<String> loadRejectedPairs(List<OrderSnapshot> group) {
        Set<String> pairs = new HashSet<>();
        for (Object[] row : events.findRejectedPairs(group.stream().map(OrderSnapshot::id).toList())) {
            pairs.add(row[0] + "/" + row[1]);
        }
        return pairs;
    }

    private static double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    // ---------------------------------------------------------------- снимки и отчёт

    private record OrderSnapshot(UUID id, String city, String tariff, GeoPoint from) {
        static OrderSnapshot of(RideOrder o) {
            return new OrderSnapshot(o.getId(), o.getQuote().getCityCode(), o.getQuote().getTariffCode(),
                    o.getQuote().getFrom());
        }
    }

    private record DriverSnapshot(Long id, GeoPoint location) {
        static DriverSnapshot of(Driver d) {
            return new DriverSnapshot(d.getId(), new GeoPoint(d.getLat(), d.getLon()));
        }
    }

    public record AssignmentReport(UUID orderId, Long driverId, double pickupMin) {
    }

    /**
     * @param totalPickupMin       сумма подач по венгерскому алгоритму
     * @param greedyTotalPickupMin сумма подач, если бы назначали жадно (для сравнения, слайд 8)
     */
    public record GroupReport(String city, String tariff, int orders, int drivers,
                              List<AssignmentReport> assignments,
                              double totalPickupMin, double greedyTotalPickupMin,
                              int optimalMatches, int greedyMatches, int conflicts) {
    }

    public record DispatchReport(Instant startedAt, int offersExpired, int ordersConsidered, int driversConsidered,
                                 int assigned, int conflicts, List<GroupReport> groups) {
    }
}
