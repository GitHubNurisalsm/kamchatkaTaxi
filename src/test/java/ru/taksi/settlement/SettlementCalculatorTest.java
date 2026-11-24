package ru.taksi.settlement;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;
import ru.taksi.settlement.SettlementCalculator.Breakdown;
import ru.taksi.settlement.SettlementCalculator.Input;

import java.math.BigDecimal;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementCalculatorTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    /** Слайд 10: поездка за 699 ₽ в Москве — сервис 156,37, парк 20,97, НПД 27,96, топливо 70,00, водителю 423,70. */
    @Test
    void slide10Example() {
        Breakdown b = SettlementCalculator.calculate(new Input(
                bd("699"), bd("0.2237"), bd("0.03"), bd("0.04"), BigDecimal.ZERO,
                bd("13.5"), bd("5.1852")));

        assertThat(b.serviceFee()).isEqualByComparingTo("156.37");
        assertThat(b.parkFee()).isEqualByComparingTo("20.97");
        assertThat(b.incomeTax()).isEqualByComparingTo("27.96");
        assertThat(b.socialFee()).isEqualByComparingTo("0");
        assertThat(b.fuelCost()).isEqualByComparingTo("70.00");
        assertThat(b.driverNet()).isEqualByComparingTo("423.70");
        // 423,70 / 699 ≈ 60,6% — «около 61% цены» (слайд 13)
        assertThat(b.driverNet().divide(b.amount(), 3, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("0.606");
    }

    /** Бишкек: 116 сом «Эконом» — сервис 16%, парк 3%, подоходный 1%, взносы 1%, 4,2 км × 5,5 сом. */
    @Test
    void bishkekExample() {
        Breakdown b = SettlementCalculator.calculate(new Input(
                bd("116"), bd("0.16"), bd("0.03"), bd("0.01"), bd("0.01"),
                bd("4.20"), bd("5.5")));

        assertThat(b.serviceFee()).isEqualByComparingTo("18.56");
        assertThat(b.parkFee()).isEqualByComparingTo("3.48");
        assertThat(b.incomeTax()).isEqualByComparingTo("1.16");
        assertThat(b.socialFee()).isEqualByComparingTo("1.16");
        assertThat(b.fuelCost()).isEqualByComparingTo("23.10");
        assertThat(b.driverNet()).isEqualByComparingTo("68.54");
    }

    /** Без парка (прямая работа) ставка парка 0 — слайд 9. */
    @Test
    void noParkMeansNoParkFee() {
        Breakdown b = SettlementCalculator.calculate(new Input(
                bd("100"), bd("0.2"), BigDecimal.ZERO, bd("0.04"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        assertThat(b.parkFee()).isEqualByComparingTo("0");
        assertThat(b.driverNet()).isEqualByComparingTo("76.00");
    }

    /** Короткая поездка с дальней подачей: топливо больше остатка — доход отрицательный, а не «обрезанный» до нуля. */
    @Test
    void netCanBeNegative() {
        Breakdown b = SettlementCalculator.calculate(new Input(
                bd("50"), bd("0.2"), bd("0.03"), bd("0.04"), BigDecimal.ZERO, bd("10"), bd("5.5")));
        assertThat(b.driverNet()).isNegative();
    }

    /** Риск: из-за округлений статьи не сходятся с ценой. Проверяем на случайных ценах и ставках. */
    @RepeatedTest(200)
    void itemsAlwaysSumToAmount(RepetitionInfo info) {
        Random rnd = new Random(info.getCurrentRepetition());
        BigDecimal amount = BigDecimal.valueOf(rnd.nextInt(500_000), 2);
        BigDecimal service = BigDecimal.valueOf(rnd.nextInt(3000), 4);
        BigDecimal park = BigDecimal.valueOf(rnd.nextInt(1000), 4);
        BigDecimal tax = BigDecimal.valueOf(rnd.nextInt(700), 4);
        BigDecimal social = BigDecimal.valueOf(rnd.nextInt(300), 4);

        Breakdown b = SettlementCalculator.calculate(new Input(amount, service, park, tax, social,
                BigDecimal.valueOf(rnd.nextInt(3000), 2), bd("5.1852")));

        assertThat(b.serviceFee().add(b.parkFee()).add(b.incomeTax()).add(b.socialFee())
                .add(b.fuelCost()).add(b.driverNet())).isEqualByComparingTo(b.amount());
    }

    @Test
    void rejectsInvalidRates() {
        assertThatThrownBy(() -> new Input(bd("100"), bd("0.9"), bd("0.2"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Input(bd("-1"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
