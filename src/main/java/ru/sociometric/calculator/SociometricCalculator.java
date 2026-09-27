package ru.sociometric.calculator;

import ru.sociometric.calculator.SurveyData.ChoiceEntry;
import ru.sociometric.calculator.SurveyData.ChoiceType;
import ru.sociometric.domain.AntipathyCategory;
import ru.sociometric.domain.GscFormula;
import ru.sociometric.domain.Participant;
import ru.sociometric.domain.StatusCategory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ядро расчёта: превращает выборы участников в матрицы, статусы, категории
 * и структурные индексы группы. Класс не зависит от Spring и БД —
 * вся логика проверяется обычными юнит-тестами.
 */
public final class SociometricCalculator {

    /** Максимальный размер «тесной микрогруппы», которую ищем среди взаимных выборов. */
    private static final int MAX_MICRO_GROUP = 4;

    private SociometricCalculator() {
    }

    public static SociometricResult calculate(SurveyData data) {
        return calculate(data, StatusThresholds.defaults(), List.of());
    }

    public static SociometricResult calculate(SurveyData data, StatusThresholds thresholds, List<SubGroup> subgroups) {
        List<Participant> participants = data.participants();
        int n = participants.size();
        if (n == 0) {
            throw new IllegalArgumentException("В опросе нет участников");
        }

        IndexMap map = buildIndexMap(participants);
        int[][] matrix = new int[n][n];
        int[][] antiMatrix = new int[n][n];
        boolean[] responded = new boolean[n];

        int choicesLimit = data.choicesLimit();
        for (ChoiceEntry entry : data.choices()) {
            Integer from = map.get(entry.from());
            Integer to = map.get(entry.to());
            if (from == null || to == null || from.equals(to)) {
                continue;
            }
            responded[from] = true;
            int weight = entry.weight();
            if (data.ranks() && entry.rank() != null) {
                // ранжированный опросник: первый выбор весит больше последнего
                weight = Math.max(1, choicesLimit - entry.rank() + 1);
            }
            if (entry.kind() == ChoiceType.SYMPATHY) {
                matrix[from][to] += weight;
            } else {
                antiMatrix[from][to] += weight;
            }
        }

        int[] incoming = new int[n];
        int[] outgoing = new int[n];
        int[] weightedIncoming = new int[n];
        int[] weightedOutgoing = new int[n];
        double[] normalizedIncoming = new double[n];
        int[] antipathyIncoming = new int[n];
        int[] antipathyOutgoing = new int[n];
        int[] mutualCount = new int[n];

        List<SociometricResult.MutualPair> mutualPairs = new ArrayList<>();
        List<SociometricResult.UnidirectionalPair> unidirectional = new ArrayList<>();
        double mutualWeightSum = 0;

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    continue;
                }
                if (matrix[i][j] > 0) {
                    outgoing[i]++;
                    weightedOutgoing[i] += matrix[i][j];
                }
                if (matrix[j][i] > 0) {
                    incoming[i]++;
                    weightedIncoming[i] += matrix[j][i];
                }
                if (antiMatrix[i][j] > 0) {
                    antipathyOutgoing[i]++;
                }
                if (antiMatrix[j][i] > 0) {
                    antipathyIncoming[i]++;
                }
            }
        }

        // пары перебираем один раз, только по i < j, иначе каждая посчитана бы дважды
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (matrix[i][j] > 0 && matrix[j][i] > 0) {
                    mutualPairs.add(new SociometricResult.MutualPair(i, j));
                    mutualCount[i]++;
                    mutualCount[j]++;
                    mutualWeightSum += (double) matrix[i][j] * matrix[j][i];
                } else if (matrix[i][j] > 0) {
                    unidirectional.add(new SociometricResult.UnidirectionalPair(i, j, matrix[i][j]));
                } else if (matrix[j][i] > 0) {
                    unidirectional.add(new SociometricResult.UnidirectionalPair(j, i, matrix[j][i]));
                }
            }
        }

        // сколько всего выборов сделал участник (любого вида) — нужно, чтобы отличить
        // «изолированного» (не выбран, но выбирает) от «аутсайдера» (не выбран и не выбирает)
        int[] selectionsMade = new int[n];
        for (int i = 0; i < n; i++) {
            selectionsMade[i] = antipathyOutgoing[i] + outgoing[i];
        }

        for (int i = 0; i < n; i++) {
            normalizedIncoming[i] = n <= 1 ? 0 : (double) incoming[i] / (n - 1);
        }

        // --- категории статуса -------------------------------------------------
        double meanStatus = mean(incoming);
        double sdStatus = stdDev(incoming, meanStatus);
        int[] limits = effectiveThresholds(thresholds, meanStatus, sdStatus);
        StatusThresholds effective = new StatusThresholds(limits[0], limits[1], limits[2], limits[3], thresholds.auto());
        String[] categories = categoriesRaw(incoming, selectionsMade, effective);
        String[] antipathyCategories = antipathyCategories(antipathyIncoming, effective.mostRejected());

        // --- индексы -----------------------------------------------------------
        GscFormula formula = data.formula() == null ? GscFormula.MUTUAL : data.formula();
        GroupIndices indices = computeIndices(n, matrix, mutualPairs.size(), unidirectional.size(),
                categories, antipathyIncoming, formula, mutualWeightSum);

        // --- граф предпочтений -------------------------------------------------
        boolean[][] graph = graphOf(matrix, antiMatrix);
        int[] degree = degrees(graph);
        int[][] distances = bfsDistances(graph);
        int[] diameterRadius = diameterAndRadius(distances);
        long distanceSum = 0;
        int orderedReachable = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j && distances[i][j] > 0) {
                    distanceSum += distances[i][j];
                    orderedReachable++;
                }
            }
        }
        double meanDistance = orderedReachable == 0 ? 0 : (double) distanceSum / orderedReachable;
        int reachablePairs = orderedReachable / 2;

        // --- сводка по категориям ---------------------------------------------
        List<String> stars = new ArrayList<>();
        List<String> isolates = new ArrayList<>();
        int[] categoriesCount = new int[StatusCategory.values().length];
        for (int i = 0; i < n; i++) {
            StatusCategory category = StatusCategory.valueOf(categories[i]);
            categoriesCount[category.ordinal()]++;
            if (category == StatusCategory.STAR) {
                stars.add(participants.get(i).code());
            }
            if (category == StatusCategory.OUTSIDER || category == StatusCategory.ISOLATED) {
                isolates.add(participants.get(i).code());
            }
        }

        // --- индексы по подгруппам --------------------------------------------
        List<SociometricResult.GroupIndex> groupIndices = new ArrayList<>();
        for (SubGroup sub : subgroups) {
            if (sub.indices().size() >= 2) {
                groupIndices.add(computeSubGroup(sub, matrix, antiMatrix, formula));
            }
        }

        String[] microGroup = densestMicroGroup(participants, matrix, n).stream()
                .map(participants::get).map(Participant::code).toArray(String[]::new);

        return new SociometricResult(
                n, choicesLimit, matrix, antiMatrix,
                incoming, outgoing, weightedIncoming, weightedOutgoing, normalizedIncoming,
                antipathyIncoming, antipathyOutgoing, responded,
                categories, antipathyCategories,
                mutualPairs.toArray(new SociometricResult.MutualPair[0]),
                unidirectional.toArray(new SociometricResult.UnidirectionalPair[0]),
                indices,
                groupIndices.toArray(new SociometricResult.GroupIndex[0]),
                degree, degree, distances, round4(meanDistance),
                diameterRadius[0], diameterRadius[1],
                reachablePairs, n * (n - 1) / 2,
                microGroup,
                isolates.toArray(String[]::new),
                stars.toArray(String[]::new),
                categoriesCount);
    }

    // ------------------------------------------------------------------
    // индексы группы
    // ------------------------------------------------------------------

    private static GroupIndices computeIndices(int n, int[][] matrix, int mutualPairsCount,
                                               int unidirectionalPairsCount, String[] categories,
                                               int[] antipathyIncoming, GscFormula formula,
                                               double mutualWeightSum) {
        int totalSelections = 0;
        int[] mutualPerPerson = new int[n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == j) {
                    continue;
                }
                if (matrix[i][j] > 0) {
                    totalSelections++;
                }
                if (matrix[i][j] > 0 && matrix[j][i] > 0) {
                    mutualPerPerson[i]++;
                }
            }
        }

        int possibleSelections = n * (n - 1);
        double gsc;
        if (possibleSelections == 0) {
            gsc = 0;
        } else {
            double numerator = formula == GscFormula.MUTUAL ? 2d * mutualPairsCount : mutualWeightSum;
            gsc = numerator / possibleSelections;
        }
        double reciprocity = totalSelections == 0 ? 0 : (2d * mutualPairsCount) / totalSelections;
        double density = possibleSelections == 0 ? 0 : (double) totalSelections / possibleSelections;

        int isolated = 0;
        int negative = 0;
        for (String category : categories) {
            StatusCategory value = StatusCategory.valueOf(category);
            if (value == StatusCategory.OUTSIDER || value == StatusCategory.ISOLATED) {
                isolated++;
            }
            if (value.isNegative()) {
                negative++;
            }
        }
        int rejectedBySomeone = 0;
        for (int count : antipathyIncoming) {
            if (count > 0) {
                rejectedBySomeone++;
            }
        }

        List<Integer> distribution = new ArrayList<>(n);
        for (int count : mutualPerPerson) {
            distribution.add(count);
        }

        return new GroupIndices(n, totalSelections, n == 0 ? 0 : (double) totalSelections / n,
                mutualPairsCount, unidirectionalPairsCount, n * (n - 1) / 2, possibleSelections,
                clamp01(gsc), clamp01(reciprocity), clamp01(density),
                n == 0 ? 0 : (double) isolated / n,
                n == 0 ? 0 : (double) rejectedBySomeone / n,
                n == 0 ? 0 : (double) negative / n,
                distribution);
    }

    private static SociometricResult.GroupIndex computeSubGroup(SubGroup sub, int[][] matrix,
                                                                int[][] antiMatrix, GscFormula formula) {
        int m = sub.indices().size();
        int[][] subMatrix = new int[m][m];
        for (int a = 0; a < m; a++) {
            for (int b = 0; b < m; b++) {
                subMatrix[a][b] = matrix[sub.indices().get(a)][sub.indices().get(b)];
            }
        }
        int[] incoming = new int[m];
        // Антипатии внутри подгруппы: без них индекс отвержения подгруппы был бы нулевым
        // и подгруппа с отвержением выглядела бы здоровой.
        int[] subAntipathyIncoming = new int[m];
        for (int a = 0; a < m; a++) {
            for (int b = 0; b < m; b++) {
                if (a != b && antiMatrix[sub.indices().get(a)][sub.indices().get(b)] > 0) {
                    subAntipathyIncoming[a]++;
                }
            }
        }
        int unidirectionalCount = 0;
        double mutualWeightSum = 0;
        for (int a = 0; a < m; a++) {
            for (int b = 0; b < m; b++) {
                if (a == b) {
                    continue;
                }
                if (subMatrix[b][a] > 0) {
                    incoming[a]++;
                }
                if (subMatrix[a][b] > 0 && subMatrix[b][a] > 0) {
                    mutualWeightSum += (double) subMatrix[a][b] * subMatrix[b][a];
                } else if (subMatrix[a][b] > 0 || subMatrix[b][a] > 0) {
                    unidirectionalCount++;
                }
            }
        }
        int mutual = countMutualPairs(subMatrix);
        String[] categories = categoriesRaw(incoming, subAntipathyIncoming, StatusThresholds.defaults());
        return new SociometricResult.GroupIndex(sub.name(), m,
                computeIndices(m, subMatrix, mutual, unidirectionalCount, categories,
                        subAntipathyIncoming, formula, mutualWeightSum));
    }

    private static int countMutualPairs(int[][] matrix) {
        int count = 0;
        for (int i = 0; i < matrix.length; i++) {
            for (int j = i + 1; j < matrix.length; j++) {
                if (matrix[i][j] > 0 && matrix[j][i] > 0) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Самая тесная микрогруппа: жадно наращиваем множество, каждый раз добавляя
     * участника с наибольшим числом взаимных выборов с уже выбранными.
     */
    static List<Integer> densestMicroGroup(List<Participant> participants, int[][] matrix, int n) {
        if (n < 2) {
            return List.of();
        }
        List<Integer> best = List.of(0);
        int bestScore = -1;
        int limit = Math.min(MAX_MICRO_GROUP, n);
        for (int seed = 0; seed < n; seed++) {
            List<Integer> group = new ArrayList<>(List.of(seed));
            int score = 0;
            for (int step = 1; step < limit; step++) {
                int bestCandidate = -1;
                int bestGain = 0;
                for (int candidate = 0; candidate < n; candidate++) {
                    if (group.contains(candidate)) {
                        continue;
                    }
                    int gain = 0;
                    for (int member : group) {
                        if (matrix[candidate][member] > 0 && matrix[member][candidate] > 0) {
                            gain++;
                        }
                    }
                    if (gain > bestGain) {
                        bestGain = gain;
                        bestCandidate = candidate;
                    }
                }
                if (bestCandidate < 0) {
                    break;
                }
                group.add(bestCandidate);
                score += bestGain;
            }
            if (score > bestScore || (score == bestScore && group.size() > best.size())) {
                bestScore = score;
                best = List.copyOf(group);
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // категории
    // ------------------------------------------------------------------

    /**
     * Категория статуса по симпатиям.
     * «Изолированный» — не выбран никем, но сам кого-то выбирает;
     * «Аутсайдер» — не выбран и сам не выбирает; их исключаем из порогов.
     */
    static String[] categoriesRaw(int[] incoming, int[] selectionsMade, StatusThresholds thresholds) {
        String[] categories = new String[incoming.length];
        for (int i = 0; i < incoming.length; i++) {
            int received = incoming[i];
            if (received == 0) {
                categories[i] = (selectionsMade[i] > 0
                        ? StatusCategory.ISOLATED : StatusCategory.OUTSIDER).name();
                continue;
            }
            if (received >= thresholds.star()) {
                categories[i] = StatusCategory.STAR.name();
            } else if (received >= thresholds.preferred()) {
                categories[i] = StatusCategory.PREFERRED.name();
            } else if (received >= thresholds.accepted()) {
                categories[i] = StatusCategory.ACCEPTED.name();
            } else {
                categories[i] = StatusCategory.REJECTED.name();
            }
        }
        return categories;
    }

    private static String[] antipathyCategories(int[] antipathyIncoming, int mostRejected) {
        String[] categories = new String[antipathyIncoming.length];
        int threshold = Math.max(1, mostRejected);
        for (int i = 0; i < antipathyIncoming.length; i++) {
            int rejected = antipathyIncoming[i];
            if (rejected == 0) {
                categories[i] = AntipathyCategory.NOT_REJECTED.name();
            } else if (rejected >= threshold) {
                categories[i] = AntipathyCategory.MOST_REJECTED.name();
            } else {
                categories[i] = AntipathyCategory.REJECTED_BY_SOME.name();
            }
        }
        return categories;
    }

    /** Пороги «среднее ± σ» либо заданные вручную: {star, preferred, accepted, mostRejected}. */
    public static int[] effectiveThresholds(StatusThresholds thresholds, double mean, double sd) {
        int mostRejected = thresholds.mostRejected() == null ? 2 : thresholds.mostRejected();
        if (!thresholds.auto()) {
            return new int[]{
                    thresholds.star() == null ? Integer.MAX_VALUE : thresholds.star(),
                    thresholds.preferred() == null ? Integer.MAX_VALUE : thresholds.preferred(),
                    thresholds.accepted() == null ? 0 : thresholds.accepted(),
                    mostRejected};
        }
        if (sd <= 0) {
            return new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE, 0, mostRejected};
        }
        return new int[]{
                (int) Math.ceil(mean + sd),
                (int) Math.ceil(mean),
                (int) Math.max(0, Math.floor(mean - sd)),
                mostRejected};
    }

    // ------------------------------------------------------------------
    // граф
    // ------------------------------------------------------------------

    static boolean[][] graphOf(int[][] matrix, int[][] antiMatrix) {
        int n = matrix.length;
        boolean[][] graph = new boolean[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j && (matrix[i][j] > 0 || matrix[j][i] > 0
                        || antiMatrix[i][j] > 0 || antiMatrix[j][i] > 0)) {
                    graph[i][j] = true;
                }
            }
        }
        return graph;
    }

    private static int[] degrees(boolean[][] graph) {
        int n = graph.length;
        int[] degree = new int[n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (graph[i][j]) {
                    degree[i]++;
                }
            }
        }
        return degree;
    }

    static int[][] bfsDistances(boolean[][] graph) {
        int n = graph.length;
        int[][] distances = new int[n][n];
        for (int[] row : distances) {
            Arrays.fill(row, -1);
        }
        for (int start = 0; start < n; start++) {
            Deque<Integer> queue = new ArrayDeque<>();
            distances[start][start] = 0;
            queue.add(start);
            while (!queue.isEmpty()) {
                int current = queue.poll();
                for (int next = 0; next < n; next++) {
                    if (graph[current][next] && distances[start][next] < 0) {
                        distances[start][next] = distances[start][current] + 1;
                        queue.add(next);
                    }
                }
            }
        }
        return distances;
    }

    /** @return {диаметр, радиус} графа; для одиночной вершины оба равны 0. */
    private static int[] diameterAndRadius(int[][] distances) {
        int n = distances.length;
        int diameter = 0;
        int radius = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            int eccentricity = 0;
            for (int j = 0; j < n; j++) {
                if (i != j && distances[i][j] > 0) {
                    eccentricity = Math.max(eccentricity, distances[i][j]);
                }
            }
            diameter = Math.max(diameter, eccentricity);
            if (n > 1) {
                radius = Math.min(radius, eccentricity);
            }
        }
        return new int[]{diameter, radius == Integer.MAX_VALUE ? 0 : radius};
    }

    // ------------------------------------------------------------------
    // вспомогательное
    // ------------------------------------------------------------------

    static IndexMap buildIndexMap(List<Participant> participants) {
        Set<String> unique = new LinkedHashSet<>();
        for (Participant participant : participants) {
            if (participant.code() == null || participant.code().isBlank()) {
                throw new IllegalArgumentException("У участника не задан код");
            }
            unique.add(participant.code());
        }
        if (unique.size() != participants.size()) {
            throw new IllegalArgumentException("Коды участников должны быть уникальны");
        }
        IndexMap map = new IndexMap();
        for (int i = 0; i < participants.size(); i++) {
            map.put(participants.get(i).code(), i);
        }
        return map;
    }

    static double mean(int[] values) {
        if (values.length == 0) {
            return 0;
        }
        long sum = 0;
        for (int value : values) {
            sum += value;
        }
        return (double) sum / values.length;
    }

    static double stdDev(int[] values, double mean) {
        if (values.length == 0) {
            return 0;
        }
        double sum = 0;
        for (int value : values) {
            double d = value - mean;
            sum += d * d;
        }
        return Math.sqrt(sum / values.length);
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static double round4(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    /** Код участника -> индекс в матрице. */
    public static final class IndexMap {
        private final Map<String, Integer> byCode = new HashMap<>();

        public void put(String code, int index) {
            byCode.put(code, index);
        }

        public Integer get(String code) {
            return byCode.get(code);
        }
    }
}
