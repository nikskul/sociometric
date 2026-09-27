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
 * @param respondent код участника, чей ответ сохраняем. Нужен, чтобы отметить
 *                   «отвечал, но никого не выбрал»: такой ответ не даёт ни одной строки
 *                   выбора, и без отметки он неотличим от не ответившего
 * @param replaceAll true — в {@code choices} лежит полное желаемое состояние опроса
 *                    (оба вида выборов сразу), а не только один вид. Именно так
 *                    сохраняет интерфейс: он держит матрицу целиком в памяти и после
 *                    правки переписывает её целиком. Значение {@code replace} при этом
 *                    игнорируется: важно, что запрос полный, а не вид выборов.
 *                    Без этого флага строки, не попавшие в запрос, остались бы на месте,
 *                    а пришедшие повторно — упёрлись бы в уникальный индекс
 *                    {@code (survey_id, respondent, target, kind)}.
 */
public record ChoicesRequest(
        @NotNull List<@NotNull ChoiceInput> choices,
        boolean replace,
        ChoiceKindInput kind,
        @Size(max = 64) String respondent,
        boolean replaceAll) {

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
