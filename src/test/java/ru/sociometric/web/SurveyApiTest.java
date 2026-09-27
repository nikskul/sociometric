package ru.sociometric.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверка REST-контракта: опросы, ввод данных, расчёт, импорт и выгрузка.
 */
@SpringBootTest
class SurveyApiTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).build();
    }

    private long createSurvey(MockMvc mvc, String name) throws Exception {
        return createSurvey(mvc, name, """
                {"code":"1","name":"Анна"},{"code":"2","name":"Борис"},
                {"code":"3","name":"Вера"},{"code":"4","name":"Глеб"}""");
    }

    private long createSurvey(MockMvc mvc, String name, String participants) throws Exception {
        String body = """
                {"name":"%s","description":"","choicesLimit":2,"collectAntipathy":true,
                 "ranks":false,"autoThresholds":true,
                 "thresholds":{"star":2,"preferred":1,"accepted":1,"mostRejected":1},
                 "participants":[%s]}
                """.formatted(name, participants);
        String response = mvc.perform(post("/api/surveys").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(response.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    @Test
    @DisplayName("Справочник категорий отдаётся для интерфейса")
    void meta() throws Exception {
        mvc().perform(get("/api/surveys/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories", hasSize(6)))
                .andExpect(jsonPath("$.gscFormulas", hasSize(2)));
    }

    @Test
    @DisplayName("Полный цикл: создать опрос, внести выборы, посчитать")
    void fullCycle() throws Exception {
        MockMvc mvc = mvc();
        long id = createSurvey(mvc, "Цикл");

        mvc.perform(post("/api/surveys/" + id + "/choices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"SYMPATHY","replace":true,"choices":[
                                  {"kind":"SYMPATHY","from":"1","to":"2","weight":1},
                                  {"kind":"SYMPATHY","from":"2","to":"1","weight":1},
                                  {"kind":"SYMPATHY","from":"3","to":"2","weight":1},
                                  {"kind":"SYMPATHY","from":"4","to":"3","weight":1}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choicesCount").value(4));

        mvc.perform(get("/api/surveys/" + id + "/result"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.n").value(4))
                .andExpect(jsonPath("$.indices.mutualPairsCount").value(1))
                .andExpect(jsonPath("$.indices.unidirectionalPairsCount").value(2))
                .andExpect(jsonPath("$.participants", hasSize(4)))
                .andExpect(jsonPath("$.matrix", hasSize(4)))
                .andExpect(jsonPath("$.graph.diameter").value(greaterThan(0)));

        mvc.perform(get("/api/surveys/" + id + "/choices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)));
    }

    @Test
    @DisplayName("Антипатии попадают в отдельную матрицу и в индекс отвержения")
    void antipathies() throws Exception {
        MockMvc mvc = mvc();
        long id = createSurvey(mvc, "Антипатии");

        mvc.perform(post("/api/surveys/" + id + "/choices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"replaceAll":true,"choices":[
                                  {"kind":"SYMPATHY","from":"1","to":"3","weight":1},
                                  {"kind":"ANTIPATHY","from":"2","to":"4","weight":1},
                                  {"kind":"ANTIPATHY","from":"3","to":"4","weight":1}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.antipathyCount").value(2));

        mvc.perform(get("/api/surveys/" + id + "/result"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.antiMatrix[1][3]").value(1))
                .andExpect(jsonPath("$.indices.rejectedIndex").value(0.25))
                .andExpect(jsonPath("$.indices.negativeStatusIndex").exists());
    }

    @Test
    @DisplayName("Подгруппы собираются по ведущим цифрам кода и считаются отдельно")
    void subgroups() throws Exception {
        MockMvc mvc = mvc();
        long id = createSurvey(mvc, "Подгруппы", """
                {"code":"1А","name":"Аня"},{"code":"1Б","name":"Боря"},
                {"code":"2А","name":"Витя"},{"code":"2Б","name":"Гриша"}""");

        mvc.perform(post("/api/surveys/" + id + "/choices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"SYMPATHY","replace":true,"choices":[
                                  {"kind":"SYMPATHY","from":"1А","to":"1Б"},
                                  {"kind":"SYMPATHY","from":"1Б","to":"1А"},
                                  {"kind":"SYMPATHY","from":"2А","to":"2Б"},
                                  {"kind":"SYMPATHY","from":"2Б","to":"2А"},
                                  {"kind":"SYMPATHY","from":"2А","to":"1А"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choicesCount").value(5));

        mvc.perform(get("/api/surveys/" + id + "/result"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups", hasSize(2)))
                // «1А» и «1Б» — подгруппа «1»; выбор «2А → 1А» в её счёт не идёт
                .andExpect(jsonPath("$.groups[0].name").value("1"))
                .andExpect(jsonPath("$.groups[0].indices.n").value(2))
                .andExpect(jsonPath("$.groups[0].indices.mutualPairsCount").value(1))
                .andExpect(jsonPath("$.groups[1].name").value("2"))
                .andExpect(jsonPath("$.groups[1].indices.n").value(2))
                .andExpect(jsonPath("$.groups[1].indices.mutualPairsCount").value(1));
    }

    @Test
    @DisplayName("Экспертные оценки дают Kendall W и корреляцию со статусом")
    void experts() throws Exception {
        MockMvc mvc = mvc();
        long id = createSurvey(mvc, "Эксперты");

        mvc.perform(post("/api/surveys/" + id + "/choices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"SYMPATHY","replace":true,"choices":[
                                  {"kind":"SYMPATHY","from":"1","to":"2","weight":1},
                                  {"kind":"SYMPATHY","from":"2","to":"1","weight":1},
                                  {"kind":"SYMPATHY","from":"2","to":"3","weight":1},
                                  {"kind":"SYMPATHY","from":"3","to":"2","weight":1},
                                  {"kind":"SYMPATHY","from":"3","to":"4","weight":1},
                                  {"kind":"SYMPATHY","from":"4","to":"3","weight":1}]}
                                """))
                .andExpect(status().isOk());

        mvc.perform(post("/api/surveys/" + id + "/experts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"replace":true,"ratings":[
                                  {"expert":"Психолог","code":"1","score":4},
                                  {"expert":"Психолог","code":"2","score":9},
                                  {"expert":"Психолог","code":"3","score":8},
                                  {"expert":"Психолог","code":"4","score":3}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expertsCount").value(4));

        // с одним экспертом согласованность не вычислима — поле отсутствует, а не «0»
        mvc.perform(get("/api/surveys/" + id + "/result"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.experts.kendallW").doesNotExist())
                .andExpect(jsonPath("$.experts.pearsonWithStatus").value(closeTo(0.98, 0.01)))
                .andExpect(jsonPath("$.experts.ranking", hasSize(4)))
                .andExpect(jsonPath("$.experts.ranking[0].code").value("2"));
    }

    @Test
    @DisplayName("Импорт длинного CSV и выгрузка результата")
    void importAndExport() throws Exception {
        MockMvc mvc = mvc();
        long id = createSurvey(mvc, "Импорт");

        mvc.perform(multipart("/api/surveys/" + id + "/import")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "data.csv",
                                "text/csv", "type;respondent;target\nSYMPATHY;1;2\nАНТИПАТИЯ;3;4\n"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .param("replace", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.skippedRows").value(0));

        mvc.perform(get("/api/surveys/" + id + "/export?format=csv"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("text/csv")));

        mvc.perform(get("/api/surveys/" + id + "/export?format=xlsx"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/surveys/" + id + "/template.csv"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("From,To,Type,Rank")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("SYMPATHY,"))));
    }

    /**
     * Имя опроса по-русски, а заголовки HTTP Tomcat пишет в ISO-8859-1.
     * Кириллица в Content-Disposition раньше роняла запись заголовка: файл
     * приходил без имени, а в лог падал стектрейс про кодирование.
     */
    @Test
    @DisplayName("Выгрузка с кириллическим названием отдаёт читаемое имя файла")
    void exportKeepsCyrillicFileName() throws Exception {
        MockMvc mvc = mvc();
        long id = createSurvey(mvc, "8А класс — учебный пример");

        for (String format : new String[]{"csv", "xlsx"}) {
            String disposition = mvc.perform(get("/api/surveys/" + id + "/export?format=" + format))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getHeader("Content-Disposition");

            assertNotNull(disposition, "нет Content-Disposition для " + format);
            // ASCII-запасное имя обязано оставаться читаемым, а не «________»
            assertTrue(disposition.contains("filename=\""),
                    "нет ASCII-имени: " + disposition);
            String asciiName = disposition.replaceAll(".*filename=\"([^\"]*)\".*", "$1");
            assertTrue(asciiName.matches("[\\x20-\\x7E]+"),
                    "ASCII-имя содержит не-ASCII: " + asciiName);
            assertTrue(asciiName.toLowerCase().endsWith("." + format),
                    "ASCII-имя без расширения: " + asciiName);
            // и точное имя по RFC 5987, чтобы браузер показал кириллицу
            assertTrue(disposition.contains("filename*=UTF-8''"),
                    "нет RFC 5987-имени: " + disposition);
            String encoded = disposition.replaceAll(".*filename\\*=UTF-8''(.*)", "$1");
            String decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            assertEquals("8А_класс_учебный_пример." + format, decoded,
                    "имя файла исказилось при кодировании");
        }
    }

    @Test
    @DisplayName("Ответ без выборов сохраняется как ответ, а не теряется")
    void responseWithoutChoicesIsKept() throws Exception {
        MockMvc mvc = mvc();
        long id = createSurvey(mvc, "Пустой ответ");

        // респондент 1 отвечает, но никого не выбирает
        mvc.perform(post("/api/surveys/" + id + "/choices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"SYMPATHY","replace":true,"respondent":"1","choices":[]}"""))
                .andExpect(status().isOk());

        mvc.perform(get("/api/surveys/" + id + "/result"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participants[0].responded").value(true))
                .andExpect(jsonPath("$.participants[0].category").value("OUTSIDER"))
                .andExpect(jsonPath("$.participants[1].responded").value(false))
                .andExpect(jsonPath("$.participants[1].category").value("NO_DATA"));
    }

    @Test
    @DisplayName("Несвязный граф: диаметр null, а не 0")
    void disconnectedGraphHasNullDiameter() throws Exception {
        MockMvc mvc = mvc();
        long id = createSurvey(mvc, "Несвязный");

        mvc.perform(post("/api/surveys/" + id + "/choices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"SYMPATHY","replace":true,"choices":[
                                  {"kind":"SYMPATHY","from":"1","to":"2","weight":1},
                                  {"kind":"SYMPATHY","from":"3","to":"4","weight":1}]}"""))
                .andExpect(status().isOk());

        mvc.perform(get("/api/surveys/" + id + "/result"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.graph.diameter").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.graph.connectedComponents").value(2))
                .andExpect(jsonPath("$.graph.connectedPairs").value(2))
                .andExpect(jsonPath("$.graph.possiblePairs").value(6));
    }

    @Test
    @DisplayName("Плохие данные дают понятную ошибку 400, а не 500")
    void validationErrors() throws Exception {
        MockMvc mvc = mvc();

        mvc.perform(post("/api/surveys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","choicesLimit":3,"participants":[]}
                                """))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/surveys/999999/result"))
                .andExpect(status().isNotFound());
    }

    /**
     * Регрессия: интерфейс держит матрицу в памяти целиком и после каждой правки
     * переписывает её целиком. Раньше такой запрос просил заменить только симпатии,
     * а присылал оба вида — и вторая подряд сохранённая правка падала на
     * уникальном индексе (survey_id, respondent, target, kind).
     */
    @Test
    @DisplayName("Полная перезапись выборов дважды подряд не ломается об антипатиях")
    void replaceAllTwiceKeepsAntipathies() throws Exception {
        MockMvc mvc = mvc();
        long id = createSurvey(mvc, "Повторная перезапись");

        String first = """
                {"replaceAll":true,"respondent":"1","choices":[
                  {"kind":"SYMPATHY","from":"1","to":"2","weight":1},
                  {"kind":"SYMPATHY","from":"1","to":"3","weight":1},
                  {"kind":"ANTIPATHY","from":"2","to":"4","weight":1}]}
                """;
        mvc.perform(post("/api/surveys/" + id + "/choices")
                        .contentType(MediaType.APPLICATION_JSON).content(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choicesCount").value(2))
                .andExpect(jsonPath("$.antipathyCount").value(1));

        // та же матрица плюс одна новая строка — антипатия из прошлого запроса
        // остаётся на месте, а не пытается вставиться второй раз
        String second = """
                {"replaceAll":true,"respondent":"1","choices":[
                  {"kind":"SYMPATHY","from":"1","to":"2","weight":1},
                  {"kind":"SYMPATHY","from":"1","to":"3","weight":1},
                  {"kind":"SYMPATHY","from":"1","to":"4","weight":1},
                  {"kind":"ANTIPATHY","from":"2","to":"4","weight":1}]}
                """;
        mvc.perform(post("/api/surveys/" + id + "/choices")
                        .contentType(MediaType.APPLICATION_JSON).content(second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choicesCount").value(3))
                .andExpect(jsonPath("$.antipathyCount").value(1));
    }

    /** Старый контракт replace трактовал оба вида как один и падал на индексе. */
    @Test
    @DisplayName("replace с чужим видом выборов даёт понятную 400, а не 500")
    void replaceRejectsMixedKinds() throws Exception {
        MockMvc mvc = mvc();
        long id = createSurvey(mvc, "Смешанные виды");

        mvc.perform(post("/api/surveys/" + id + "/choices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"SYMPATHY","replace":true,"choices":[
                                  {"kind":"SYMPATHY","from":"1","to":"2"},
                                  {"kind":"ANTIPATHY","from":"2","to":"4"}]}
                                """))
                .andExpect(status().isBadRequest());

        // ничего не записалось: и симпатий, ни антипатий
        mvc.perform(get("/api/surveys/" + id + "/choices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** Снятый выбор должен исчезнуть, а не остаться со второй перезаписи на месте. */
    @Test
    @DisplayName("Полная перезапись действительно удаляет снятые выборы")
    void replaceAllRemovesDroppedChoices() throws Exception {
        MockMvc mvc = mvc();
        long id = createSurvey(mvc, "Снятие выбора");

        mvc.perform(post("/api/surveys/" + id + "/choices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"replaceAll":true,"respondent":"1","choices":[
                                  {"kind":"SYMPATHY","from":"1","to":"2","weight":1},
                                  {"kind":"SYMPATHY","from":"1","to":"3","weight":1},
                                  {"kind":"SYMPATHY","from":"1","to":"4","weight":1},
                                  {"kind":"ANTIPATHY","from":"2","to":"4","weight":1}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choicesCount").value(3))
                .andExpect(jsonPath("$.antipathyCount").value(1));

        mvc.perform(post("/api/surveys/" + id + "/choices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"replaceAll":true,"respondent":"1","choices":[
                                  {"kind":"SYMPATHY","from":"1","to":"2","weight":1},
                                  {"kind":"ANTIPATHY","from":"2","to":"4","weight":1}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choicesCount").value(1))
                .andExpect(jsonPath("$.antipathyCount").value(1));

        mvc.perform(get("/api/surveys/" + id + "/choices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    /** Ранг и вес обновляются на месте: строка та же, но содержимое поменялось. */
    @Test
    @DisplayName("Полная перезапись обновляет ранг существующего выбора")
    void replaceAllUpdatesRank() throws Exception {
        MockMvc mvc = mvc();
        long id = createSurvey(mvc, "Ранги");

        mvc.perform(post("/api/surveys/" + id + "/choices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"replaceAll":true,"respondent":"1","choices":[
                                  {"kind":"SYMPATHY","from":"1","to":"2","rank":1,"weight":3},
                                  {"kind":"SYMPATHY","from":"1","to":"3","rank":2,"weight":2}]}
                                """))
                .andExpect(status().isOk());

        mvc.perform(post("/api/surveys/" + id + "/choices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"replaceAll":true,"respondent":"1","choices":[
                                  {"kind":"SYMPATHY","from":"1","to":"2","rank":2,"weight":2},
                                  {"kind":"SYMPATHY","from":"1","to":"3","rank":1,"weight":3}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choicesCount").value(2))
                .andExpect(jsonPath("$.antipathyCount").value(0));

        mvc.perform(get("/api/surveys/" + id + "/choices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rank").value(2))
                .andExpect(jsonPath("$[0].weight").value(2))
                .andExpect(jsonPath("$[1].rank").value(1))
                .andExpect(jsonPath("$[1].weight").value(3));
    }
}
