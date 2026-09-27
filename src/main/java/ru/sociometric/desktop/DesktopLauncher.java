package ru.sociometric.desktop;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import ru.sociometric.SociometricApplication;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Настольная точка входа: поднимает приложение на свободном порту и открывает
 * его в браузере по умолчанию.
 *
 * <p>Приложение остаётся обычным веб-сервисом — интерфейс, расчёты и экспорт
 * работают без изменений. Разница только в запуске: порт выбирается системой,
 * база лежит в профиле пользователя, окно консоли не появляется, журнал пишется
 * в файл.
 *
 * <p>Главная тонкость — как завершить работу. Окна у приложения нет, поэтому
 * «выход» лежит в трее; если трей недоступен, показывается маленькое окно с
 * адресом и кнопкой выхода.
 */
public final class DesktopLauncher {

    private static final int STARTUP_TIMEOUT_SECONDS = 90;
    private static final java.util.concurrent.atomic.AtomicBoolean BROWSER_OPENED =
            new java.util.concurrent.atomic.AtomicBoolean();

    private DesktopLauncher() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && ("--paths".equals(args[0]) || "--help".equals(args[0]))) {
            printPaths();
            return;
        }

        Path home = DesktopPaths.home();
        Path runFile = DesktopPaths.runFile(home);

        // Повторное нажатие на ярлык открывает уже работающее приложение,
        // а не поднимает вторую копию на другом порту.
        RunState running = RunState.readLive(runFile);
        if (running != null) {
            openInBrowser(running.url());
            return;
        }

        // Порт 0 просит у системы любой свободный: приложение не откажется
        // запускаться из-за занятого 8080 и не столкнётся с другим экземпляром.
        System.setProperty("server.port", "0");
        System.setProperty("SOCIOMETRIC_DB_URL", DesktopPaths.databaseUrl(home));
        System.setProperty("SOCIOMETRIC_LOG", DesktopPaths.logFile(home).toString());

        SpringApplication app = new SpringApplication(SociometricApplication.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        app.setAdditionalProfiles("desktop");
        app.setBannerMode(Banner.Mode.OFF);
        ConfigurableApplicationContext context = app.run(args);

        RunState state = RunState.write(runFile, resolvePort(context));
        // Заметка снимается при любом выходе, включаем завершение процесса.
        Runtime.getRuntime().addShutdownHook(new Thread(state::clear, "sociometric-cleanup"));

        // Действие выхода должно разрушить и сам трей, а трей создаётся уже
        // после того, как действие сформировано, поэтому ссылка замыкается позже.
        AtomicReference<TrayController> tray = new AtomicReference<>();
        tray.set(TrayController.install(state.url(), () -> shutdown(context, state, tray.get())));

        waitUntilResponsive(state.port());
        openInBrowser(state.url());
    }

    private static void shutdown(ConfigurableApplicationContext context, RunState state, TrayController tray) {
        if (tray != null) {
            tray.dispose();
        }
        state.clear();
        context.close();
    }

    private static int resolvePort(ConfigurableApplicationContext context) {
        String port = context.getEnvironment().getProperty("local.server.port");
        if (port == null) {
            throw new IllegalStateException("Не удалось определить порт приложения");
        }
        return Integer.parseInt(port);
    }

    /** Ждём ответа сервера, иначе браузер откроет пустую страницу. */
    private static void waitUntilResponsive(int port) {
        long deadline = System.nanoTime() + STARTUP_TIMEOUT_SECONDS * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (RunState.isPortOpen(port)) {
                return;
            }
            sleep();
        }
        System.err.println("Приложение не отвечает на порту " + port);
    }

    private static void sleep() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Открывает адрес в браузере по умолчанию — но только один раз за запуск.
     *
     * <p>Значок в трее и запасное окно зовут тот же метод, а AWT в некоторых
     * окружениях срабатывает на их создании событием нажатия. Без защиты
     * пользователь получал две вкладки вместо одной.
     */
    public static void openInBrowser(String url) {
        if (!BROWSER_OPENED.compareAndSet(false, true)) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception e) {
            System.err.println("Не удалось открыть браузер: " + e.getMessage());
        }
        // Окна консоли у настольной сборки нет, поэтому это сообщение видно
        // только при запуске из командной строки.
        System.out.println("Откройте в браузере: " + url);
    }

    /** Диагностика для тех, кто запускает лаунчер из командной строки. */
    private static void printPaths() {
        Path home = DesktopPaths.home();
        System.out.println("Каталог приложения: " + home);
        System.out.println("Каталог базы:       " + DesktopPaths.dataDir(home));
        System.out.println("URL базы:           " + DesktopPaths.databaseUrl(home));
        System.out.println("Журнал:             " + DesktopPaths.logFile(home));
        System.out.println("Состояние запуска:  " + DesktopPaths.runFile(home));
    }
}
