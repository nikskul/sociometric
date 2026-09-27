package ru.sociometric.service;

import ru.sociometric.domain.Participant;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Статистика по экспертным оценкам популярности: разброс мнений,
 * согласованность экспертов (Kendall W) и корреляция оценок с реальным
 * социометрическим статусом (Пирсон и Спирмен).
 */
public final class ExpertAgreement {

    private ExpertAgreement() {
    }

    /**
     * Считает согласованность экспертов по участникам, которых оценили все.
     *
     * <p>Kendall W требует сбалансированного дизайна: если хоть один экспер��
     * пропустил участника, этот участник выбрасывается из расчёта целиком
     * (listwise). Подставлять пропуску «средний ранг» нельзя (§12 спецификации):
     * это выдаёт несуществующую оценку за настоящую и занижает W.
     *
     * @param matrix ratings[m][n] — m экспертов, n участников; NaN — эксперт не оценил
     * @return коэффициент согласованности Kendall W; null если данных мало
     */
    public static Double kendallW(double[][] matrix) {
        return kendallW(matrix, null);
    }

    /**
     * @param info получает число экспертов m и число учтённых участников n
     * @see #kendallW(double[][])
     */
    public static Double kendallW(double[][] matrix, int[] info) {
        int m = matrix.length;
        if (m < 2) {
            if (info != null) {
                info[0] = m;
                info[1] = 0;
            }
            return null;
        }
        int n = 0;
        for (double[] row : matrix) {
            n = Math.max(n, row.length);
        }

        // listwise: оставляем только тех, кого оценили все m экспертов
        boolean[] rated = new boolean[n];
        Arrays.fill(rated, n > 0);
        for (double[] row : matrix) {
            for (int i = 0; i < n; i++) {
                if (i >= row.length || Double.isNaN(row[i])) {
                    rated[i] = false;
                }
            }
        }
        int counted = 0;
        for (boolean value : rated) {
            if (value) {
                counted++;
            }
        }
        if (info != null) {
            info[0] = m;
            info[1] = counted;
        }
        if (counted < 3) {
            return null;
        }

        double[] rankSums = new double[counted];
        double tieCorrection = 0;
        for (double[] row : matrix) {
            double[] all = ranksWithAverageTies(row);
            double[] ranks = new double[counted];
            for (int i = 0, at = 0; i < n; i++) {
                if (rated[i]) {
                    ranks[at++] = all[i];
                }
            }
            // поправка на связанные ранги: группы равных рангов
            Map<Double, Integer> tieSizes = new LinkedHashMap<>();
            for (int i = 0; i < counted; i++) {
                rankSums[i] += ranks[i];
                tieSizes.merge(ranks[i], 1, Integer::sum);
            }
            for (int size : tieSizes.values()) {
                tieCorrection += (double) size * size * size - size;
            }
        }

        double meanRankSum = (double) m * (counted + 1) / 2;
        double s = 0;
        for (double rankSum : rankSums) {
            s += (rankSum - meanRankSum) * (rankSum - meanRankSum);
        }
        double denominator = (double) m * m * ((double) counted * counted * counted - counted)
                - m * tieCorrection;
        if (denominator <= 0) {
            return null;
        }
        // W тождественно неотрицателен: S — сумма квадратов отклонений от среднего
        // ранга, поэтому знак терять нечего. Зажим оставлен как страховка от
        // округления и от превышения 1 при грубой поправке на связи.
        return round4(Math.max(0, Math.min(1, 12 * s / denominator)));
    }

    /**
     * Линейный коэффициент корреляции Пирсона.
     *
     * @return null если ряд вырожден (все значения одинаковы или слишком мало данных)
     */
    public static Double pearson(double[] x, double[] y) {
        if (x.length != y.length || x.length < 3) {
            return null;
        }
        double meanX = mean(x);
        double meanY = mean(y);
        double covariance = 0;
        double varX = 0;
        double varY = 0;
        for (int i = 0; i < x.length; i++) {
            double dx = x[i] - meanX;
            double dy = y[i] - meanY;
            covariance += dx * dy;
            varX += dx * dx;
            varY += dy * dy;
        }
        if (varX <= 0 || varY <= 0) {
            return null;
        }
        return round4(covariance / Math.sqrt(varX * varY));
    }

    /**
     * Ранговая корреляция Спирмена (корреляция Пирсона по рангам).
     */
    public static Double spearman(double[] x, double[] y) {
        if (x.length != y.length || x.length < 3) {
            return null;
        }
        double[] ranksX = ranksWithAverageTies(x);
        double[] ranksY = ranksWithAverageTies(y);
        return pearson(ranksX, ranksY);
    }

    /**
     * Строит сопоставление рангов «эксперты против социометрии».
     *
     * <p>При ничьих участники получают общий средний ранг, а не разные места
     * по порядку в списке: иначе колонка «расхождение» показывала бы не расхождение
     * оценок, а разницу в порядке participants.
     *
     * <p>1 — самый популярный; ранг считается по убыванию, поэтому функция
     * разворачивает обычные возрастающие ранги.
     */
    public static List<RankRow> ranking(List<Participant> participants,
                                        Map<String, Double> expertScore,
                                        int[] sociometricStatus) {
        List<RankRow> rows = new ArrayList<>();
        int n = participants.size();
        double[] sociometric = new double[n];
        double[] expert = new double[n];
        for (int i = 0; i < n; i++) {
            sociometric[i] = sociometricStatus[i];
            expert[i] = expertScore.getOrDefault(participants.get(i).code(), 0.0);
        }
        double[] sociometricRank = ranksDescending(sociometric);
        double[] expertRank = ranksDescending(expert);

        Integer[] bySociometric = new Integer[n];
        for (int i = 0; i < n; i++) {
            bySociometric[i] = i;
        }
        Arrays.sort(bySociometric, Comparator.comparingDouble((Integer i) -> sociometricRank[i])
                .thenComparingInt(i -> i));
        for (int position = 0; position < n; position++) {
            int i = bySociometric[position];
            rows.add(new RankRow(participants.get(i).code(), participants.get(i).name(),
                    round4(expertRank[i]), round4(sociometricRank[i]),
                    round4(expertRank[i] - sociometricRank[i])));
        }
        return rows;
    }

    /** Ранги 1..n по убыванию значения; равные значения получают общий средний ранг. */
    private static double[] ranksDescending(double[] values) {
        List<Integer> order = new ArrayList<>(values.length);
        for (int i = 0; i < values.length; i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer>comparingDouble((Integer i) -> -values[i]).thenComparingInt(i -> i));
        double[] ranks = new double[values.length];
        int i = 0;
        while (i < order.size()) {
            int j = i;
            while (j + 1 < order.size() && values[order.get(j + 1)] == values[order.get(i)]) {
                j++;
            }
            double average = (i + j) / 2d + 1;
            for (int k = i; k <= j; k++) {
                ranks[order.get(k)] = average;
            }
            i = j + 1;
        }
        return ranks;
    }

    /**
     * @param code             код участника
     * @param name             имя
     * @param expertRank       место по средней экспертной оценке (дробное при ничьих)
     * @param sociometricRank  место по социометрическому статусу (дробное при ничьих)
     * @param difference       expertRank - sociometricRank
     */
    public record RankRow(String code, String name, double expertRank, double sociometricRank,
                          double difference) {
    }

    /** Группирует оценки по участникам, пропуская тех, кого не оценивали. */
    public static Map<String, double[]> byParticipant(List<String> expertNames, double[][] ratings,
                                                      List<Participant> participants) {
        Set<String> codes = new LinkedHashSet<>();
        participants.forEach(participant -> codes.add(participant.code()));
        Map<String, List<Double>> collected = new LinkedHashMap<>();
        for (String code : codes) {
            collected.put(code, new ArrayList<>());
        }
        for (double[] row : ratings) {
            for (int j = 0; j < row.length; j++) {
                if (!Double.isNaN(row[j]) && j < participants.size()) {
                    collected.get(participants.get(j).code()).add(row[j]);
                }
            }
        }
        Map<String, double[]> result = new LinkedHashMap<>();
        collected.forEach((code, values) -> result.put(code,
                values.stream().mapToDouble(Double::doubleValue).toArray()));
        return result;
    }

    static double mean(double[] values) {
        if (values.length == 0) {
            return 0;
        }
        double sum = 0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    static double sd(double[] values) {
        if (values.length < 2) {
            return 0;
        }
        double mean = mean(values);
        double sum = 0;
        for (double value : values) {
            double d = value - mean;
            sum += d * d;
        }
        return Math.sqrt(sum / values.length);
    }

    /**
     * Ранги 1..n по возрастанию значения; равные значения получают средний ранг,
     * пропуски (NaN) — средний ранг всех заполненных.
     *
     * <p>Ранги присваиваются строго по отсортированному порядку значений. Если
     * раздавать их по индексам исходного массива, получится последовательность
     * 1, 2, 3, …, то есть «ранги» станут номерами участников: корреляция с любой
     * величиной, упорядоченной по коду, окажется почти единичной.
     */
    static double[] ranksWithAverageTies(double[] values) {
        int n = values.length;
        List<Integer> order = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(values[i])) {
                order.add(i);
            }
        }
        // возрастание по значению; при равенстве — по индексу, чтобы порядок был стабильным
        order.sort(Comparator.<Integer>comparingDouble(i -> values[i]).thenComparingInt(i -> i));

        double[] ranks = new double[n];
        int filled = order.size();
        for (int position = 0; position < filled; position++) {
            ranks[order.get(position)] = position + 1;
        }
        // пропуски получают средний ранг заполненных, но в Kendall W не попадают вовсе
        double meanOfFilled = filled == 0 ? 0 : (filled + 1) / 2d;
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(values[i])) {
                ranks[i] = meanOfFilled;
            }
        }

        // усредняем ранги внутри групп равных значений — идём по отсортированному порядку
        int i = 0;
        while (i < filled) {
            int j = i;
            while (j + 1 < filled && values[order.get(j + 1)] == values[order.get(i)]) {
                j++;
            }
            if (j > i) {
                double average = (i + j) / 2d + 1;
                for (int k = i; k <= j; k++) {
                    ranks[order.get(k)] = average;
                }
            }
            i = j + 1;
        }
        return ranks;
    }

    private static double round4(double value) {
        return Math.round(value * 10000d) / 10000d;
    }
}
