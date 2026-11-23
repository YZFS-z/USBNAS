package com.littlesauce.usbnaslink.desktop;

import javax.swing.AbstractButton;
import javax.swing.ButtonModel;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.plaf.basic.BasicCheckBoxUI;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/**
 * 深色勾选框 —— 网页里那一排 {@code .adm-cand} 勾选点的等价物：
 * 16px 圆角小方块，未选是描边 {@code #232a34}，选中是 {@code #2f80ed} 底 + 白勾。
 *
 * <p>原生 {@code WindowsCheckBoxUI} 画的是 13px 的实心方块，在 {@code #0e1116}
 * 的底上看着像「一块浅灰色补丁」，所以在深色主题里必须自绘。
 */
final class FlatCheckUI extends BasicCheckBoxUI {

    private static final int BOX = 16;

    @Override
    public void paint(Graphics g0, JComponent c) {
        AbstractButton b = (AbstractButton) c;
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            ButtonModel m = b.getModel();
            boolean on = m.isEnabled();
            boolean sel = m.isSelected();
            boolean hot = on && m.isRollover();

            int h = b.getHeight();
            int boxY = (h - BOX) / 2 + 1;
            int boxX = 1;

            if (sel) {
                g.setColor(on ? Theme.BRAND : Theme.BG_HOVER);
                g.fillRoundRect(boxX, boxY, BOX, BOX, 8, 8);
                g.setColor(on ? Theme.BRAND : Theme.LINE_SOFT);
                g.drawRoundRect(boxX, boxY, BOX, BOX, 8, 8);

                g.setColor(on ? Color.WHITE : Theme.FG_MUTE);
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int x = boxX + 4;
                int y = boxY + 8;
                g.drawLine(x, y, x + 3, y + 3);
                g.drawLine(x + 3, y + 3, x + 8, y - 4);
            } else {
                g.setColor(hot ? Theme.BG_CARD : Theme.BG_DEEP);
                g.fillRoundRect(boxX, boxY, BOX, BOX, 8, 8);
                g.setColor(on ? (hot ? Theme.LINE_SOFT : Theme.LINE) : Theme.LINE);
                g.drawRoundRect(boxX, boxY, BOX, BOX, 8, 8);
            }

            String t = b.getText();
            if (t != null && !t.isEmpty()) {
                g.setFont(b.getFont());
                g.setColor(on ? (sel || hot ? Theme.FG_TITLE : Theme.FG_SUB) : Theme.FG_MUTE);
                FontMetrics fm = g.getFontMetrics();
                Rectangle r = new Rectangle(BOX + 10, 0, b.getWidth() - BOX - 10, h);
                g.drawString(t, r.x, r.y + (r.height - fm.getHeight()) / 2 + fm.getAscent());
            }

            if (b.isFocusOwner()) {
                g.setColor(Theme.BRAND_LINK);
                g.drawRoundRect(boxX - 2, boxY - 2, BOX + 3, BOX + 3, 10, 10);
            }
        } finally {
            g.dispose();
        }
    }

    @Override
    public java.awt.Dimension getPreferredSize(JComponent c) {
        AbstractButton b = (AbstractButton) c;
        FontMetrics fm = b.getFontMetrics(b.getFont());
        String t = b.getText() == null ? "" : b.getText();
        int w = BOX + 10 + (t.isEmpty() ? 0 : fm.stringWidth(t)) + 4;
        int h = Math.max(fm.getHeight() + 8, BOX + 8);
        return new java.awt.Dimension(w, h);
    }

    static JCheckBox make(String text, boolean on) {
        JCheckBox c = new JCheckBox(text, on);
        c.setUI(new FlatCheckUI());
        c.setFont(Theme.ui(13));
        c.setFocusPainted(false);
        c.setOpaque(false);
        c.setBorderPainted(false);
        return c;
    }
}
