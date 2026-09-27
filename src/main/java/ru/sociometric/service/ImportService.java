package ru.sociometric.service;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.sociometric.domain.Choice;
import ru.sociometric.domain.ChoiceKind;
import ru.sociometric.domain.Participant;
import ru.sociometric.domain.Survey;
import ru.sociometric.dto.ImportResponse;
import ru.sociometric.repository.ChoiceRepository;
import ru.sociometric.web.BadRequestException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Загрузка выборов из CSV или XLSX.
 *
 * <p>Поддерживаются два формата:
 * <ul>
 *   <li>длинный — колонки {@code type, respondent, target, rank}:
 *       {@code SYMPATHY,1,2} или {@code antipathy,3,4,1};</li>
 *   <li>широкий (сама матрица) — первая колонка «кто выбирает», остальные —
 *       коды участников, значение 1/0 или ранг 1..N.</li>
 * </ul>
 */
@Service
public class ImportService {

    private final SurveyService surveyService;
    private final ChoiceRepository choices;

    public ImportService(SurveyService surveyService, ChoiceRepository choices) {
        this.surveyService = surveyService;
        this.choices = choices;
    }

    @Transactional
    public ImportResponse importChoices(Long surveyId, MultipartFile file, boolean replace) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Файл не выбран");
        }
        Survey survey = surveyService.requireSurvey(surveyId);
        List<Participant> participants = surveyService.participantsOf(survey);
        List<String[]> rows = read(file);
        if (rows.isEmpty()) {
            throw new BadRequestException("Файл пуст");
        }
        if (replace) {
            choices.deleteBySurveyId(surveyId);
            surveyService.clearResponses(surveyId);
        }

        Parsed parsed = looksLong(rows.get(0)) ? parseLong(rows, participants) : parseWide(rows, participants);

        List<String> warnings = new ArrayList<>(parsed.warnings());
        if (parsed.entries().isEmpty()) {
            throw new BadRequestException("В файле не найдено ни одного выбора. "
                    + "Ожидаются колонки type,respondent,target,rank либо матрица с кодами участников. "
                    + (warnings.isEmpty() ? "" : "Замечания: " + String.join("; ", warnings)));
        }
        if (!survey.isCollectAntipathy()) {
            List<SurveyDataEntry> filtered = new ArrayList<>();
            for (SurveyDataEntry entry : parsed.entries()) {
                if (entry.kind() == ChoiceKind.SYMPATHY) {
                    filtered.add(entry);
                }
            }
            if (filtered.size() != parsed.entries().size()) {
                warnings.add("Антипатии отброшены: в настройках опроса они не собираются");
            }
            parsed = new Parsed(filtered, warnings, parsed.answered());
        }

        Set<String> seen = new LinkedHashSet<>();
        List<Choice> entities = new ArrayList<>(parsed.entries().size());
        for (SurveyDataEntry entry : parsed.entries()) {
            if (seen.add(entry.kind() + "|" + entry.from() + "|" + entry.to())) {
                Choice choice = new Choice(surveyId, entry.from(), entry.to(), entry.kind());
                choice.setRank(entry.rank());
                choice.setWeight(entry.weight());
                entities.add(choice);
            }
        }
        choices.saveAll(entities);
        for (String respondent : parsed.answered()) {
            surveyService.markResponded(surveyId, respondent);
        }
        return new ImportResponse(entities.size(), warnings.size(), warnings, participants.size());
    }

    /** Строит шаблон CSV под конкретный опрос. */
    @Transactional(readOnly = true)
    public String buildTemplate(Long surveyId) {
        Survey survey = surveyService.requireSurvey(surveyId);
        List<Participant> participants = surveyService.participantsOf(survey);
        StringBuilder csv = new StringBuilder();
        csv.append("From,To,Type,Rank\n");
        csv.append("# По строке один выбор. Type = SYMPATHY (симпатия) или ANTIPATHY (антипатия).\n");
        csv.append("# Rank — место в предпочтении, 1 = самый желательный. Для антипатий -1 = ненавижу больше всех.\n");
        csv.append("# Строка с заполненным From и пустым To означает «отвечал, но никого не выбрал».\n");
        csv.append("# Участники этого опроса: ");
        participants.forEach(participant -> csv.append(participant.code()).append(' '));
        csv.append('\n');
        // строк-примеров нет: шаблон должен открываться пустым, иначе получатель
        // заведёт фиктивные выборы, которые потом попадут в расчёт
        return csv.toString();
    }

    // ------------------------------------------------------------------

    private List<String[]> read(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        try (InputStream input = file.getInputStream()) {
            if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
                return readSheet(input);
            }
            return readCsv(input);
        } catch (IOException e) {
            throw new BadRequestException("Не удалось прочитать файл: " + e.getMessage());
        }
    }

    private List<String[]> readCsv(InputStream input) {
        String text;
        try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            StringBuilder builder = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) > 0) {
                builder.append(buffer, 0, read);
            }
            text = builder.toString();
        } catch (IOException e) {
            throw new BadRequestException("Файл не похож на CSV: " + e.getMessage());
        }
        // снимаем возможный BOM, иначе первый заголовок не распознаётся
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        // Excel в русской локали сохраняет CSV через «;» — разделитель определяем сами
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setDelimiter(detectDelimiter(text))
                .setIgnoreSurroundingSpaces(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build();
        List<String[]> rows = new ArrayList<>();
        try (CSVParser parser = CSVParser.parse(text, format)) {
            for (CSVRecord record : parser) {
                String[] row = new String[record.size()];
                for (int c = 0; c < row.length; c++) {
                    row[c] = record.get(c).trim();
                }
                if (!isBlankRow(row)) {
                    rows.add(row);
                }
            }
        } catch (IOException e) {
            throw new BadRequestException("Файл не похож на CSV: " + e.getMessage());
        }
        return rows;
    }

    /** Разделитель — тот, что чаще встречается в первой непустой строке. */
    private static char detectDelimiter(String text) {
        int best = 0;
        int bestCount = 0;
        for (String line : text.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            for (char candidate : new char[]{',', ';', '\t', '|'}) {
                int count = 0;
                for (int i = 0; i < line.length(); i++) {
                    if (line.charAt(i) == candidate) {
                        count++;
                    }
                }
                if (count > bestCount) {
                    bestCount = count;
                    best = candidate;
                }
            }
            break;
        }
        return best == 0 ? ',' : (char) best;
    }

    private List<String[]> readSheet(InputStream input) {
        List<String[]> rows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                int last = row.getLastCellNum();
                String[] values = new String[Math.max(0, last)];
                for (int c = 0; c < last; c++) {
                    Cell cell = row.getCell(c);
                    values[c] = cell == null ? "" : formatter.formatCellValue(cell).trim();
                }
                if (!isBlankRow(values)) {
                    rows.add(values);
                }
            }
        } catch (IOException e) {
            throw new BadRequestException("Файл не похож на таблицу Excel: " + e.getMessage());
        } catch (RuntimeException e) {
            throw new BadRequestException("Не удалось прочитать Excel: " + e.getMessage());
        }
        return rows;
    }

    private static boolean isBlankRow(String[] row) {
        for (String value : row) {
            if (value != null && !value.isBlank() && !value.startsWith("#")) {
                return false;
            }
        }
        return true;
    }

    /** Похоже на длинный формат: есть колонка type/тип и respondent/кто. */
    private static boolean looksLong(String[] header) {
        boolean type = false;
        boolean from = false;
        boolean to = false;
        for (String value : header) {
            String lower = value.toLowerCase(Locale.ROOT);
            if (lower.equals("type") || lower.equals("тип") || lower.equals("вид")) {
                type = true;
            }
            if (lower.equals("from") || lower.equals("respondent") || lower.equals("кто")
                    || lower.equals("выбирает") || lower.equals("отвечает")) {
                from = true;
            }
            if (lower.equals("to") || lower.equals("target") || lower.equals("кого")
                    || lower.equals("выбран")) {
                to = true;
            }
        }
        return type && from && to;
    }

    private Parsed parseLong(List<String[]> rows, List<Participant> participants) {
        List<SurveyDataEntry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> codes = codes(participants);
        String[] header = rows.get(0);
        int typeColumn = indexOf(header, "type", "тип", "вид");
        int fromColumn = indexOf(header, "from", "respondent", "кто", "выбирает", "отвечает");
        int toColumn = indexOf(header, "to", "target", "кого", "выбран");
        int rankColumn = indexOf(header, "rank", "ранг", "место", "номер");

        Set<String> answered = new LinkedHashSet<>();
        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            String from = value(row, fromColumn);
            if (from.isBlank()) {
                continue;
            }
            if (!codes.contains(from)) {
                warnings.add("Строка " + (i + 1) + ": участник " + from + " в опросе не найден — пропущено");
                continue;
            }
            String to = value(row, toColumn);
            if (to.isBlank()) {
                // строка без адресата — это «отвечал, но никого не выбрал».
                // Без неё такой ответ неотличим от не ответившего.
                answered.add(from);
                continue;
            }
            String kindText = value(row, typeColumn).toUpperCase(Locale.ROOT);
            ChoiceKind kind = kindText.startsWith("ANTI") || kindText.startsWith("АНТИ")
                    ? ChoiceKind.ANTIPATHY : ChoiceKind.SYMPATHY;
            answered.add(from);
            if (!codes.contains(to)) {
                warnings.add("Строка " + (i + 1) + ": участник не найден (" + from + " -> " + to + ") — пропущено");
                continue;
            }
            if (from.equals(to)) {
                warnings.add("Строка " + (i + 1) + ": выбор самого себя (" + from + ") — пропущено");
                continue;
            }
            Integer rank = parseRank(value(row, rankColumn), warnings, i + 1);
            entries.add(new SurveyDataEntry(kind, from, to, rank, 1));
        }
        return new Parsed(entries, warnings, answered);
    }

    private Parsed parseWide(List<String[]> rows, List<Participant> participants) {
        List<SurveyDataEntry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> answered = new LinkedHashSet<>();
        Set<String> codes = codes(participants);
        String[] header = rows.get(0);
        int kindColumn = indexOf(header, "type", "тип", "вид");
        boolean hasKindColumn = kindColumn >= 0;

        // в широком формате первая колонка всегда отвечает, кто выбирает,
        // а её заголовок может быть пустым или подписью («код», «кто выбирает»)
        final int fromColumn = 0;
        List<String> targets = new ArrayList<>();
        for (int c = fromColumn + 1; c < header.length; c++) {
            String value = header[c];
            if (value.isBlank()) {
                continue;
            }
            if (!codes.contains(value)) {
                warnings.add("В шапке «" + value + "» — такого участника в опросе нет, столбец пропущен");
                continue;
            }
            targets.add(value);
        }
        if (targets.isEmpty()) {
            throw new BadRequestException("В шапке не найдено ни одного кода участника из опроса. "
                    + "Ожидаются коды: " + String.join(", ", codes));
        }

        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i);
            String from = value(row, fromColumn);
            if (from.isBlank()) {
                continue;
            }
            if (!codes.contains(from)) {
                warnings.add("Строка " + (i + 1) + ": «" + from + "» не участник опроса — строка пропущена");
                continue;
            }
            ChoiceKind kind = hasKindColumn && value(row, kindColumn).toUpperCase(Locale.ROOT).startsWith("ANTI")
                    ? ChoiceKind.ANTIPATHY : ChoiceKind.SYMPATHY;
            for (int c = fromColumn + 1; c < row.length; c++) {
                String cell = row[c];
                if (cell.isBlank() || cell.equals("0") || cell.equalsIgnoreCase("false")) {
                    continue;
                }
                String to = header[c];
                if (!targets.contains(to)) {
                    continue;
                }
                if (from.equals(to)) {
                    continue;
                }
                Integer parsed = parseInt(cell);
                if (parsed == null) {
                    continue;
                }
                if (parsed == 1) {
                    entries.add(new SurveyDataEntry(kind, from, to, null, 1));
                } else if (parsed > 1) {
                    // значение больше 1 трактуем как место в предпочтении
                    entries.add(new SurveyDataEntry(kind, from, to, parsed, 1));
                } else if (parsed < 0 && parsed >= -3) {
                    // -1..-3 — ранги антипатий в обратном порядке
                    entries.add(new SurveyDataEntry(kind, from, to, parsed, 1));
                }
            }
            // строка респондента есть, даже если он ничего не выбрал:
            // пустая строка в широком формате и есть ответ «никого»
            answered.add(from);
        }
        return new Parsed(entries, warnings, answered);
    }

    private static Set<String> codes(List<Participant> participants) {
        Set<String> codes = new LinkedHashSet<>();
        participants.forEach(participant -> codes.add(participant.code()));
        return codes;
    }

    private static int indexOf(String[] header, String... names) {
        for (int i = 0; i < header.length; i++) {
            String lower = header[i].toLowerCase(Locale.ROOT);
            for (String name : names) {
                if (lower.equals(name)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String value(String[] row, int index) {
        if (index < 0 || index >= row.length || row[index] == null) {
            return "";
        }
        return row[index].trim();
    }

    /**
     * Разбирает ранг. Отрицательные значения (-1, -2, -3) — это ранги антипатий
     * в обратном порядке: -1 значит «ненавижу больше всех», -3 — последний.
     */
    private static Integer parseRank(String value, List<String> warnings, int line) {
        Integer rank = parseInt(value);
        if (rank != null && rank < 0) {
            if (rank < -3) {
                warnings.add("Строка " + line + ": ранг " + rank + " вне диапазона -1..-3 — пропущен");
                return null;
            }
            return rank;
        }
        return rank;
    }

    private static Integer parseInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record SurveyDataEntry(ChoiceKind kind, String from, String to, Integer rank, int weight) {
    }

    private record Parsed(List<SurveyDataEntry> entries, List<String> warnings,
                          Set<String> answered) {

        private static Parsed of(List<SurveyDataEntry> entries, List<String> warnings) {
            return new Parsed(entries, warnings, Set.of());
        }
    }
}
