package ru.taksi.settlement;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.taksi.api.ApiErrors;
import ru.taksi.settlement.SettlementDtos.EarningsResponse;
import ru.taksi.settlement.SettlementDtos.SettlementResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static ru.taksi.api.ApiErrors.PROBLEM_JSON;

@RestController
@Tag(name = "Расчёт с водителем", description = "Шаги 7–8: оплата и «куда идут деньги» (слайды 9–10)")
public class SettlementController {

    private static final String DRIVER_HEADER = "X-Driver-Id";

    private final SettlementService service;

    public SettlementController(SettlementService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/driver/orders/{id}/settlement")
    @Operation(summary = "Детализация завершённой поездки", description = "Только для водителя этой поездки.")
    @ApiResponse(responseCode = "200", description = "Статьи расчёта и остаток водителю")
    @ApiErrors.Unauthorized
    @ApiResponse(responseCode = "404", description = "SETTLEMENT_NOT_FOUND — поездка не завершена или не ваша",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public SettlementResponse settlement(@RequestHeader(DRIVER_HEADER) Long driverId, @PathVariable UUID id) {
        return service.forDriver(driverId, id);
    }

    @GetMapping("/api/v1/driver/earnings")
    @Operation(summary = "Заработок водителя за период",
            description = "По умолчанию — последние 7 дней. Период не длиннее 31 дня. Время в ISO-8601 (UTC).")
    @ApiResponse(responseCode = "200", description = "Итоги по валютам")
    @ApiResponse(responseCode = "400", description = "INVALID_PERIOD",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiErrors.Unauthorized
    public EarningsResponse earnings(@RequestHeader(DRIVER_HEADER) Long driverId,
                                     @Parameter(example = "2026-11-17T00:00:00Z") @RequestParam(required = false) Instant from,
                                     @Parameter(example = "2026-11-25T00:00:00Z") @RequestParam(required = false) Instant to) {
        return service.earnings(driverId, from, to);
    }

    @GetMapping("/api/v1/settlements/preview")
    @Operation(summary = "Калькулятор «куда идут деньги»",
            description = "Расчёт по ставкам города и тарифа без записи в БД. "
                    + "Слайд 10: city=MOSCOW, tariff=ECONOM, price=699, km=13.5, parkRate=0.03 → водителю 423,70 ₽.")
    @ApiResponse(responseCode = "200", description = "Статьи расчёта")
    @ApiResponse(responseCode = "400", description = "INVALID_PARAMETER",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "CITY_NOT_FOUND",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "UNKNOWN_TARIFF, INVALID_RATES",
            content = @Content(mediaType = PROBLEM_JSON, schema = @Schema(implementation = ProblemDetail.class)))
    public SettlementResponse preview(
            @Parameter(example = "BISHKEK") @RequestParam String city,
            @Parameter(example = "ECONOM") @RequestParam String tariff,
            @Parameter(description = "Цена поездки", example = "116") @RequestParam BigDecimal price,
            @Parameter(description = "Км подачи + поездки (для топлива)", example = "4.2") @RequestParam BigDecimal km,
            @Parameter(description = "Ставка таксопарка, доля (0.03 = 3%; 0 — без парка)", example = "0.03")
            @RequestParam(defaultValue = "0.03") BigDecimal parkRate) {
        return service.preview(city, tariff, price, km, parkRate);
    }
}
