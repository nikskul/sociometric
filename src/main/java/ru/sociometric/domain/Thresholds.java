package ru.sociometric.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Пороги категорий статуса — минимальное число полученных симпатий.
 * Если {@code autoThresholds} включен, значения вычисляются от среднего и σ
 * (звезда &gt;= μ+σ, предпочитаемый &gt;= μ, принятый &gt;= μ-σ) и игнорируются.
 */
@Embeddable
public class Thresholds {

    /** «Звезда»: полученных выборов не меньше этого числа. */
    @Column(name = "th_star")
    private Integer star = 3;

    /** «Предпочитаемый». */
    @Column(name = "th_preferred")
    private Integer preferred = 2;

    /** «Принятый». */
    @Column(name = "th_accepted")
    private Integer accepted = 1;

    /** Порог «наиболее отвергаемого» по антипатиям. */
    @Column(name = "th_most_rejected")
    private Integer mostRejected = 2;

    public Integer getStar() {
        return star;
    }

    public void setStar(Integer star) {
        this.star = star;
    }

    public Integer getPreferred() {
        return preferred;
    }

    public void setPreferred(Integer preferred) {
        this.preferred = preferred;
    }

    public Integer getAccepted() {
        return accepted;
    }

    public void setAccepted(Integer accepted) {
        this.accepted = accepted;
    }

    public Integer getMostRejected() {
        return mostRejected;
    }

    public void setMostRejected(Integer mostRejected) {
        this.mostRejected = mostRejected;
    }
}
