package ru.sociometric.domain;

/**
 * Таксономические категории социометрического статуса по Я. Морено.
 * Границы задаются настройками опроса (см. Thresholds).
 */
public enum StatusCategory {

    /** «Звезда» — наиболее популярный участник, лидер группы. */
    STAR("Звезда"),

    /** «Предпочитаемый» — статус выше среднего, выборы получает устойчиво. */
    PREFERRED("Предпочитаемый"),

    /** «Принятый» — нейтральный, положительный или слегка отрицательный статус. */
    ACCEPTED("Принятый"),

    /** «Изолированный» — не выбран никем, но сам кого-то выбирает. */
    ISOLATED("Изолированный"),

    /** «Отвергаемый» — не получает выборов. */
    REJECTED("Отвергаемый"),

    /** «Аутсайдер» — не выбран никем и сам никого не выбирает. */
    OUTSIDER("Аутсайдер"),

    /** Данных недостаточно (участник не отвечал). */
    NO_DATA("Нет данных");

    private final String title;

    StatusCategory(String title) {
        this.title = title;
    }

    public String getTitle() {
        return title;
    }

    public boolean isPositive() {
        return this == STAR || this == PREFERRED;
    }

    public boolean isNegative() {
        return this == ISOLATED || this == REJECTED || this == OUTSIDER;
    }
}
