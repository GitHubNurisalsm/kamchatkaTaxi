package ru.taksi.quote;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.taksi.quote.QuoteDtos.CreateQuoteRequest;
import ru.taksi.quote.QuoteDtos.QuoteResponse;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/quotes")
@Tag(name = "Цена поездки", description = "Шаги 1–2: маршрут и расчёт цены P = S + k × (c_t × T + c_d × D) + O")
public class QuoteController {

    private static final String PROBLEM_JSON = "application/problem+json";

    private final QuoteService service;

    public QuoteController(QuoteService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Рассчитать и зафиксировать цену поездки",
            description = "Город определяется по точке А (см. GET /api/v1/cities), цена — в валюте города. "
                    + "Ставит пин спроса в точке А, считает k, прогноз подачи и цену. "
                    + "Цена действует ограниченное время (expiresAt).")
    @ApiResponse(responseCode = "201", description = "Цена рассчитана и зафиксирована. "
            + "Заголовок Location содержит адрес созданного предложения.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = QuoteResponse.class)))
    @ApiResponse(responseCode = "400", description = "Некорректный запрос: ошибка валидации полей или неверный JSON",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class),
                    examples = {
                            @ExampleObject(name = "VALIDATION_ERROR", summary = "Координата вне диапазона", value = """
                                    {"type":"about:blank","title":"Bad Request","status":400,
                                     "detail":"Ошибка валидации запроса","instance":"/api/v1/quotes",
                                     "code":"VALIDATION_ERROR","fields":{"from.lat":"must be less than or equal to 90.0"}}"""),
                            @ExampleObject(name = "MALFORMED_REQUEST", summary = "Тело запроса — не JSON", value = """
                                    {"type":"about:blank","title":"Bad Request","status":400,
                                     "detail":"Некорректное тело запроса","instance":"/api/v1/quotes",
                                     "code":"MALFORMED_REQUEST"}""")
                    }))
    @ApiResponse(responseCode = "422", description = "Запрос корректен, но нарушает бизнес-правило",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class),
                    examples = {
                            @ExampleObject(name = "OUTSIDE_SERVICE_AREA",
                                    summary = "Точка А или Б вне зоны обслуживания города", value = """
                                    {"type":"about:blank","title":"Unprocessable Entity","status":422,
                                     "detail":"Точка Б вне зоны обслуживания города Бишкек","instance":"/api/v1/quotes",
                                     "code":"OUTSIDE_SERVICE_AREA"}"""),
                            @ExampleObject(name = "ROUTE_TOO_LONG", summary = "Маршрут длиннее лимита", value = """
                                    {"type":"about:blank","title":"Unprocessable Entity","status":422,
                                     "detail":"Маршрут длиннее 200.0 км","instance":"/api/v1/quotes",
                                     "code":"ROUTE_TOO_LONG"}"""),
                            @ExampleObject(name = "ROUTE_TOO_SHORT", summary = "Точки А и Б совпадают", value = """
                                    {"type":"about:blank","title":"Unprocessable Entity","status":422,
                                     "detail":"Точки А и Б слишком близко друг к другу","instance":"/api/v1/quotes",
                                     "code":"ROUTE_TOO_SHORT"}"""),
                            @ExampleObject(name = "UNKNOWN_TARIFF", summary = "Тариф не существует", value = """
                                    {"type":"about:blank","title":"Unprocessable Entity","status":422,
                                     "detail":"Тариф SPACE не найден или неактивен в городе Бишкек","instance":"/api/v1/quotes",
                                     "code":"UNKNOWN_TARIFF"}"""),
                            @ExampleObject(name = "UNKNOWN_OPTION", summary = "Опция не существует", value = """
                                    {"type":"about:blank","title":"Unprocessable Entity","status":422,
                                     "detail":"Неизвестные или неактивные опции: [JACUZZI]","instance":"/api/v1/quotes",
                                     "code":"UNKNOWN_OPTION"}""")
                    }))
    public ResponseEntity<QuoteResponse> create(@Valid @RequestBody CreateQuoteRequest request) {
        QuoteResponse quote = service.createQuote(request);
        return ResponseEntity.created(URI.create("/api/v1/quotes/" + quote.id())).body(quote);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Получить предложение цены",
            description = "Возвращает зафиксированную цену. Поле expired = true, если срок действия истёк.")
    @ApiResponse(responseCode = "200", description = "Предложение найдено",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = QuoteResponse.class)))
    @ApiResponse(responseCode = "400", description = "id не является UUID",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class),
                    examples = @ExampleObject(name = "INVALID_PARAMETER", value = """
                            {"type":"about:blank","title":"Bad Request","status":400,
                             "detail":"Некорректное значение параметра 'id'","instance":"/api/v1/quotes/abc",
                             "code":"INVALID_PARAMETER"}""")))
    @ApiResponse(responseCode = "404", description = "Предложение не найдено",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class),
                    examples = @ExampleObject(name = "QUOTE_NOT_FOUND", value = """
                            {"type":"about:blank","title":"Not Found","status":404,
                             "detail":"Предложение цены не найдено",
                             "instance":"/api/v1/quotes/3fa85f64-5717-4562-b3fc-2c963f66afa6",
                             "code":"QUOTE_NOT_FOUND"}""")))
    public QuoteResponse get(@PathVariable UUID id) {
        return service.getQuote(id);
    }
}
