package ru.taksi.dispatch;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import ru.taksi.dispatch.HungarianAlgorithm.Pair;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class HungarianAlgorithmTest {

    private static final double X = Double.POSITIVE_INFINITY;

    /** Слайд 8: жадный выбор даёт 2 + 9 = 11 мин, буфер с венгерским алгоритмом — 3 + 3 = 6 мин. */
    @Test
    void slide8Example() {
        double[][] eta = {
                {2, 3},   // заказ А: водитель 1, водитель 2
                {3, 9}    // заказ Б
        };

        List<Pair> optimal = HungarianAlgorithm.solve(eta);
        List<Pair> greedy = HungarianAlgorithm.greedy(eta);

        assertThat(optimal).containsExactly(new Pair(0, 1, 3), new Pair(1, 0, 3));   // А–2, Б–1
        assertThat(HungarianAlgorithm.total(optimal)).isEqualTo(6);
        assertThat(greedy).containsExactly(new Pair(0, 0, 2), new Pair(1, 1, 9));     // А–1, Б–2
        assertThat(HungarianAlgorithm.total(greedy)).isEqualTo(11);
    }

    /** Водителей больше, чем заказов: каждый заказ получает водителя, лишние водители свободны. */
    @Test
    void moreDriversThanOrders() {
        double[][] eta = {{5, 1, 7}};
        assertThat(HungarianAlgorithm.solve(eta)).containsExactly(new Pair(0, 1, 1));
    }

    /** Заказов больше, чем водителей: назначается столько, сколько водителей, с минимальной суммой. */
    @Test
    void moreOrdersThanDrivers() {
        double[][] eta = {{4}, {1}, {3}};
        assertThat(HungarianAlgorithm.solve(eta)).containsExactly(new Pair(1, 0, 1));
    }

    /** Запрещённые пары (далеко, отказался) не назначаются, даже если иначе заказ останется без водителя. */
    @Test
    void forbiddenPairsAreNeverReturned() {
        double[][] eta = {{X, X}, {2, X}};
        assertThat(HungarianAlgorithm.solve(eta)).containsExactly(new Pair(1, 0, 2));
    }

    /** Приоритет — число назначенных заказов, а уже потом сумма: дешёвая пара не должна оставить заказ без водителя. */
    @Test
    void maximizesNumberOfMatchesBeforeCost() {
        double[][] eta = {
                {1, 100},
                {2, X}
        };
        assertThat(HungarianAlgorithm.solve(eta)).containsExactly(new Pair(0, 1, 100), new Pair(1, 0, 2));
    }

    @Test
    void emptyInputs() {
        assertThat(HungarianAlgorithm.solve(new double[0][0])).isEmpty();
        assertThat(HungarianAlgorithm.solve(new double[][]{{}})).isEmpty();
    }

    @Test
    void rejectsInvalidMatrix() {
        assertThatThrownBy(() -> HungarianAlgorithm.solve(new double[][]{{1, 2}, {3}}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HungarianAlgorithm.solve(new double[][]{{-1}}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Риск: ошибка в реализации, незаметная на ручных примерах. Сверяем с полным перебором
     * на случайных матрицах до 6×6 (в том числе прямоугольных и с запрещёнными парами).
     */
    @RepeatedTest(300)
    void matchesBruteForceOnRandomMatrices(org.junit.jupiter.api.RepetitionInfo info) {
        Random rnd = new Random(info.getCurrentRepetition());
        int n = 1 + rnd.nextInt(6);
        int m = 1 + rnd.nextInt(6);
        double[][] c = new double[n][m];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                c[i][j] = rnd.nextInt(10) == 0 ? X : rnd.nextInt(30);
            }
        }

        List<Pair> result = HungarianAlgorithm.solve(c);
        double[] best = bruteForce(c);

        assertThat(result).hasSize((int) best[0]);
        assertThat(HungarianAlgorithm.total(result)).isCloseTo(best[1], within(1e-9));
        assertThat(result.stream().map(Pair::col).distinct()).hasSameSizeAs(result);   // водитель — максимум один заказ
        assertThat(result.stream().map(Pair::row).distinct()).hasSameSizeAs(result);
    }

    /** {максимальное число допустимых пар, минимальная сумма при этом числе}. */
    private static double[] bruteForce(double[][] c) {
        double[] best = {-1, 0};
        search(c, 0, new boolean[c[0].length], 0, 0, best);
        return best;
    }

    private static void search(double[][] c, int row, boolean[] used, int matched, double sum, double[] best) {
        if (row == c.length) {
            if (matched > best[0] || (matched == best[0] && sum < best[1])) {
                best[0] = matched;
                best[1] = sum;
            }
            return;
        }
        search(c, row + 1, used, matched, sum, best);   // строка без пары
        for (int j = 0; j < c[row].length; j++) {
            if (!used[j] && !Double.isInfinite(c[row][j])) {
                used[j] = true;
                search(c, row + 1, used, matched + 1, sum + c[row][j], best);
                used[j] = false;
            }
        }
    }
}
