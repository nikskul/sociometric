package ru.sociometric.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Создание или изменение опроса.
 *
 * @param name            название исследования
 * @param description     описание (класс, дата, условия проведения)
 * @param choicesLimit    сколько участников разрешено выбрать каждому
 * @param collectAntipathy собирать ли блок антипатий
 * @param ranks           ранжированный опросник (учёт порядка выбора)
 * @param autoThresholds  вычислять границы категорий автоматически (среднее ± σ)
 * @param thresholds      границы категорий при {@code autoThresholds = false}
 * @param participants    участники в порядке строк и столбцов матрицы
 */
public record SurveyRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 1000) String description,
        @Min(1) @Max(20) int choicesLimit,
        boolean collectAntipathy,
        boolean ranks,
        boolean autoThresholds,
        @Valid ThresholdsDto thresholds,
        @NotEmpty @Size(max = 200) List<@Valid ParticipantDto> participants) {
}
