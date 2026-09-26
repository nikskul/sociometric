package ru.sociometric.calculator;

import java.util.List;

/**
 * Структурные индексы группы по социометрии Морено.
 *
 * @param n                        число участников
 * @param totalSelections          общее число сделанных выборов
 * @param meanSelectionsPerPerson  среднее число выборов на участника
 * @param mutualPairsCount         число пар взаимного выбора
 * @param unidirectionalPairsCount число пар одностороннего выбора
 * @param possiblePairsCount       n*(n-1)/2 — число неупорядоченных пар
 * @param possibleSelectionsCount  n*(n-1) — число упорядоченных пар
 * @param gsc                      коэффициент групповой сплоченности, 0..1
 * @param reciprocityIndex         индекс взаимности (доля выбора, получившего ответ), 0..1
 * @param density                  плотность графа предпочтений, 0..1
 * @param isolationIndex           доля участников без входящих выборов
 * @param rejectedIndex            доля участников, получивших хотя бы одну антипатию
 * @param negativeStatusIndex      доля участников с негативным статусом (изолированные и отвергаемые)
 * @param mutualChoiceDistribution распределение числа взаимных пар по участникам
 */
public record GroupIndices(
        int n,
        int totalSelections,
        double meanSelectionsPerPerson,
        int mutualPairsCount,
        int unidirectionalPairsCount,
        int possiblePairsCount,
        int possibleSelectionsCount,
        double gsc,
        double reciprocityIndex,
        double density,
        double isolationIndex,
        double rejectedIndex,
        double negativeStatusIndex,
        List<Integer> mutualChoiceDistribution) {
}
