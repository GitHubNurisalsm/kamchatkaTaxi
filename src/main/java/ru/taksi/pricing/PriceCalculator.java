package ru.taksi.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Цена для пассажира (слайд 5, наша модель):
 * <pre>
 *   P = S + k × (c_t × T + c_d × D) + O
 * </pre>
 * S — подача (плюс доплата за дальнюю подачу), k — спрос, c_t/c_d — цена минуты и километра,
 * T/D — минуты и километры маршрута, O — опции. Итог округляется до целых рублей.
 */
public final class PriceCalculator {

    private static final int MONEY_SCALE = 2;

    private PriceCalculator() {
    }

    public static PriceBreakdown calculate(PriceInput in) {
        BigDecimal base = in.pricePerMin().multiply(in.durationMin())
                .add(in.pricePerKm().multiply(in.distanceKm()));
        BigDecimal ride = in.k().multiply(base).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal supply = in.supplyPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal longPickup = in.longPickupFee().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal options = in.optionsPrice().setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        BigDecimal totalExact = supply.add(longPickup).add(ride).add(options);
        BigDecimal total = totalExact.setScale(0, RoundingMode.HALF_UP);
        return new PriceBreakdown(supply, longPickup, ride, options, totalExact, total);
    }

    public record PriceInput(
            BigDecimal supplyPrice,
            BigDecimal longPickupFee,
            BigDecimal k,
            BigDecimal pricePerMin,
            BigDecimal durationMin,
            BigDecimal pricePerKm,
            BigDecimal distanceKm,
            BigDecimal optionsPrice
    ) {
        public PriceInput {
            Objects.requireNonNull(supplyPrice);
            Objects.requireNonNull(longPickupFee);
            Objects.requireNonNull(k);
            Objects.requireNonNull(pricePerMin);
            Objects.requireNonNull(durationMin);
            Objects.requireNonNull(pricePerKm);
            Objects.requireNonNull(distanceKm);
            Objects.requireNonNull(optionsPrice);
            if (k.compareTo(BigDecimal.ONE) < 0) {
                throw new IllegalArgumentException("k must be >= 1");
            }
            for (BigDecimal v : new BigDecimal[]{supplyPrice, longPickupFee, pricePerMin, durationMin,
                    pricePerKm, distanceKm, optionsPrice}) {
                if (v.signum() < 0) {
                    throw new IllegalArgumentException("price components must be >= 0");
                }
            }
        }
    }

    /**
     * @param totalExact точная сумма, копейки
     * @param total      итог для пассажира, целые рубли
     */
    public record PriceBreakdown(
            BigDecimal supplyPrice,
            BigDecimal longPickupFee,
            BigDecimal ridePrice,
            BigDecimal optionsPrice,
            BigDecimal totalExact,
            BigDecimal total
    ) {
    }
}
