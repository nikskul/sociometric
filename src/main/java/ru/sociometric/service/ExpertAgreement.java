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
     * Считает согласованность экспертов.
     *
     * @param matrix ratings[m][n] — m экспертов, n участников; NaN — эксперт не оценил
     * @return коэффициент согласованности Kendall W, 0..1; null если данных мало
     */
    public static Double kendallW(double[][] matrix) {
        int m = matrix.length;
        if (m < 2) {
            return null;
        }
        int n = 0;
        for (double[] row : matrix) {
            n = Math.max(n, row.length);
        }
        if (n < 3) {
            return null;
        }

        // ранги внутри строки; пропуски (NaN) получают средний ранг
        double[] rankSums = new double[n];
        double tieCorrection = 0;
        for (double[] row : matrix) {
            double[] ranks = ranksWithAverageTies(row);
            Map<Double, Integer> tieSizes = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                rankSums[i] += ranks[i];
                tieSizes.merge(ranks[i], 1, Integer::sum);
            }
            for (int size : tieSizes.values()) {
                tieCorrection += (double) size * size * size - size;
            }
        }

        double meanRankSum = (double) m * (n + 1) / 2;
        double s = 0;
        for (double rankSum : rankSums) {
            s += (rankSum - meanRankSum) * (rankSum - meanRankSum);
        }
        double denominator = (double) m * m * ((double) n * n * n - n) - m * tieCorrection;
        if (denominator <= 0) {
            return null;
        }
        double w = 12 * s / denominator;
        return round4(Math.max(0, Math.min(1, w)));
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

    /** Строит сопоставление рангов «эксперты против социометрии». */
    public static List<RankRow> ranking(List<Participant> participants,
                                        Map<String, Double> expertScore,
                                        int[] sociometricStatus) {
        List<RankRow> rows = new ArrayList<>();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < participants.size(); i++) {
            order.add(i);
        }
        // 1 — самый популярный
        List<Integer> bySociometric = new ArrayList<>(order);
        bySociometric.sort(Comparator.comparingInt((Integer i) -> -sociometricStatus[i]));
        Map<Integer, Integer> sociometricRank = new LinkedHashMap<>();
        for (int position = 0; position < bySociometric.size(); position++) {
            sociometricRank.put(bySociometric.get(position), position + 1);
        }

        List<Integer> byExperts = new ArrayList<>(order);
        byExperts.sort(Comparator.comparingDouble((Integer i) ->
                expertScore.getOrDefault(participants.get(i).code(), 0.0)).reversed());
        Map<Integer, Integer> expertRank = new LinkedHashMap<>();
        for (int position = 0; position < byExperts.size(); position++) {
            expertRank.put(byExperts.get(position), position + 1);
        }

        for (int position = 0; position < bySociometric.size(); position++) {
            int i = bySociometric.get(position);
            int expertPosition = expertRank.get(i);
            rows.add(new RankRow(participants.get(i).code(), participants.get(i).name(),
                    expertPosition, sociometricRank.get(i), expertPosition - sociometricRank.get(i)));
        }
        return rows;
    }

    /**
     * @param code             код участника
     * @param name             имя
     * @param expertRank       место по средней экспертной оценке
     * @param sociometricRank  место по социометрическому статусу
     * @param difference       expertRank - sociometricRank
     */
    public record RankRow(String code, String name, int expertRank, int sociometricRank, int difference) {
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

    /** Ранги 1..n; одинаковые значения получают средний ранг, NaN — средний ранг всех заполненных. */
    static double[] ranksWithAverageTies(double[] values) {
        int n = values.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        List<Integer> indices = new ArrayList<>(Arrays.asList(order));
        indices.sort(Comparator.comparingDouble(i -> Double.isNaN(values[i])
                ? Double.MAX_VALUE : values[i]));

        double[] ranks = new double[n];
        double sumOfFilledRanks = 0;
        int filled = 0;
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(values[i])) {
                ranks[i] = filled + 1;
                sumOfFilledRanks += filled + 1;
                filled++;
            }
        }
        double meanOfFilled = filled == 0 ? 0 : sumOfFilledRanks / filled;
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(values[i])) {
                ranks[i] = meanOfFilled;
            }
        }

        // пересчитываем средние ранги для равных значений
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && !Double.isNaN(values[order[j + 1]]) && values[order[j + 1]] == values[order[i]]) {
                j++;
            }
            if (j > i) {
                double average = (ranks[order[i]] + ranks[order[j]]) / 2;
                for (int k = i; k <= j; k++) {
                    ranks[order[k]] = average;
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
