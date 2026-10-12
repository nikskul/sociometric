package ru.sociometric.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.sociometric.repository.ChoiceRepository;
import ru.sociometric.repository.ExpertRatingRepository;
import ru.sociometric.repository.ResponseMarkRepository;
import ru.sociometric.repository.SurveyRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** School-instance maintenance: portable backup and complete data erasure. */
@Service
public class AdminService {
    private final JdbcTemplate jdbc;
    private final SurveyRepository surveys;
    private final ChoiceRepository choices;
    private final ExpertRatingRepository experts;
    private final ResponseMarkRepository responses;

    public AdminService(JdbcTemplate jdbc, SurveyRepository surveys, ChoiceRepository choices,
                        ExpertRatingRepository experts, ResponseMarkRepository responses) {
        this.jdbc = jdbc;
        this.surveys = surveys;
        this.choices = choices;
        this.experts = experts;
        this.responses = responses;
    }

    public byte[] backup() {
        Path directory;
        Path archive;
        try {
            directory = Files.createTempDirectory("sociometric-backup-");
            archive = directory.resolve("sociometric-backup.zip");
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось создать временный файл резервной копии", e);
        }
        try {
            String path = archive.toAbsolutePath().toString().replace("\\", "/").replace("'", "''");
            jdbc.execute("BACKUP TO '" + path + "'");
            return Files.readAllBytes(archive);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось создать резервную копию базы", e);
        } finally {
            try {
                Files.deleteIfExists(archive);
                Files.deleteIfExists(directory);
            } catch (IOException ignored) { }
        }
    }

    @Transactional
    public void eraseAllData() {
        choices.deleteAllInBatch();
        experts.deleteAllInBatch();
        responses.deleteAllInBatch();
        surveys.deleteAllInBatch();
    }
}
