package ru.sociometric.calculator;

/**
 * Пороги категорий статуса.
 * При {@code auto == true} значения пересчитываются от среднего и стандартного
 * отклонения числа полученных выборов (правило «среднее ± σ»).
 *
 * @param star         минимум симпатий для категории «звезда»
 * @param preferred    минимум симпатий для категории «предпочитаемый»
 * @param accepted     минимум симпатий для категории «принятый»
 * @param mostRejected минимум антипатий для категории «наиболее отвергаемый»
 * @param auto         пересчитывать пороги автоматически
 */
public record StatusThresholds(Integer star, Integer preferred, Integer accepted,
                               Integer mostRejected, boolean auto) {

    public static StatusThresholds defaults() {
        return new StatusThresholds(3, 2, 1, 2, true);
    }

    public static StatusThresholds fixed(int star, int preferred, int accepted, int mostRejected) {
        return new StatusThresholds(star, preferred, accepted, mostRejected, false);
    }
}
