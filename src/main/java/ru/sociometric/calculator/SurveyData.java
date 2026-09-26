package ru.sociometric.calculator;

import ru.sociometric.domain.GscFormula;
import ru.sociometric.domain.Participant;

import java.util.List;

/**
 * Входные данные расчёта: список участников в фиксированном порядке
 * (порядок задаёт нумерацию строк и столбцов матрицы) и сами выборы.
 *
 * @param participants  участники в порядке матрицы
 * @param choices       выборы; матрица строится с учетом весов
 * @param choicesLimit  сколько участников разрешено выбрать каждому респонденту
 * @param ranks         true — ранжированный опросник, вес выбора = choicesLimit - rank + 1
 * @param formula       формула коэффициента групповой сплоченности
 */
public record SurveyData(List<Participant> participants,
                         List<ChoiceEntry> choices,
                         int choicesLimit,
                         boolean ranks,
                         GscFormula formula) {

    public SurveyData {
        participants = List.copyOf(participants);
        choices = List.copyOf(choices);
    }

    public int size() {
        return participants.size();
    }

    /**
     * @param kind    вид выбора (симпатии/антипатии)
     * @param from    код респондента
     * @param to      код выбранного
     * @param rank    место в предпочтении, null если не ранжировалось
     * @param weight  вес вклада в статус; 1 если не задан
     */
    public record ChoiceEntry(ChoiceType kind, String from, String to, Integer rank, int weight) {

        public ChoiceEntry {
            if (weight <= 0) {
                weight = 1;
            }
        }

        public static ChoiceEntry of(ChoiceType kind, String from, String to) {
            return new ChoiceEntry(kind, from, to, null, 1);
        }
    }

    public enum ChoiceType {
        SYMPATHY,
        ANTIPATHY
    }
}
