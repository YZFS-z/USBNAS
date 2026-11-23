package com.littlesauce.usbnaslink.desktop;

import com.littlesauce.usbnaslink.Music;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 桌面歌词浮窗。
 *
 * 定位是「贴着屏幕底部的一条」，不抢焦点、不进任务栏、可拖动。
 * 之所以不做成网页那种滚动歌词条：Windows 上人们习惯把它丢在一边看，
 * 真正需要滚动的场景已经由主播放器窗口覆盖了。
 *
 * 两行显示：当前行加粗高亮，下一行灰字预告。没有时间轴的 LRC 也能用
 * （全部按「一行一行翻」处理，只是不准时）。
 */
final class LyricWindow extends JWindow {

    private final JLabel line1 = new JLabel("", SwingConstants.CENTER);
    private final JLabel line2 = new JLabel("", SwingConstants.CENTER);
    private final JPanel root = new JPanel(new GridLayout(2, 1, 0, 2));

    private List<long[]> times = new ArrayList<>();
    private List<String> texts = new ArrayList<>();
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

        Font base = Theme.bold(20);
        line1.setFont(base);
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

    /** 拖动整条歌词，位置记在系统属性里由调用方持久化 */
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

    void setLrc(String lrc) {
        times.clear();
        texts.clear();
        cur = -1;
        if (lrc != null) parse(lrc);
        if (texts.isEmpty()) {
            line1.setText("（这首没有歌词）");
            line2.setText("");
        } else {
            line1.setText(texts.get(0));
            line2.setText(texts.size() > 1 ? texts.get(1) : "");
        }
    }

    void clear() {
        times.clear();
        texts.clear();
        cur = -1;
        line1.setText("");
        line2.setText("");
    }

    /** 按播放位置刷新（毫秒） */
    void tick(long posMs) {
        if (times.isEmpty()) return;
        int idx = -1;
        for (int i = 0; i < times.size(); i++) {
            if (times.get(i)[0] <= posMs) idx = i;
            else break;
        }
        if (idx == cur) return;
        cur = idx;
        if (idx < 0) {
            line1.setText(texts.isEmpty() ? "" : texts.get(0));
            line2.setText("");
            return;
        }
        // 空行表示间奏，此时把上一句留在上面，下面预告下一句
        String a = texts.get(idx);
        line1.setText(a.isEmpty() && idx > 0 ? texts.get(idx - 1) : a);
        line2.setText(idx + 1 < texts.size() ? texts.get(idx + 1) : "");
    }

    /**
     * LRC 解析。
     *
     * 与网页端 MUSIC_JS 的 parseLRC 保持同一套规则：支持一行多个时间戳、
     * `[offset:]`、以及没有时间轴的纯文本歌词。这里是 Java 版而不是复用
     * 网页那套，因为桌面端要按毫秒自己驱动刷新，不走 <audio> 的 timeupdate。
     */
    private void parse(String raw) {
        long offset = 0;
        List<long[]> ts = new ArrayList<>();
        List<String> tx = new ArrayList<>();

        String[] lines = raw.split("\\r?\\n");
        for (String ln : lines) {
            String s = ln.trim();
            if (s.isEmpty()) continue;
            // 元信息
            if (s.startsWith("[") && s.indexOf(']') > 0) {
                String inner = s.substring(1, s.indexOf(']'));
                if (inner.startsWith("offset:")) {
                    try {
                        offset = Long.parseLong(inner.substring(7).trim());
                    } catch (NumberFormatException ignored) {
                    }
                    continue;
                }
            }
            List<Long> stamps = new ArrayList<>();
            String rest = s;
            while (rest.startsWith("[")) {
                int close = rest.indexOf(']');
                if (close < 0) break;
                String inner = rest.substring(1, close);
                long t = stamp(inner);
                if (t < 0) break;
                stamps.add(t);
                rest = rest.substring(close + 1);
            }
            if (stamps.isEmpty()) continue;
            String text = rest.trim();
            for (Long t : stamps) {
                ts.add(new long[]{t});
                tx.add(text);
            }
        }

        // 按时间排序（一行多时间戳的情况时间戳本来就递增，这里稳妥起见再排一次）
        Integer[] order = new Integer[ts.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        final List<long[]> fts = ts;
        java.util.Arrays.sort(order, (a, b) -> Long.compare(fts.get(a)[0], fts.get(b)[0]));

        for (Integer i : order) {
            times.add(new long[]{ts.get(i)[0] + offset});
            texts.add(tx.get(i));
        }
    }

    /** `mm:ss.xxx` / `mm:ss:xxx` / `mm:ss` → 毫秒；不是时间戳返回 -1 */
    private static long stamp(String s) {
        if (s == null || s.length() < 4) return -1;
        int c1 = s.indexOf(':');
        if (c1 < 0) return -1;
        try {
            int mm = Integer.parseInt(s.substring(0, c1).trim());
            String rest = s.substring(c1 + 1);
            int frac = 0;
            String secPart = rest;
            int dot = -1;
            for (int i = 0; i < rest.length(); i++) {
                char ch = rest.charAt(i);
                if (ch == '.' || ch == ':') {
                    dot = i;
                    break;
                }
            }
            if (dot >= 0) {
                secPart = rest.substring(0, dot);
                String f = rest.substring(dot + 1);
                if (!f.isEmpty()) {
                    // 两位按百分秒、三位按毫秒
                    if (f.length() >= 3) frac = Integer.parseInt(f.substring(0, 3));
                    else if (f.length() == 2) frac = Integer.parseInt(f) * 10;
                    else frac = Integer.parseInt(f) * 100;
                }
            }
            int ss = Integer.parseInt(secPart.trim());
            return (long) mm * 60000L + ss * 1000L + frac;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
