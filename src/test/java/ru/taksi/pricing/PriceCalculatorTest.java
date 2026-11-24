package ru.taksi.pricing;

import org.junit.jupiter.api.Test;
import ru.taksi.pricing.PriceCalculator.PriceBreakdown;
import ru.taksi.pricing.PriceCalculator.PriceInput;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriceCalculatorTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    /** Риск: ошибка в формуле P = S + k × (c_t × T + c_d × D) + O. Числа — слайд 10. */
    @Test
    void endToEndExampleFromSlide10Gives699() {
        PriceBreakdown p = PriceCalculator.calculate(new PriceInput(
                bd("189"), BigDecimal.ZERO, bd("1.12"),
                bd("13"), bd("25"), bd("13"), bd("10"), BigDecimal.ZERO));

        assertThat(p.ridePrice()).isEqualByComparingTo("509.60");
        assertThat(p.totalExact()).isEqualByComparingTo("698.60");
        assertThat(p.total()).isEqualByComparingTo("699");
    }

    /** Риск: k применяется не к той части цены. Слайд 6: (458 + 175) × 1,12 ≈ 709, подача и опции без k. */
    @Test
    void surgeAppliesOnlyToTimeAndDistance() {
        // 458 ₽ = 13 × 35,23 мин, 175 ₽ = 13 × 13,46 км (округлено как в детализации)
        PriceBreakdown p = PriceCalculator.calculate(new PriceInput(
                bd("233"), bd("150"), bd("1.12"),
                BigDecimal.ONE, bd("458"), BigDecimal.ONE, bd("175"), bd("150")));

        assertThat(p.ridePrice()).isEqualByComparingTo("708.96");
        assertThat(p.supplyPrice()).isEqualByComparingTo("233");
        assertThat(p.optionsPrice()).isEqualByComparingTo("150");
        assertThat(p.total()).isEqualByComparingTo("1242");
    }

    /** Риск: неправильное округление до рублей (HALF_UP на границе 0,50). */
    @Test
    void roundsHalfUpToWholeRubles() {
        PriceBreakdown p = PriceCalculator.calculate(new PriceInput(
                bd("100.50"), BigDecimal.ZERO, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        assertThat(p.total()).isEqualByComparingTo("101");
    }

    /** Риск: инвариант «итог = сумма частей» нарушается из-за округлений (его же проверяет CHECK в БД). */
    @Test
    void totalExactIsSumOfComponents() {
        PriceBreakdown p = PriceCalculator.calculate(new PriceInput(
                bd("189"), bd("150"), bd("1.37"),
                bd("13"), bd("17.3"), bd("13"), bd("7.21"), bd("300")));
        assertThat(p.totalExact()).isEqualByComparingTo(
                p.supplyPrice().add(p.longPickupFee()).add(p.ridePrice()).add(p.optionsPrice()));
        assertThat(p.totalExact().scale()).isEqualTo(2);
    }

    @Test
    void rejectsSurgeBelowOne() {
        assertThatThrownBy(() -> new PriceInput(bd("1"), BigDecimal.ZERO, bd("0.9"),
                bd("1"), bd("1"), bd("1"), bd("1"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeComponents() {
        assertThatThrownBy(() -> new PriceInput(bd("1"), BigDecimal.ZERO, BigDecimal.ONE,
                bd("1"), bd("-1"), bd("1"), bd("1"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
