package ru.sociometric.dto;

import ru.sociometric.domain.Thresholds;

/**
 * Пороги категорий статуса в API.
 *
 * @param star         звезда
 * @param preferred    предпочитаемый
 * @param accepted     принятый
 * @param mostRejected наиболее отвергаемый (по антипатиям)
 */
public record ThresholdsDto(Integer star, Integer preferred, Integer accepted, Integer mostRejected) {

    public static ThresholdsDto from(Thresholds thresholds) {
        if (thresholds == null) {
            return new ThresholdsDto(3, 2, 1, 2);
        }
        return new ThresholdsDto(thresholds.getStar(), thresholds.getPreferred(),
                thresholds.getAccepted(), thresholds.getMostRejected());
    }

    public void applyTo(Thresholds thresholds) {
        if (star != null) {
            thresholds.setStar(star);
        }
        if (preferred != null) {
            thresholds.setPreferred(preferred);
        }
        if (accepted != null) {
            thresholds.setAccepted(accepted);
        }
        if (mostRejected != null) {
            thresholds.setMostRejected(mostRejected);
        }
    }
}
