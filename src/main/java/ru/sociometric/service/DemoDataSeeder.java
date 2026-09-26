package ru.sociometric.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.sociometric.config.AppProperties;
import ru.sociometric.domain.Choice;
import ru.sociometric.domain.ChoiceKind;
import ru.sociometric.domain.ExpertRating;
import ru.sociometric.repository.ChoiceRepository;
import ru.sociometric.repository.ExpertRatingRepository;
import ru.sociometric.repository.SurveyRepository;
import ru.sociometric.dto.ParticipantDto;
import ru.sociometric.dto.SurveyRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * При пустой базе создаёт демонстрационный опрос: 12 участников, симпатии,
 * антипатии и оценки трёх экспертов. Так результаты видны сразу после запуска.
 */
@Component
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final SurveyRepository surveys;
    private final ChoiceRepository choices;
    private final ExpertRatingRepository experts;
    private final SurveyService surveyService;
    private final AppProperties properties;

    public DemoDataSeeder(SurveyRepository surveys, ChoiceRepository choices,
                          ExpertRatingRepository experts, SurveyService surveyService,
                          AppProperties properties) {
        this.surveys = surveys;
        this.choices = choices;
        this.experts = experts;
        this.surveyService = surveyService;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!properties.demoDataOnStartup() || surveys.count() > 0) {
            return;
        }
        SurveyRequest request = new SurveyRequest(
                "8А класс — учебный пример",
                "Демонстрационные данные: социометрический тест с блоком антипатий "
                        + "и оценками популярности тремя экспертами.",
                3, true, false, true, null, participants());
        Long surveyId = surveyService.create(request).id();

        List<Choice> entities = new ArrayList<>();
        for (String[] pair : sympathy()) {
            entities.add(new Choice(surveyId, pair[0], pair[1], ChoiceKind.SYMPATHY));
        }
        for (String[] pair : antipathy()) {
            entities.add(new Choice(surveyId, pair[0], pair[1], ChoiceKind.ANTIPATHY));
        }
        choices.saveAll(entities);

        List<ExpertRating> ratings = new ArrayList<>();
        ratings.addAll(expert(surveyId, "Психолог", new int[]{9, 8, 8, 6, 7, 5, 5, 4, 4, 3, 1, 0}));
        ratings.addAll(expert(surveyId, "Классный руководитель", new int[]{10, 9, 7, 5, 6, 5, 4, 4, 3, 3, 1, 0}));
        ratings.addAll(expert(surveyId, "Родители", new int[]{8, 9, 9, 4, 5, 6, 6, 5, 3, 2, 0, 0}));
        experts.saveAll(ratings);

        log.info("Создан демонстрационный опрос №{} «{}»", surveyId, request.name());
    }

    private List<ExpertRating> expert(Long surveyId, String name, int[] scores) {
        List<ExpertRating> result = new ArrayList<>();
        for (int i = 0; i < scores.length; i++) {
            result.add(new ExpertRating(surveyId, name, String.valueOf(i + 1), scores[i]));
        }
        return result;
    }

    private static List<ParticipantDto> participants() {
        String[] names = {"Анна", "Борис", "Вера", "Глеб", "Дарья", "Егор", "Жанна", "Захар",
                "Ирина", "Кирилл", "Лев", "Матвей"};
        List<ParticipantDto> result = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            result.add(new ParticipantDto(String.valueOf(i + 1), names[i]));
        }
        return result;
    }

    /** Симпатии: у 11 участника есть выборы, но его не выбирает никто; 12 — аутсайдер. */
    private static String[][] sympathy() {
        return new String[][]{
                {"1", "2"}, {"1", "3"}, {"1", "5"},
                {"2", "1"}, {"2", "3"}, {"2", "4"},
                {"3", "1"}, {"3", "2"}, {"3", "4"},
                {"4", "2"}, {"4", "3"}, {"4", "6"},
                {"5", "1"}, {"5", "4"}, {"5", "6"},
                {"6", "4"}, {"6", "5"}, {"6", "7"},
                {"7", "6"}, {"7", "8"}, {"7", "9"},
                {"8", "7"}, {"8", "9"}, {"8", "10"},
                {"9", "7"}, {"9", "8"}, {"9", "10"},
                {"10", "6"}, {"10", "8"}, {"10", "9"},
                {"11", "2"},
        };
    }

    private static String[][] antipathy() {
        return new String[][]{
                {"1", "12"}, {"2", "12"}, {"3", "12"}, {"5", "12"}, {"7", "12"},
                {"4", "11"}, {"6", "11"}, {"8", "11"},
        };
    }
}
