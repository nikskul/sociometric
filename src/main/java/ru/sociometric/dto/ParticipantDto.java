package ru.sociometric.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Участник опроса в API.
 *
 * @param code уникальный код участника (номер в матрице)
 * @param name отображаемое имя
 */
public record ParticipantDto(@NotBlank @Size(max = 64) String code, @Size(max = 255) String name) {
}
