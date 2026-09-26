package ru.sociometric.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Пакетная загрузка экспертных оценок популярности.
 *
 * @param ratings  оценки
 * @param replace  true — заменить все оценки
 */
public record ExpertsRequest(@NotNull List<@NotNull @Valid RatingInput> ratings, boolean replace) {

    /**
     * @param expert имя эксперта
     * @param code   код оцениваемого участника
     * @param score  балл популярности
     */
    public record RatingInput(
            @NotBlank @Size(max = 128) String expert,
            @NotBlank @Size(max = 64) String code,
            int score) {
    }
}
