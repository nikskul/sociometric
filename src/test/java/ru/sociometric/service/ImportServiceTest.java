package ru.sociometric.service;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import ru.sociometric.domain.ChoiceKind;
import ru.sociometric.dto.ImportResponse;
import ru.sociometric.dto.ParticipantDto;
import ru.sociometric.dto.SurveyRequest;
import ru.sociometric.domain.Choice;
import ru.sociometric.repository.ChoiceRepository;
import ru.sociometric.web.BadRequestException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Импорт выборов из CSV и XLSX в обоих поддерживаемых форматах.
 */
@SpringBootTest
@Transactional
class ImportServiceTest {

    @Autowired
    private SurveyService surveys;
    @Autowired
    private ImportService imports;
    @Autowired
    private ChoiceRepository choices;
    @Autowired
    private ru.sociometric.repository.ResponseMarkRepository responses;

    private Long surveyId;

    @BeforeEach
    void setUp() {
        SurveyRequest request = new SurveyRequest("Импорт", "", 3, true, false, true, null,
                List.of(new ParticipantDto("1", "Анна"), new ParticipantDto("2", "Борис"),
                        new ParticipantDto("3", "Вера")));
        surveyId = surveys.create(request).id();
    }

    private long count(ChoiceKind kind) {
        return choices.findBySurveyIdAndKindOrderById(surveyId, kind).size();
    }

    private MockMultipartFile csv(String name, String body) {
        return new MockMultipartFile("file", name, "text/csv", body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("CSV в длинном формате: распознаются колонки type/respondent/target/rank")
    void longFormatCsv() {
        ImportResponse response = imports.importChoices(surveyId, csv("choices.csv", """
                type,respondent,target,rank
                SYMPATHY,1,2,1
                SYMPATHY,1,3,2
                ANTIPATHY,2,3,1
                """), false);

        assertThat(response.imported()).isEqualTo(3);
        assertThat(count(ChoiceKind.SYMPATHY)).isEqualTo(2);
        assertThat(count(ChoiceKind.ANTIPATHY)).isEqualTo(1);
    }

    @Test
    @DisplayName("Канонические колонки From/To/Type/Rank и отрицательные ранги антипатий")
    void canonicalHeadersAndNegativeRanks() {
        ImportResponse response = imports.importChoices(surveyId, csv("canonical.csv", """
                From,To,Type,Rank
                1,2,SYMPATHY,1
                2,3,ANTIPATHY,-1
                """), false);

        assertThat(response.imported()).isEqualTo(2);
        Choice antipathy = choices.findBySurveyIdAndKindOrderById(surveyId, ChoiceKind.ANTIPATHY).get(0);
        assertThat(antipathy.getRank()).isEqualTo(-1);
        assertThat(antipathy.getRespondent()).isEqualTo("2");
    }

    @Test
    @DisplayName("Строка с From и пустым To — это ответ «никого не выбрал», а не отсутствие ответа")
    void blankTargetMarksAnsweredWithoutChoices() {
        imports.importChoices(surveyId, csv("blank.csv", """
                From,To,Type,Rank
                1,2,SYMPATHY,1
                3,,,,
                """), false);

        assertThat(responses.findBySurveyIdAndRespondent(surveyId, "3"))
                .as("участник 3 отвечал, хотя никого не выбрал")
                .hasSize(1);
    }

    @Test
    @DisplayName("Шаблон открывается пустым: заголовок From,To,Type,Rank и комментарии, без строк-примеров")
    void templateIsEmptyAndCanonical() {
        String template = imports.buildTemplate(surveyId);

        assertThat(template).startsWith("From,To,Type,Rank");
        assertThat(template).contains("# По строке один выбор");
        assertThat(template).doesNotContain("SYMPATHY,1");
    }

    @Test
    @DisplayName("CSV в широком формате: шапка — коды участников")
    void wideFormatCsv() {
        ImportResponse response = imports.importChoices(surveyId, csv("matrix.csv", """
                ;1;2;3
                1;0;1;1
                2;1;0;0
                3;1;1;0
                """), false);

        assertThat(response.imported()).isEqualTo(5);
        assertThat(count(ChoiceKind.SYMPATHY)).isEqualTo(5);
    }

    @Test
    @DisplayName("CSV с русскими заголовками и BOM")
    void russianHeadersWithBom() {
        ImportResponse response = imports.importChoices(surveyId,
                csv("choices.csv", "﻿тип;кто;кого;ранг\nсимпатия;1;2;1\nсимпатия;2;3;1\n"), false);

        assertThat(response.imported()).isEqualTo(2);
    }

    @Test
    @DisplayName("CSV с табуляцией и запятой как разделителем разбирается одинаково")
    void tabAndCommaDelimiters() {
        ImportResponse commas = imports.importChoices(surveyId, csv("a.csv",
                "type,respondent,target\nSYMPATHY,1,2\n"), false);
        assertThat(commas.imported()).isEqualTo(1);

        ImportResponse tabs = imports.importChoices(surveyId, csv("b.csv",
                "type\trespondent\ttarget\nSYMPATHY\t2\t3\n"), false);
        assertThat(tabs.imported()).isEqualTo(1);
    }

    @Test
    @DisplayName("Строки с неизвестными кодами пропускаются с предупреждением")
    void unknownCodesAreSkipped() {
        ImportResponse response = imports.importChoices(surveyId, csv("choices.csv", """
                type,respondent,target
                SYMPATHY,1,2
                SYMPATHY,1,99
                """), false);

        assertThat(response.imported()).isEqualTo(1);
        assertThat(response.skippedRows()).isEqualTo(1);
        assertThat(response.warnings()).anyMatch(w -> w.contains("99"));
    }

    @Test
    @DisplayName("XLSX в длинном формате")
    void longFormatXlsx() throws IOException {
        ImportResponse response = imports.importChoices(surveyId, xlsx(new String[][]{
                {"type", "respondent", "target", "rank"},
                {"SYMPATHY", "1", "2", "1"},
                {"SYMPATHY", "1", "3", "2"}
        }), false);

        assertThat(response.imported()).isEqualTo(2);
    }

    @Test
    @DisplayName("XLSX в широком формате")
    void wideFormatXlsx() throws IOException {
        ImportResponse response = imports.importChoices(surveyId, xlsx(new String[][]{
                {"", "1", "2", "3"},
                {"1", "0", "1", "1"},
                {"2", "1", "0", "1"},
                {"3", "0", "1", "0"}
        }), false);

        assertThat(response.imported()).isEqualTo(5);
    }

    @Test
    @DisplayName("replace очищает прежние данные")
    void replaceClearsPreviousChoices() {
        imports.importChoices(surveyId, csv("a.csv", "type,respondent,target\nSYMPATHY,1,2\n"), true);
        assertThat(count(ChoiceKind.SYMPATHY)).isEqualTo(1);

        ImportResponse response = imports.importChoices(surveyId,
                csv("b.csv", "type,respondent,target\nSYMPATHY,2,3\n"), true);
        assertThat(response.imported()).isEqualTo(1);
        assertThat(count(ChoiceKind.SYMPATHY)).isEqualTo(1);
    }

    @Test
    @DisplayName("Только комментарии — понятная ошибка вместо молчаливого успеха")
    void commentsOnly() {
        BadRequestException failure = org.junit.jupiter.api.Assertions.assertThrows(
                BadRequestException.class,
                () -> imports.importChoices(surveyId, csv("empty.csv", "# только комментарий\n"), false));
        assertThat(failure.getMessage()).contains("пуст");
    }

    private MockMultipartFile xlsx(String[][] data) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("choices");
            for (int r = 0; r < data.length; r++) {
                var row = sheet.createRow(r);
                for (int c = 0; c < data[r].length; c++) {
                    row.createCell(c).setCellValue(data[r][c]);
                }
            }
            workbook.write(out);
            return new MockMultipartFile("file", "matrix.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }
}
