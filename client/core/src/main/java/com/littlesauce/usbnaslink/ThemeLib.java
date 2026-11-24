package com.littlesauce.usbnaslink;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配色主题（三端共用一份真源，存在服务端）。
 *
 * <p>为什么主题要放服务端：用户要的是「同一台设备上，网页端和 exe/APK 客户端的
 * 日间/夜间一致」。客户端各自存一份偏好就不可能一致 —— 手机上点了日间，电脑上
 * 还是夜间，用户看到的是两台设备两种颜色。所以把主题当成站点级配置（和站点名称
 * 同性质），三端都读服务端的这一个值。
 *
 * <p>三种取值：
 * <ul>
 *   <li>{@link #DARK} 夜间（默认，也是网页端的基准样式）</li>
 *   <li>{@link #LIGHT} 日间</li>
 *   <li>{@link #INK} 水墨屏（灰阶，给墨水屏设备用）</li>
 * </ul>
 *
 * <p>服务端端点：{@code GET /_theme} 读，{@code POST /_theme theme=…} 写。
 * 读不需要登录；写需要（服务端判，匿名改配色是个恶作剧入口）。
 */
public final class ThemeLib {

    public static final String DARK = "dark";
    public static final String LIGHT = "light";
    public static final String INK = "ink";

    /** 默认主题。与网页端 {@code prefTheme} 的初值一致 */
    public static final String DEFAULT = DARK;

    private ThemeLib() {
    }

    /** 服务端返回的当前主题 + 一些附加信息 */
    public static final class State {
        public boolean ok;
        public String error = "";
        /** dark / light / ink，永远已规范化 */
        public String mode = DEFAULT;

        public boolean isDark() {
            return DARK.equals(mode);
        }

        public boolean isLight() {
            return LIGHT.equals(mode);
        }

        public boolean isInk() {
            return INK.equals(mode);
        }
    }

    /**
     * 读服务端当前主题。
     *
     * <p>失败（旧版服务端没这个端点、网络断）时返回 {@code ok=false}，调用方应当
     * 退回本地偏好 —— 不要因为一个配色把界面挡住。
     */
    public static State fetch(String baseUrl, String cookie, int timeoutMs) {
        State st = new State();
        String text = get(baseUrl + "/_theme", cookie, timeoutMs);
        Map<String, Object> m = Music.Json.parseObject(text);
        if (m == null) {
            st.error = "连不上服务端";
            return st;
        }
        Object ok = m.get("ok");
        if (!(ok instanceof Boolean) || !((Boolean) ok)) {
            st.error = str(m.get("error"));
            if (st.error == null || st.error.isEmpty()) st.error = "服务端没给主题";
            return st;
        }
        st.ok = true;
        st.mode = normalize(str(m.get("theme")));
        return st;
    }

    /**
     * 写服务端主题。
     *
     * @return null 表示成功；否则是人类可读的失败原因
     */
    public static String save(String baseUrl, String cookie, String mode, int timeoutMs) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("theme", normalize(mode));
        String text = post(baseUrl + "/_theme", cookie, Session.encode(form), timeoutMs);
        Map<String, Object> m = Music.Json.parseObject(text);
        if (m == null) return "连不上服务端";
        Object ok = m.get("ok");
        if (ok instanceof Boolean && (Boolean) ok) return null;
        String err = str(m.get("error"));
        return err == null || err.isEmpty() ? "保存失败" : err;
    }

    /** 把任意输入钳成 dark / light / ink 三者之一 */
    public static String normalize(String raw) {
        if (raw == null) return DEFAULT;
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (LIGHT.equals(s) || INK.equals(s)) return s;
        return DARK;
    }

    /** 主题中文名，给按钮和提示用 */
    public static String name(String mode) {
        String s = normalize(mode);
        if (LIGHT.equals(s)) return "日间";
        if (INK.equals(s)) return "水墨";
        return "夜间";
    }

    /** 点一下之后应该切到哪个（日夜两态之间翻；水墨时回夜间） */
    public static String next(String mode) {
        return LIGHT.equals(normalize(mode)) ? DARK : LIGHT;
    }

    // ---- 内部小工具：与 Music / BookLib 那套同款，避免对外暴露私有实现 ----

    private static String get(String url, String cookie, int timeoutMs) {
        java.net.HttpURLConnection c = null;
        try {
            c = Music.openPublic(url, cookie, timeoutMs);
            try {
                return Music.readAllPublic(c.getInputStream(), 1024 * 1024);
            } catch (java.io.IOException ioe) {
                // 404/403 之类：读错误流，服务端在错误体里也给了 JSON
                java.io.InputStream e = c.getErrorStream();
                if (e == null) return null;
                try {
                    return Music.readAllPublic(e, 1024 * 1024);
                } finally {
                    e.close();
                }
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String post(String url, String cookie, String body, int timeoutMs) {
        try {
            return Music.postFormPublic(url, cookie, body, timeoutMs);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
