package com.littlesauce.usbnaslink.desktop;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * 窗口内的多行歌词（自绘）。
 *
 * <p>手机端播放页是「中间一句大字、上面一句下面是下一句」的三行式，
 * 这里做五行的滚动式：桌面窗口比手机高，五行看起来更从容，也方便
 * 用户一眼扫到前后文。
 *
 * <p>同时兼任<b>歌词位移</b>的全局状态持有者：{@link #offsetMs()} /
 * {@link #setOffsetMs(int)} 是 static 的，因为 {@link LyricWindow}（桌面
 * 浮窗）和本组件（播放器窗口内）必须共用同一份偏移量 —— 用户在任一
 * 侧按了「歌词提前 0.2 秒」，另一边也要立刻跟上，否则就成了两个世界。
 * 落盘用 {@link Preferences}，重启后还记得。
 */
final class LrcView extends JComponent {

    /** 偏移量允许范围：±10 秒。再大就不是「微调」了，说明拿错了歌词。 */
    private static final int OFFSET_LIMIT = 10_000;
    private static final String KEY_OFFSET = "lyric.offset.ms";
    private static final Preferences PREFS = Preferences.userNodeForPackage(LrcView.class);

    private static int offset = Integer.MIN_VALUE;

    /**
     * 歌词相对音频的偏移量（毫秒）。正数＝歌词提前显示。
     *
     * <p>手上有大量 LRC 是网友抓的，时间轴偏几百毫秒很常见；音频前头被
     * 剪过一段的更是能差好几秒。与其让用户去改 LRC 文件，不如给两个
     * 按钮现场拨。
     */
    static int offsetMs() {
        if (offset == Integer.MIN_VALUE) {
            int v = PREFS.getInt(KEY_OFFSET, 0);
            offset = Math.max(-OFFSET_LIMIT, Math.min(OFFSET_LIMIT, v));
        }
        return offset;
    }

    static void setOffsetMs(int ms) {
        int v = Math.max(-OFFSET_LIMIT, Math.min(OFFSET_LIMIT, ms));
        offset = v;
        try {
            PREFS.putInt(KEY_OFFSET, v);
        } catch (Throwable ignored) {
            // 注册表/配置目录写不进去也不能让播放挂掉
        }
    }

    /** 偏移量的可读形式，例如「+0.4s」「-1.2s」「0.0s」 */
    static String offsetLabel() {
        int v = offsetMs();
        String sign = v > 0 ? "+" : v < 0 ? "-" : "";
        int a = Math.abs(v);
        return sign + (a / 1000) + "." + ((a % 1000) / 100) + "s";
    }

    private Lrc lrc = Lrc.EMPTY;
    private int cur = -1;
    private Runnable onOffsetChanged;

    LrcView() {
        setOpaque(true);
        setBackground(Theme.BG_DEEP);
        setFont(Theme.ui(15));
    }

    void setOnOffsetChanged(Runnable r) {
        onOffsetChanged = r;
    }

    void setLrc(String raw) {
        lrc = Lrc.parse(raw);
        cur = -1;
        repaint();
    }

    void clear() {
        lrc = Lrc.EMPTY;
        cur = -1;
        repaint();
    }

    boolean hasLyric() {
        return !lrc.isEmpty();
    }

    /** 偏移量变了以后强制重画当前行 —— 不然要等下一句时间戳到才看得出变化 */
    void refresh() {
        cur = -2;
        repaint();
    }

    /** 按播放位置刷新（毫秒）；偏移量在这里统一加，调用方不用管 */
    void tick(long posMs) {
        if (lrc.isEmpty()) return;
        int idx = lrc.indexAt(posMs + offsetMs());
        if (idx == cur) return;
        cur = idx;
        repaint();
    }

    /** 拨偏移量：delta 毫秒。返回新的可读值。 */
    String nudge(int deltaMs) {
        setOffsetMs(offsetMs() + deltaMs);
        cur = -2;
        repaint();
        if (onOffsetChanged != null) onOffsetChanged.run();
        return offsetLabel();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());

            if (lrc.isEmpty()) {
                g.setColor(Theme.FG_MUTE);
                g.setFont(Theme.ui(14));
                drawCenter(g, "（没有歌词）", getHeight() / 2);
                return;
            }

            final int lines = 5;
            int lh = Math.max(26, getHeight() / lines);
            int mid = getHeight() / 2;
            int base = cur < 0 ? 0 : cur;

            List<String> show = new ArrayList<>();
            List<Integer> rel = new ArrayList<>();
            for (int i = base - lines / 2; i <= base + lines / 2; i++) {
                if (i < 0 || i >= lrc.texts.length) continue;
                String s = lrc.textAt(i);
                if (s.isEmpty()) continue;
                show.add(s);
                rel.add(i - base);
            }
            if (show.isEmpty()) return;

            for (int k = 0; k < show.size(); k++) {
                int d = rel.get(k);
                int y = mid + d * lh;
                boolean on = d == 0;
                g.setFont(on ? Theme.bold(19) : Theme.ui(15));
                g.setColor(on ? Theme.BRAND_LINK
                        : Math.abs(d) == 1 ? Theme.FG_SUB : Theme.FG_MUTE);
                drawCenter(g, show.get(k), y);
            }
        } finally {
            g.dispose();
        }
    }

    private void drawCenter(Graphics2D g, String s, int y) {
        FontMetrics fm = g.getFontMetrics();
        int x = (getWidth() - fm.stringWidth(s)) / 2;
        // y 传的是「行中心」，转成基线
        g.drawString(s, Math.max(6, x), y + fm.getAscent() / 2 - 2);
    }
}
