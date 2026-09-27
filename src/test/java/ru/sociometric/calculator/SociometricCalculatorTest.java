package ru.sociometric.calculator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.sociometric.domain.GscFormula;
import ru.sociometric.domain.Participant;
import ru.sociometric.domain.StatusCategory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Проверка ядра расчёта на примерах, посчитанных вручную.
 *
 * <p>Пример 1 (n = 4, ручной расчет):
 * <pre>
 *           выбрал
 *        A  B  C  D   исходящие
 *   A [   0  1  1  0 ]     2
 *   B [   1  0  1  0 ]     2
 *   C [   1  0  0  1 ]     2
 *   D [   0  0  1  0 ]     1
 *   входящие 2  1  3  1
 * </pre>
 * μ = 1.75, σ = 0.8292 → пороги: звезда 3, предпочитаемый 2, принятый 0
 * C — звезда, A — предпочитаемый, B и D — принятые.
 * Взаимные пары: (A,B), (A,C), (C,D) → M = 3. Односторонняя: (B→C).
 * GSC = 2*3 / 12 = 0.5; взаимность = 6/7 ≈ 0.857; плотность = 7/12 ≈ 0.583.
 * Граф: A-B, A-C, B-C, C-D → сумма расстояний 8, диаметр 2, радиус 2.
 */
class SociometricCalculatorTest {

    private static final List<Participant> ABCD = List.of(
            new Participant("A", "Аня"),
            new Participant("B", "Боря"),
            new Participant("C", "Вера"),
            new Participant("D", "Глеб"));

    private static SurveyData data(List<Participant> participants, int limit, String... pairs) {
        List<SurveyData.ChoiceEntry> choices = new java.util.ArrayList<>();
        for (String pair : pairs) {
            String[] parts = pair.split("->");
            choices.add(SurveyData.ChoiceEntry.of(SurveyData.ChoiceType.SYMPATHY, parts[0], parts[1]));
        }
        return new SurveyData(participants, choices, limit, false, GscFormula.MUTUAL);
    }

    @Test
    @DisplayName("Матрица, входящие и исходящие выборы считаются по столбцам и строкам")
    void buildsMatrixAndStatuses() {
        SociometricResult result = SociometricCalculator.calculate(
                data(ABCD, 3, "A->B", "A->C", "B->A", "B->C", "C->A", "C->D", "D->C"));

        assertArrayEquals(new int[][]{
                {0, 1, 1, 0},
                {1, 0, 1, 0},
                {1, 0, 0, 1},
                {0, 0, 1, 0}}, result.matrix());
        assertArrayEquals(new int[]{2, 1, 3, 1}, result.incoming());
        assertArrayEquals(new int[]{2, 2, 2, 1}, result.outgoing());
        assertEquals(4, result.n());
    }

    @Test
    @DisplayName("Категории статуса по правилу «среднее ± σ»")
    void classifiesStatuses() {
        SociometricResult result = SociometricCalculator.calculate(
                data(ABCD, 3, "A->B", "A->C", "B->A", "B->C", "C->A", "C->D", "D->C"));

        assertEquals(0.8292, SociometricCalculator.stdDev(result.incoming(),
                SociometricCalculator.mean(result.incoming())), 0.0001);
        assertArrayEquals(new String[]{"PREFERRED", "ACCEPTED", "STAR", "ACCEPTED"}, result.categories());
        assertArrayEquals(new String[]{"C"}, result.stars());
        assertEquals(0, result.isolates().length);
    }

    @Test
    @DisplayName("Взаимные и односторонние пары найдены верно")
    void findsMutualAndUnidirectionalPairs() {
        SociometricResult result = SociometricCalculator.calculate(
                data(ABCD, 3, "A->B", "A->C", "B->A", "B->C", "C->A", "C->D", "D->C"));

        assertEquals(3, result.mutualPairs().length);
        assertEquals(1, result.unidirectionalPairs().length);
        assertEquals(1, result.unidirectionalPairs()[0].a(), "выбрал Боря (индекс 1)");
        assertEquals(2, result.unidirectionalPairs()[0].b(), "Вера Борю не выбрала");
    }

    @Test
    @DisplayName("Индексы группы: GSC, взаимность, плотность")
    void computesGroupIndices() {
        SociometricResult result = SociometricCalculator.calculate(
                data(ABCD, 3, "A->B", "A->C", "B->A", "B->C", "C->A", "C->D", "D->C"));

        GroupIndices indices = result.indices();
        assertEquals(7, indices.totalSelections());
        assertEquals(1.75, indices.meanSelectionsPerPerson(), 1e-9);
        assertEquals(3, indices.mutualPairsCount());
        assertEquals(1, indices.unidirectionalPairsCount());
        assertEquals(6, indices.possiblePairsCount());
        assertEquals(12, indices.possibleSelectionsCount());
        assertEquals(0.5, indices.gsc(), 1e-9);
        assertEquals(0.8571, indices.reciprocityIndex(), 1e-4);
        assertEquals(0.5833, indices.density(), 1e-4);
        assertEquals(0.0, indices.isolationIndex(), 1e-9);
        assertEquals(0.0, indices.rejectedIndex(), 1e-9);
        assertEquals(List.of(2, 1, 2, 1), indices.mutualChoiceDistribution());
    }

    @Test
    @DisplayName("Метрики графа: расстояния, диаметр, радиус, степени")
    void computesGraphMetrics() {
        SociometricResult result = SociometricCalculator.calculate(
                data(ABCD, 3, "A->B", "A->C", "B->A", "B->C", "C->A", "C->D", "D->C"));

        assertArrayEquals(new int[]{2, 2, 3, 1}, result.outdegree());
        assertArrayEquals(new int[][]{
                {0, 1, 1, 2},
                {1, 0, 1, 2},
                {1, 1, 0, 1},
                {2, 2, 1, 0}}, result.distances());
        assertEquals(1.3333, result.meanDistance(), 1e-4);
        assertEquals(Integer.valueOf(2), result.diameter());
        assertEquals(1, result.radius(), "Вера (C) — центр графа, расстояние до всех равно 1");
        assertEquals(1, result.connectedComponents());
        assertArrayEquals(new int[]{1}, result.componentRadii());
        assertArrayEquals(new int[]{2}, result.componentDiameters());
        assertEquals(6, result.connectedPairs());
        assertEquals(6, result.possiblePairs());
    }

    @Test
    @DisplayName("Изолированный, аутсайдер и «нет данных» — три разные категории")
    void separatesIsolatedOutsiderAndNoData() {
        List<Participant> people = List.of(new Participant("A", "Аня"), new Participant("B", "Боря"),
                new Participant("C", "Вера"), new Participant("D", "Даня"));
        // A выбрал B. Ответили A, B и C; D не отвечал вовсе.
        SociometricResult result = SociometricCalculator.calculate(
                new SurveyData(people, List.of(
                        SurveyData.ChoiceEntry.of(SurveyData.ChoiceType.SYMPATHY, "A", "B")),
                        3, false, GscFormula.MUTUAL, java.util.Set.of("A", "B", "C")));

        assertEquals(StatusCategory.ISOLATED.name(), result.categories()[0], "A выбирал, но его не выбрали");
        assertEquals(StatusCategory.PREFERRED.name(), result.categories()[1], "B выбран единственным A");
        assertEquals(StatusCategory.OUTSIDER.name(), result.categories()[2],
                "C отвечал «никого не выбрал» — это ответ, а не отсутствие ответа");
        assertEquals(StatusCategory.NO_DATA.name(), result.categories()[3], "D не отвечал — данных нет");
        assertArrayEquals(new String[]{"A", "C"}, result.isolates(),
                "не ответивший не изолирован, его в списке изолированных быть не должно");
    }

    @Test
    @DisplayName("Без отметок об ответе отвечавшим считается сделавший хотя бы один выбор")
    void fallsBackToSelectionsWhenNoResponseMarks() {
        SociometricResult result = SociometricCalculator.calculate(
                data(List.of(new Participant("A", "Аня"), new Participant("B", "Боря"),
                        new Participant("C", "Вера")), 3, "A->B"));

        assertEquals(StatusCategory.ISOLATED.name(), result.categories()[0]);
        assertEquals(StatusCategory.PREFERRED.name(), result.categories()[1]);
        assertEquals(StatusCategory.NO_DATA.name(), result.categories()[2],
                "без отметок «не выбрал» от «не ответил» не отличить — считаем вторым");
    }

    @Test
    @DisplayName("Фиксированные пороги дают заданные категории")
    void appliesManualThresholds() {
        SociometricResult result = SociometricCalculator.calculate(
                data(ABCD, 3, "A->B", "A->C", "B->A", "B->C", "C->A", "C->D", "D->C"),
                StatusThresholds.fixed(2, 1, 1, 1), List.of());

        assertArrayEquals(new String[]{"STAR", "PREFERRED", "STAR", "PREFERRED"}, result.categories());
    }

    @Test
    @DisplayName("Антипатии дают отдельные категории отвержения")
    void classifiesAntipathies() {
        List<SurveyData.ChoiceEntry> choices = new java.util.ArrayList<>();
        choices.add(SurveyData.ChoiceEntry.of(SurveyData.ChoiceType.SYMPATHY, "A", "B"));
        choices.add(SurveyData.ChoiceEntry.of(SurveyData.ChoiceType.SYMPATHY, "B", "A"));
        choices.add(SurveyData.ChoiceEntry.of(SurveyData.ChoiceType.SYMPATHY, "A", "C"));
        choices.add(SurveyData.ChoiceEntry.of(SurveyData.ChoiceType.ANTIPATHY, "A", "C"));
        choices.add(SurveyData.ChoiceEntry.of(SurveyData.ChoiceType.ANTIPATHY, "B", "C"));
        choices.add(SurveyData.ChoiceEntry.of(SurveyData.ChoiceType.ANTIPATHY, "C", "C"));

        SociometricResult result = SociometricCalculator.calculate(
                new SurveyData(ABCD, choices, 3, false, GscFormula.MUTUAL),
                StatusThresholds.fixed(9, 9, 1, 2), List.of());

        assertArrayEquals(new int[]{0, 0, 2, 0}, result.antipathyIncoming());
        assertArrayEquals(new String[]{"NOT_REJECTED", "NOT_REJECTED", "MOST_REJECTED", "NOT_REJECTED"},
                result.antipathyCategories());
        assertArrayEquals(new int[][]{{0, 0, 1, 0}, {0, 0, 1, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}},
                result.antiMatrix(), "выбор самого себя не учитывается");
    }

    @Test
    @DisplayName("Ранжированный опросник: первый выбор весит больше последнего")
    void appliesRankWeights() {
        List<SurveyData.ChoiceEntry> choices = List.of(
                new SurveyData.ChoiceEntry(SurveyData.ChoiceType.SYMPATHY, "A", "B", 1, 1),
                new SurveyData.ChoiceEntry(SurveyData.ChoiceType.SYMPATHY, "A", "C", 2, 1),
                new SurveyData.ChoiceEntry(SurveyData.ChoiceType.SYMPATHY, "A", "D", 3, 1),
                new SurveyData.ChoiceEntry(SurveyData.ChoiceType.SYMPATHY, "B", "A", 1, 1),
                new SurveyData.ChoiceEntry(SurveyData.ChoiceType.SYMPATHY, "B", "C", 1, 1),
                new SurveyData.ChoiceEntry(SurveyData.ChoiceType.SYMPATHY, "C", "B", 1, 1),
                new SurveyData.ChoiceEntry(SurveyData.ChoiceType.SYMPATHY, "D", "A", 1, 1));

        SociometricResult result = SociometricCalculator.calculate(
                new SurveyData(ABCD, choices, 3, true, GscFormula.MUTUAL));

        assertEquals(3, result.matrix()[0][1], "первый выбор весит 3");
        assertEquals(2, result.matrix()[0][2], "второй выбор весит 2");
        assertEquals(1, result.matrix()[0][3], "третий выбор весит 1");
        assertArrayEquals(new int[]{2, 2, 2, 1}, result.incoming(), "число полученных выборов не взвешено");
        assertArrayEquals(new int[]{6, 6, 5, 1}, result.weightedIncoming(), "взвешенный статус учитывает ранг");
    }

    @Test
    @DisplayName("Две формулы GSC совпадают на невзвешенных данных и расходятся на ранжированных")
    void comparesGscFormulas() {
        // A выбрал B первым (вес 3), B выбрал A вторым (вес 2)
        List<SurveyData.ChoiceEntry> choices = List.of(
                new SurveyData.ChoiceEntry(SurveyData.ChoiceType.SYMPATHY, "A", "B", 1, 1),
                new SurveyData.ChoiceEntry(SurveyData.ChoiceType.SYMPATHY, "B", "A", 2, 1));

        double mutual = SociometricCalculator.calculate(
                new SurveyData(ABCD, choices, 3, true, GscFormula.MUTUAL)).indices().gsc();
        double doublePass = SociometricCalculator.calculate(
                new SurveyData(ABCD, choices, 3, true, GscFormula.MUTUAL_DOUBLE_PASS)).indices().gsc();

        assertEquals(2.0 / 12, mutual, 1e-9, "2M/(n(n-1)) считает только факт взаимности");
        // L = 3 — максимальный вес в матрице; нормировка на L^2 держит коэффициент в 0..1,
        // иначе на ранжированных данных сумма весов легко переваливала за единицу
        assertEquals(2 * 3.0 * 2 / (4 * 3 * 9), doublePass, 1e-9,
                "Σ Aij·Aji / (n(n-1)·L²) с удвоением за упорядоченные пары");
    }

    @Test
    @DisplayName("Несвязный граф: расстояния -1, средний путь по достижимым парам, диаметр null")
    void handlesDisconnectedGraph() {
        SociometricResult result = SociometricCalculator.calculate(
                data(ABCD, 3, "A->B", "B->A", "C->D", "D->C"));

        assertEquals(-1, result.distances()[0][2]);
        assertEquals(2, result.connectedPairs());
        assertEquals(6, result.possiblePairs());
        assertEquals(1.0, result.meanDistance(), 1e-9);
        assertNull(result.diameter(), "у несвязного графа диаметр не определён");
        assertEquals(2, result.connectedComponents());
        assertArrayEquals(new int[]{1, 1}, result.componentRadii());
        assertArrayEquals(new int[]{1, 1}, result.componentDiameters());
        assertEquals(1, result.radius(), "радиус — минимум по компонентам, а не бесконечность");
        assertEquals(0.3333, result.indices().density(), 1e-4);
    }

    @Test
    @DisplayName("Один участник не приводит к делению на ноль")
    void handlesSingleParticipant() {
        SociometricResult result = SociometricCalculator.calculate(
                data(List.of(new Participant("A", "Аня")), 3));

        assertEquals(1, result.n());
        assertEquals(0, result.indices().gsc());
        assertEquals(0, result.indices().reciprocityIndex());
        assertEquals(0, result.indices().density());
        assertEquals(Integer.valueOf(0), result.diameter(), "единственная вершина образует связный граф");
        assertEquals(0, result.radius());
        assertEquals(0.0, result.meanDistance());
        assertEquals(StatusCategory.NO_DATA.name(), result.categories()[0],
                "единственный участник ничего не выбрал, и ответа от него не было");
    }

    @Test
    @DisplayName("Пустой опрос дает понятную ошибку")
    void rejectsEmptySurvey() {
        assertThrows(IllegalArgumentException.class, () -> SociometricCalculator.calculate(data(List.of(), 3)));
    }

    @Test
    @DisplayName("Повторяющиеся коды участников отклоняются")
    void rejectsDuplicateCodes() {
        List<Participant> duplicated = List.of(new Participant("A", "Аня"), new Participant("A", "Аня-2"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SociometricCalculator.calculate(data(duplicated, 3)));
        assertTrue(error.getMessage().contains("уникальны"));
    }

    @Test
    @DisplayName("Самая тесная микрогруппа ищется из взаимных выборов")
    void findsDensestMicroGroup() {
        List<Participant> six = List.of(
                new Participant("1", "Первый"), new Participant("2", "Второй"), new Participant("3", "Третий"),
                new Participant("4", "Четвертый"), new Participant("5", "Пятый"), new Participant("6", "Шестой"));

        SociometricResult result = SociometricCalculator.calculate(
                data(six, 2, "1->2", "2->1", "1->3", "3->1", "2->3", "3->2", "3->4", "4->3", "5->6", "6->5"));

        assertEquals(4, result.strongest().length);
        assertTrue(List.of(result.strongest()).contains("1")
                && List.of(result.strongest()).contains("4"));
    }

    @Test
    @DisplayName("Индексы по подгруппам считаются отдельно")
    void computesSubGroupIndices() {
        SociometricResult result = SociometricCalculator.calculate(
                data(ABCD, 3, "A->B", "B->A", "A->C", "C->A", "C->D", "D->C"),
                StatusThresholds.defaults(),
                List.of(new SubGroup("первые двое", List.of(0, 1))));

        assertEquals(1, result.groups().length);
        SociometricResult.GroupIndex group = result.groups()[0];
        assertEquals("первые двое", group.name());
        assertEquals(2, group.n());
        assertEquals(1.0, group.indices().gsc(), 1e-9, "в паре A-B оба выбрали друг друга");
    }
}
