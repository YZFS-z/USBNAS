package com.littlesauce.usbnaslink.desktop;

import javax.swing.AbstractButton;
import javax.swing.ButtonModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/**
 * 扁平按钮 —— 对应网页里两种按钮：
 *
 * <ul>
 *   <li>{@code primary=true} → 主按钮，底 {@code #2f80ed}、字白，悬停压暗到
 *       {@code #2463b8}（网页 {@code .search button}）</li>
 *   <li>{@code primary=false} → 次按钮，底透明、描 {@code #232a34}、字
 *       {@code #9aa4b2}，悬停底色 {@code #171b22}、描边转 {@code #2f3a48}（网页
 *       {@code .root} 的悬停态）</li>
 * </ul>
 *
 * <p>为什么不用 {@code JButton.setBackground}：Windows LAF 的 {@code WindowsButtonUI}
 * 在 {@code paintButtonPressed} 里会**无视**你设的底色重画一遍渐变，结果出现
 * 「平时是深色、一按就变浅灰」的跳变。自绘一个 BasicButtonUI 是最省事、跨 LAF 一致的做法。
 *
 * <p>圆角 9px 与网页 {@code border-radius:9~10px} 对齐；因为 Swing 的
 * {@code fillRoundRect} 在低分辨率下会把 1px 描边吃掉，所以描边用给定的
 * {@link #setArc} 值单独画一遍。
 */
final class FlatButtonUI extends BasicButtonUI {

    private final boolean primary;
    private int arc = 9;
    private int padH = 14;
    private int padV = 7;

    FlatButtonUI(boolean primary) {
        this.primary = primary;
    }

    FlatButtonUI arc(int a) {
        this.arc = a;
        return this;
    }

    FlatButtonUI padding(int h, int v) {
        this.padH = h;
        this.padV = v;
        return this;
    }

    /** 让按钮自己算出一个够大的首选尺寸：原生 UI 的边距是给方块按钮准备的，太窄 */
    @Override
    public java.awt.Dimension getPreferredSize(JComponent c) {
        AbstractButton b = (AbstractButton) c;
        FontMetrics fm = b.getFontMetrics(b.getFont());
        String text = b.getText() == null ? "" : b.getText();
        int w = fm.stringWidth(text) + padH * 2;
        int h = Math.max(fm.getHeight() + padV * 2, 30);
        return new java.awt.Dimension(w, h);
    }

    @Override
    public void paint(Graphics g0, JComponent c) {
        AbstractButton b = (AbstractButton) c;
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            ButtonModel m = b.getModel();
            boolean on = m.isEnabled();
            boolean hot = on && (m.isRollover() || m.isArmed() || m.isSelected());
            boolean down = on && m.isArmed() && m.isPressed();

            Color fill;
            Color edge;
            Color text;

            if (primary) {
                fill = !on ? Theme.BG_HOVER : down ? Theme.BRAND_DIM : hot ? Theme.BRAND_DIM : Theme.BRAND;
                edge = fill;
                text = on ? Color.WHITE : Theme.FG_MUTE;
            } else {
                fill = !on ? Theme.BG_DEEP : hot ? Theme.BG_CARD : Theme.BG_DEEP;
                edge = !on ? Theme.LINE : hot ? Theme.LINE_SOFT : Theme.LINE;
                text = on ? (hot ? Theme.FG_TITLE : Theme.FG_SUB) : Theme.FG_MUTE;
            }

            int w = b.getWidth();
            int h = b.getHeight();

            g.setColor(fill);
            g.fillRoundRect(0, 0, w - 1, h - 1, arc * 2, arc * 2);

            g.setColor(edge);
            g.drawRoundRect(0, 0, w - 1, h - 1, arc * 2, arc * 2);

            // 焦点框只画一条虚线内圈，不抢主色
            if (b.isFocusOwner() && on) {
                g.setColor(primary ? new Color(0xff, 0xff, 0xff, 110) : Theme.BRAND_LINK);
                g.drawRoundRect(2, 2, w - 5, h - 5, arc * 2 - 4, arc * 2 - 4);
            }

            String t = b.getText();
            if (t != null && !t.isEmpty()) {
                g.setFont(b.getFont());
                g.setColor(text);
                FontMetrics fm = g.getFontMetrics();
                Rectangle r = new Rectangle(0, 0, w, h);
                int tx = r.x + (r.width - fm.stringWidth(t)) / 2;
                int ty = r.y + (r.height - fm.getHeight()) / 2 + fm.getAscent();
                // 按下时文字下移 1px，给一点点触感
                g.drawString(t, tx, down ? ty + 1 : ty);
            }

            if (b.getIcon() != null) {
                int iw = b.getIcon().getIconWidth();
                int ih = b.getIcon().getIconHeight();
                int tw = t == null ? 0 : g.getFontMetrics().stringWidth(t);
                int x = (w - iw - (tw > 0 ? 6 + tw : 0)) / 2;
                b.getIcon().paintIcon(b, g, x, (h - ih) / 2);
            }
        } finally {
            g.dispose();
        }
    }

    /** 让所有主按钮都用同一套：省得每个窗口各写一遍 */
    static JButton primary(String text) {
        JButton b = new JButton(text);
        b.setUI(new FlatButtonUI(true));
        b.setFont(Theme.ui(13));
        b.setFocusPainted(false);
        b.setContentAreaFilled(false);
        b.setOpaque(false);
        b.setBorderPainted(false);
        b.setRolloverEnabled(true);
        return b;
    }

    static JButton ghost(String text) {
        JButton b = new JButton(text);
        b.setUI(new FlatButtonUI(false));
        b.setFont(Theme.ui(13));
        b.setFocusPainted(false);
        b.setContentAreaFilled(false);
        b.setOpaque(false);
        b.setBorderPainted(false);
        b.setRolloverEnabled(true);
        return b;
    }
}
