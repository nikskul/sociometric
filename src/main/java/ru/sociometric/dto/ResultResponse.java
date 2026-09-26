package ru.sociometric.dto;

import java.util.List;
import java.util.Map;

/**
 * Полный результат расчёта социометрии.
 *
 * @param surveyId          идентификатор опроса
 * @param name              название
 * @param n                 число участников
 * @param choicesLimit      лимит выборов
 * @param participants      результат по каждому участнику
 * @param indices           структурные индексы группы
 * @param groups            индексы по подгруппам
 * @param matrix            матрица симпатий (веса)
 * @param antiMatrix        матрица антипатий (веса)
 * @param mutualPairs       пары взаимного выбора
 * @param unidirectionalPairs пары одностороннего выбора
 * @param thresholds        реально примененные пороги категорий
 * @param autoThresholds    вычислены ли пороги автоматически
 * @param meanStatus        среднее число полученных выборов
 * @param sdStatus          стандартное отклонение числа полученных выборов
 * @param categoriesCount   количество участников в каждой категории
 * @param stars             коды «звезд»
 * @param isolates          коды изолированных и аутсайдеров
 * @param strongest         коды самой тесной микрогруппы
 * @param graph             метрики графа предпочтений
 * @param experts           согласованность экспертных оценок, null если оценок нет
 */
public record ResultResponse(
        Long surveyId,
        String name,
        int n,
        int choicesLimit,
        List<ParticipantResult> participants,
        IndicesDto indices,
        List<GroupDto> groups,
        int[][] matrix,
        int[][] antiMatrix,
        List<MutualPairDto> mutualPairs,
        List<UnidirectionalPairDto> unidirectionalPairs,
        ThresholdsDto thresholds,
        boolean autoThresholds,
        double meanStatus,
        double sdStatus,
        Map<String, Integer> categoriesCount,
        List<String> stars,
        List<String> isolates,
        List<String> strongest,
        GraphDto graph,
        ExpertDto experts) {

    /**
     * Результат по одному участнику.
     *
     * @param index              номер строки/столбца в матрице
     * @param code               код участника
     * @param name               имя участника
     * @param received           число полученных симпатий (входящие выборы)
     * @param given              число сделанных симпатий
     * @param weightedReceived   взвешенный полученный статус
     * @param weightedGiven      взвешенное число сделанных выборов
     * @param normalizedReceived доля полученных симпатий от максимально возможной
     * @param rejectedBy         число полученных антипатий
     * @param rejectedTo         число сделанных антипатий
     * @param category           категория статуса
     * @param categoryTitle      название категории
     * @param antipathyCategory  категория по антипатиям
     * @param antipathyCategoryTitle название категории по антипатиям
     * @param mutualCount        число взаимных пар с этим участником
     * @param degree             степень в графе предпочтений
     * @param responded          отвечал ли участник
     */
    public record ParticipantResult(
            int index,
            String code,
            String name,
            int received,
            int given,
            int weightedReceived,
            int weightedGiven,
            double normalizedReceived,
            int rejectedBy,
            int rejectedTo,
            String category,
            String categoryTitle,
            String antipathyCategory,
            String antipathyCategoryTitle,
            int mutualCount,
            int degree,
            boolean responded) {
    }

    /** Структурные индексы группы. */
    public record IndicesDto(
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

    /** Индексы по подгруппе. */
    public record GroupDto(String name, int n, IndicesDto indices) {
    }

    /** @param a индекс первого участника @param b индекс второго */
    public record MutualPairDto(int a, int b) {
    }

    /**
     * @param a     индекс выбравшего
     * @param b     индекс выбранного
     * @param weight вес выбора
     */
    public record UnidirectionalPairDto(int a, int b, int weight) {
    }

    /** Метрики графа предпочтений. */
    public record GraphDto(
            double meanDistance,
            int diameter,
            int radius,
            int connectedPairs,
            int reachablePairs,
            int[][] distances) {
    }

    /**
     * Согласованность экспертных оценок.
     *
     * @param experts            эксперты
     * @param participants       средняя оценка и разброс по каждому участнику
     * @param kendallW           коэффициент согласованности Kendall W, 0..1;
     *                           null, если экспертов меньше двух
     * @param pearsonWithStatus  корреляция Пирсона между оценкой и статусом
     * @param spearmanWithStatus ранговая корреляция Спирмена
     * @param ranking            сопоставление рангов «эксперты против социометрии»
     */
    public record ExpertDto(
            List<String> experts,
            List<ParticipantScore> participants,
            Double kendallW,
            Double pearsonWithStatus,
            Double spearmanWithStatus,
            List<RankingRow> ranking) {
    }

    /**
     * @param code   код участника
     * @param name   имя
     * @param mean   средний балл экспертов
     * @param sd     разброс оценок
     * @param min    минимальный балл
     * @param max    максимальный балл
     * @param votes  сколько экспертов оценили
     * @param statusReceived число полученных симпатий
     */
    public record ParticipantScore(
            String code, String name, double mean, double sd, int min, int max, int votes, int statusReceived) {
    }

    /**
     * @param position       место в таблице
     * @param code           код участника
     * @param name           имя
     * @param expertRank     место по средней экспертной оценке
     * @param sociometricRank место по социометрическому статусу
     * @param difference     expertRank - sociometricRank
     */
    public record RankingRow(
            int position, String code, String name, int expertRank, int sociometricRank, int difference) {
    }
}
