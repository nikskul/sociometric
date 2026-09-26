package ru.sociometric.domain;

/**
 * Участник опроса (ученик/член группы).
 * Денормализованная копия хранится в {@link Survey#getParticipantsJson()},
 * чтобы порядок строк и столбцов матрицы был стабильным.
 */
public record Participant(String code, String name) {
}
