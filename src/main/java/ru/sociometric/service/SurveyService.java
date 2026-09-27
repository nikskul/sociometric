package ru.sociometric.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.sociometric.domain.Choice;
import ru.sociometric.domain.ChoiceKind;
import ru.sociometric.domain.Participant;
import ru.sociometric.domain.ResponseMark;
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
import ru.sociometric.repository.ResponseMarkRepository;
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
    private final ResponseMarkRepository responses;
    private final ExpertRatingRepository experts;
    private final ObjectMapper json;

    public SurveyService(SurveyRepository surveys, ChoiceRepository choices,
                         ExpertRatingRepository experts, ResponseMarkRepository responses,
                         ObjectMapper json) {
        this.surveys = surveys;
        this.choices = choices;
        this.experts = experts;
        this.responses = responses;
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

        // отмечаем ответившим до проверки выборов: «отвечал, но никого не выбрал» —
        // это тоже ответ, и он не должен превращаться в «не ответил»
        if (request.respondent() != null && !request.respondent().isBlank()) {
            String code = request.respondent();
            if (!codes.contains(code)) {
                throw new BadRequestException("Респондент " + code + " не найден среди участников");
            }
            markResponded(id, code);
        }

        List<ParsedChoice> parsed = parseChoices(request, survey, codes);

        if (request.replaceAll()) {
            syncFullState(id, parsed);
        } else if (request.replace()) {
            // kind replace сносит только один вид, поэтому чужие строки в теле
            // запроса — это не «лишнее», а ошибка клиента: без проверки вставка
            // упала бы на уникальном индексе с невнятным 500
            requireSameKind(request, parsed);
            choices.deleteBySurveyIdAndKind(id, ChoiceKind.valueOf(request.kind().name()));
            choices.saveAll(parsed.stream().map(choice -> choice.toEntity(id)).toList());
        } else {
            List<Choice> entities = new ArrayList<>();
            for (ParsedChoice choice : parsed) {
                entities.add(choice.toEntity(id));
            }
            choices.saveAll(entities);
        }

        // без явного respondent отвечавшими считаем всех, кто что-то выбрал
        if (request.respondent() == null || request.respondent().isBlank()) {
            parsed.stream().map(ParsedChoice::from).distinct().forEach(code -> markResponded(id, code));
        }
        survey.setUpdatedAt(Instant.now());
        surveys.save(survey);
        return toResponse(survey);
    }

    /**
     * Естественный ключ строки выбора — ровно то, чем строка уникальна в
     * {@code uk_choice}. Отдельная record, а не склейка строк: код участника —
     * произвольный текст, так что разделитель пришлось бы как-то угадывать.
     */
    private record ChoiceRef(ChoiceKind kind, String from, String to) {
    }

    /** Разобранный и проверенный выбор: до момента сохранения это ещё не сущность. */
    private record ParsedChoice(ChoiceKind kind, String from, String to, Integer rank, int weight) {

        ChoiceRef ref() {
            return new ChoiceRef(kind, from, to);
        }

        Choice toEntity(Long surveyId) {
            Choice choice = new Choice(surveyId, from, to, kind);
            choice.setRank(rank);
            choice.setWeight(weight);
            return choice;
        }
    }

    private static ChoiceRef refOf(Choice choice) {
        return new ChoiceRef(choice.getKind(), choice.getRespondent(), choice.getTarget());
    }

    /**
     * У режима {@code replace} тело запроса должно состоять из одного вида
     * выборов: сносит он тоже только один вид, и чужая строка осталась бы в базе,
     * а повторная отправка упала бы на {@code uk_choice}.
     */
    private static void requireSameKind(ChoicesRequest request, List<ParsedChoice> parsed) {
        if (request.kind() == null) {
            throw new BadRequestException("Для замены выборов нужно указать kind: SYMPATHY или ANTIPATHY");
        }
        ChoiceKind kind = ChoiceKind.valueOf(request.kind().name());
        for (ParsedChoice choice : parsed) {
            if (choice.kind() != kind) {
                throw new BadRequestException("kind=" + request.kind()
                        + " заменяет только один вид выборов, а в запросе есть " + choice.kind()
                        + " (" + choice.from() + " -> " + choice.to() + ")"
                        + ". Чтобы перезаписать оба вида сразу, пришлите replaceAll=true");
            }
        }
    }

    /**
     * Проверяет запрос и превращает его в список выборов. Ошибки накапливаются и
     * выбрасываются одним исключением, чтобы не оставить опрос наполовину
     * переписанным из-за первой же негодной строки.
     */
    private List<ParsedChoice> parseChoices(ChoicesRequest request, Survey survey, Set<String> codes) {
        Set<ChoiceRef> seen = new LinkedHashSet<>();
        List<ParsedChoice> parsed = new ArrayList<>();
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
            if (!seen.add(new ChoiceRef(kind, input.from(), input.to()))) {
                continue;
            }
            int weight = input.weight() == null || input.weight() <= 0 ? 1 : input.weight();
            parsed.add(new ParsedChoice(kind, input.from(), input.to(), input.rank(), weight));
        }
        if (!problems.isEmpty()) {
            throw new BadRequestException(String.join("; ", problems));
        }
        return parsed;
    }

    /**
     * Приводит набор выборов в опросе к состоянию, описанному запросом.
     *
     * <p>Именно сравнение, а не «снести всё и вставить заново»: строки выборов
     * уникальны по {@code (опрос, респондент, цель, вид)}, поэтому удалять и
     * вставлять их по второму разу нельзя — вторая вставка упирается в
     * уникальный индекс. Здесь незатронутые строки остаются на месте (id
     * сохраняется), у изменившихся правится ранг и вес, и только по-настоящему
     * исчезнувшие удаляются.
     */
    private void syncFullState(Long id, List<ParsedChoice> parsed) {
        List<Choice> existing = choices.findBySurveyIdOrderById(id);
        Map<ChoiceRef, Choice> byRef = new HashMap<>();
        for (Choice choice : existing) {
            byRef.put(refOf(choice), choice);
        }

        Set<ChoiceRef> wanted = new LinkedHashSet<>();
        List<Choice> toInsert = new ArrayList<>();
        for (ParsedChoice choice : parsed) {
            ChoiceRef ref = choice.ref();
            wanted.add(ref);
            Choice current = byRef.get(ref);
            if (current == null) {
                toInsert.add(choice.toEntity(id));
            } else {
                // та же строка, но, возможно, с другим рангом или весом
                current.setRank(choice.rank());
                current.setWeight(choice.weight());
            }
        }

        List<Choice> toDelete = new ArrayList<>();
        for (Choice choice : existing) {
            if (!wanted.contains(refOf(choice))) {
                toDelete.add(choice);
            }
        }

        // вставки и удаления затрагивают разные ключи, поэтому порядок не важен
        choices.deleteAll(toDelete);
        choices.saveAll(toInsert);
    }

    /** Отмечает участника ответившим. Публичный метод: импорт тоже знает про ответы. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markResponded(Long surveyId, String respondent) {
        if (responses.findBySurveyIdAndRespondent(surveyId, respondent).isEmpty()) {
            responses.save(new ResponseMark(surveyId, respondent));
        }
    }

    /** Снимает все отметки об ответе: данных об ответах больше нет. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void clearResponses(Long surveyId) {
        responses.deleteBySurveyId(surveyId);
    }

    @Transactional
    public SurveyResponse clearChoices(Long id, String kind) {
        Survey survey = requireSurvey(id);
        if (kind == null || kind.isBlank() || "all".equalsIgnoreCase(kind)) {
            choices.deleteBySurveyId(id);
            // ответов больше нет — снимаем и отметки об ответе
            responses.deleteBySurveyId(id);
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
    /**
     * Переносит выборы и отметки об ответе на новый состав участников.
     *
     * <p>Коды сопоставляются по позиции, но только если это единственное возможное
     * прочтение: если старый код остался в списке на другой позиции, участника
     * вставили в середину, и позиционная перестановка молча перевесила бы выборы
     * на чужих людей. В таком случае выборы не трогаем — лучше потерять
     * автоматическое переименование, чем испортить данные.
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
            String oldCode = oldCodes.get(i);
            String newCode = newCodes.get(i);
            if (oldCode.equals(newCode)) {
                continue;
            }
            int oldPosition = oldCodes.indexOf(oldCode);
            int newPosition = newCodes.indexOf(oldCode);
            if (newPosition >= 0 && newPosition != oldPosition) {
                // тот же код переехал на другую позицию: это вставка, а не переименование
                return;
            }
            if (newCodes.subList(0, i).contains(newCode)) {
                // новый код уже занят другим участником — переименование создаст дубль
                return;
            }
            renamed.put(oldCode, newCode);
        }
        if (renamed.isEmpty()) {
            return;
        }

        Long surveyId = survey.getId();
        List<Choice> stale = new ArrayList<>();
        for (Choice choice : choices.findBySurveyIdOrderById(surveyId)) {
            String from = renamed.getOrDefault(choice.getRespondent(), choice.getRespondent());
            String to = renamed.getOrDefault(choice.getTarget(), choice.getTarget());
            if (from.equals(to)) {
                // после переименования участник «выбрал бы самого себя» — выбор не имеет смысла
                stale.add(choice);
                continue;
            }
            choice.setRespondent(from);
            choice.setTarget(to);
        }
        if (!stale.isEmpty()) {
            choices.deleteAll(stale);
        }

        Set<String> newCodeSet = new LinkedHashSet<>(newCodes);
        for (ResponseMark mark : responses.findBySurveyId(surveyId)) {
            String respondent = renamed.getOrDefault(mark.getRespondent(), mark.getRespondent());
            if (!newCodeSet.contains(respondent)) {
                // участника убрали из списка — отметка об ответе больше не нужна
                responses.deleteByRespondent(surveyId, mark.getRespondent());
                continue;
            }
            if (!respondent.equals(mark.getRespondent())) {
                // код сменился, а пара (опрос, респондент) уже занята — обновляем
                // через удаление и вставку, иначе нарушится уникальный индекс
                responses.deleteByRespondent(surveyId, mark.getRespondent());
                responses.save(new ResponseMark(surveyId, respondent));
            }
        }
    }

    public SurveyResponse toResponse(Survey survey) {
        long sympathy = choices.countBySurveyIdAndKind(survey.getId(), ChoiceKind.SYMPATHY);
        long antipathy = choices.countBySurveyIdAndKind(survey.getId(), ChoiceKind.ANTIPATHY);
        // ответившие считаются по отметкам, а не по наличию строк выбора:
        // ответ «никого не выбрал» не оставляет ни одной строки
        int responded = (int) responses.countBySurveyId(survey.getId());
        return new SurveyResponse(survey.getId(), survey.getName(), survey.getDescription(),
                survey.getChoicesLimit(), survey.isCollectAntipathy(), survey.isRanks(),
                survey.isAutoThresholds(), ThresholdsDto.from(survey.getThresholds()),
                read(survey.getParticipantsJson()), sympathy, antipathy,
                experts.countBySurveyId(survey.getId()), responded,
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
