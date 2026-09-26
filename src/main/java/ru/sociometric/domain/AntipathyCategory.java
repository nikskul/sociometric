package ru.sociometric.domain;

/**
 * Категория статуса по антипатиям: «с кем бы участник не хотел быть в группе».
 */
public enum AntipathyCategory {

    NO_ANTIPATHY("Нет антипатий"),

    /** «Наиболее отвергаемый» — его называют чаще всех в качестве нежелательного соседа. */
    MOST_REJECTED("Наиболее отвергаемый"),

    REJECTED_BY_SOME("Отвергаемый"),

    /** «Аутсайдер по антипатиям» — не вызван нежелательным ни разу. */
    NOT_REJECTED("Не отвергаемый");

    private final String title;

    AntipathyCategory(String title) {
        this.title = title;
    }

    public String getTitle() {
        return title;
    }
}
