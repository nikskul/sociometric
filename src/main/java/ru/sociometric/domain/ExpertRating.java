package ru.sociometric.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Оценка популярности участника, выставленная экспертом.
 * Независимый от социометрии метод оценки; используется для проверки
 * согласованности экспертов и корреляции с реальным социометрическим статусом.
 */
@Entity
@Table(name = "expert_rating",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_expert_rating", columnNames = {"survey_id", "expert", "participant_code"}),
        indexes = @Index(name = "ix_expert_survey", columnList = "survey_id"))
public class ExpertRating {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "survey_id", nullable = false)
    private Long surveyId;

    @Column(name = "expert", nullable = false, length = 128)
    private String expert;

    @Column(name = "participant_code", nullable = false, length = 64)
    private String participantCode;

    /** Балл популярности: чем выше, тем привлекательнее участник для группы. */
    @Column(name = "score", nullable = false)
    private int score;

    public ExpertRating() {
    }

    public ExpertRating(Long surveyId, String expert, String participantCode, int score) {
        this.surveyId = surveyId;
        this.expert = expert;
        this.participantCode = participantCode;
        this.score = score;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSurveyId() {
        return surveyId;
    }

    public void setSurveyId(Long surveyId) {
        this.surveyId = surveyId;
    }

    public String getExpert() {
        return expert;
    }

    public void setExpert(String expert) {
        this.expert = expert;
    }

    public String getParticipantCode() {
        return participantCode;
    }

    public void setParticipantCode(String participantCode) {
        this.participantCode = participantCode;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }
}
