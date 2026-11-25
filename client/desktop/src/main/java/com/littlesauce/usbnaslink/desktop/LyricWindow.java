package com.littlesauce.usbnaslink.desktop;

import javax.swing.*;
import java.awt.*;
import java.util.prefs.Preferences;

/**
 * 桌面歌词浮窗。
 *
 * <p>定位是「贴着屏幕底部的一条」，不抢焦点、不进任务栏、可拖动。
 * 之所以不做成网页那种滚动歌词条：Windows 上人们习惯把它丢在一边看，
 * 真正需要滚动的场景已经由主播放器窗口覆盖了。
 *
 * <p>两行显示：当前行加粗高亮，下一行灰字预告。没有时间轴的 LRC 也能用
 * （全部按「一行一行翻」处理，只是不准时）。
 *
 * <p>歌词位移：很多人手上的 LRC 和音频差个几百毫秒（抓的时间轴不准、
 * 或者音频前面被剪过一段）。这里存一个「歌词提前多少毫秒」的偏移量，
 * 加减法和主窗口共用同一份持久化值（{@link LrcView#offsetStore()}），
 * 用户在主窗口调完，这条浮窗立刻跟着变。
 */
final class LyricWindow extends JWindow {

    private final JLabel line1 = new JLabel("", SwingConstants.CENTER);
    private final JLabel line2 = new JLabel("", SwingConstants.CENTER);
    private final JPanel root = new JPanel(new GridLayout(2, 1, 0, 2));

    private Lrc lrc = Lrc.EMPTY;
    private int cur = -1;
    private Point dragFrom;

    LyricWindow() {
        super();
        setAlwaysOnTop(true);
        // 半透明浮窗：底色取 Theme.BG_DEEP 再降到 ~91% 不透明，描边用品牌蓝的 35%
        root.setBackground(new Color(Theme.BG_DEEP.getRed(), Theme.BG_DEEP.getGreen(),
                Theme.BG_DEEP.getBlue(), 232));
        root.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(Theme.BRAND.getRed(), Theme.BRAND.getGreen(),
                        Theme.BRAND.getBlue(), 90)),
                Theme.padding(8, 16, 8, 16)));

        line1.setFont(Theme.bold(20));
        line1.setForeground(Theme.FG_TITLE);
        line2.setFont(Theme.ui(15));
        line2.setForeground(Theme.FG_SUB);

        root.add(line1);
        root.add(line2);
        setContentPane(root);
        setSize(820, 86);

        MouseAdapterDrag d = new MouseAdapterDrag();
        root.addMouseListener(d);
        root.addMouseMotionListener(d);
        line1.addMouseListener(d);
        line2.addMouseListener(d);

        placeBottomCenter();
    }

    private void placeBottomCenter() {
        Dimension sc = Toolkit.getDefaultToolkit().getScreenSize();
        setLocation((sc.width - getWidth()) / 2, sc.height - getHeight() - 90);
    }

    /** 拖动整条歌词 */
    private final class MouseAdapterDrag extends java.awt.event.MouseAdapter {
        @Override
        public void mousePressed(java.awt.event.MouseEvent e) {
            dragFrom = e.getPoint();
        }

        @Override
        public void mouseDragged(java.awt.event.MouseEvent e) {
            if (dragFrom == null) return;
            Point p = e.getLocationOnScreen();
            setLocation(p.x - dragFrom.x, p.y - dragFrom.y);
        }
    }

    void setLrc(String raw) {
        lrc = Lrc.parse(raw);
        cur = -1;
        if (lrc.isEmpty()) {
            line1.setText("（这首没有歌词）");
            line2.setText("");
        } else {
            line1.setText(lrc.textAt(0));
            line2.setText(lrc.textAt(1));
        }
    }

    void clear() {
        lrc = Lrc.EMPTY;
        cur = -1;
        line1.setText("");
        line2.setText("");
    }

    /** 按播放位置刷新（毫秒）。偏移量在这里加，调用方不用管。 */
    void tick(long posMs) {
        if (lrc.isEmpty()) return;
        int idx = lrc.indexAt(posMs + LrcView.offsetMs());
        if (idx == cur) return;
        cur = idx;
        if (idx < 0) {
            line1.setText(lrc.textAt(0));
            line2.setText("");
            return;
        }
        // 空行表示间奏，此时把上一句留在上面，下面预告下一句
        String a = lrc.textAt(idx);
        line1.setText(a.isEmpty() && idx > 0 ? lrc.textAt(idx - 1) : a);
        line2.setText(lrc.textAt(idx + 1));
    }

    /** 偏移量变了以后强制重画当前行（不靠下一句的时间戳碰运气） */
    void refresh() {
        cur = -2;
    }
}
