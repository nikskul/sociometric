package ru.sociometric.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Социометрический опрос (одно исследование одной группы).
 */
@Entity
@Table(name = "survey")
public class Survey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 1000)
    private String description;

    /** Сколько участников разрешено выбрать каждому респонденту (обычно 3). */
    @Column(name = "choices_limit", nullable = false)
    private int choicesLimit = 3;

    /** Собираются ли антипатии (второй блок вопросов «с кем бы НЕ хотел»). */
    @Column(name = "collect_antipathy", nullable = false)
    private boolean collectAntipathy;

    /** Ранжированный опросник: учитывается порядок выбора, первый весит больше. */
    @Column(name = "ranks", nullable = false)
    private boolean ranks;

    /** Пороги категорий статуса: числа полученных выборов. */
    @Embedded
    private Thresholds thresholds = new Thresholds();

    @Column(name = "auto_thresholds", nullable = false)
    private boolean autoThresholds = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "participants", columnDefinition = "CLOB")
    private String participantsJson = "[]";

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getChoicesLimit() {
        return choicesLimit;
    }

    public void setChoicesLimit(int choicesLimit) {
        this.choicesLimit = choicesLimit;
    }

    public boolean isCollectAntipathy() {
        return collectAntipathy;
    }

    public void setCollectAntipathy(boolean collectAntipathy) {
        this.collectAntipathy = collectAntipathy;
    }

    public boolean isRanks() {
        return ranks;
    }

    public void setRanks(boolean ranks) {
        this.ranks = ranks;
    }

    public Thresholds getThresholds() {
        return thresholds;
    }

    public void setThresholds(Thresholds thresholds) {
        this.thresholds = thresholds == null ? new Thresholds() : thresholds;
    }

    public boolean isAutoThresholds() {
        return autoThresholds;
    }

    public void setAutoThresholds(boolean autoThresholds) {
        this.autoThresholds = autoThresholds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getParticipantsJson() {
        return participantsJson;
    }

    public void setParticipantsJson(String participantsJson) {
        this.participantsJson = participantsJson;
    }
}
