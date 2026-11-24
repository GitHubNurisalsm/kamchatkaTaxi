package ru.taksi.pricing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SurgeCalculatorTest {

    private final SurgeCalculator calc = new SurgeCalculator(new BigDecimal("0.4"), new BigDecimal("3"));

    /** Риск: формула k = 1 + 0,4 × (r − 1) расходится с таблицей слайда 7. */
    @ParameterizedTest(name = "r = {0} → k = {1}")
    @CsvSource({
            "0.8, 1.00",   // машин хватает — нижняя граница
            "1.3, 1.12",   // небольшой дефицит
            "2,   1.40",   // час пик
            "4,   2.20",   // сильный дефицит
            "10,  3.00",   // верхняя граница k_max
            "1,   1.00",
            "0,   1.00"
    })
    void matchesSlide7Table(String r, String expectedK) {
        assertThat(calc.fromRatio(new BigDecimal(r))).isEqualByComparingTo(expectedK);
    }

    @Test
    void ratioIsPinsOverFreeCars() {
        SurgeCalculator.SurgeResult res = calc.calculate(13, 10);
        assertThat(res.ratio()).isEqualByComparingTo("1.3");
        assertThat(res.k()).isEqualByComparingTo("1.12");
    }

    /** Риск: деление на ноль, когда свободных машин нет. */
    @Test
    void noFreeCarsWithDemandGivesMaxK() {
        SurgeCalculator.SurgeResult res = calc.calculate(5, 0);
        assertThat(res.ratio()).isNull();
        assertThat(res.k()).isEqualByComparingTo("3.00");
    }

    @Test
    void noFreeCarsAndNoDemandGivesBaseK() {
        assertThat(calc.calculate(0, 0).k()).isEqualByComparingTo("1.00");
    }

    @Test
    void rejectsNegativeCounts() {
        assertThatThrownBy(() -> calc.calculate(-1, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new SurgeCalculator(new BigDecimal("0.4"), new BigDecimal("0.5")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
