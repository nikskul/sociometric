package ru.sociometric.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.sociometric.calculator.SociometricCalculator;
import ru.sociometric.calculator.StatusThresholds;
import ru.sociometric.calculator.SubGroup;
import ru.sociometric.calculator.SurveyData;
import ru.sociometric.domain.GscFormula;
import ru.sociometric.domain.Participant;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбиение на подгруппы по ведущим цифрам кода участника.
 *
 * <p>Подгруппы не бросают исключение, а просто не появляются, поэтому поломка здесь
 * выглядит как «в отчёте пусто» и легко проходит незамеченной.
 */
class SubGroupsTest {

    private static List<Participant> participants(String... codes) {
        return java.util.Arrays.stream(codes)
                .map(code -> new Participant(code, "Участник " + code))
                .toList();
    }

    private static Map<String, List<String>> groupCodes(List<Participant> people) {
        return ResultService.subGroups(people).stream().collect(Collectors.toMap(
                sub -> sub.name(),
                sub -> sub.indices().stream().map(i -> people.get(i).code()).toList()));
    }

    @Test
    @DisplayName("«1А» и «1Б» — одна подгруппа, «2А» и «2Б» — другая")
    void splitsByLeadingDigits() {
        Map<String, List<String>> groups =
                groupCodes(participants("1А", "1Б", "2А", "2Б"));

        assertEquals(Map.of("1", List.of("1А", "1Б"), "2", List.of("2А", "2Б")), groups);
    }

    @Test
    @DisplayName("подгруппа из одного человека индексов не считает")
    void dropsTooSmallGroups() {
        Map<String, List<String>> groups = groupCodes(participants("1А", "1Б", "2А", "3Б"));

        assertEquals(Map.of("1", List.of("1А", "1Б")), groups, "одиночная подгруппа «3» должна отпасть");
    }

    @Test
    @DisplayName("коды без ведущих цифр не создают подгрупп: сравнивать не с чем")
    void letterCodesFallIntoOneGroupSoNoComparison() {
        // «А», «Б», «В» → одна общая подгруппа «». Подгруппа одна — сравнения нет.
        assertTrue(ResultService.subGroups(participants("А", "Б", "В")).isEmpty());
    }

    @Test
    @DisplayName("набор без буквенных хвостов подгрупп не даёт")
    void plainNumericCodesHaveNoGroups() {
        assertTrue(ResultService.subGroups(participants("1", "2", "3")).isEmpty(),
                "коды «1»–«3» — это один опрос, а не три подгруппы");
    }

    @Test
    @DisplayName("пустой опрос не падает")
    void emptySurvey() {
        assertTrue(ResultService.subGroups(List.of()).isEmpty());
    }

    @Test
    @DisplayName("индексы подгруппы считаются только по её участникам")
    void indicesUseOnlyOwnMembers() {
        List<Participant> people = participants("1А", "1Б", "2А", "2Б");
        List<SubGroup> groups = ResultService.subGroups(people);
        assertEquals(2, groups.size());

        // 1А → 1Б, 1Б → 1А: внутри первой подгруппы одна взаимная пара,
        // связи со второй подгруппой в её индексы не входят.
        SurveyData data = new SurveyData(people, List.of(
                SurveyData.ChoiceEntry.of(SurveyData.ChoiceType.SYMPATHY, "1А", "1Б"),
                SurveyData.ChoiceEntry.of(SurveyData.ChoiceType.SYMPATHY, "1Б", "1А"),
                SurveyData.ChoiceEntry.of(SurveyData.ChoiceType.SYMPATHY, "2А", "1А")),
                2, false, GscFormula.MUTUAL);

        var result = SociometricCalculator.calculate(data, new StatusThresholds(9, 9, 9, 1, false), groups);

        assertEquals(2, result.groups().length);
        var first = result.groups()[0];
        assertEquals("1", first.name());
        assertEquals(2, first.indices().n(), "в подгруппе «1» двое участников");
        assertEquals(1, first.indices().mutualPairsCount(), "одна взаимная пара внутри подгруппы");
    }
}
