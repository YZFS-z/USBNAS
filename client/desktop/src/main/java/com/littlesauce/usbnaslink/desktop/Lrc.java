package com.littlesauce.usbnaslink.desktop;

import java.util.ArrayList;
import java.util.List;

/**
 * LRC 解析结果（时间轴 + 文本）。
 *
 * <p>抽成独立类是因为现在有两个消费者：{@link LyricWindow}（桌面悬浮歌词条）
 * 和 {@link PlayerWindow}（窗口内的大字歌词）。两边要是各自 parse 一遍，
 * 时间戳容错规则（一行多时间戳 / {@code [offset:]} / 没有时间轴的纯文本）
 * 迟早会走散，出问题时还得对两套代码。
 */
final class Lrc {

    static final Lrc EMPTY = new Lrc(new long[0], new String[0]);

    final long[] times;
    final String[] texts;

    private Lrc(long[] t, String[] s) {
        times = t;
        texts = s;
    }

    boolean isEmpty() {
        return times.length == 0;
    }

    String textAt(int i) {
        return i >= 0 && i < texts.length ? texts[i] : "";
    }

    /**
     * 给定播放位置，返回当前应该高亮的那一行；第一行之前返回 -1。
     *
     * <p>用二分而不是从 0 线性扫：歌词几百行、每 200ms 调一次，
     * 线性扫在长歌词上会白烧一点 CPU，二分反正也不难写。
     */
    int indexAt(long posMs) {
        if (times.length == 0 || posMs < times[0]) return -1;
        int lo = 0;
        int hi = times.length - 1;
        int ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= posMs) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    /**
     * 解析 LRC。
     *
     * <p>与网页端 MUSIC_JS 的 parseLRC 保持同一套规则：支持一行多个时间戳、
     * {@code [offset:]}、以及没有时间轴的纯文本歌词（全部按「一行一行翻」，
     * 只是不准时）。
     */
    static Lrc parse(String raw) {
        if (raw == null || raw.isEmpty()) return EMPTY;
        long offset = 0;
        List<long[]> ts = new ArrayList<>();
        List<String> tx = new ArrayList<>();

        String[] lines = raw.split("\\r?\\n");
        for (String ln : lines) {
            String s = ln.trim();
            if (s.isEmpty()) continue;
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
                long t = stamp(rest.substring(1, close));
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
        if (ts.isEmpty()) return EMPTY;

        Integer[] order = new Integer[ts.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        final List<long[]> fts = ts;
        java.util.Arrays.sort(order, (a, b) -> Long.compare(fts.get(a)[0], fts.get(b)[0]));

        long[] outT = new long[order.length];
        String[] outS = new String[order.length];
        for (int i = 0; i < order.length; i++) {
            outT[i] = ts.get(order[i])[0] + offset;
            outS[i] = tx.get(order[i]);
        }
        return new Lrc(outT, outS);
    }

    /** {@code mm:ss.xxx} / {@code mm:ss:xxx} / {@code mm:ss} → 毫秒；不是时间戳返回 -1 */
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
