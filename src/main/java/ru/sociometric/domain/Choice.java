package ru.sociometric.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Один выбор респондента. Хранится строка на каждое направление «кто кого выбрал».
 */
@Entity
@Table(name = "choice",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_choice", columnNames = {"survey_id", "respondent", "target", "kind"}),
        indexes = @Index(name = "ix_choice_survey", columnList = "survey_id"))
public class Choice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "survey_id", nullable = false)
    private Long surveyId;

    /** Код участника-респондента (Participant.code). */
    @Column(name = "respondent", nullable = false, length = 64)
    private String respondent;

    /** Код участника, которого выбрали. */
    @Column(name = "target", nullable = false, length = 64)
    private String target;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private ChoiceKind kind = ChoiceKind.SYMPATHY;

    /** Место в предпочтении: 1 — самый желательный. null — ранг не указан. */
    @Column(name = "pref_rank")
    private Integer rank;

    /** Вес вклада в статус (по умолчанию 1; при ранжировании можно 3/2/1). */
    @Column(name = "weight", nullable = false)
    private int weight = 1;

    public Choice() {
    }

    public Choice(Long surveyId, String respondent, String target, ChoiceKind kind) {
        this.surveyId = surveyId;
        this.respondent = respondent;
        this.target = target;
        this.kind = kind;
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

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public ChoiceKind getKind() {
        return kind;
    }

    public void setKind(ChoiceKind kind) {
        this.kind = kind;
    }

    public Integer getRank() {
        return rank;
    }

    public void setRank(Integer rank) {
        this.rank = rank;
    }

    public int getWeight() {
        return weight;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }
}
