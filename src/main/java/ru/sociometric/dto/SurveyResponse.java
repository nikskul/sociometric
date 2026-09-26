package ru.sociometric.dto;

import java.time.Instant;
import java.util.List;

/**
 * Опрос в ответе API вместе со сводкой: сколько данных уже введено.
 *
 * @param id               идентификатор
 * @param name             название
 * @param description      описание
 * @param choicesLimit     лимит выборов
 * @param collectAntipathy собираются ли антипатии
 * @param ranks            ранжированный опросник
 * @param autoThresholds   автопороги
 * @param thresholds       заданные пороги
 * @param participants     участники
 * @param choicesCount     введено выборов (симпатий)
 * @param antipathyCount   введено антипатий
 * @param expertsCount     введено экспертных оценок
 * @param respondedCount   участников, уже ответивших хотя бы раз
 * @param createdAt        дата создания
 * @param updatedAt        дата изменения
 */
public record SurveyResponse(
        Long id,
        String name,
        String description,
        int choicesLimit,
        boolean collectAntipathy,
        boolean ranks,
        boolean autoThresholds,
        ThresholdsDto thresholds,
        List<ParticipantDto> participants,
        long choicesCount,
        long antipathyCount,
        long expertsCount,
        int respondedCount,
        Instant createdAt,
        Instant updatedAt) {
}
