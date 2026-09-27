package ru.sociometric.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ранги и связанность экспертов.
 *
 * <p>Ожидаемые числа посчитаны независимо от кода, вручную, и обязаны остаться
 * такими же после любых правок. Раньше ранги выдавались по номеру участника, а не
 * по значению оценки: «ранги» монотонно росли с кодом, поэтому и Спирмен, и
 * Kendall W выдавали почти единицу на любых данных и завышали согласованность
 * экспертов с реальным социометрическим статусом.
 */
class ExpertAgreementTest {

    /** Оценки трёх экспертов из демо-опроса, участники идут в порядке кодов. */
    private static final double[][] EXPERTS = {
            {9, 8, 8, 6, 7, 5, 5, 4, 4, 3, 1, 0},
            {10, 9, 7, 5, 6, 5, 4, 4, 3, 3, 1, 0},
            {8, 9, 9, 4, 5, 6, 6, 5, 3, 2, 0, 0}};
    /** Число полученных выборов для тех же двенадцати участников. */
    private static final double[] INCOMING = {3, 4, 3, 4, 2, 4, 3, 3, 3, 2, 0, 0};

    private static double[] expertMeans() {
        double[] means = new double[EXPERTS[0].length];
        for (double[] row : EXPERTS) {
            for (int i = 0; i < row.length; i++) {
                means[i] += row[i] / EXPERTS.length;
            }
        }
        return means;
    }

    @Test
    @DisplayName("ранги строятся по значению, а не по номеру участника")
    void ranksFollowValuesNotPositions() {
        double[] ranks = ExpertAgreement.ranksWithAverageTies(EXPERTS[0]);
        // по возрастанию: 0, 1, 3, 4, 4, 5, 5, 6, 7, 8, 8, 9
        assertEquals(12.0, ranks[0], 1e-9, "девятка — самая большая, ранг 12");
        assertEquals(1.0, ranks[11], 1e-9, "ноль — самый маленький, ранг 1");
        assertEquals(2.0, ranks[10], 1e-9, "единица — вторая по величине, ранг 2");
        assertEquals(3.0, ranks[9], 1e-9, "тройка — третья по величине, ранг 3");
        // 8 встречается дважды, на местах 10 и 11 -> средний ранг 10.5
        assertEquals(10.5, ranks[1], 1e-9);
        assertEquals(10.5, ranks[2], 1e-9);
        // 5 тоже дважды, на местах 6 и 7 -> 6.5
        assertEquals(6.5, ranks[5], 1e-9);
        assertEquals(6.5, ranks[6], 1e-9);
    }

    @Test
    @DisplayName("равные значения получают один средний ранг независимо от позиции")
    void equalValuesShareAverageRank() {
        // 5 и 5 обязаны получить одинаковый ранг, хотя стоят на первом и третьем местах
        assertArrayEqualsWithTolerance(
                new double[]{2.5, 1.0, 2.5, 4.0},
                ExpertAgreement.ranksWithAverageTies(new double[]{5, 3, 5, 7}));
    }

    @Test
    @DisplayName("Kendall W на демо-данных = 0.945, а не 0.9937")
    void kendallWIsNotInflatedByPosition() {
        assertEquals(0.945, ExpertAgreement.kendallW(EXPERTS), 1e-9);
    }

    @Test
    @DisplayName("Спирмен на демо-данных = 0.6061, а не 0.9912")
    void spearmanIsNotInflatedByPosition() {
        assertEquals(0.6061, ExpertAgreement.spearman(expertMeans(), INCOMING), 1e-9);
    }

    @Test
    @DisplayName("Пирсон не использует ранги и не меняется")
    void pearsonIsUnaffectedByRankFix() {
        assertEquals(0.7422, ExpertAgreement.pearson(expertMeans(), INCOMING), 1e-9);
    }

    @Test
    @DisplayName("пропуски выбрасывают участника целиком, а не подставляют средний ранг")
    void kendallWDropsUnratedParticipants() {
        int[] info = new int[2];
        // первый эксперт не оценил двенадцатого участника -> остаётся n = 11
        double[][] withGap = EXPERTS.clone();
        withGap[0] = java.util.Arrays.copyOf(withGap[0], 11);
        Double w = ExpertAgreement.kendallW(withGap, info);
        assertNotNull(w);
        assertEquals(11, info[1], "в расчёт должно попасть одиннадцать участников");
        assertEquals(3, info[0]);
        assertEquals(0.988, w, 1e-9, "W по усечённой выборке");
    }

    @Test
    @DisplayName("у всех согласных экспертов W = 1, у несогласных — меньше")
    void kendallWBoundsStayInUnitRange() {
        double[][] agreeing = {{1, 2, 3, 4}, {1, 2, 3, 4}, {1, 2, 3, 4}};
        assertEquals(1.0, ExpertAgreement.kendallW(agreeing), 1e-9);
        // третий эксперт переставляет всё наоборот
        double[][] mixed = {{1, 2, 3, 4}, {1, 2, 3, 4}, {4, 3, 2, 1}};
        Double w = ExpertAgreement.kendallW(mixed);
        assertNotNull(w);
        assertTrue(w > 0 && w < 1, "ожидалась промежуточная согласованность, получено " + w);
    }

    @Test
    @DisplayName("мало данных даёт null, а не ноль")
    void notEnoughDataYieldsNull() {
        assertNull(ExpertAgreement.kendallW(new double[][]{{1, 2, 3}}));
        assertNull(ExpertAgreement.kendallW(new double[][]{{1, 2}, {2, 1}}));
    }

    private static void assertArrayEqualsWithTolerance(double[] expected, double[] actual) {
        assertEquals(expected.length, actual.length, "разная длина рангов");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], 1e-9, "ранг " + i);
        }
    }
}
