package ru.taksi.order;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import ru.taksi.driver.Driver;
import ru.taksi.quote.PriceQuote;
import ru.taksi.quote.QuoteDtos.PointDto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record CreateOrderRequest(
            @Schema(description = "id предложения цены из POST /api/v1/quotes") @NotNull UUID quoteId
    ) {
    }

    @Schema(description = "Водитель — без персональных данных сверх необходимого пассажиру")
    public record DriverInfo(Long id, String name, String tariff, PointDto location) {
        static DriverInfo of(Driver d) {
            PointDto loc = d.getLat() == null ? null : new PointDto(d.getLat(), d.getLon());
            return new DriverInfo(d.getId(), d.getFullName(), d.getTariffCode(), loc);
        }
    }

    public record OrderResponse(
            UUID id,
            OrderStatus status,
            UUID quoteId,
            String city,
            String tariff,
            PointDto from,
            PointDto to,
            @Schema(description = "Цена, зафиксированная в quote (шаг 3: пассажир согласился с ценой)") BigDecimal price,
            String currency,
            List<String> options,
            @Schema(description = "null, пока водитель не назначен") DriverInfo driver,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt,
            Instant cancelledAt,
            String cancelledBy
    ) {
        static OrderResponse of(RideOrder o) {
            PriceQuote q = o.getQuote();
            DriverInfo driver = o.getStatus().holdsDriver() && o.getDriver() != null ? DriverInfo.of(o.getDriver()) : null;
            return new OrderResponse(o.getId(), o.getStatus(), q.getId(), q.getCityCode(), q.getTariffCode(),
                    new PointDto(q.getFrom().lat(), q.getFrom().lon()), new PointDto(q.getTo().lat(), q.getTo().lon()),
                    q.getTotal(), q.getCurrency(), q.getOptions(), driver,
                    o.getCreatedAt(), o.getUpdatedAt(), o.getCompletedAt(), o.getCancelledAt(), o.getCancelledBy());
        }
    }

    public record OrderPage(List<OrderResponse> items, int page, int size, long totalItems, int totalPages) {
    }

    /** created = false — повтор запроса с тем же Idempotency-Key вернул ранее созданный заказ. */
    public record CreateResult(OrderResponse order, boolean created) {
    }
}
