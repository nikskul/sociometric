package ru.sociometric.desktop;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Каталоги настольной сборки.
 *
 * <p>В режиме разработки база лежит в {@code ./data} рядом с проектом, и всё
 * работает как раньше. Настольное приложение запускается из ярлыка, у которого
 * рабочий каталог — системный, поэтому туда писать нельзя. Данные уходят в
 * профиль пользователя: {@code %LOCALAPPDATA%\Sociometric} в Windows,
 * {@code ~/.sociometric} в остальных системах.
 */
public final class DesktopPaths {

    /** Имя каталога приложения в профиле пользователя. */
    public static final String APP_DIR = "Sociometric";

    private DesktopPaths() {
    }

    /**
     * Базовый каталог данных.
     *
     * <p>Порядок выбора:
     * <ol>
     *   <li>переменная окружения {@code SOCIOMETRIC_HOME} — для тестов и нестандартных установок;</li>
     *   <li>{@code %LOCALAPPDATA%\Sociometric} в Windows;</li>
     *   <li>{@code ~/.sociometric} в Linux и macOS.</li>
     * </ol>
     */
    public static Path home() {
        return home(System.getenv("SOCIOMETRIC_HOME"));
    }

    /** Тот же выбор, но с явным переопределением — так удобнее проверять в тестах. */
    public static Path home(String override) {
        if (override != null && !override.isBlank()) {
            return Paths.get(override).toAbsolutePath();
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Paths.get(localAppData, APP_DIR).toAbsolutePath();
        }
        return Paths.get(System.getProperty("user.home"), "." + APP_DIR.toLowerCase(java.util.Locale.ROOT))
                .toAbsolutePath();
    }

    /** Каталог файла базы H2. */
    public static Path dataDir(Path home) {
        return home.resolve("data");
    }

    /** URL подключения к базе в нужном каталоге. */
    public static String databaseUrl(Path home) {
        // Windows требует прямых слэшей в пути к файлу БД, иначе H2 не найдёт базу.
        String path = dataDir(home).toAbsolutePath().toString().replace('\\', '/');
        return "jdbc:h2:file:" + path + "/sociometric;DB_CLOSE_ON_EXIT=FALSE";
    }

    /** Файл журнала настольной сборки. */
    public static Path logFile(Path home) {
        return home.resolve("sociometric.log");
    }

    /** Файл с pid и портом текущего запуска — по нему находится уже открытый экземпляр. */
    public static Path runFile(Path home) {
        return home.resolve("run.properties");
    }

    /** URL, который открываем в браузере. */
    public static String appUrl(int port) {
        return "http://localhost:" + port + "/";
    }
}
