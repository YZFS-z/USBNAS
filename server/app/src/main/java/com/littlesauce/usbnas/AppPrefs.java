package com.littlesauce.usbnas;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * App 级设置：目前只有「标题」一个可改项。
 *
 * 标题同时作用于 App 首页、网页 &lt;title&gt;、网页顶栏大标题和登录页，
 * 所以必须存在一个全局唯一的地方，NasService 起服务时直接读这里，
 * 用户在 App 里改完立刻热更新到运行中的 HttpServer.Configuration。
 */
public final class AppPrefs {

    private static final String PREF = "usbnas_app";
    private static final String KEY_TITLE = "title";
    private static final String KEY_ANTI_BURN = "anti_burn";
    private static final String KEY_DIM_PCT = "dim_pct";
    private static final String KEY_USB_SLEEP = "usb_sleep";
    private static final String KEY_USB_SLEEP_SEC = "usb_sleep_sec";
    private static final String KEY_MUSIC_DIRS = "music_dirs";
    private static final String KEY_VIDEO_DIRS = "video_dirs";
    private static final String KEY_BOOK_DIRS = "book_dirs";
    /** 相册目录，与音乐/影视/图书各自独立（默认空 = 不扫，见 photoDirs 的说明） */
    private static final String KEY_PHOTO_DIRS = "photo_dirs";
    /** 自动更新开关（默认关，见文件末尾 autoUpdate 的注释） */
    private static final String KEY_AUTO_UPDATE = "auto_update";
    /** 跳过某个版本 */
    private static final String KEY_SKIP_VERSION = "skip_version";
    /** 上次检查更新的 wall clock 毫秒 */
    private static final String KEY_LAST_UPDATE_CHECK = "last_update_check";
    private static final String KEY_THEME = "theme";
    /** 运行环境自检是否已经全部通过。只在全绿时写 true，之后不再弹自检窗。 */
    private static final String KEY_SELF_CHECK_DONE = "self_check_done";
    /**
     * 登录会话的落盘快照。
     *
     * 为什么必须落盘：会话表原来只活在 HttpServer 的内存里，服务端进程一重启
     * （改设置触发、App 被 MIUI 杀、手机重启）所有 sid 立刻作废，而客户端
     * SharedPreferences 里还存着那个死掉的 sid —— 于是影视/音乐/相册全部 401，
     * 用户看到的是「影片放不了」「一直在跳歌」，而不是「登录过期了」。
     * 存到 SharedPreferences 之后，重新起来还能认原来的 sid。
     *
     * 存的是纯文本，一行一条：token \t 用户名 \t 过期时刻(wall clock 毫秒)。
     * 这里存的是 bearer 凭据，和 sid 本身同等敏感；它落在 App 私有目录
     * （/data/data/com.littlesauce.usbnas/shared_prefs），和非 root 设备上
     * Cookie 的暴露面一致。
     */
    private static final String KEY_SESSIONS = "sessions";

    /** 标题最大长度，太长会把首页顶栏和网页 header 挤坏 */
    public static final int TITLE_MAX = 24;

    /** U 盘空闲休眠的默认等待时间（秒） */
    public static final int USB_SLEEP_SEC_DEFAULT = 60;

    private AppPrefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 默认标题：保持和以前一样的 USBNAS，升级后观感不变 */
    public static String title(Context c) {
        String s = sp(c).getString(KEY_TITLE, "");
        if (s == null || s.trim().isEmpty()) return defaultTitle(c);
        return s.trim();
    }

    /** 用户没设过标题时用应用名，这样改名后 App 里显示的名字也不会打架 */
    public static String defaultTitle(Context c) {
        try {
            CharSequence n = c.getApplicationContext().getApplicationInfo()
                    .loadLabel(c.getPackageManager());
            if (n != null && n.length() > 0) return n.toString();
        } catch (Throwable ignored) {
        }
        return "USBNAS";
    }

    /** 保存标题；传空串表示恢复默认。返回最终生效的标题。 */
    public static String saveTitle(Context c, String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.length() > TITLE_MAX) s = s.substring(0, TITLE_MAX);
        sp(c).edit().putString(KEY_TITLE, s).apply();
        return title(c);
    }

    // ---------- 防烧屏 ----------

    /** 防烧屏默认关：这台机器是当 NAS 长期亮屏用的，但开关交给自己决定 */
    public static boolean antiBurn(Context c) {
        return sp(c).getBoolean(KEY_ANTI_BURN, false);
    }

    public static void setAntiBurn(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_ANTI_BURN, on).apply();
    }

    /**
     * 屏保遮罩的透明度（0~90）。
     *
     * 上限压到 90 不是保守 —— OLED 全黑像素是真正断电的，压到 100 就等于息屏，
     * 想看一眼状态还得先关屏保，反而更麻烦。
     */
    public static int dimPercent(Context c) {
        int v = sp(c).getInt(KEY_DIM_PCT, 70);
        if (v < 0) v = 0;
        if (v > 90) v = 90;
        return v;
    }

    public static void setDimPercent(Context c, int pct) {
        if (pct < 0) pct = 0;
        if (pct > 90) pct = 90;
        sp(c).edit().putInt(KEY_DIM_PCT, pct).apply();
    }

    // ---------- U 盘空闲休眠 ----------

    /**
     * 外接盘空闲后是否允许停转。默认开 —— 机械盘常转的嗡嗡声是实打实的噪音，
     * 而休眠对正常使用是无感的（实测唤醒耗时 25ms）。
     */
    public static boolean usbSleep(Context c) {
        return sp(c).getBoolean(KEY_USB_SLEEP, true);
    }

    public static void setUsbSleep(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_USB_SLEEP, on).apply();
    }

    /** 空闲多少秒后挂起。太快会影响连续浏览，太慢等于没效果。 */
    public static int usbSleepSec(Context c) {
        int v = sp(c).getInt(KEY_USB_SLEEP_SEC, USB_SLEEP_SEC_DEFAULT);
        if (v < 10) v = 10;
        if (v > 600) v = 600;
        return v;
    }

    public static void setUsbSleepSec(Context c, int sec) {
        if (sec < 10) sec = 10;
        if (sec > 600) sec = 600;
        sp(c).edit().putInt(KEY_USB_SLEEP_SEC, sec).apply();
    }

    // ---------- 音乐目录 ----------

    /**
     * 管理员预设的音乐根目录（绝对路径，可多个）。
     *
     * 为什么要存在这里而不是让客户端每次自己扫：曲库动辄几千首，
     * 客户端每次打开都遍历全盘，机械盘要转很久，手机也要跟着耗电。
     * 管理员在 App 里指一次，之后所有客户端（EXE/APK）拉同一份列表即可。
     *
     * 存成换行分隔的纯文本，不引 JSON —— 路径里出现引号、反斜杠的概率
     * 远高于出现换行的概率，换行分隔是这里最省事的编码。
     */
    public static java.util.List<String> musicDirs(Context c) {
        String s = sp(c).getString(KEY_MUSIC_DIRS, "");
        java.util.List<String> out = new java.util.ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    public static void setMusicDirs(Context c, java.util.List<String> dirs) {
        StringBuilder sb = new StringBuilder();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        if (dirs != null) {
            for (String d : dirs) {
                String t = d == null ? "" : d.trim();
                if (t.isEmpty()) continue;
                if (!seen.add(t)) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(t);
            }
        }
        sp(c).edit().putString(KEY_MUSIC_DIRS, sb.toString()).apply();
    }

    /** 曲库整个目录扫描的最长耗时，超过就给客户端返回已扫到的部分 */
    public static final int MUSIC_SCAN_MAX = 4000;

    // ---------- 影视目录 ----------

    /**
     * 管理员预设的影视根目录（绝对路径，可多个）。
     *
     * 为什么允许多个：电影常见布局是「盘0/电影」「盘1/电影」各放一部分，
     * 只给一个根就得把两个盘的内容拼进同一个目录，实际做不到。
     * 客户端按 dir 分组显示，正好对应「电影板块里盘0、盘1 两块」。
     *
     * 编码方式与音乐目录一致（换行分隔），两者各自独立：
     * 音乐和影视往往是不同目录，混用一个键会让管理员改一个丢一个。
     */
    public static java.util.List<String> videoDirs(Context c) {
        String s = sp(c).getString(KEY_VIDEO_DIRS, "");
        java.util.List<String> out = new java.util.ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    public static void setVideoDirs(Context c, java.util.List<String> dirs) {
        StringBuilder sb = new StringBuilder();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        if (dirs != null) {
            for (String d : dirs) {
                String t = d == null ? "" : d.trim();
                if (t.isEmpty()) continue;
                if (!seen.add(t)) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(t);
            }
        }
        sp(c).edit().putString(KEY_VIDEO_DIRS, sb.toString()).apply();
    }

    /**
     * 管理员预设的图书根目录（绝对路径，可多个）。
     *
     * 和音乐 / 影视目录各自独立存一个键，理由同 videoDirs：管理员改一个不该丢另一个。
     * 图书单独列出来不是为了「再抄一遍」，而是因为书库的形态和影音差得远：
     * 一个书目录动辄几万个小文件（散装 txt、按作者分层的 epub），
     * 扫一次比影音贵得多，必须由管理员明确指路，不能让客户端猜。
     */
    public static java.util.List<String> bookDirs(Context c) {
        String s = sp(c).getString(KEY_BOOK_DIRS, "");
        java.util.List<String> out = new java.util.ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    public static void setBookDirs(Context c, java.util.List<String> dirs) {
        StringBuilder sb = new StringBuilder();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        if (dirs != null) {
            for (String d : dirs) {
                String t = d == null ? "" : d.trim();
                if (t.isEmpty()) continue;
                if (!seen.add(t)) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(t);
            }
        }
        sp(c).edit().putString(KEY_BOOK_DIRS, sb.toString()).apply();
    }

    // ==================== 相册目录 ====================

    /**
     * 管理员预设的相册根目录（绝对路径，可多个）。
     *
     * <p>独立一个键，理由同 bookDirs：相册和影音书的扫描代价完全不是一回事 ——
     * 一个相册目录可能几万张图，缩略图列表必须分页/限数，管理员得能单独关掉它。
     *
     * <p>默认空：**不回落全盘扫**。手机上 jpg/png 满地都是（图标、缓存、截图），
     * 一旦默认扫全盘，「相册」页会变成一堆垃圾图，比空着更糟。
     */
    public static java.util.List<String> photoDirs(Context c) {
        String s = sp(c).getString(KEY_PHOTO_DIRS, "");
        java.util.List<String> out = new java.util.ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    public static void setPhotoDirs(Context c, java.util.List<String> dirs) {
        StringBuilder sb = new StringBuilder();
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        if (dirs != null) {
            for (String d : dirs) {
                String t = d == null ? "" : d.trim();
                if (t.isEmpty()) continue;
                if (!seen.add(t)) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(t);
            }
        }
        sp(c).edit().putString(KEY_PHOTO_DIRS, sb.toString()).apply();
    }

    // ==================== 自动更新 ====================
    //
    // 这三个键只控制「App 自己去 GitHub 看一眼有没有新版本」，与 NAS 服务的
    // 文件、账号、目录设置毫无关系。默认关闭的理由：
    //   1) 这台机器常常是一台常年插着盘、跑在角落里的旧手机，主人在意的是
    //      「别乱跑流量、别自己弹窗」，而不是尽快升级；
    //   2) 关闭状态下 Updater 一个请求都不发，用户能明确知道自己没被联网。
    // 用户主动打开后，才按 UPDATE_MIN_GAP_MS 的节奏检查。

    /** 自动更新开关，默认关 */
    public static boolean autoUpdate(Context c) {
        return sp(c).getBoolean(KEY_AUTO_UPDATE, false);
    }

    public static void setAutoUpdate(Context c, boolean on) {
        sp(c).edit().putBoolean(KEY_AUTO_UPDATE, on).apply();
    }

    /** 用户点了「跳过这个版本」的版本号；空串表示没跳过 */
    public static String skipVersion(Context c) {
        String s = sp(c).getString(KEY_SKIP_VERSION, "");
        return s == null ? "" : s;
    }

    public static void setSkipVersion(Context c, String v) {
        sp(c).edit().putString(KEY_SKIP_VERSION, v == null ? "" : v).apply();
    }

    /** 上次检查时间（wall clock，因为要跨重启保留） */
    public static long lastUpdateCheck(Context c) {
        return sp(c).getLong(KEY_LAST_UPDATE_CHECK, 0L);
    }

    public static void setLastUpdateCheck(Context c, long ms) {
        sp(c).edit().putLong(KEY_LAST_UPDATE_CHECK, ms).apply();
    }

    // ==================== 配色主题 ====================
    //
    // 一个字符串管三端：网页端（服务端自己吐的 HTML）、exe 客户端、APK 客户端
    // 都读这一个值，这样「在同一台 NAS 上，手机看到的底色和电脑看到的底色一样」。
    //
    // 为什么存在服务端而不是各端自己存：
    //   客户端拿不到别的设备的偏好；而用户要的恰恰是「同设备的网页端与客户端
    //   看起来一致」。所以把主题当成一项站点级配置（和站点名称同性质）。
    //
    // 三种取值：dark（默认）/ light / ink（水墨屏）。
    // 非法值一律回落到 dark，避免某个旧客户端写进奇怪字符串把页面搞坏。

    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_INK = "ink";

    /** 当前主题，默认 dark */
    public static String theme(Context c) {
        String s = sp(c).getString(KEY_THEME, THEME_DARK);
        return normalizeTheme(s);
    }

    /** 保存主题；返回值是真正落盘的规范化结果 */
    public static String setTheme(Context c, String raw) {
        String s = normalizeTheme(raw);
        sp(c).edit().putString(KEY_THEME, s).apply();
        return s;
    }

    /** 把任意输入钳成 dark / light / ink 三者之一 */
    public static String normalizeTheme(String raw) {
        if (raw == null) return THEME_DARK;
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (THEME_LIGHT.equals(s) || THEME_INK.equals(s)) return s;
        return THEME_DARK;
    }

    /** 主题的中文名，给按钮和提示用 */
    public static String themeName(String mode) {
        String s = normalizeTheme(mode);
        if (THEME_LIGHT.equals(s)) return "日间";
        if (THEME_INK.equals(s)) return "水墨";
        return "夜间";
    }

    // ==================== 登录会话的落盘 ====================
    //
    // 见 KEY_SESSIONS 的注释。两个方法都很土：读一整行、按 tab 切三段。
    // 用 json 或序列化不是不行，但这里只有「token/用户名/过期时刻」三个字段，
    // 引一个解析器反而多出一堆可能出错的路径 —— 存坏了就整行丢掉重新登录即可。

    /**
     * 读出上次落盘的会话，形如 {@code token -> [用户名, 过期时刻]}。
     *
     * 过期的条目在这里就丢掉，不往上传；读不出来（文件被改坏、格式变了）
     * 一律当空表处理，宁可让用户重登一次，也不要拿半个解析结果去建会话。
     */
    public static java.util.Map<String, Object[]> loadSessions(Context c) {
        java.util.Map<String, Object[]> out = new java.util.LinkedHashMap<>();
        String raw;
        try {
            raw = sp(c).getString(KEY_SESSIONS, "");
        } catch (Throwable t) {
            return out;
        }
        if (raw == null || raw.isEmpty()) return out;
        long now = System.currentTimeMillis();
        for (String line : raw.split("\n")) {
            if (line == null || line.isEmpty()) continue;
            String[] f = line.split("\t");
            if (f.length < 3) continue;
            String tok = f[0];
            String user = f[1];
            if (tok.isEmpty() || user.isEmpty()) continue;
            long exp;
            try {
                exp = Long.parseLong(f[2].trim());
            } catch (Throwable t) {
                continue;
            }
            if (exp <= now) continue;   // 已经过期，不用留着占地方
            out.put(tok, new Object[]{user, exp});
        }
        return out;
    }

    /**
     * 把当前会话表写回去。
     *
     * @param entries 形如 {@code token -> [用户名, 过期时刻(wall clock 毫秒)]}
     */
    public static void saveSessions(Context c, java.util.Map<String, Object[]> entries) {
        StringBuilder sb = new StringBuilder();
        if (entries != null) {
            for (java.util.Map.Entry<String, Object[]> e : entries.entrySet()) {
                Object[] v = e.getValue();
                if (v == null || v.length < 2) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(e.getKey()).append('\t').append(String.valueOf(v[0]))
                        .append('\t').append(String.valueOf(v[1]));
            }
        }
        sp(c).edit().putString(KEY_SESSIONS, sb.toString()).apply();
    }

    // ---------- 首次启动自检 ----------

    /** 自检是否已经全绿过；只要还有一项没过就不写，下次启动继续提醒 */
    public static boolean selfCheckDone(Context c) {
        return sp(c).getBoolean(KEY_SELF_CHECK_DONE, false);
    }

    public static void setSelfCheckDone(Context c) {
        sp(c).edit().putBoolean(KEY_SELF_CHECK_DONE, true).apply();
    }
}
