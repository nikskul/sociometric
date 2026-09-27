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
 * Отметка «участник отправил ответ».
 *
 * <p>Без неё нельзя отличить не ответившего от ответившего «никого не выбираю»:
 * у второго в таблице {@link Choice} нет ни одной строки, ровно как у первого.
 * Поэтому сам факт ответа хранится отдельно от самих выборов.
 */
@Entity
@Table(name = "survey_response",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_survey_response", columnNames = {"survey_id", "respondent"}),
        indexes = @Index(name = "ix_survey_response_survey", columnList = "survey_id"))
public class ResponseMark {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "survey_id", nullable = false)
    private Long surveyId;

    /** Код участника-респондента (Participant.code). */
    @Column(name = "respondent", nullable = false, length = 64)
    private String respondent;

    public ResponseMark() {
    }

    public ResponseMark(Long surveyId, String respondent) {
        this.surveyId = surveyId;
        this.respondent = respondent;
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

    public String getRespondent() {
        return respondent;
    }

    public void setRespondent(String respondent) {
        this.respondent = respondent;
    }
}
