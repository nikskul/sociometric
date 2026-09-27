package ru.sociometric.desktop;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;

/**
 * Значок в системном лотке с меню «Открыть» и «Выход».
 *
 * <p>Окна у приложения нет, а процесс должен жить, пока им пользуются. Без трея
 * пользователь не смог бы завершить работу иначе как через диспетчер задач.
 *
 * <p>Если лотка в системе нет (редкие конфигурации, серверные сессии), вместо
 * него показывается небольшое окно с адресом и кнопкой «Выход». Если недоступно
 * и графическое окружение, приложение всё равно работает: адрес печатается на
 * экран, а выход — по закрытию процесса.
 */
public final class TrayController {

    private static final int ICON_SIZE = 64;

    private final Runnable exitAction;
    private TrayIcon tray;
    private JWindow window;

    private TrayController(Runnable exitAction) {
        this.exitAction = exitAction;
    }

    /**
     * Создаёт значок в трее либо, если он недоступен, запасное окно.
     * Возвращает контроллер, который нужно разрушить при выходе.
     */
    public static TrayController install(String url, Runnable exitAction) {
        TrayController controller = new TrayController(exitAction);
        if (tryInstallTray(controller, url)) {
            return controller;
        }
        if (tryShowFallbackWindow(controller, url)) {
            return controller;
        }
        // Адрес напечатает вызывающий: заодно он откроет вкладку в браузере.
        System.out.println("Значок в трее недоступен, завершение — по закрытию процесса.");
        return controller;
    }

    private static boolean tryInstallTray(TrayController controller, String url) {
        try {
            if (!SystemTray.isSupported() || GraphicsEnvironment.isHeadless()) {
                return false;
            }
            TrayIcon icon = new TrayIcon(AppIcon.image(ICON_SIZE), "Социометрия по Я. Морено",
                    buildMenu(url, controller.exitAction));
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> openInBrowser(url));
            SystemTray.getSystemTray().add(icon);
            controller.tray = icon;
            return true;
        } catch (Exception e) {
            System.err.println("Не удалось создать значок в трее: " + e.getMessage());
            return false;
        }
    }

    private static PopupMenu buildMenu(String url, Runnable exitAction) {
        MenuItem openItem = new MenuItem("Открыть приложение");
        openItem.addActionListener(e -> openInBrowser(url));
        MenuItem exitItem = new MenuItem("Выход");
        exitItem.addActionListener(e -> exitAction.run());

        PopupMenu menu = new PopupMenu();
        menu.add(openItem);
        menu.add(exitItem);
        return menu;
    }

    private static boolean tryShowFallbackWindow(TrayController controller, String url) {
        if (GraphicsEnvironment.isHeadless()) {
            return false;
        }
        try {
            SwingUtilities.invokeAndWait(() -> controller.buildWindow(url));
            return true;
        } catch (Exception e) {
            System.err.println("Не удалось показать окно приложения: " + e.getMessage());
            return false;
        }
    }

    /** Маленькое окно на случай, если трея нет. */
    private void buildWindow(String url) {
        window = new JWindow();
        window.setAlwaysOnTop(true);

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(new Color(244, 246, 249));
        panel.setBorder(javax.swing.BorderFactory.createEmptyBorder(14, 16, 14, 16));

        JLabel title = new JLabel("Социометрия по Я. Морено");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
        JLabel address = new JLabel(url);
        address.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        address.setForeground(new Color(47, 111, 208));

        JButton open = new JButton("Открыть в браузере");
        open.addActionListener(e -> openInBrowser(url));
        JButton exit = new JButton("Выход");
        exit.addActionListener(e -> exitAction.run());

        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.add(open);
        buttons.add(exit);

        panel.add(title, BorderLayout.NORTH);
        panel.add(address, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);

        window.setContentPane(panel);
        window.pack();
        window.setLocationRelativeTo(null);
        window.setVisible(true);
    }

    /** Снимает значок и окно при выходе. */
    public void dispose() {
        if (tray != null) {
            try {
                SystemTray.getSystemTray().remove(tray);
            } catch (Exception e) {
                System.err.println("Не удалось убрать значок из трея: " + e.getMessage());
            }
            tray = null;
        }
        if (window != null) {
            window.setVisible(false);
            window.dispose();
            window = null;
        }
    }

    /**
     * Открытие в браузере живёт в лаунчере и защищено от повторов: иначе
     * значок в трее и вызов при старте могли бы открыть две вкладки.
     */
    private static void openInBrowser(String url) {
        DesktopLauncher.openInBrowser(url);
    }
}
