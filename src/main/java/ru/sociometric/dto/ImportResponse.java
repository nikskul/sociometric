package ru.sociometric.dto;

import java.util.List;

/**
 * Итог загрузки файла.
 *
 * @param imported           сколько выборов сохранено
 * @param skippedRows        сколько строк пропущено
 * @param warnings           чем именно пропущено
 * @param participantsCount  участников в опросе
 */
public record ImportResponse(int imported, int skippedRows, List<String> warnings, int participantsCount) {
}
