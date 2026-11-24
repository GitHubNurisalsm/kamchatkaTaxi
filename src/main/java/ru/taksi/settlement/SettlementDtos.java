package ru.taksi.settlement;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SettlementDtos {

    private SettlementDtos() {
    }

    /** Одна строка таблицы «Куда идут деньги» (слайд 10). */
    public record Item(
            @Schema(example = "Сервис") String name,
            @Schema(description = "Как посчитано", example = "699.00 × 22.37%") String calculation,
            BigDecimal amount,
            @Schema(description = "Доля от цены, %", example = "22.4") BigDecimal sharePercent
    ) {
    }

    public record SettlementResponse(
            @Schema(description = "null — для расчёта-превью") UUID orderId,
            String currency,
            @Schema(description = "Цена поездки — выручка") BigDecimal amount,
            List<Item> items,
            @Schema(description = "Остаётся водителю") BigDecimal driverNet,
            @Schema(description = "Доля водителя от цены, %") BigDecimal driverSharePercent,
            Instant createdAt
    ) {
        static SettlementResponse of(UUID orderId, String currency, String taxName, SettlementCalculator.Input in,
                                     SettlementCalculator.Breakdown b, Instant createdAt) {
            BigDecimal amount = b.amount();
            List<Item> items = new java.util.ArrayList<>();
            items.add(new Item("Сервис", amount + " × " + pct(in.serviceRate()) + "%", b.serviceFee(), share(b.serviceFee(), amount)));
            items.add(new Item("Таксопарк", amount + " × " + pct(in.parkRate()) + "%", b.parkFee(), share(b.parkFee(), amount)));
            items.add(new Item(taxName, amount + " × " + pct(in.incomeTaxRate()) + "%", b.incomeTax(), share(b.incomeTax(), amount)));
            if (in.socialRate().signum() > 0) {
                items.add(new Item("Страховые взносы", amount + " × " + pct(in.socialRate()) + "%", b.socialFee(),
                        share(b.socialFee(), amount)));
            }
            items.add(new Item("Топливо", in.fuelKm() + " км × " + in.fuelCostPerKm().stripTrailingZeros().toPlainString(),
                    b.fuelCost(), share(b.fuelCost(), amount)));
            return new SettlementResponse(orderId, currency, amount, items, b.driverNet(), share(b.driverNet(), amount),
                    createdAt);
        }

        static SettlementResponse of(RideSettlement s) {
            SettlementCalculator.Input in = new SettlementCalculator.Input(s.getAmount(), s.getServiceRate(),
                    s.getParkRate(), s.getIncomeTaxRate(), s.getSocialRate(), s.getFuelKm(), s.getFuelCostPerKm());
            SettlementCalculator.Breakdown b = new SettlementCalculator.Breakdown(s.getAmount(), s.getServiceFee(),
                    s.getParkFee(), s.getIncomeTax(), s.getSocialFee(), s.getFuelCost(), s.getDriverNet());
            return of(s.getOrderId(), s.getCurrency(), s.getIncomeTaxName(), in, b, s.getCreatedAt());
        }

        private static String pct(BigDecimal rate) {
            return rate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString();
        }

        private static BigDecimal share(BigDecimal part, BigDecimal amount) {
            if (amount.signum() == 0) {
                return BigDecimal.ZERO;
            }
            return part.multiply(BigDecimal.valueOf(100)).divide(amount, 1, RoundingMode.HALF_UP);
        }
    }

    public record EarningsResponse(Long driverId, Instant from, Instant to, List<EarningsTotals> totals) {
    }
}
