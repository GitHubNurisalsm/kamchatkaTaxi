package ru.taksi.order;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.taksi.api.ApiErrors;
import ru.taksi.order.OrderDtos.CreateOrderRequest;
import ru.taksi.order.OrderDtos.CreateResult;
import ru.taksi.order.OrderDtos.OrderPage;
import ru.taksi.order.OrderDtos.OrderResponse;

import java.net.URI;
import java.util.UUID;

import static ru.taksi.api.ApiErrors.PROBLEM_JSON;

@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Заказы (пассажир)", description = "Шаг 3: заказ по зафиксированной цене. "
        + "Учебный MVP: пассажир указывается в заголовке X-Passenger-Id.")
public class OrderController {

    public static final String PASSENGER_HEADER = "X-Passenger-Id";

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Создать заказ по предложению цены",
            description = "Idempotency-Key — любая уникальная строка (например UUID). "
                    + "Повтор с тем же ключом и тем же quoteId вернёт уже созданный заказ (200), а не создаст второй.")
    @ApiResponse(responseCode = "201", description = "Заказ создан, статус SEARCHING")
    @ApiResponse(responseCode = "200", description = "Повтор запроса: возвращён ранее созданный заказ")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR, MISSING_HEADER (нет Idempotency-Key), "
            + "INVALID_IDEMPOTENCY_KEY",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiErrors.Unauthorized
    @ApiResponse(responseCode = "409", description = "QUOTE_ALREADY_USED, ACTIVE_ORDER_EXISTS, IDEMPOTENCY_KEY_REUSED",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "QUOTE_NOT_FOUND, QUOTE_EXPIRED",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<OrderResponse> create(
            @RequestHeader(PASSENGER_HEADER) Long passengerId,
            @Parameter(description = "Ключ идемпотентности, 1–64 символа [A-Za-z0-9_-]")
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        CreateResult result = service.createOrder(passengerId, idempotencyKey, request.quoteId());
        if (!result.created()) {
            return ResponseEntity.ok(result.order());
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/orders/" + result.order().id()))
                .body(result.order());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Заказ пассажира")
    @ApiResponse(responseCode = "200", description = "Заказ")
    @ApiErrors.Unauthorized
    @ApiErrors.OrderNotFound
    public OrderResponse get(@RequestHeader(PASSENGER_HEADER) Long passengerId, @PathVariable UUID id) {
        return service.getOrder(passengerId, id);
    }

    @GetMapping
    @Operation(summary = "История заказов пассажира", description = "Сначала новые. size не больше 50.")
    @ApiResponse(responseCode = "200", description = "Страница заказов")
    @ApiResponse(responseCode = "400", description = "INVALID_PAGE",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiErrors.Unauthorized
    public OrderPage list(@RequestHeader(PASSENGER_HEADER) Long passengerId,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "20") int size) {
        return service.listOrders(passengerId, page, size);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Отменить заказ",
            description = "Можно до начала поездки. Назначенный водитель освобождается. "
                    + "Повторная отмена уже отменённого заказа возвращает 200.")
    @ApiResponse(responseCode = "200", description = "Заказ отменён")
    @ApiErrors.Unauthorized
    @ApiErrors.OrderNotFound
    @ApiErrors.InvalidTransition
    public OrderResponse cancel(@RequestHeader(PASSENGER_HEADER) Long passengerId, @PathVariable UUID id) {
        return service.cancelByPassenger(passengerId, id);
    }
}
