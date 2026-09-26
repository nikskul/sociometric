package ru.sociometric.domain;

public enum GscFormula {
    /**
     * Классическая формула Морено: GSC = 2M / (n(n-1)),
     * где M — число пар взаимного выбора.
     */
    MUTUAL("2M / n(n-1)"),

    /**
     * GSC = sum(A[i][j] * A[j][i]) / (n(n-1)) — двупроходная сумма по всем упорядоченным парам.
     */
    MUTUAL_DOUBLE_PASS("sum(Aij*Aji) / n(n-1)");

    private final String formula;

    GscFormula(String formula) {
        this.formula = formula;
    }

    public String getFormula() {
        return formula;
    }
}
