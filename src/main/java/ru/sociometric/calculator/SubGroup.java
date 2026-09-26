package ru.sociometric.calculator;

import java.util.List;

/**
 * Подгруппа участников (класс, параллель, команда) для расчета индексов по частям.
 *
 * @param name    название подгруппы
 * @param indices индексы участников в общем массиве
 */
public record SubGroup(String name, List<Integer> indices) {

    public SubGroup {
        indices = List.copyOf(indices);
    }
}
