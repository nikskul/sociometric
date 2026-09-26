package ru.sociometric.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sociometric.dto.ResultResponse;
import ru.sociometric.web.BadRequestException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Выгрузка результатов в CSV и XLSX: социометрическая матрица, таблица
 * статусов, индексы группы и сопоставление с экспертными оценками.
 */
@Service
public class ExportService {

    private final SurveyService surveyService;
    private final ResultService resultService;

    public ExportService(SurveyService surveyService, ResultService resultService) {
        this.surveyService = surveyService;
        this.resultService = resultService;
    }

    @Transactional(readOnly = true)
    public byte[] toCsv(Long surveyId) {
        ResultResponse result = resultService.calculate(surveyId);
        StringBuilder csv = new StringBuilder("\uFEFF");
        String name = "Социометрия: " + result.name() + "\n\n";

        csv.append(name).append("Матрица симпатий (строки — кто выбирает, столбцы — кого выбирают)\n");
        appendMatrix(csv, result.participants(), result.matrix());
        csv.append('\n');

        if (hasAntipathies(result)) {
            csv.append("Матрица антипатий\n");
            appendMatrix(csv, result.participants(), result.antiMatrix());
            csv.append('\n');
        }

        csv.append("Социометрические статусы\n");
        csv.append("№;Код;Участник;Получил выборов;Выбрал сам;Взвешенный статус;Доля;Категория;"
                + "Получил антипатий;Категория по антипатиям;Взаимных пар;Степень\n");
        int row = 1;
        for (ResultResponse.ParticipantResult participant : result.participants()) {
            csv.append(row++).append(';')
                    .append(cell(participant.code())).append(';')
                    .append(cell(participant.name())).append(';')
                    .append(participant.received()).append(';')
                    .append(participant.given()).append(';')
                    .append(participant.weightedReceived()).append(';')
                    .append(participant.normalizedReceived()).append(';')
                    .append(participant.categoryTitle()).append(';')
                    .append(participant.rejectedBy()).append(';')
                    .append(participant.antipathyCategoryTitle()).append(';')
                    .append(participant.mutualCount()).append(';')
                    .append(participant.degree()).append('\n');
        }
        csv.append('\n');

        csv.append("Индексы группы\n");
        csv.append("Показатель;Значение\n");
        appendIndices(csv, result.indices());

        csv.append('\n').append("Метрики графа предпочтений\n");
        csv.append("Средняя длина пути;Диаметр;Радиус;Связных пар;Всего пар\n");
        csv.append(result.graph().meanDistance()).append(';')
                .append(result.graph().diameter()).append(';')
                .append(result.graph().radius()).append(';')
                .append(result.graph().connectedPairs()).append(';')
                .append(result.graph().reachablePairs()).append('\n');

        csv.append('\n').append("Средний статус (μ);Стандартное отклонение (σ)\n");
        csv.append(result.meanStatus()).append(';').append(result.sdStatus()).append('\n');

        if (!result.stars().isEmpty()) {
            csv.append('\n').append("Звёзды: ").append(String.join(", ", result.stars())).append('\n');
        }
        if (!result.isolates().isEmpty()) {
            csv.append("Изолированные и аутсайдеры: ").append(String.join(", ", result.isolates())).append('\n');
        }
        if (result.strongest() != null && !result.strongest().isEmpty()) {
            csv.append("Тесная микрогруппа: ").append(String.join(", ", result.strongest())).append('\n');
        }

        if (result.experts() != null) {
            csv.append('\n').append("Экспертные оценки популярности\n");
            csv.append("Код;Участник;Средний балл;Разброс;Мин;Макс;Оценок;Социометрический статус;"
                    + "Место по экспертам;Место по социометрии;Разница\n");
            for (ResultResponse.ParticipantScore score : result.experts().participants()) {
                ResultResponse.RankingRow row2 = result.experts().ranking().stream()
                        .filter(r -> r.code().equals(score.code())).findFirst().orElse(null);
                csv.append(cell(score.code())).append(';')
                        .append(cell(score.name())).append(';')
                        .append(score.mean()).append(';')
                        .append(score.sd()).append(';')
                        .append(score.min()).append(';')
                        .append(score.max()).append(';')
                        .append(score.votes()).append(';')
                        .append(score.statusReceived()).append(';')
                        .append(row2 == null ? "" : row2.expertRank()).append(';')
                        .append(row2 == null ? "" : row2.sociometricRank()).append(';')
                        .append(row2 == null ? "" : row2.difference()).append('\n');
            }
            csv.append('\n').append("Kendall W (согласованность экспертов);Пирсон;Спирмен\n");
            csv.append(result.experts().kendallW()).append(';')
                    .append(result.experts().pearsonWithStatus()).append(';')
                    .append(result.experts().spearmanWithStatus()).append('\n');
        }

        for (ResultResponse.GroupDto group : result.groups()) {
            csv.append('\n').append("Индексы подгруппы «").append(group.name()).append("»\n");
            appendIndices(csv, group.indices());
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public byte[] toXlsx(Long surveyId) {
        ResultResponse result = resultService.calculate(surveyId);
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            writeStatusSheet(workbook, result);
            writeMatrixSheet(workbook, result);
            writeIndicesSheet(workbook, result);
            if (result.experts() != null) {
                writeExpertsSheet(workbook, result);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new BadRequestException("Не удалось сформировать Excel: " + e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public String fileName(Long surveyId, String extension) {
        String name = surveyService.requireSurvey(surveyId).getName();
        String safe = name.replaceAll("[^a-zA-Zа-яА-Я0-9]+", "_").replaceAll("^_|_$", "");
        if (safe.isBlank()) {
            safe = "sociometric_" + surveyId;
        }
        return safe + "." + extension;
    }

    // ------------------------------------------------------------------

    private void writeStatusSheet(Workbook workbook, ResultResponse result) {
        Sheet sheet = workbook.createSheet("Статусы");
        CellStyle header = headerStyle(workbook);
        CellStyle wrapped = wrapStyle(workbook);

        Row title = sheet.createRow(0);
        title.createCell(0).setCellValue("Социометрия по Морено: " + result.name());
        title.getCell(0).setCellStyle(wrapped);

        String[] columns = {"№", "Код", "Участник", "Получил выборов", "Выбрал сам", "Взвешенный статус",
                "Доля полученных", "Категория", "Получил антипатий", "Категория по антипатиям",
                "Взаимных пар", "Степень", "Отвечал"};
        Row headerRow = sheet.createRow(2);
        for (int c = 0; c < columns.length; c++) {
            headerRow.createCell(c).setCellValue(columns[c]);
            headerRow.getCell(c).setCellStyle(header);
        }

        int rowIndex = 3;
        for (ResultResponse.ParticipantResult participant : result.participants()) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(participant.index() + 1);
            row.createCell(1).setCellValue(participant.code());
            row.createCell(2).setCellValue(participant.name());
            row.createCell(3).setCellValue(participant.received());
            row.createCell(4).setCellValue(participant.given());
            row.createCell(5).setCellValue(participant.weightedReceived());
            row.createCell(6).setCellValue(participant.normalizedReceived());
            row.createCell(7).setCellValue(participant.categoryTitle());
            row.createCell(8).setCellValue(participant.rejectedBy());
            row.createCell(9).setCellValue(participant.antipathyCategoryTitle());
            row.createCell(10).setCellValue(participant.mutualCount());
            row.createCell(11).setCellValue(participant.degree());
            row.createCell(12).setCellValue(participant.responded() ? "да" : "нет");
            row.getCell(7).setCellStyle(categoryStyle(workbook, participant.category()));
        }

        for (int c = 0; c < columns.length; c++) {
            sheet.setColumnWidth(c, 18 * 256);
        }
        sheet.createFreezePane(3, 3);
    }

    private void writeMatrixSheet(Workbook workbook, ResultResponse result) {
        Sheet sheet = workbook.createSheet("Матрица");
        CellStyle header = headerStyle(workbook);
        Row headerRow = sheet.createRow(0);
        headerRow.createCell(0).setCellValue("Выбирает \\ Выбирают");
        headerRow.getCell(0).setCellStyle(header);
        for (int j = 0; j < result.participants().size(); j++) {
            Cell cell = headerRow.createCell(j + 1);
            cell.setCellValue(result.participants().get(j).code());
            cell.setCellStyle(header);
        }
        for (int i = 0; i < result.participants().size(); i++) {
            Row row = sheet.createRow(i + 1);
            row.createCell(0).setCellValue(result.participants().get(i).code() + " "
                    + result.participants().get(i).name());
            row.getCell(0).setCellStyle(header);
            for (int j = 0; j < result.participants().size(); j++) {
                row.createCell(j + 1).setCellValue(result.matrix()[i][j]);
            }
        }
        sheet.setColumnWidth(0, 26 * 256);
        for (int c = 1; c <= result.participants().size(); c++) {
            sheet.setColumnWidth(c, 7 * 256);
        }
    }

    private void writeIndicesSheet(Workbook workbook, ResultResponse result) {
        Sheet sheet = workbook.createSheet("Индексы");
        CellStyle header = headerStyle(workbook);
        Row headerRow = sheet.createRow(0);
        headerRow.createCell(0).setCellValue("Показатель");
        headerRow.createCell(1).setCellValue("Значение");
        headerRow.getCell(0).setCellStyle(header);
        headerRow.getCell(1).setCellStyle(header);

        int rowIndex = 1;
        rowIndex = appendIndicesRows(sheet, result.indices(), rowIndex);
        sheet.createRow(rowIndex++).createCell(0).setCellValue("Средняя длина пути");
        sheet.getRow(rowIndex - 1).createCell(1).setCellValue(result.graph().meanDistance());
        sheet.createRow(rowIndex++).createCell(0).setCellValue("Диаметр");
        sheet.getRow(rowIndex - 1).createCell(1).setCellValue(result.graph().diameter());
        sheet.createRow(rowIndex++).createCell(0).setCellValue("Радиус");
        sheet.getRow(rowIndex - 1).createCell(1).setCellValue(result.graph().radius());
        sheet.createRow(rowIndex++).createCell(0).setCellValue("Средний статус (μ)");
        sheet.getRow(rowIndex - 1).createCell(1).setCellValue(result.meanStatus());
        sheet.createRow(rowIndex++).createCell(0).setCellValue("Стандартное отклонение (σ)");
        sheet.getRow(rowIndex - 1).createCell(1).setCellValue(result.sdStatus());

        for (ResultResponse.GroupDto group : result.groups()) {
            sheet.createRow(rowIndex++).createCell(0)
                    .setCellValue("Индексы подгруппы «" + group.name() + "» (n = " + group.n() + ")");
            rowIndex = appendIndicesRows(sheet, group.indices(), rowIndex);
        }
        sheet.setColumnWidth(0, 44 * 256);
        sheet.setColumnWidth(1, 16 * 256);
    }

    private int appendIndicesRows(Sheet sheet, ResultResponse.IndicesDto indices, int rowIndex) {
        Object[][] rows = {
                {"Участников (n)", indices.n()},
                {"Всего выборов", indices.totalSelections()},
                {"Среднее число выборов на участника", indices.meanSelectionsPerPerson()},
                {"Взаимных пар", indices.mutualPairsCount()},
                {"Односторонних пар", indices.unidirectionalPairsCount()},
                {"Коэффициент групповой сплоченности (GSC)", indices.gsc()},
                {"Индекс взаимности", indices.reciprocityIndex()},
                {"Плотность графа предпочтений", indices.density()},
                {"Индекс изоляции", indices.isolationIndex()},
                {"Индекс отвержения (получили антипатию)", indices.rejectedIndex()},
                {"Доля негативных статусов", indices.negativeStatusIndex()},
                {"Возможных пар n(n-1)/2", indices.possiblePairsCount()},
                {"Возможных выборов n(n-1)", indices.possibleSelectionsCount()},
        };
        for (Object[] values : rows) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(String.valueOf(values[0]));
            Cell cell = row.createCell(1);
            if (values[1] instanceof Integer number) {
                cell.setCellValue(number);
            } else {
                cell.setCellValue(((Number) values[1]).doubleValue());
            }
        }
        return rowIndex;
    }

    private void writeExpertsSheet(Workbook workbook, ResultResponse result) {
        Sheet sheet = workbook.createSheet("Эксперты");
        CellStyle header = headerStyle(workbook);
        String[] columns = {"Код", "Участник", "Средний балл", "Разброс", "Мин", "Макс", "Оценок",
                "Социометрический статус", "Место по экспертам", "Место по социометрии", "Разница"};
        Row headerRow = sheet.createRow(0);
        for (int c = 0; c < columns.length; c++) {
            headerRow.createCell(c).setCellValue(columns[c]);
            headerRow.getCell(c).setCellStyle(header);
        }
        int rowIndex = 1;
        for (ResultResponse.ParticipantScore score : result.experts().participants()) {
            ResultResponse.RankingRow ranking = result.experts().ranking().stream()
                    .filter(r -> r.code().equals(score.code())).findFirst().orElse(null);
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(score.code());
            row.createCell(1).setCellValue(score.name());
            row.createCell(2).setCellValue(score.mean());
            row.createCell(3).setCellValue(score.sd());
            row.createCell(4).setCellValue(score.min());
            row.createCell(5).setCellValue(score.max());
            row.createCell(6).setCellValue(score.votes());
            row.createCell(7).setCellValue(score.statusReceived());
            if (ranking != null) {
                row.createCell(8).setCellValue(ranking.expertRank());
                row.createCell(9).setCellValue(ranking.sociometricRank());
                row.createCell(10).setCellValue(ranking.difference());
            }
        }
        Row summary = sheet.createRow(rowIndex + 1);
        summary.createCell(0).setCellValue("Kendall W");
        summary.createCell(1).setCellValue(result.experts().kendallW());
        summary.createCell(2).setCellValue("Пирсон");
        summary.createCell(3).setCellValue(result.experts().pearsonWithStatus());
        summary.createCell(4).setCellValue("Спирмен");
        summary.createCell(5).setCellValue(result.experts().spearmanWithStatus());
        for (int c = 0; c < columns.length; c++) {
            sheet.setColumnWidth(c, 20 * 256);
        }
    }

    private static void appendMatrix(StringBuilder csv, List<ResultResponse.ParticipantResult> participants,
                                     int[][] matrix) {
        csv.append("Код\\Код");
        for (ResultResponse.ParticipantResult participant : participants) {
            csv.append(';').append(cell(participant.code()));
        }
        csv.append(";Всего\n");
        for (int i = 0; i < participants.size(); i++) {
            csv.append(cell(participants.get(i).code()));
            int rowSum = 0;
            for (int j = 0; j < participants.size(); j++) {
                csv.append(';').append(matrix[i][j]);
                rowSum += matrix[i][j] > 0 ? 1 : 0;
            }
            csv.append(';').append(rowSum).append('\n');
        }
    }

    private static void appendIndices(StringBuilder csv, ResultResponse.IndicesDto indices) {
        csv.append("Участников (n)").append(';').append(indices.n()).append('\n');
        csv.append("Всего выборов").append(';').append(indices.totalSelections()).append('\n');
        csv.append("Среднее число выборов на участника").append(';')
                .append(indices.meanSelectionsPerPerson()).append('\n');
        csv.append("Взаимных пар").append(';').append(indices.mutualPairsCount()).append('\n');
        csv.append("Односторонних пар").append(';').append(indices.unidirectionalPairsCount()).append('\n');
        csv.append("Коэффициент групповой сплоченности (GSC)").append(';').append(indices.gsc()).append('\n');
        csv.append("Индекс взаимности").append(';').append(indices.reciprocityIndex()).append('\n');
        csv.append("Плотность графа предпочтений").append(';').append(indices.density()).append('\n');
        csv.append("Индекс изоляции").append(';').append(indices.isolationIndex()).append('\n');
        csv.append("Индекс отвержения (получили антипатию)").append(';').append(indices.rejectedIndex()).append('\n');
        csv.append("Доля негативных статусов").append(';').append(indices.negativeStatusIndex()).append('\n');
    }

    private static boolean hasAntipathies(ResultResponse result) {
        for (int[] row : result.antiMatrix()) {
            for (int value : row) {
                if (value > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String cell(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace(';', ',').replace('\n', ' ').trim();
        return "\"" + cleaned + "\"";
    }

    private static CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setWrapText(true);
        return style;
    }

    private static CellStyle wrapStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 13);
        style.setFont(font);
        return style;
    }

    private static CellStyle categoryStyle(Workbook workbook, String category) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(switch (category) {
            case "STAR" -> IndexedColors.BRIGHT_GREEN.getIndex();
            case "PREFERRED" -> IndexedColors.LIGHT_GREEN.getIndex();
            case "ACCEPTED" -> IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex();
            case "ISOLATED" -> IndexedColors.LIGHT_ORANGE.getIndex();
            case "REJECTED" -> IndexedColors.ROSE.getIndex();
            case "OUTSIDER" -> IndexedColors.GREY_50_PERCENT.getIndex();
            default -> IndexedColors.WHITE.getIndex();
        });
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }
}
