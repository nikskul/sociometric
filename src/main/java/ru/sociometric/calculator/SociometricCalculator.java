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
        // Без явного сигнала об ответе отвечавшим считаем того, у кого есть хоть
        // какой-то след в данных: выбрал сам или был выбран. Отметки об ответе нужны
        // только чтобы отличить «отвечал, но никого не выбрал» от «не отвечал».
        Set<String> declared = data.responded();
        for (String code : declared == null ? Set.<String>of() : declared) {
            Integer index = map.get(code);
            if (index != null) {
                responded[index] = true;
            }
        }

        int choicesLimit = data.choicesLimit();
        for (ChoiceEntry entry : data.choices()) {
            Integer from = map.get(entry.from());
            Integer to = map.get(entry.to());
            if (from == null || to == null || from.equals(to)) {
                continue;
            }
            responded[from] = true;
            responded[to] = true;
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

        // пары перебираем один раз, только по i < j, иначе каждая посчитана бы дважды.
        // Вес в сумму идёт удвоенным, потому что формула берёт упорядоченные пары.
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (matrix[i][j] > 0 && matrix[j][i] > 0) {
                    mutualPairs.add(new SociometricResult.MutualPair(i, j));
                    mutualCount[i]++;
                    mutualCount[j]++;
                    // именно ×2: формула суммирует по ВСЕМ упорядоченным парам,
                    // иначе на невзвешенных данных получилась бы половина MUTUAL
                    mutualWeightSum += 2d * matrix[i][j] * matrix[j][i];
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
        Limits limits = effectiveThresholds(thresholds, meanStatus, sdStatus);
        StatusThresholds effective = new StatusThresholds(limits.star(), limits.preferred(),
                limits.accepted(), limits.mostRejected(), thresholds.auto());
        String[] categories = categoriesRaw(incoming, antipathyIncoming, selectionsMade, responded, effective);
        String[] antipathyCategories = antipathyCategories(antipathyIncoming, effective.mostRejected());

        // --- индексы -----------------------------------------------------------
        GscFormula formula = data.formula() == null ? GscFormula.MUTUAL : data.formula();
        GroupIndices indices = computeIndices(n, matrix, mutualPairs.size(), unidirectional.size(),
                categories, antipathyIncoming, formula, mutualWeightSum);

        // --- граф предпочтений -------------------------------------------------
        boolean[][] graph = graphOf(matrix);
        int[] degree = degrees(graph);
        int[][] distances = bfsDistances(graph);
        GraphMetrics graphMetrics = diameterAndRadius(distances, components(graph));
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
        int connectedPairs = orderedReachable / 2;   // пары, между которыми вообще есть путь

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
            // NO_DATA в список изолированных не идёт: отсутствие данных — не изоляция
            if (category == StatusCategory.OUTSIDER || category == StatusCategory.ISOLATED) {
                isolates.add(participants.get(i).code());
            }
        }

        // --- индексы по подгруппам --------------------------------------------
        List<SociometricResult.GroupIndex> groupIndices = new ArrayList<>();
        for (SubGroup sub : subgroups) {
            if (sub.indices().size() >= 2) {
                groupIndices.add(computeSubGroup(sub, matrix, antiMatrix, formula, thresholds));
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
                graphMetrics.diameter(), graphMetrics.radius(),
                graphMetrics.connectedComponents(),
                graphMetrics.componentRadii(), graphMetrics.componentDiameters(),
                connectedPairs, n * (n - 1) / 2,
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
        int maxWeight = maxWeight(matrix);
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
            // нормируем на L^2, где L — наибольший вес выбора: на невзвешенных данных
            // L = 1 и обе формулы совпадают, на ранжированных значение остаётся 0..1.
            // Без этой нормировки сумма по упорядоченным парам даёт значения до L^2.
            double weightSquare = formula == GscFormula.MUTUAL
                    ? 1d : (double) maxWeight * maxWeight;
            gsc = numerator / (possibleSelections * weightSquare);
        }
        double reciprocity = totalSelections == 0 ? 0 : (2d * mutualPairsCount) / totalSelections;
        double density = possibleSelections == 0 ? 0 : (double) totalSelections / possibleSelections;

        // Знаменатель долей — все участники n, а не только отвечавшие:
        // иначе показатель зависел бы от того, как импортированы данные,
        // и «индекс изоляции» нельзя было бы сравнивать между опросами.
        int isolated = 0;
        int negative = 0;
        for (String category : categories) {
            StatusCategory value = StatusCategory.valueOf(category);
            // не ответившие в числитель не идут: они не изолированы, про них нет данных.
            // Знаменатель остаётся n, иначе показатель зависел бы от способа ввода данных
            // и индексы разных опросов стали бы несравнимыми.
            if (value.isNoData()) {
                continue;
            }
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
                                                                int[][] antiMatrix, GscFormula formula,
                                                                StatusThresholds activeThresholds) {
        int m = sub.indices().size();
        int[][] subMatrix = new int[m][m];
        for (int a = 0; a < m; a++) {
            for (int b = 0; b < m; b++) {
                subMatrix[a][b] = matrix[sub.indices().get(a)][sub.indices().get(b)];
            }
        }
        int[] incoming = new int[m];
        int[] selectionsMade = new int[m];
        // Антипатии внутри подгруппы: без них индекс отвержения подгруппы был бы нулевым
        // и подгруппа с отвержением выглядела бы здоровой.
        int[] subAntipathyIncoming = new int[m];
        boolean[] responded = new boolean[m];
        for (int a = 0; a < m; a++) {
            for (int b = 0; b < m; b++) {
                if (a == b) {
                    continue;
                }
                int global = sub.indices().get(a);
                if (matrix[global][sub.indices().get(b)] > 0 || antiMatrix[global][sub.indices().get(b)] > 0) {
                    selectionsMade[a]++;
                    responded[a] = true;
                }
                if (antiMatrix[global][sub.indices().get(b)] > 0) {
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
                    // упорядоченная сумма: пара (a,b) и (b,a) считаются раздельно,
                    // иначе взвешенная формула дала бы половину 2M/n(n-1)
                    mutualWeightSum += (double) subMatrix[a][b] * subMatrix[b][a];
                }
            }
        }
        // односторонние пары считаем один раз, перебирая только a < b
        for (int a = 0; a < m; a++) {
            for (int b = a + 1; b < m; b++) {
                boolean forward = subMatrix[a][b] > 0;
                boolean backward = subMatrix[b][a] > 0;
                if (forward != backward) {
                    unidirectionalCount++;
                }
            }
        }
        int mutual = countMutualPairs(subMatrix);
        // пороги считаем по выборкам самой подгруппы, иначе статус подгруппы
        // сравнивался бы с порогами всего класса
        double subMean = mean(incoming);
        StatusThresholds subThresholds = new StatusThresholds(
                activeThresholds.star(), activeThresholds.preferred(), activeThresholds.accepted(),
                activeThresholds.mostRejected(), activeThresholds.auto());
        Limits subLimits = effectiveThresholds(subThresholds, subMean, stdDev(incoming, subMean));
        StatusThresholds subEffective = new StatusThresholds(subLimits.star(), subLimits.preferred(),
                subLimits.accepted(), subLimits.mostRejected(), subThresholds.auto());
        String[] categories = categoriesRaw(incoming, subAntipathyIncoming, selectionsMade,
                responded, subEffective);
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
     * Категория статуса.
     *
     * <p>Порядок проверки (description.md, §15 и §18):
     * <ol>
     *   <li>{@code NO_DATA} — участник не сделал ни одного выбора; данных нет,
     *       и подставлять вместо них «ноль симпатий» нельзя (§12): это занижает
     *       индексы изоляции и отрицательности.</li>
     *   <li>положительные категории — по числу входящих симпатий;</li>
     *   <li>{@code REJECTED} — по входящим антипатиям: раньше этот статус выдавался
     *       за «мало симпатий», хотя по спецификации отвергает именно антипатия;</li>
     *   <li>{@code LOW_ACCEPTED} — получил хоть что-то, но меньше порога «принятый»;</li>
     *   <li>{@code ISOLATED} — не выбран, но сам выбирает; {@code OUTSIDER} — не выбран и не выбирает.</li>
     * </ol>
     */
    static String[] categoriesRaw(int[] incoming, int[] antipathyIncoming, int[] selectionsMade,
                                  boolean[] responded, StatusThresholds thresholds) {
        String[] categories = new String[incoming.length];
        int rejectionBar = Math.max(1, thresholds.mostRejected());
        for (int i = 0; i < incoming.length; i++) {
            int received = incoming[i];
            if (responded != null && !responded[i]) {
                categories[i] = StatusCategory.NO_DATA.name();
                continue;
            }
            if (received == 0) {
                // ноль входящих симпатий проверяем первым: иначе порог «accepted»,
                // опустившийся до нуля, отдал бы изолированных «принятым»
                categories[i] = antipathyIncoming[i] >= rejectionBar
                        ? StatusCategory.REJECTED.name()
                        : (selectionsMade[i] > 0
                                ? StatusCategory.ISOLATED.name() : StatusCategory.OUTSIDER.name());
            } else if (received >= thresholds.star()) {
                categories[i] = StatusCategory.STAR.name();
            } else if (received >= thresholds.preferred()) {
                categories[i] = StatusCategory.PREFERRED.name();
            } else if (received >= thresholds.accepted()) {
                categories[i] = StatusCategory.ACCEPTED.name();
            } else {
                categories[i] = StatusCategory.LOW_ACCEPTED.name();
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

    /**
     * Пороги «среднее ± σ» либо заданные вручную.
     *
     * <p>{@code preferred} считается как {@code floor(mean) + 1}, то есть строго
     * выше среднего: при {@code ceil(mean)} участник, получивший ровно среднее
     * число голосов, попадал в «предпочитаемые», хотя README и §15 обещают
     * «выше среднего».
     *
     * <p>Значение {@link Integer#MAX_VALUE} больше не возвращается: оно попадало
     * в отчёт и печаталось как «звезда от 2147483647». Если параметр не задан,
     * берётся заведомо недостижимое, но осмысленное значение — максимум плюс один.
     *
     * @param degenerate true, если все участники получили одинаковое число выборов
     *                   и «среднее ± σ» не разделяет группу (σ = 0)
     */
    public static Limits effectiveThresholds(StatusThresholds thresholds, double mean, double sd) {
        int ceiling = (int) Math.ceil(mean) + 1;
        int mostRejected = thresholds.mostRejected() == null ? 2 : thresholds.mostRejected();
        if (!thresholds.auto()) {
            return new Limits(
                    thresholds.star() == null ? ceiling : thresholds.star(),
                    thresholds.preferred() == null ? ceiling : thresholds.preferred(),
                    thresholds.accepted() == null ? 1 : thresholds.accepted(),
                    Math.max(1, mostRejected), false);
        }
        if (sd <= 0) {
            // вырожденный случай: разброса нет, «среднее ± σ» не работает
            return new Limits((int) Math.ceil(mean) + 1, (int) Math.floor(mean) + 1,
                    Math.max(1, (int) Math.floor(mean) - 1), Math.max(1, mostRejected), true);
        }
        int preferred = (int) Math.floor(mean) + 1;
        // нижняя граница «принятых» не может опуститься до нуля: ноль входящих
        // выборов — это изоляция, а не принятие
        int accepted = Math.max(1, (int) Math.floor(mean - sd));
        int star = Math.max((int) Math.ceil(mean + sd), preferred + 1);
        return new Limits(star, preferred, accepted, Math.max(1, mostRejected), false);
    }

    /** Пороги, приведённые к целым, и признак вырожденной выборки. */
    public record Limits(int star, int preferred, int accepted, int mostRejected,
                         boolean degenerate) {
    }

    // ------------------------------------------------------------------
    // граф
    // ------------------------------------------------------------------

    /**
     * Граф предпочтений: только симпатии.
     *
     * <p>Раньше сюда попадали и антипатии, из-за чего аутсайдер, не получивший ни
     * одной симпатии, оказывался «связанным» с группой, а метрики плотности и
     * расстояний менялись от добавления отрицательного критерия. Отрицательные
     * связи — отдельный слой, их показывает социограмма, но в метрики структуры
     * они не входят (§13, §19).
     */
    static boolean[][] graphOf(int[][] matrix) {
        int n = matrix.length;
        boolean[][] graph = new boolean[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j && (matrix[i][j] > 0 || matrix[j][i] > 0)) {
                    graph[i][j] = true;
                }
            }
        }
        return graph;
    }

    /** Разбиение на связные компоненты: список списков индексов. */
    static List<List<Integer>> components(boolean[][] graph) {
        int n = graph.length;
        boolean[] seen = new boolean[n];
        List<List<Integer>> result = new ArrayList<>();
        for (int start = 0; start < n; start++) {
            if (seen[start]) {
                continue;
            }
            List<Integer> component = new ArrayList<>();
            Deque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            seen[start] = true;
            while (!queue.isEmpty()) {
                int current = queue.poll();
                component.add(current);
                for (int next = 0; next < n; next++) {
                    if (graph[current][next] && !seen[next]) {
                        seen[next] = true;
                        queue.add(next);
                    }
                }
            }
            result.add(component);
        }
        return result;
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
    /**
     * Диаметр и радиус.
     *
     * <p>Для связного графа — обычные определения. Для несвязного конечный
     * диаметр не определён (между вершинами разных компонент нет пути), поэтому
     * возвращаем {@code null}. Радиус же определён: это минимум эксцентриситетов
     * среди вершин с конечным эксцентриситетом, то есть минимум по компонентам.
     * Раньше эксцентриситет изолированной вершины считался равным нулю, и радиус
     * всего графа схлопывался в ноль.
     *
     * @return {diameter, radius}; diameter = null, если граф несвязен
     */
    private static GraphMetrics diameterAndRadius(int[][] distances, List<List<Integer>> components) {
        int n = distances.length;
        boolean connected = components.size() <= 1;
        Integer diameter = null;
        int radius = Integer.MAX_VALUE;
        int maxComponentDiameter = 0;
        List<Integer> componentRadii = new ArrayList<>();
        List<Integer> componentDiameters = new ArrayList<>();
        for (List<Integer> component : components) {
            int componentRadius = Integer.MAX_VALUE;
            int componentDiameter = 0;
            for (int i : component) {
                int eccentricity = 0;
                for (int j : component) {
                    if (i != j) {
                        eccentricity = Math.max(eccentricity, distances[i][j]);
                    }
                }
                componentDiameter = Math.max(componentDiameter, eccentricity);
                if (component.size() > 1) {
                    componentRadius = Math.min(componentRadius, eccentricity);
                }
            }
            componentRadii.add(componentRadius == Integer.MAX_VALUE ? 0 : componentRadius);
            componentDiameters.add(componentDiameter);
            maxComponentDiameter = Math.max(maxComponentDiameter, componentDiameter);
            if (componentRadius != Integer.MAX_VALUE) {
                radius = Math.min(radius, componentRadius);
            }
        }
        int finalRadius = radius == Integer.MAX_VALUE ? 0 : radius;
        if (n <= 1) {
            return new GraphMetrics(0, 0, Math.max(1, components.size()),
                    new int[]{0}, new int[]{0});
        }
        return new GraphMetrics(connected ? maxComponentDiameter : null, finalRadius,
                components.size(),
                componentRadii.stream().mapToInt(Integer::intValue).toArray(),
                componentDiameters.stream().mapToInt(Integer::intValue).toArray());
    }

    /**
     * Метрики графа предпочтений.
     *
     * @param diameter           {@code null}, если граф несвязен: конечное значение не определено
     * @param radius             минимум эксцентриситетов среди вершин с конечным эксцентриситетом
     * @param connectedComponents число связных компонент
     * @param componentRadii     радиус каждой компоненты
     * @param componentDiameters диаметр каждой компоненты
     */
    public record GraphMetrics(Integer diameter, int radius, int connectedComponents,
                               int[] componentRadii, int[] componentDiameters) {
    }

    // ------------------------------------------------------------------
    // вспомогательное
    // ------------------------------------------------------------------

    /** Наибольший вес одного выбора в матрице; 0, если выборов нет. */
    static int maxWeight(int[][] matrix) {
        int max = 0;
        for (int[] row : matrix) {
            for (int value : row) {
                max = Math.max(max, value);
            }
        }
        return max;
    }

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
