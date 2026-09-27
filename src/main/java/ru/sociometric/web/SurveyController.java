package ru.sociometric.web;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.sociometric.dto.ChoicesRequest;
import ru.sociometric.dto.ExpertsRequest;
import ru.sociometric.dto.ImportResponse;
import ru.sociometric.dto.ParticipantDto;
import ru.sociometric.dto.SurveyRequest;
import ru.sociometric.dto.SurveyResponse;
import ru.sociometric.service.ExportService;
import ru.sociometric.service.ImportService;
import ru.sociometric.service.ResultService;
import ru.sociometric.service.SurveyService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Работа с опросами: участники, выборы, экспертные оценки, импорт, выгрузка.
 */
@RestController
@RequestMapping("/api/surveys")
public class SurveyController {

    private final SurveyService surveys;
    private final ResultService results;
    private final ImportService imports;
    private final ExportService exports;

    public SurveyController(SurveyService surveys, ResultService results,
                            ImportService imports, ExportService exports) {
        this.surveys = surveys;
        this.results = results;
        this.imports = imports;
        this.exports = exports;
    }

    @GetMapping
    public List<SurveyResponse> list() {
        return surveys.list();
    }

    @GetMapping("/{id}")
    public SurveyResponse get(@PathVariable Long id) {
        return surveys.get(id);
    }

    @PostMapping
    public ResponseEntity<SurveyResponse> create(@Valid @RequestBody SurveyRequest request) {
        return ResponseEntity.ok(surveys.create(request));
    }

    @PutMapping("/{id}")
    public SurveyResponse update(@PathVariable Long id, @Valid @RequestBody SurveyRequest request) {
        return surveys.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        surveys.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/participants")
    public SurveyResponse participants(@PathVariable Long id, @Valid @RequestBody List<ParticipantDto> participants) {
        return surveys.replaceParticipants(id, participants);
    }

    @GetMapping("/{id}/choices")
    public List<SurveyService.ChoiceView> choices(@PathVariable Long id) {
        return surveys.listChoices(id);
    }

    @PostMapping("/{id}/choices")
    public SurveyResponse saveChoices(@PathVariable Long id, @Valid @RequestBody ChoicesRequest request) {
        return surveys.saveChoices(id, request);
    }

    @DeleteMapping("/{id}/choices")
    public SurveyResponse clearChoices(@PathVariable Long id, @RequestParam(required = false) String kind) {
        return surveys.clearChoices(id, kind);
    }

    @GetMapping("/{id}/experts")
    public List<SurveyService.ExpertRatingView> experts(@PathVariable Long id) {
        return surveys.listExperts(id);
    }

    @PostMapping("/{id}/experts")
    public SurveyResponse saveExperts(@PathVariable Long id, @Valid @RequestBody ExpertsRequest request) {
        return surveys.saveExperts(id, request);
    }

    @DeleteMapping("/{id}/experts")
    public void clearExperts(@PathVariable Long id) {
        surveys.clearExperts(id);
    }

    @PostMapping(value = "/{id}/import", consumes = "multipart/form-data")
    public ImportResponse importChoices(@PathVariable Long id,
                                         @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                         @RequestParam(defaultValue = "false") boolean replace) {
        return imports.importChoices(id, file, replace);
    }

    @GetMapping(value = "/{id}/template.csv", produces = "text/csv")
    public ResponseEntity<byte[]> template(@PathVariable Long id) {
        byte[] body = imports.buildTemplate(id).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header("Content-Disposition", contentDisposition("template.csv"))
                .body(body);
    }

    @GetMapping(value = "/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable Long id,
                                         @RequestParam(defaultValue = "csv") String format) {
        boolean excel = "xlsx".equalsIgnoreCase(format) || "excel".equalsIgnoreCase(format);
        byte[] body = excel ? exports.toXlsx(id) : exports.toCsv(id);
        String name = exports.fileName(id, excel ? "xlsx" : "csv");
        String contentType = excel
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                : "text/csv; charset=UTF-8";
        return ResponseEntity.ok()
                .header("Content-Type", contentType)
                .header("Content-Disposition", contentDisposition(name))
                .body(body);
    }

    /**
     * Заголовок Content-Disposition с именем файла.
     *
     * <p>Имя опроса — произвольный русский текст, а HTTP-заголовки Tomcat
     * записывает в ISO-8859-1. Кириллица оттуда вылетает с
     * «Unicode character cannot be encoded», и в ответе не остаётся вообще
     * ничего — файл скачивается без имени, а в лог падает стектрейс. Поэтому
     * рядом кладём читаемое ASCII-имя, а точное отдаём через RFC 5987.
     */
    private static String contentDisposition(String filename) {
        String ascii = transliterate(filename);
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }

    /** Русские буквы — латиницей, всё непечатное и служебное — подчёркиванием. */
    private static String transliterate(String value) {
        String[] cyrillic = {"а","б","в","г","д","е","ё","ж","з","и","й","к","л","м","н","о","п",
                "р","с","т","у","ф","х","ц","ч","ш","щ","ъ","ы","ь","э","ю","я"};
        String[] latin = {"a","b","v","g","d","e","e","zh","z","i","y","k","l","m","n","o","p",
                "r","s","t","u","f","h","c","ch","sh","sch","","y","","e","yu","ya"};
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toLowerCase().toCharArray()) {
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                out.append(c);
            } else if (c == '.' || c == '-' || c == '_' || c == ' ') {
                out.append(c);
            } else {
                int index = indexOf(cyrillic, String.valueOf(c));
                out.append(index < 0 ? "_" : latin[index]);
            }
        }
        String ascii = out.toString().replaceAll("_+", "_").replaceAll("^_|_$", "");
        return ascii.isBlank() ? "export" : ascii;
    }

    private static int indexOf(String[] haystack, String needle) {
        for (int i = 0; i < haystack.length; i++) {
            if (haystack[i].equals(needle)) {
                return i;
            }
        }
        return -1;
    }

    /** Справочник значений для интерфейса. */
    @GetMapping("/meta")
    public Map<String, Object> meta() {
        return Map.of(
                "categories", List.of(
                        Map.of("name", "STAR", "title", "Звезда"),
                        Map.of("name", "PREFERRED", "title", "Предпочитаемый"),
                        Map.of("name", "ACCEPTED", "title", "Принятый"),
                        Map.of("name", "ISOLATED", "title", "Изолированный"),
                        Map.of("name", "REJECTED", "title", "Отвергаемый"),
                        Map.of("name", "OUTSIDER", "title", "Аутсайдер")),
                "antipathyCategories", List.of(
                        Map.of("name", "MOST_REJECTED", "title", "Наиболее отвергаемый"),
                        Map.of("name", "REJECTED_BY_SOME", "title", "Отвергаемый"),
                        Map.of("name", "NOT_REJECTED", "title", "Не отвергаемый")),
                "gscFormulas", List.of(
                        Map.of("name", "MUTUAL", "title", "2M / n(n-1)"),
                        Map.of("name", "MUTUAL_DOUBLE_PASS", "title", "sum(Aij*Aji) / n(n-1)")));
    }
}
