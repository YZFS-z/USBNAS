package com.littlesauce.usbnaslink.desktop;

import javax.swing.JComponent;
import javax.swing.JTabbedPane;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/**
 * 网页风格的页签 —— 没有凸起的「文件卡」，只有底部一条 2px 的蓝色指示条。
 *
 * <p>对位网页里的 tab 切换：未选 {@code #7d8590}，选中 {@code #e6e9ef} + 下方
 * {@code #2f80ed} 粗线。原生 {@code WindowsTabbedPaneUI} 会在深色底上画出浅灰的
 * 梯形卡片和一圈奇怪的焦点框，和网页差得太远，所以整段重画。
 */
final class FlatTabbedPaneUI extends BasicTabbedPaneUI {

    private static final int PAD_X = 16;
    private static final int H = 38;

    public static ComponentUI createUI(JComponent c) {
        return new FlatTabbedPaneUI();
    }

    @Override
    protected int calculateTabHeight(int tabPlacement, int tabIndex, int fontHeight) {
        return H;
    }

    @Override
    protected int calculateTabWidth(int tabPlacement, int tabIndex, FontMetrics metrics) {
        Insets i = tabPane.getInsets();
        String title = tabPane.getTitleAt(tabIndex);
        return (title == null ? 0 : metrics.stringWidth(title)) + PAD_X * 2;
    }

    @Override
    protected int calculateTabAreaHeight(int tabPlacement, int runCount, int maxTabHeight) {
        return H + 1;
    }

    @Override
    protected Insets getTabInsets(int tabPlacement, int tabIndex) {
        return new Insets(0, PAD_X, 0, PAD_X);
    }

    @Override
    protected void paintTabArea(Graphics g, int tabPlacement, int selectedIndex) {
        g.setColor(Theme.BG_DEEP);
        g.fillRect(0, 0, tabPane.getWidth(), H + 1);
        g.setColor(Theme.LINE);
        g.drawLine(0, H, tabPane.getWidth(), H);
        super.paintTabArea(g, tabPlacement, selectedIndex);
    }

    @Override
    protected void paintTabBackground(Graphics g0, int tabPlacement, int tabIndex,
                                      int x, int y, int w, int h, boolean selected) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean hot = tabIndex == getRolloverTab();
            if (selected) {
                g.setColor(Theme.BG_CARD);
                g.fillRoundRect(x + 4, 6, w - 8, h - 8, 8, 8);
            } else if (hot) {
                g.setColor(Theme.BG_CARD);
                g.fillRoundRect(x + 4, 6, w - 8, h - 8, 8, 8);
            }
        } finally {
            g.dispose();
        }
    }

    @Override
    protected void paintTabBorder(Graphics g, int tabPlacement, int tabIndex,
                                  int x, int y, int w, int h, boolean selected) {
        // 不画边框：指示条在 paintText 里单独补
    }

    @Override
    protected void paintText(Graphics g0, int tabPlacement, Font font, FontMetrics metrics,
                             int tabIndex, String title, Rectangle textRect,
                             boolean selected) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setFont(Theme.ui(13));
            boolean hot = tabIndex == getRolloverTab();
            g.setColor(selected ? Theme.FG_TITLE : hot ? Theme.FG_SUB : Theme.FG_DIM);
            FontMetrics fm = g.getFontMetrics();
            int tx = textRect.x + (textRect.width - fm.stringWidth(title)) / 2;
            int ty = textRect.y + (textRect.height - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString(title, tx, ty);
        } finally {
            g.dispose();
        }
        if (selected) {
            Rectangle r = getTabBounds(tabPane, tabIndex);
            Graphics2D gg = (Graphics2D) g0.create();
            try {
                gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                gg.setColor(Theme.BRAND);
                gg.fillRoundRect(r.x + PAD_X / 2, H - 3, r.width - PAD_X, 3, 3, 3);
            } finally {
                gg.dispose();
            }
        }
    }

    @Override
    protected void paintFocusIndicator(Graphics g, int tabPlacement, Rectangle[] rects,
                                       int tabIndex, Rectangle iconRect, Rectangle textRect,
                                       boolean selected) {
        // 网页没有焦点框，这里也不画
    }

    @Override
    protected void paintContentBorder(Graphics g, int tabPlacement, int selectedIndex) {
        g.setColor(Theme.BG_DEEP);
        g.fillRect(0, tabPane.getHeight() - 2, tabPane.getWidth(), 2);
    }

    @Override
    protected int getTabLabelShiftY(int tabPlacement, int tabIndex, boolean isSelected) {
        return 0;
    }

    @Override
    protected void installDefaults() {
        super.installDefaults();
        tabPane.setBackground(Theme.BG_DEEP);
        tabPane.setOpaque(true);
        highlight = Theme.LINE;
        lightHighlight = Theme.LINE;
        shadow = Theme.LINE;
        darkShadow = Theme.LINE;
        focus = Theme.BG_DEEP;
    }

    /** 页面切换时不要闪一下「选中卡片」的浅色 */
    @Override
    protected void paintTab(Graphics g, int tabPlacement, Rectangle[] rects, int tabIndex,
                            Rectangle iconRect, Rectangle textRect) {
        super.paintTab(g, tabPlacement, rects, tabIndex, iconRect, textRect);
    }

    /**
     * 内容区底色 —— BasicTabbedPaneUI 是通过 {@code tabPane.getBackground()} 取的，
     * 这里只保证页签容器自身的底色是网页 body 那档深色，避免内容区露出白块。
     */
    @Override
    protected void paintContentBorderBottomEdge(Graphics g, int tabPlacement, int selectedIndex,
                                                int x, int y, int w, int h) {
        // 网页内容区没有下边框，这里也不画
    }
}
