package ru.sociometric.calculator;

/**
 * Результат расчёта социометрии по Морено.
 *
 * @param n                  число участников
 * @param choicesLimit       сколько участников разрешено выбрать каждому
 * @param matrix             матрица симпатий: matrix[i][j] — вес выбора i -> j
 * @param antiMatrix         матрица антипатий: antiMatrix[i][j] — вес выбора i -> j
 * @param incoming           полученные симпатии (столбец матрицы) по каждому участнику
 * @param outgoing           сделанные симпатии (строка матрицы) по каждому участнику
 * @param weightedIncoming   взвешенный статус (с учетом ранга выбора)
 * @param weightedOutgoing   взвешенное число сделанных выборов
 * @param normalizedIncoming доля полученных симпатий от максимально возможной
 * @param antipathyIncoming  полученные антипатии по каждому участнику
 * @param antipathyOutgoing  сделанные антипатии по каждому участнику
 * @param responded          участвовал ли участник в опросе (хоть один выбор любого вида)
 * @param categories         категория статуса по симпатиям
 * @param antipathyCategories категория по антипатиям
 * @param mutualPairs        пары взаимного выбора
 * @param unidirectionalPairs пары одностороннего выбора
 * @param indices            структурные индексы группы
 * @param groups             индексы, посчитанные по подгруппам (кодам участников)
 * @param outdegree          исходящая степень в неориентированном графе предпочтений
 * @param indegree           входящая степень в том же графе
 * @param distances          матрица кратчайших расстояний (BFS), -1 если пути нет
 * @param meanDistance       средняя длина кратчайшего пути по достижимым парам
 * @param diameter           диаметр графа; {@code null}, если граф несвязен
 * @param radius             минимум эксцентриситетов среди вершин с конечным эксцентриситетом
 * @param connectedComponents число связных компонент
 * @param componentRadii     радиус каждой компоненты
 * @param componentDiameters диаметр каждой компоненты
 * @param connectedPairs     число пар участников, между которыми есть путь
 * @param possiblePairs      общее число неупорядоченных пар n(n-1)/2
 * @param strongest          самая тесная подгруппа (список кодов)
 * @param isolates           участники без единого выбора
 * @param stars              участники со статусом «звезда»
 * @param categoriesCount    количество участников в каждой категории
 */
public record SociometricResult(
        int n,
        int choicesLimit,
        int[][] matrix,
        int[][] antiMatrix,
        int[] incoming,
        int[] outgoing,
        int[] weightedIncoming,
        int[] weightedOutgoing,
        double[] normalizedIncoming,
        int[] antipathyIncoming,
        int[] antipathyOutgoing,
        boolean[] responded,
        String[] categories,
        String[] antipathyCategories,
        MutualPair[] mutualPairs,
        UnidirectionalPair[] unidirectionalPairs,
        GroupIndices indices,
        GroupIndex[] groups,
        int[] outdegree,
        int[] indegree,
        int[][] distances,
        double meanDistance,
        Integer diameter,
        int radius,
        int connectedComponents,
        int[] componentRadii,
        int[] componentDiameters,
        int connectedPairs,
        int possiblePairs,
        String[] strongest,
        String[] isolates,
        String[] stars,
        int[] categoriesCount) {

    /**
     * @param a первый участник
     * @param b второй участник
     */
    public record MutualPair(int a, int b) {
    }

    /**
     * @param a     выбравший
     * @param b     выбранный, не выбравший в ответ
     * @param weight вес выбора a -> b
     */
    public record UnidirectionalPair(int a, int b, int weight) {
    }

    /** Индексы, посчитанные отдельно для подгруппы участников. */
    public record GroupIndex(String name, int n, GroupIndices indices) {
    }
}
