package ru.sociometric.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sociometric.calculator.GroupIndices;
import ru.sociometric.calculator.SociometricCalculator;
import ru.sociometric.calculator.SociometricResult;
import ru.sociometric.calculator.StatusThresholds;
import ru.sociometric.calculator.SubGroup;
import ru.sociometric.calculator.SurveyData;
import ru.sociometric.config.AppProperties;
import ru.sociometric.domain.AntipathyCategory;
import ru.sociometric.domain.Choice;
import ru.sociometric.domain.ChoiceKind;
import ru.sociometric.domain.ExpertRating;
import ru.sociometric.domain.Participant;
import ru.sociometric.domain.ResponseMark;
import ru.sociometric.domain.StatusCategory;
import ru.sociometric.domain.Survey;
import ru.sociometric.domain.Thresholds;
import ru.sociometric.dto.ResultResponse;
import ru.sociometric.dto.ThresholdsDto;
import ru.sociometric.repository.ChoiceRepository;
import ru.sociometric.repository.ResponseMarkRepository;
import ru.sociometric.repository.ExpertRatingRepository;
import ru.sociometric.web.BadRequestException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Собирает результат расчёта для API: подтягивает данные опроса, считает
 * статусы и индексы, добавляет статистику по экспертным оценкам.
 */
@Service
public class ResultService {

    private final SurveyService surveyService;
    private final ChoiceRepository choices;
    private final ExpertRatingRepository experts;
    private final ResponseMarkRepository responses;
    private final AppProperties properties;

    public ResultService(SurveyService surveyService, ChoiceRepository choices,
                         ExpertRatingRepository experts, ResponseMarkRepository responses,
                         AppProperties properties) {
        this.surveyService = surveyService;
        this.choices = choices;
        this.experts = experts;
        this.responses = responses;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public ResultResponse calculate(Long surveyId) {
        Survey survey = surveyService.requireSurvey(surveyId);
        List<Participant> participants = surveyService.participantsOf(survey);
        if (participants.isEmpty()) {
            throw new BadRequestException("В опросе нет участников");
        }

        List<Choice> entities = choices.findBySurveyIdOrderById(surveyId);
        List<SurveyData.ChoiceEntry> entries = new ArrayList<>(entities.size());
        for (Choice choice : entities) {
            entries.add(new SurveyData.ChoiceEntry(
                    choice.getKind() == ChoiceKind.SYMPATHY
                            ? SurveyData.ChoiceType.SYMPATHY : SurveyData.ChoiceType.ANTIPATHY,
                    choice.getRespondent(), choice.getTarget(), choice.getRank(), choice.getWeight()));
        }

        Set<String> responded = responses.findBySurveyId(surveyId).stream()
                .map(ResponseMark::getRespondent)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        SurveyData data = new SurveyData(participants, entries, survey.getChoicesLimit(),
                survey.isRanks(), properties.formula(), responded);
        SociometricResult result = SociometricCalculator.calculate(data, thresholdsOf(survey),
                subGroups(participants));

        ResultResponse.ParticipantResult[] participantResults = participantResults(participants, result);
        double mean = average(result.incoming());
        double sd = stdDev(result.incoming(), mean);
        SociometricCalculator.Limits limits = SociometricCalculator.effectiveThresholds(
                thresholdsOf(survey), mean, sd);

        return new ResultResponse(
                surveyId, survey.getName(), result.n(), result.choicesLimit(),
                List.of(participantResults),
                indicesDto(result.indices()),
                groupDtos(result),
                result.matrix(), result.antiMatrix(),
                mutualPairs(result), unidirectionalPairs(result),
                effectiveThresholds(survey, result),
                survey.isAutoThresholds(),
                round4(mean), round4(sd),
                categoriesCount(result),
                List.of(result.stars()),
                List.of(result.isolates()),
                List.of(result.strongest()),
                new ResultResponse.GraphDto(result.meanDistance(), result.diameter(), result.radius(),
                        result.connectedComponents(), result.componentRadii(), result.componentDiameters(),
                        result.connectedPairs(), result.possiblePairs(), result.distances()),
                expertStats(survey, participants, result),
                properties.formula().name(),
                limits.degenerate());
    }

    private StatusThresholds thresholdsOf(Survey survey) {
        Thresholds thresholds = survey.getThresholds();
        return new StatusThresholds(
                thresholds == null ? 3 : thresholds.getStar(),
                thresholds == null ? 2 : thresholds.getPreferred(),
                thresholds == null ? 1 : thresholds.getAccepted(),
                thresholds == null ? 2 : thresholds.getMostRejected(),
                survey.isAutoThresholds());
    }

    private ThresholdsDto effectiveThresholds(Survey survey, SociometricResult result) {
        if (!survey.isAutoThresholds()) {
            return ThresholdsDto.from(survey.getThresholds());
        }
        double mean = average(result.incoming());
        SociometricCalculator.Limits limits = SociometricCalculator.effectiveThresholds(
                thresholdsOf(survey), mean, stdDev(result.incoming(), mean));
        return new ThresholdsDto(limits.star(), limits.preferred(), limits.accepted(),
                limits.mostRejected());
    }

    private ResultResponse.ParticipantResult[] participantResults(List<Participant> participants,
                                                                SociometricResult result) {
        ResultResponse.ParticipantResult[] rows = new ResultResponse.ParticipantResult[participants.size()];
        for (int i = 0; i < participants.size(); i++) {
            StatusCategory category = StatusCategory.valueOf(result.categories()[i]);
            AntipathyCategory antipathy = AntipathyCategory.valueOf(result.antipathyCategories()[i]);
            int mutual = 0;
            for (SociometricResult.MutualPair pair : result.mutualPairs()) {
                if (pair.a() == i || pair.b() == i) {
                    mutual++;
                }
            }
            rows[i] = new ResultResponse.ParticipantResult(
                    i, participants.get(i).code(), participants.get(i).name(),
                    result.incoming()[i], result.outgoing()[i],
                    result.weightedIncoming()[i], result.weightedOutgoing()[i],
                    round4(result.normalizedIncoming()[i]),
                    result.antipathyIncoming()[i], result.antipathyOutgoing()[i],
                    category.name(), category.getTitle(),
                    antipathy.name(), antipathy.getTitle(),
                    mutual, result.outdegree()[i], result.responded()[i]);
        }
        return rows;
    }

    private List<ResultResponse.GroupDto> groupDtos(SociometricResult result) {
        List<ResultResponse.GroupDto> dtos = new ArrayList<>(result.groups().length);
        for (SociometricResult.GroupIndex group : result.groups()) {
            dtos.add(new ResultResponse.GroupDto(group.name(), group.n(), indicesDto(group.indices())));
        }
        return dtos;
    }

    private List<ResultResponse.MutualPairDto> mutualPairs(SociometricResult result) {
        List<ResultResponse.MutualPairDto> dtos = new ArrayList<>(result.mutualPairs().length);
        for (SociometricResult.MutualPair pair : result.mutualPairs()) {
            dtos.add(new ResultResponse.MutualPairDto(pair.a(), pair.b()));
        }
        return dtos;
    }

    private List<ResultResponse.UnidirectionalPairDto> unidirectionalPairs(SociometricResult result) {
        List<ResultResponse.UnidirectionalPairDto> dtos = new ArrayList<>(result.unidirectionalPairs().length);
        for (SociometricResult.UnidirectionalPair pair : result.unidirectionalPairs()) {
            dtos.add(new ResultResponse.UnidirectionalPairDto(pair.a(), pair.b(), pair.weight()));
        }
        return dtos;
    }

    private Map<String, Integer> categoriesCount(SociometricResult result) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (StatusCategory category : StatusCategory.values()) {
            counts.put(category.name(), result.categoriesCount()[category.ordinal()]);
        }
        return counts;
    }

    private ResultResponse.ExpertDto expertStats(Survey survey, List<Participant> participants,
                                                 SociometricResult result) {
        List<ExpertRating> ratings = experts.findBySurveyIdOrderByExpertAscIdAsc(survey.getId());
        if (ratings.isEmpty()) {
            return null;
        }
        List<String> names = ratings.stream().map(ExpertRating::getExpert).distinct().sorted().toList();
        Map<String, Integer> codeToIndex = new LinkedHashMap<>();
        for (int i = 0; i < participants.size(); i++) {
            codeToIndex.put(participants.get(i).code(), i);
        }

        // матрица m экспертов x n участников; NaN — эксперт не оценил
        double[][] matrix = new double[names.size()][participants.size()];
        for (double[] row : matrix) {
            Arrays.fill(row, Double.NaN);
        }
        for (ExpertRating rating : ratings) {
            int m = names.indexOf(rating.getExpert());
            Integer j = codeToIndex.get(rating.getParticipantCode());
            if (m >= 0 && j != null) {
                matrix[m][j] = rating.getScore();
            }
        }

        Map<String, double[]> byCode = ExpertAgreement.byParticipant(names, matrix, participants);
        List<ResultResponse.ParticipantScore> scores = new ArrayList<>(participants.size());
        double[] expertMeans = new double[participants.size()];
        for (int i = 0; i < participants.size(); i++) {
            String code = participants.get(i).code();
            double[] values = byCode.getOrDefault(code, new double[0]);
            // кого не оценил ни один эксперт, того не считаем нулём: ноль — это оценка
            // «самый непопулярный», и в корреляцию попал бы вымышленный балл
            expertMeans[i] = values.length == 0 ? Double.NaN : ExpertAgreement.mean(values);
            scores.add(new ResultResponse.ParticipantScore(code, participants.get(i).name(),
                    values.length == 0 ? null : round4(expertMeans[i]),
                    values.length == 0 ? null : round4(ExpertAgreement.sd(values)),
                    values.length == 0 ? 0 : (int) Math.round(Arrays.stream(values).min().orElse(0)),
                    values.length == 0 ? 0 : (int) Math.round(Arrays.stream(values).max().orElse(0)),
                    values.length, result.incoming()[i]));
        }

        Double pearson = ExpertAgreement.pearson(expertMeans, toDouble(result.incoming()));
        Double spearman = ExpertAgreement.spearman(expertMeans, toDouble(result.incoming()));
        int[] kendallInfo = new int[2];
        Double kendallW = ExpertAgreement.kendallW(matrix, kendallInfo);

        Map<String, Double> scoreByCode = new LinkedHashMap<>();
        for (int i = 0; i < participants.size(); i++) {
            scoreByCode.put(participants.get(i).code(), expertMeans[i]);
        }
        List<ResultResponse.RankingRow> ranking = new ArrayList<>();
        for (ExpertAgreement.RankRow row : ExpertAgreement.ranking(participants, scoreByCode, result.incoming())) {
            ranking.add(new ResultResponse.RankingRow(ranking.size() + 1, row.code(), row.name(),
                    row.expertRank(), row.sociometricRank(), row.difference()));
        }

        return new ResultResponse.ExpertDto(names, scores, kendallW, pearson, spearman, ranking,
                kendallInfo[0], kendallInfo[1]);
    }

    /**
     * Делит участников на подгруппы по буквенному префиксу кода:
     * «1А», «1Б» — две подгруппы, «1»..«25» — одна группа без деления.
     */
    /**
     * Раскладывает участников по подгруппам.
     *
     * <p>Ключ подгруппы — ведущие цифры кода: «1А» и «1Б» попадают в подгруппу «1»,
     * «2А» и «2Б» — в «2». Коды без ведущих цифр складываются в одну подгруппу «»,
     * то есть сравнивать в ней нечего. Подгруппы меньше двух человек индексов не
     * считают, поэтому отбрасываются.
     */
    static List<SubGroup> subGroups(List<Participant> participants) {
        Map<String, List<Integer>> byPrefix = new LinkedHashMap<>();
        for (int i = 0; i < participants.size(); i++) {
            String code = participants.get(i).code();
            String prefix = code == null ? "" : code.replaceAll("^(\\d+).*$", "$1");
            byPrefix.computeIfAbsent(prefix, key -> new ArrayList<>()).add(i);
        }
        if (byPrefix.size() < 2) {
            return List.of();
        }
        List<SubGroup> groups = new ArrayList<>();
        byPrefix.forEach((prefix, indices) -> {
            if (indices.size() >= 2) {
                groups.add(new SubGroup(prefix, indices));
            }
        });
        return groups;
    }

    private static ResultResponse.IndicesDto indicesDto(GroupIndices indices) {
        return new ResultResponse.IndicesDto(indices.n(), indices.totalSelections(),
                indices.meanSelectionsPerPerson(), indices.mutualPairsCount(),
                indices.unidirectionalPairsCount(), indices.possiblePairsCount(),
                indices.possibleSelectionsCount(), indices.gsc(), indices.reciprocityIndex(),
                indices.density(), indices.isolationIndex(), indices.rejectedIndex(),
                indices.negativeStatusIndex(), indices.mutualChoiceDistribution());
    }

    private static double[] toDouble(int[] values) {
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[i];
        }
        return result;
    }

    private static double average(int[] values) {
        if (values.length == 0) {
            return 0;
        }
        long sum = 0;
        for (int value : values) {
            sum += value;
        }
        return (double) sum / values.length;
    }

    private static double stdDev(int[] values, double mean) {
        if (values.length == 0) {
            return 0;
        }
        double sum = 0;
        for (int value : values) {
            double d = value - mean;
            sum += d * d;
        }
        return Math.sqrt(sum / values.length);
    }

    private static double round4(double value) {
        return Math.round(value * 10000d) / 10000d;
    }
}
