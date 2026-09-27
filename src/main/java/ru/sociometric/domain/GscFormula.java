package ru.sociometric.domain;

/**
 * Способ счёта коэффициента групповой взаимности.
 *
 * <p>Это <b>не</b> коэффициент согласованности Gower W: W измеряет согласие
 * экспертов по участникам, а формулы ниже — взаимность самих выборов внутри
 * группы. Называть их GSC нельзя, отчёт обязан показывать применённую формулу
 * (description.md, §26).
 *
 * <p>Имя перечисления оставлено прежним, чтобы не ломать ключ конфигурации
 * {@code sociometric.gsc-formula} в уже настроенных развёртываниях.
 */
public enum GscFormula {

    /**
     * Классическая формула Морено: 2M / (n(n-1)), где M — число пар взаимного выбора.
     * На невзвешенных данных совпадает с {@link #MUTUAL_DOUBLE_PASS}.
     */
    MUTUAL("2M / n(n-1)", true),

    /**
     * Взвешенная взаимность: sum(A[i][j] * A[j][i]) / (n(n-1) * L²) по всем
     * упорядоченным парам, где L — наибольший вес выбора в матрице.
     *
     * <p>Сумма идёт по <b>упорядоченным</b> парам, иначе на невзвешенных данных
     * получилась бы ровно половина {@link #MUTUAL}. Деление на L² держит значение
     * в диапазоне 0..1: на невзвешенных данных L = 1, на ранжированных с лимитом
     * 3 вес первого места равен 3, и нормировка убирает переполнение.
     */
    MUTUAL_DOUBLE_PASS("sum(Aij*Aji) / (n(n-1)*L^2)", false);

    private final String formula;

    /** @see #MUTUAL */
    private final boolean unweighted;

    GscFormula(String formula, boolean unweighted) {
        this.formula = formula;
        this.unweighted = unweighted;
    }

    public String getFormula() {
        return formula;
    }

    /** @return true, если формула не использует веса и совпадает сама с собой на любых данных */
    public boolean isUnweighted() {
        return unweighted;
    }
}
