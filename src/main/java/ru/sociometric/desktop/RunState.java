package ru.sociometric.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Заметка о текущем запуске: pid и порт, по которым второй запуск находит уже
 * открытое приложение.
 *
 * <p>Без этого нажатие на ярлык второй раз поднимал ещё один сервер и ещё одно
 * окно браузера. Теперь второй запуск просто открывает вкладку в уже работающем
 * приложении.
 */
public final class RunState {

    private static final String PID = "pid";
    private static final String PORT = "port";
    private static final String URL = "url";
    private static final String STARTED = "started";

    private final Path file;
    private final long pid;
    private final int port;

    private RunState(Path file, long pid, int port) {
        this.file = file;
        this.pid = pid;
        this.port = port;
    }

    /**
     * Записывает заметку о текущем процессе.
     *
     * <p>Ошибка записи не мешает работе: без заметки приложение всё равно
     * запускается, просто повторное нажатие на ярлык откроет лишнюю копию.
     */
    public static RunState write(Path file, int port) {
        Properties props = new Properties();
        props.setProperty(PID, String.valueOf(ProcessHandle.current().pid()));
        props.setProperty(PORT, String.valueOf(port));
        props.setProperty(URL, DesktopPaths.appUrl(port));
        props.setProperty(STARTED, java.time.LocalDateTime.now().toString());
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "sociometric desktop launcher");
            }
        } catch (IOException e) {
            // Без заметки приложение всё равно работает, просто второй запуск
            // откроет лишнюю копию. Это не повод отказываться в старте.
            System.err.println("Не удалось сохранить состояние запуска: " + e.getMessage());
        }
        return new RunState(file, ProcessHandle.current().pid(), port);
    }

    /**
     * Читает заметку и возвращает её, только если процесс жив и порт отвечает.
     * Иначе возвращает {@code null}: запись устарела, её можно затереть.
     */
    public static RunState readLive(Path file) {
        if (!Files.isReadable(file)) {
            return null;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            return null;
        }
        long pid = parseLong(props.getProperty(PID));
        int port = parseInt(props.getProperty(PORT));
        if (pid <= 0 || port <= 0) {
            return null;
        }
        if (!isProcessAlive(pid) || !isPortOpen(port)) {
            return null;
        }
        return new RunState(file, pid, port);
    }

    /** Убирает заметку при штатном выходе. */
    public void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            System.err.println("Не удалось удалить состояние запуска: " + e.getMessage());
        }
    }

    public int port() {
        return port;
    }

    public long pid() {
        return pid;
    }

    public String url() {
        return DesktopPaths.appUrl(port);
    }

    /** Процесс с таким pid существует и ещё не завершился. */
    public static boolean isProcessAlive(long pid) {
        if (pid <= 0) {
            return false;
        }
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** На указанном порту кто-то принимает соединения. */
    public static boolean isPortOpen(int port) {
        if (port <= 0 || port > 65535) {
            return false;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), 400);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static long parseLong(String value) {
        try {
            return value == null ? -1 : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int parseInt(String value) {
        try {
            return value == null ? -1 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
