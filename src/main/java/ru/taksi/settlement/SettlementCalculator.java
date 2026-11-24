package ru.taksi.settlement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Доход водителя (слайд 9):
 * <pre>
 *   Доход = Выручка − Выручка × (комиссия сервиса + парк + налог + взносы) − Топливо
 * </pre>
 * Каждая статья округляется до копеек (HALF_UP), водителю достаётся остаток — поэтому сумма статей
 * всегда равна цене до копейки (этот же инвариант проверяет CHECK в БД). Чистая функция без БД.
 */
public final class SettlementCalculator {

    private static final int MONEY = 2;

    private SettlementCalculator() {
    }

    public static Breakdown calculate(Input in) {
        BigDecimal amount = in.amount().setScale(MONEY, RoundingMode.HALF_UP);
        BigDecimal service = share(amount, in.serviceRate());
        BigDecimal park = share(amount, in.parkRate());
        BigDecimal tax = share(amount, in.incomeTaxRate());
        BigDecimal social = share(amount, in.socialRate());
        BigDecimal fuel = in.fuelKm().multiply(in.fuelCostPerKm()).setScale(MONEY, RoundingMode.HALF_UP);
        BigDecimal net = amount.subtract(service).subtract(park).subtract(tax).subtract(social).subtract(fuel);
        return new Breakdown(amount, service, park, tax, social, fuel, net);
    }

    private static BigDecimal share(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).setScale(MONEY, RoundingMode.HALF_UP);
    }

    public record Input(BigDecimal amount, BigDecimal serviceRate, BigDecimal parkRate, BigDecimal incomeTaxRate,
                        BigDecimal socialRate, BigDecimal fuelKm, BigDecimal fuelCostPerKm) {
        public Input {
            for (BigDecimal v : new BigDecimal[]{amount, serviceRate, parkRate, incomeTaxRate, socialRate,
                    fuelKm, fuelCostPerKm}) {
                Objects.requireNonNull(v);
                if (v.signum() < 0) {
                    throw new IllegalArgumentException("values must be >= 0");
                }
            }
            BigDecimal totalRate = serviceRate.add(parkRate).add(incomeTaxRate).add(socialRate);
            if (totalRate.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("sum of rates must be <= 1");
            }
        }
    }

    /** @param driverNet остаётся водителю; может быть отрицательным, если топливо дороже остатка */
    public record Breakdown(BigDecimal amount, BigDecimal serviceFee, BigDecimal parkFee, BigDecimal incomeTax,
                            BigDecimal socialFee, BigDecimal fuelCost, BigDecimal driverNet) {
    }
}
