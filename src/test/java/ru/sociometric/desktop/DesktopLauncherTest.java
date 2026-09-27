package ru.sociometric.desktop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopLauncherTest {

    @Test
    @DisplayName("Каталог приложения берётся из переопределения, если оно есть")
    void homeRespectsOverride(@TempDir Path temp) {
        Path home = DesktopPaths.home(temp.toString());
        assertEquals(temp.toAbsolutePath(), home);
    }

    @Test
    @DisplayName("Пустое переопределение игнорируется: путь не должен становиться пустым")
    void blankOverrideIsIgnored(@TempDir Path temp) {
        assertFalse(DesktopPaths.home("  ").toString().isBlank());
        assertNotNull(DesktopPaths.home());
    }

    @Test
    @DisplayName("URL базы не содержит обратных слэшей: их не понимает H2 на Windows")
    void databaseUrlUsesForwardSlashes(@TempDir Path temp) {
        // На Windows путь выглядит как C:\Users\Иван\Sociometric\data — такой
        // путь в URL базы H2 не читается, и база молча создаётся не там.
        Path windowsLike = temp.resolve("Пользователь").resolve("AppData").resolve("Local");
        String url = DesktopPaths.databaseUrl(windowsLike);

        assertTrue(url.startsWith("jdbc:h2:file:"), url);
        assertTrue(url.contains("/data/sociometric"), url);
        assertFalse(url.contains("\\"), "в URL базы остались обратные слэши: " + url);
        assertTrue(url.endsWith(";DB_CLOSE_ON_EXIT=FALSE"), url);
    }

    @Test
    @DisplayName("Путь к базе внутри каталога приложения, а не рядом с ним")
    void dataDirIsInsideHome(@TempDir Path temp) {
        assertEquals(temp.resolve("data"), DesktopPaths.dataDir(temp));
        assertEquals(temp.resolve("sociometric.log"), DesktopPaths.logFile(temp));
        assertEquals(temp.resolve("run.properties"), DesktopPaths.runFile(temp));
    }

    @Test
    @DisplayName("Заметка о запуске читается обратно и снимается при выходе")
    void runStateRoundTrip(@TempDir Path temp) throws IOException {
        Path file = DesktopPaths.runFile(temp);
        assertNull(RunState.readLive(file), "до запуска заметки быть не должно");

        try (ServerSocket socket = new ServerSocket(0)) {
            int port = socket.getLocalPort();
            RunState state = RunState.write(file, port);
            assertEquals(port, state.port());
            assertEquals(ProcessHandle.current().pid(), state.pid());

            RunState found = RunState.readLive(file);
            assertNotNull(found, "живой запуск должен находиться по заметке");
            assertEquals(port, found.port());
            assertEquals(DesktopPaths.appUrl(port), found.url());

            state.clear();
            assertFalse(Files.exists(file), "после выхода заметка должна исчезнуть");
        }
    }

    @Test
    @DisplayName("Заметка с мёртвым процессом или закрытым портом считается устаревшей")
    void staleRunStateIsIgnored(@TempDir Path temp) throws IOException {
        Path file = DesktopPaths.runFile(temp);

        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        // порт уже закрыт — значит процесса за заметкой уже нет
        RunState.write(file, freePort);
        assertNull(RunState.readLive(file), "заметка с закрытым портом принята за рабочую");

        // порт отвечает, но процесса с таким pid не существует
        try (ServerSocket socket = new ServerSocket(0)) {
            writeRaw(file, ProcessHandle.current().pid() + 1_000_000, socket.getLocalPort());
            assertNull(RunState.readLive(file), "заметка с чужим pid принята за рабочую");

            writeRaw(file, ProcessHandle.current().pid(), socket.getLocalPort());
            assertNotNull(RunState.readLive(file), "живая заметка собственного процесса отвергнута");
        }
    }

    @Test
    @DisplayName("Испорченная заметка не мешает запуску: она просто игнорируется")
    void corruptRunStateIsIgnored(@TempDir Path temp) throws IOException {
        Path file = DesktopPaths.runFile(temp);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "port=\u043F\u043E\u0440\u0442\npid=abc\n");

        assertNull(RunState.readLive(file), "испорченная заметка принята за рабочую");
    }

    @Test
    @DisplayName("Проверка процесса и порта не выбрасывает исключений на краях")
    void livenessChecksAreSafe() {
        assertFalse(RunState.isProcessAlive(0));
        assertFalse(RunState.isProcessAlive(-1));
        assertFalse(RunState.isPortOpen(0));
        assertFalse(RunState.isPortOpen(70000));
        assertTrue(RunState.isProcessAlive(ProcessHandle.current().pid()));
    }

    @Test
    @DisplayName("Значок рисуется и на прозрачном, и на непрозрачном фоне")
    void iconRenders() {
        var image = AppIcon.image(64);
        assertEquals(64, image.getWidth());
        assertEquals(64, image.getHeight());
        // в углу должен быть прозрачный пиксель, в центре — непрозрачный
        assertEquals(0, image.getRGB(0, 0) >>> 24, "угол значка должен быть прозрачным");
        assertEquals(255, image.getRGB(32, 32) >>> 24, "центр значка должен быть непрозрачным");
    }

    @Test
    @DisplayName("Браузер открывается один раз за запуск, даже если позвали ещё раз")
    void browserOpensOnlyOnce() {
        // Регрессия: значок в трее и вызов при старте звали открытие браузера
        // отдельно, и пользователь получал две вкладки вместо одной. Второй
        // вызов не должен ничего печатать.
        java.io.ByteArrayOutputStream first = new java.io.ByteArrayOutputStream();
        java.io.PrintStream original = System.out;
        try {
            System.setOut(new java.io.PrintStream(first, true, java.nio.charset.StandardCharsets.UTF_8));
            DesktopLauncher.openInBrowser("http://localhost:1/");
        } finally {
            System.setOut(original);
        }

        // Второй вызов обязан быть полностью немым, даже если первый напечатал
        java.io.ByteArrayOutputStream second = new java.io.ByteArrayOutputStream();
        try {
            System.setOut(new java.io.PrintStream(second, true, java.nio.charset.StandardCharsets.UTF_8));
            DesktopLauncher.openInBrowser("http://localhost:2/");
        } finally {
            System.setOut(original);
        }
        assertEquals(0, second.size(),
            "повторный вызов напечатал: ' + second.toString(StandardCharsets.UTF_8) + '");
    }

    private void writeRaw(Path file, long pid, int port) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "pid=" + pid + "\nport=" + port + "\n");
    }
}
