package ru.sociometric.desktop;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * Значок приложения: та же схема, что и на вкладке «Социограмма», — цветные
 * кружки участников и стрелки выборов на синем фоне.
 *
 * <p>Рисуется кодом, а не берётся файлом: нечего тащить в сборку, а при смене
 * цветов статусов значок меняется вместе с ними.
 */
final class AppIcon {

    private AppIcon() {
    }

    /** Квадратный значок заданного размера. */
    static BufferedImage image(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        double s = size;
        g.setColor(new Color(0x2F6FD0));
        g.fill(new RoundRectangle2D.Double(0, 0, s, s, s * 0.22, s * 0.22));

        // лидер в центре и четыре участника по орбите — раскладка «звезда»
        double cx = s * 0.5;
        double cy = s * 0.52;
        double rLead = s * 0.13;
        double rOther = s * 0.10;
        double[][] nodes = {
                {cx, cy, rLead},
                {s * 0.22, s * 0.25, rOther},
                {s * 0.78, s * 0.25, rOther},
                {s * 0.22, s * 0.79, rOther},
                {s * 0.78, s * 0.79, rOther}
        };
        // цвета статусов совпадают с палитрой социограммы
        int[] fills = {0x2E9E4F, 0x8FD19A, 0xBCD6F5, 0xF5C26B, 0xE88B8B};

        // стрелки под кружками, чтобы их концы не торчали наружу
        g.setColor(new Color(0xFFFFFF, true));
        g.setStroke(new BasicStroke((float) (s * 0.022), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (double[] node : nodes) {
            g.draw(new Line2D.Double(cx, cy, node[0], node[1]));
        }

        g.setStroke(new BasicStroke((float) (s * 0.018)));
        for (int i = 0; i < nodes.length; i++) {
            double[] node = nodes[i];
            Ellipse2D circle = new Ellipse2D.Double(node[0] - node[2], node[1] - node[2],
                    node[2] * 2, node[2] * 2);
            g.setColor(new Color(fills[i]));
            g.fill(circle);
            g.setColor(new Color(0x1D2430));
            g.draw(circle);
        }
        g.dispose();
        return image;
    }
}
