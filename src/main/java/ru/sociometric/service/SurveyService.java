package ru.sociometric.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sociometric.domain.Choice;
import ru.sociometric.domain.ChoiceKind;
import ru.sociometric.domain.Participant;
import ru.sociometric.domain.Survey;
import ru.sociometric.domain.Thresholds;
import ru.sociometric.dto.ChoicesRequest;
import ru.sociometric.dto.ExpertsRequest;
import ru.sociometric.dto.ParticipantDto;
import ru.sociometric.dto.SurveyRequest;
import ru.sociometric.dto.SurveyResponse;
import ru.sociometric.dto.ThresholdsDto;
import ru.sociometric.repository.ChoiceRepository;
import ru.sociometric.repository.ExpertRatingRepository;
import ru.sociometric.repository.SurveyRepository;
import ru.sociometric.web.BadRequestException;
import ru.sociometric.web.NotFoundException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Создание и изменение опросов, участников, выборов и экспертных оценок.
 */
@Service
public class SurveyService {

    private final SurveyRepository surveys;
    private final ChoiceRepository choices;
    private final ExpertRatingRepository experts;
    private final ObjectMapper json;

    public SurveyService(SurveyRepository surveys, ChoiceRepository choices,
                         ExpertRatingRepository experts, ObjectMapper json) {
        this.surveys = surveys;
        this.choices = choices;
        this.experts = experts;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<SurveyResponse> list() {
        return surveys.findAllByOrderByIdDesc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public SurveyResponse get(Long id) {
        return toResponse(requireSurvey(id));
    }

    @Transactional
    public SurveyResponse create(SurveyRequest request) {
        Survey survey = new Survey();
        apply(survey, request);
        surveys.save(survey);
        return toResponse(survey);
    }

    @Transactional
    public SurveyResponse update(Long id, SurveyRequest request) {
        Survey survey = requireSurvey(id);
        List<Participant> before = participantsOf(survey);
        apply(survey, request);
        remapChoices(survey, before, participantsOf(survey));
        surveys.save(survey);
        return toResponse(survey);
    }

    @Transactional
    public void delete(Long id) {
        Survey survey = requireSurvey(id);
        choices.deleteBySurveyId(id);
        experts.deleteBySurveyId(id);
        surveys.delete(survey);
    }

    @Transactional
    public SurveyResponse replaceParticipants(Long id, List<ParticipantDto> participants) {
        Survey survey = requireSurvey(id);
        List<Participant> before = participantsOf(survey);
        validateParticipants(participants);
        survey.setParticipantsJson(write(participants));
        remapChoices(survey, before, participantsOf(survey));
        surveys.save(survey);
        return toResponse(survey);
    }

    // ------------------------------------------------------------------
    // выборы
    // ------------------------------------------------------------------

    @Transactional
    public SurveyResponse saveChoices(Long id, ChoicesRequest request) {
        Survey survey = requireSurvey(id);
        List<Participant> participants = participantsOf(survey);
        Set<String> codes = new LinkedHashSet<>();
        participants.forEach(participant -> codes.add(participant.code()));

        if (request.replace() && request.kind() != null) {
            choices.deleteBySurveyIdAndKind(id, ChoiceKind.valueOf(request.kind().name()));
        }

        Set<String> seen = new LinkedHashSet<>();
        List<Choice> entities = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (ChoicesRequest.ChoiceInput input : request.choices()) {
            ChoiceKind kind;
            try {
                kind = ChoiceKind.valueOf(input.kind().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Неизвестный вид выбора: " + input.kind()
                        + ". Ожидается SYMPATHY или ANTIPATHY");
            }
            if (kind == ChoiceKind.ANTIPATHY && !survey.isCollectAntipathy()) {
                problems.add("Антипатии в опросе не собираются: " + input.from() + " -> " + input.to());
                continue;
            }
            if (!codes.contains(input.from())) {
                throw new BadRequestException("Респондент " + input.from() + " не найден среди участников");
            }
            if (!codes.contains(input.to())) {
                problems.add("Участник " + input.to() + " не найден — выбор пропущен");
                continue;
            }
            if (input.from().equals(input.to())) {
                problems.add("Участник " + input.from() + " не может выбрать самого себя — пропущено");
                continue;
            }
            String key = kind + "|" + input.from() + "|" + input.to();
            if (!seen.add(key)) {
                continue;
            }
            Choice choice = new Choice(id, input.from(), input.to(), kind);
            choice.setRank(input.rank());
            choice.setWeight(input.weight() == null || input.weight() <= 0 ? 1 : input.weight());
            entities.add(choice);
        }

        if (!problems.isEmpty()) {
            throw new BadRequestException(String.join("; ", problems));
        }
        choices.saveAll(entities);
        survey.setUpdatedAt(Instant.now());
        surveys.save(survey);
        return toResponse(survey);
    }

    @Transactional
    public SurveyResponse clearChoices(Long id, String kind) {
        Survey survey = requireSurvey(id);
        if (kind == null || kind.isBlank() || "all".equalsIgnoreCase(kind)) {
            choices.deleteBySurveyId(id);
        } else {
            choices.deleteBySurveyIdAndKind(id, parseKind(kind));
        }
        survey.setUpdatedAt(Instant.now());
        surveys.save(survey);
        return toResponse(survey);
    }

    @Transactional(readOnly = true)
    public List<ChoiceView> listChoices(Long id) {
        requireSurvey(id);
        return choices.findBySurveyIdOrderById(id).stream()
                .map(choice -> new ChoiceView(choice.getRespondent(), choice.getTarget(),
                        choice.getKind().name(), choice.getRank(), choice.getWeight()))
                .toList();
    }

    // ------------------------------------------------------------------
    // экспертные оценки
    // ------------------------------------------------------------------

    @Transactional
    public SurveyResponse saveExperts(Long id, ExpertsRequest request) {
        Survey survey = requireSurvey(id);
        List<Participant> participants = participantsOf(survey);
        Set<String> codes = new LinkedHashSet<>();
        participants.forEach(participant -> codes.add(participant.code()));

        if (request.replace()) {
            experts.deleteBySurveyId(id);
        }
        Map<String, ru.sociometric.domain.ExpertRating> existing = new HashMap<>();
        experts.findBySurveyIdOrderByExpertAscIdAsc(id)
                .forEach(rating -> existing.put(rating.getExpert() + "|" + rating.getParticipantCode(), rating));

        List<ru.sociometric.domain.ExpertRating> entities = new ArrayList<>();
        for (ExpertsRequest.RatingInput input : request.ratings()) {
            if (!codes.contains(input.code())) {
                throw new BadRequestException("Участник " + input.code() + " не найден среди участников");
            }
            String key = input.expert() + "|" + input.code();
            ru.sociometric.domain.ExpertRating rating = existing.remove(key);
            if (rating == null) {
                rating = new ru.sociometric.domain.ExpertRating();
                rating.setSurveyId(id);
                rating.setExpert(input.expert());
                rating.setParticipantCode(input.code());
            }
            rating.setScore(input.score());
            entities.add(rating);
        }
        experts.saveAll(entities);
        survey.setUpdatedAt(Instant.now());
        surveys.save(survey);
        return toResponse(survey);
    }

    @Transactional(readOnly = true)
    public List<ExpertRatingView> listExperts(Long id) {
        requireSurvey(id);
        return experts.findBySurveyIdOrderByExpertAscIdAsc(id).stream()
                .map(rating -> new ExpertRatingView(rating.getExpert(), rating.getParticipantCode(), rating.getScore()))
                .toList();
    }

    @Transactional
    public void clearExperts(Long id) {
        requireSurvey(id);
        experts.deleteBySurveyId(id);
    }

    // ------------------------------------------------------------------
    // служебное
    // ------------------------------------------------------------------

    public Survey requireSurvey(Long id) {
        Survey survey = surveys.findById(id)
                .orElseThrow(() -> new NotFoundException("Опрос " + id + " не найден"));
        return survey;
    }

    public List<Participant> participantsOf(Survey survey) {
        List<ParticipantDto> dtos = read(survey.getParticipantsJson());
        List<Participant> result = new ArrayList<>(dtos.size());
        for (ParticipantDto dto : dtos) {
            result.add(new Participant(dto.code(), dto.name() == null ? dto.code() : dto.name()));
        }
        return result;
    }

    private void apply(Survey survey, SurveyRequest request) {
        validateParticipants(request.participants());
        survey.setName(request.name().trim());
        survey.setDescription(request.description());
        survey.setChoicesLimit(request.choicesLimit());
        survey.setCollectAntipathy(request.collectAntipathy());
        survey.setRanks(request.ranks());
        survey.setAutoThresholds(request.autoThresholds());
        Thresholds thresholds = survey.getThresholds() == null ? new Thresholds() : survey.getThresholds();
        if (request.thresholds() != null) {
            request.thresholds().applyTo(thresholds);
        }
        survey.setThresholds(thresholds);
        survey.setParticipantsJson(write(request.participants()));
        survey.setUpdatedAt(Instant.now());
    }

    private void validateParticipants(List<ParticipantDto> participants) {
        if (participants == null || participants.isEmpty()) {
            throw new BadRequestException("Нужен хотя бы один участник");
        }
        Set<String> codes = new LinkedHashSet<>();
        for (ParticipantDto participant : participants) {
            String code = participant.code() == null ? "" : participant.code().trim();
            if (code.isEmpty()) {
                throw new BadRequestException("У участника не задан код");
            }
            if (!codes.add(code)) {
                throw new BadRequestException("Код участника повторяется: " + code);
            }
        }
    }

    /**
     * Если код участника изменился, выборы и оценки, ссылавшиеся на старый код,
     * должны уехать вместе с ним.
     */
    private void remapChoices(Survey survey, List<Participant> before, List<Participant> after) {
        if (before.isEmpty() || before.size() != after.size()) {
            return;
        }
        List<String> oldCodes = before.stream().map(Participant::code).toList();
        List<String> newCodes = after.stream().map(Participant::code).toList();
        if (oldCodes.equals(newCodes)) {
            return;
        }
        Map<String, String> renamed = new HashMap<>();
        for (int i = 0; i < oldCodes.size(); i++) {
            renamed.put(oldCodes.get(i), newCodes.get(i));
        }
        for (Choice choice : choices.findBySurveyIdOrderById(survey.getId())) {
            if (renamed.containsKey(choice.getRespondent())) {
                choice.setRespondent(renamed.get(choice.getRespondent()));
            }
            if (renamed.containsKey(choice.getTarget())) {
                choice.setTarget(renamed.get(choice.getTarget()));
            }
        }
    }

    public SurveyResponse toResponse(Survey survey) {
        long sympathy = choices.countBySurveyIdAndKind(survey.getId(), ChoiceKind.SYMPATHY);
        long antipathy = choices.countBySurveyIdAndKind(survey.getId(), ChoiceKind.ANTIPATHY);
        Set<String> responded = new LinkedHashSet<>();
        choices.findBySurveyIdOrderById(survey.getId())
                .forEach(choice -> responded.add(choice.getRespondent()));
        return new SurveyResponse(survey.getId(), survey.getName(), survey.getDescription(),
                survey.getChoicesLimit(), survey.isCollectAntipathy(), survey.isRanks(),
                survey.isAutoThresholds(), ThresholdsDto.from(survey.getThresholds()),
                read(survey.getParticipantsJson()), sympathy, antipathy,
                experts.countBySurveyId(survey.getId()), responded.size(),
                survey.getCreatedAt(), survey.getUpdatedAt());
    }

    private static ChoiceKind parseKind(String kind) {
        try {
            return ChoiceKind.valueOf(kind.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Неизвестный вид выбора: " + kind);
        }
    }

    private String write(List<ParticipantDto> participants) {
        try {
            return json.writeValueAsString(participants);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сохранить список участников", e);
        }
    }

    private List<ParticipantDto> read(String participantsJson) {
        if (participantsJson == null || participantsJson.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(participantsJson, new TypeReference<List<ParticipantDto>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось прочитать список участников", e);
        }
    }

    /** Один выбор в ответе API. */
    public record ChoiceView(String from, String to, String kind, Integer rank, int weight) {
    }

    /** Одна экспертная оценка в ответе API. */
    public record ExpertRatingView(String expert, String code, int score) {
    }
}
