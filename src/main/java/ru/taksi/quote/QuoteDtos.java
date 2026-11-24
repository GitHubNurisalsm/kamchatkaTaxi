package ru.taksi.quote;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import ru.taksi.geo.GeoPoint;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class QuoteDtos {

    private QuoteDtos() {
    }

    public record PointDto(
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double lon
    ) {
        public GeoPoint toGeoPoint() {
            return new GeoPoint(lat, lon);
        }

        static PointDto of(GeoPoint p) {
            return new PointDto(p.lat(), p.lon());
        }
    }

    @Schema(description = "Город определяется по точке А; точка Б должна быть в том же городе.",
            example = """
            {"from":{"lat":42.8765,"lon":74.6037},"to":{"lat":42.8559,"lon":74.5860},
             "tariff":"ECONOM","options":["CHILD_SEAT"]}""")
    public record CreateQuoteRequest(
            @NotNull @Valid PointDto from,
            @NotNull @Valid PointDto to,
            @NotBlank @Size(max = 32) String tariff,
            @Size(max = 10) List<@NotBlank @Pattern(regexp = "[A-Z_]{1,32}") String> options
    ) {
    }

    public record SurgeDto(
            @Schema(description = "Пины в зоне за окно") long pins,
            @Schema(description = "Свободные машины в зоне") long freeCars,
            @Schema(description = "k — коэффициент спроса") BigDecimal k
    ) {
    }

    public record PickupDto(BigDecimal distanceKm, BigDecimal etaMin, boolean longPickup) {
    }

    public record BreakdownDto(
            @Schema(description = "S — подача") BigDecimal supply,
            @Schema(description = "Доплата за дальнюю подачу") BigDecimal longPickupFee,
            @Schema(description = "k × (c_t × T + c_d × D)") BigDecimal ride,
            @Schema(description = "O — опции") BigDecimal options,
            @Schema(description = "Точная сумма до округления") BigDecimal totalExact
    ) {
    }

    public record QuoteResponse(
            UUID id,
            @Schema(description = "Код города, например BISHKEK") String city,
            String tariff,
            PointDto from,
            PointDto to,
            BigDecimal distanceKm,
            BigDecimal durationMin,
            SurgeDto surge,
            @Schema(description = "null — свободных водителей тарифа рядом нет") PickupDto pickup,
            List<String> options,
            BreakdownDto breakdown,
            @Schema(description = "Итог для пассажира, целые единицы валюты города") BigDecimal total,
            @Schema(description = "Валюта города, ISO 4217: KGS, RUB") String currency,
            Instant createdAt,
            Instant expiresAt,
            boolean expired
    ) {
        static QuoteResponse from(PriceQuote q, Instant now) {
            PickupDto pickup = q.getPickupKm() == null ? null
                    : new PickupDto(q.getPickupKm(), q.getPickupMin(), q.getLongPickupFee().signum() > 0);
            return new QuoteResponse(
                    q.getId(), q.getCityCode(), q.getTariffCode(), PointDto.of(q.getFrom()), PointDto.of(q.getTo()),
                    q.getDistanceKm(), q.getDurationMin(),
                    new SurgeDto(q.getPins(), q.getFreeCars(), q.getSurgeK()),
                    pickup, q.getOptions(),
                    new BreakdownDto(q.getSupplyPrice(), q.getLongPickupFee(), q.getRidePrice(),
                            q.getOptionsPrice(), q.getTotalExact()),
                    q.getTotal(), q.getCurrency(), q.getCreatedAt(), q.getExpiresAt(), q.isExpired(now));
        }
    }
}
