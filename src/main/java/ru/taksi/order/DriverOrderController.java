package ru.taksi.order;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.taksi.api.ApiErrors;
import ru.taksi.driver.DriverService;
import ru.taksi.driver.DriverService.DriverResponse;
import ru.taksi.order.OrderDtos.OrderResponse;
import ru.taksi.quote.QuoteDtos.PointDto;

import java.util.UUID;

import static ru.taksi.api.ApiErrors.PROBLEM_JSON;

@RestController
@RequestMapping("/api/v1/driver")
@Tag(name = "Водитель", description = "Шаги 4–6: линия, принятие заказа, подача и поездка. "
        + "Учебный MVP: водитель указывается в заголовке X-Driver-Id.")
public class DriverOrderController {

    public static final String DRIVER_HEADER = "X-Driver-Id";

    private final DriverService drivers;
    private final OrderService orders;

    public DriverOrderController(DriverService drivers, OrderService orders) {
        this.drivers = drivers;
        this.orders = orders;
    }

    @GetMapping("/me")
    @Operation(summary = "Профиль и статус водителя")
    @ApiResponse(responseCode = "200", description = "Водитель")
    @ApiErrors.Unauthorized
    public DriverResponse me(@RequestHeader(DRIVER_HEADER) Long driverId) {
        return drivers.me(driverId);
    }

    @PostMapping("/online")
    @Operation(summary = "Выйти на линию", description = "Координаты должны быть в зоне города водителя.")
    @ApiResponse(responseCode = "200", description = "Статус FREE (или BUSY, если уже есть заказ)")
    @ApiErrors.Unauthorized
    @ApiResponse(responseCode = "422", description = "OUTSIDE_SERVICE_AREA",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public DriverResponse online(@RequestHeader(DRIVER_HEADER) Long driverId, @Valid @RequestBody PointDto location) {
        return drivers.goOnline(driverId, location.toGeoPoint());
    }

    @PostMapping("/offline")
    @Operation(summary = "Уйти с линии")
    @ApiResponse(responseCode = "200", description = "Статус OFFLINE")
    @ApiErrors.Unauthorized
    @ApiResponse(responseCode = "409", description = "DRIVER_HAS_ACTIVE_ORDER",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public DriverResponse offline(@RequestHeader(DRIVER_HEADER) Long driverId) {
        return drivers.goOffline(driverId);
    }

    @PutMapping("/location")
    @Operation(summary = "Обновить координаты")
    @ApiResponse(responseCode = "200", description = "Координаты обновлены")
    @ApiErrors.Unauthorized
    @ApiResponse(responseCode = "409", description = "DRIVER_OFFLINE",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "OUTSIDE_SERVICE_AREA",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public DriverResponse location(@RequestHeader(DRIVER_HEADER) Long driverId, @Valid @RequestBody PointDto location) {
        return drivers.updateLocation(driverId, location.toGeoPoint());
    }

    @GetMapping("/orders/current")
    @Operation(summary = "Текущий заказ водителя")
    @ApiResponse(responseCode = "200", description = "Активный заказ")
    @ApiResponse(responseCode = "204", description = "Активного заказа нет")
    @ApiErrors.Unauthorized
    public ResponseEntity<OrderResponse> current(@RequestHeader(DRIVER_HEADER) Long driverId) {
        return orders.currentDriverOrder(driverId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/orders/{id}/accept")
    @Operation(summary = "Принять предложенный заказ", description = "DRIVER_ASSIGNED → DRIVER_EN_ROUTE")
    @ApiResponse(responseCode = "200", description = "Заказ принят")
    @ApiErrors.Unauthorized
    @ApiErrors.OrderNotFound
    @ApiErrors.InvalidTransition
    public OrderResponse accept(@RequestHeader(DRIVER_HEADER) Long driverId, @PathVariable UUID id) {
        return orders.driverAction(driverId, id, OrderAction.ACCEPT);
    }

    @PostMapping("/orders/{id}/decline")
    @Operation(summary = "Отказаться от предложенного заказа",
            description = "DRIVER_ASSIGNED → SEARCHING: заказ снова ищет водителя, водитель свободен")
    @ApiResponse(responseCode = "200", description = "Отказ принят")
    @ApiErrors.Unauthorized
    @ApiErrors.OrderNotFound
    @ApiErrors.InvalidTransition
    public OrderResponse decline(@RequestHeader(DRIVER_HEADER) Long driverId, @PathVariable UUID id) {
        return orders.driverAction(driverId, id, OrderAction.DECLINE);
    }

    @PostMapping("/orders/{id}/arrive")
    @Operation(summary = "Прибыл в точку А", description = "DRIVER_EN_ROUTE → DRIVER_ARRIVED")
    @ApiResponse(responseCode = "200", description = "Водитель на месте")
    @ApiErrors.Unauthorized
    @ApiErrors.OrderNotFound
    @ApiErrors.InvalidTransition
    public OrderResponse arrive(@RequestHeader(DRIVER_HEADER) Long driverId, @PathVariable UUID id) {
        return orders.driverAction(driverId, id, OrderAction.ARRIVE);
    }

    @PostMapping("/orders/{id}/start")
    @Operation(summary = "Начать поездку", description = "DRIVER_ARRIVED → IN_PROGRESS")
    @ApiResponse(responseCode = "200", description = "Поездка началась")
    @ApiErrors.Unauthorized
    @ApiErrors.OrderNotFound
    @ApiErrors.InvalidTransition
    public OrderResponse start(@RequestHeader(DRIVER_HEADER) Long driverId, @PathVariable UUID id) {
        return orders.driverAction(driverId, id, OrderAction.START);
    }

    @PostMapping("/orders/{id}/complete")
    @Operation(summary = "Завершить поездку", description = "IN_PROGRESS → COMPLETED, водитель свободен в точке Б")
    @ApiResponse(responseCode = "200", description = "Поездка завершена")
    @ApiErrors.Unauthorized
    @ApiErrors.OrderNotFound
    @ApiErrors.InvalidTransition
    public OrderResponse complete(@RequestHeader(DRIVER_HEADER) Long driverId, @PathVariable UUID id) {
        return orders.driverAction(driverId, id, OrderAction.COMPLETE);
    }

    @PostMapping("/orders/{id}/cancel")
    @Operation(summary = "Отменить принятый заказ",
            description = "DRIVER_EN_ROUTE / DRIVER_ARRIVED → SEARCHING: заказ снова ищет водителя")
    @ApiResponse(responseCode = "200", description = "Водитель снят с заказа")
    @ApiErrors.Unauthorized
    @ApiErrors.OrderNotFound
    @ApiErrors.InvalidTransition
    public OrderResponse cancel(@RequestHeader(DRIVER_HEADER) Long driverId, @PathVariable UUID id) {
        return orders.driverAction(driverId, id, OrderAction.DRIVER_CANCEL);
    }
}
