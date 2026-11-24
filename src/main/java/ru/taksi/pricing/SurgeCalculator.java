package ru.taksi.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Коэффициент спроса (слайд 7, наша модель):
 * <pre>
 *   r = пины / свободные машины
 *   k = 1 + alpha × (r − 1),  k ограничен диапазоном [1; kMax]
 * </pre>
 * Чистая функция без БД — проверяется unit-тестами по таблице со слайда.
 */
public final class SurgeCalculator {

    private static final int RATIO_SCALE = 4;
    private static final int K_SCALE = 2;

    private final BigDecimal alpha;
    private final BigDecimal kMax;

    public SurgeCalculator(BigDecimal alpha, BigDecimal kMax) {
        this.alpha = Objects.requireNonNull(alpha);
        this.kMax = Objects.requireNonNull(kMax);
        if (alpha.signum() < 0) {
            throw new IllegalArgumentException("alpha must be >= 0");
        }
        if (kMax.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("kMax must be >= 1");
        }
    }

    public SurgeResult calculate(long pins, long freeCars) {
        if (pins < 0 || freeCars < 0) {
            throw new IllegalArgumentException("pins and freeCars must be >= 0");
        }
        if (freeCars == 0) {
            // Машин нет: при любом спросе — максимальный коэффициент, без спроса — базовый.
            BigDecimal k = pins == 0 ? BigDecimal.ONE.setScale(K_SCALE) : kMax.setScale(K_SCALE, RoundingMode.HALF_UP);
            return new SurgeResult(pins, freeCars, null, k);
        }
        BigDecimal r = BigDecimal.valueOf(pins).divide(BigDecimal.valueOf(freeCars), RATIO_SCALE, RoundingMode.HALF_UP);
        return new SurgeResult(pins, freeCars, r, fromRatio(r));
    }

    /** k по готовому отношению r; используется и в тестах таблицы со слайда 7. */
    public BigDecimal fromRatio(BigDecimal r) {
        BigDecimal raw = BigDecimal.ONE.add(alpha.multiply(r.subtract(BigDecimal.ONE)));
        BigDecimal clamped = raw.max(BigDecimal.ONE).min(kMax);
        return clamped.setScale(K_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * @param ratio r = pins / freeCars; {@code null}, если свободных машин нет
     */
    public record SurgeResult(long pins, long freeCars, BigDecimal ratio, BigDecimal k) {
    }
}
