package ru.sociometric.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import ru.sociometric.domain.GscFormula;

/**
 * Настройки приложения из {@code application.yml}.
 *
 * @param gscFormula        формула коэффициента групповой сплоченности
 * @param demoDataOnStartup создавать демонстрационный опрос при пустой базе
 */
@ConfigurationProperties(prefix = "sociometric")
public record AppProperties(GscFormula gscFormula, boolean demoDataOnStartup) {

    public AppProperties {
        if (gscFormula == null) {
            gscFormula = GscFormula.MUTUAL;
        }
    }

    public GscFormula formula() {
        return gscFormula;
    }
}
