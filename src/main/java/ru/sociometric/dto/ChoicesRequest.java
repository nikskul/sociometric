package ru.sociometric.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Пакетная загрузка выборов.
 *
 * @param choices список выборов
 * @param replace true — заменить все выборы указанного вида, false — добавить
 * @param kind    вид выборов, которые заменяем при {@code replace = true}
 */
public record ChoicesRequest(
        @NotNull List<@NotNull ChoiceInput> choices,
        boolean replace,
        ChoiceKindInput kind) {

    public enum ChoiceKindInput {
        SYMPATHY,
        ANTIPATHY
    }

    /**
     * @param kind   вид выбора
     * @param from   код респондента
     * @param to     код выбранного
     * @param rank   место в предпочтении, 1 — самый желательный
     * @param weight вес выбора, по умолчанию 1
     */
    public record ChoiceInput(
            @NotBlank String kind,
            @NotBlank @Size(max = 64) String from,
            @NotBlank @Size(max = 64) String to,
            Integer rank,
            Integer weight) {
    }
}
