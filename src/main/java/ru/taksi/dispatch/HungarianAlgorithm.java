package ru.taksi.dispatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Задача о назначениях (слайд 8): минимизировать суммарное время подачи.
 * Венгерский алгоритм (Кун — Манкрес) с потенциалами, O(n²·m) для матрицы n × m (n ≤ m).
 *
 * <p>Матрица может быть прямоугольной (заказов и водителей разное число). Запрещённая пара —
 * {@code Double.POSITIVE_INFINITY}: алгоритм сначала максимизирует число допустимых пар,
 * затем минимизирует их суммарную стоимость; запрещённые пары в ответ не попадают.
 */
public final class HungarianAlgorithm {

    /** Стоимость «запрещённой» пары внутри алгоритма: больше любой суммы допустимых стоимостей. */
    private static final double FORBIDDEN_COST = 1e9;

    private HungarianAlgorithm() {
    }

    /** Пара «строка (заказ) → столбец (водитель)» и её стоимость. */
    public record Pair(int row, int col, double cost) {
    }

    public static List<Pair> solve(double[][] cost) {
        int rows = cost.length;
        if (rows == 0) {
            return List.of();
        }
        int cols = cost[0].length;
        if (cols == 0) {
            return List.of();
        }
        for (double[] r : cost) {
            if (r.length != cols) {
                throw new IllegalArgumentException("matrix must be rectangular");
            }
            for (double c : r) {
                if (Double.isNaN(c) || c < 0) {
                    throw new IllegalArgumentException("costs must be >= 0");
                }
            }
        }
        boolean transposed = rows > cols;
        double[][] a = transposed ? transpose(cost) : cost;
        int[] rowToCol = solveRowsNotMoreThanCols(a);

        List<Pair> result = new ArrayList<>();
        for (int i = 0; i < rowToCol.length; i++) {
            int j = rowToCol[i];
            if (j < 0 || Double.isInfinite(a[i][j])) {
                continue;
            }
            result.add(transposed ? new Pair(j, i, a[i][j]) : new Pair(i, j, a[i][j]));
        }
        result.sort((x, y) -> Integer.compare(x.row(), y.row()));
        return List.copyOf(result);
    }

    /** Классическая реализация с потенциалами u, v (индексация с 1, столбец 0 — фиктивный). */
    private static int[] solveRowsNotMoreThanCols(double[][] c) {
        int n = c.length;
        int m = c[0].length;
        double[] u = new double[n + 1];
        double[] v = new double[m + 1];
        int[] p = new int[m + 1];      // p[j] — строка, назначенная столбцу j
        int[] way = new int[m + 1];

        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            double[] minv = new double[m + 1];
            Arrays.fill(minv, Double.POSITIVE_INFINITY);
            boolean[] used = new boolean[m + 1];
            do {
                used[j0] = true;
                int i0 = p[j0];
                double delta = Double.POSITIVE_INFINITY;
                int j1 = -1;
                for (int j = 1; j <= m; j++) {
                    if (!used[j]) {
                        double cur = cell(c, i0 - 1, j - 1) - u[i0] - v[j];
                        if (cur < minv[j]) {
                            minv[j] = cur;
                            way[j] = j0;
                        }
                        if (minv[j] < delta) {
                            delta = minv[j];
                            j1 = j;
                        }
                    }
                }
                for (int j = 0; j <= m; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);
            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }

        int[] rowToCol = new int[n];
        Arrays.fill(rowToCol, -1);
        for (int j = 1; j <= m; j++) {
            if (p[j] != 0) {
                rowToCol[p[j] - 1] = j - 1;
            }
        }
        return rowToCol;
    }

    private static double cell(double[][] c, int i, int j) {
        double x = c[i][j];
        return Double.isInfinite(x) ? FORBIDDEN_COST : x;
    }

    private static double[][] transpose(double[][] a) {
        double[][] t = new double[a[0].length][a.length];
        for (int i = 0; i < a.length; i++) {
            for (int j = 0; j < a[0].length; j++) {
                t[j][i] = a[i][j];
            }
        }
        return t;
    }

    /**
     * Жадный выбор для сравнения (слайд 8, «11 мин»): заказы по очереди, каждому — ближайший
     * свободный водитель. Строки обрабатываются в порядке индексов (у нас — по времени создания заказа).
     */
    public static List<Pair> greedy(double[][] cost) {
        List<Pair> result = new ArrayList<>();
        if (cost.length == 0) {
            return result;
        }
        boolean[] taken = new boolean[cost[0].length];
        for (int i = 0; i < cost.length; i++) {
            int best = -1;
            for (int j = 0; j < cost[i].length; j++) {
                if (!taken[j] && !Double.isInfinite(cost[i][j]) && (best < 0 || cost[i][j] < cost[i][best])) {
                    best = j;
                }
            }
            if (best >= 0) {
                taken[best] = true;
                result.add(new Pair(i, best, cost[i][best]));
            }
        }
        return result;
    }

    public static double total(List<Pair> pairs) {
        return pairs.stream().mapToDouble(Pair::cost).sum();
    }
}
