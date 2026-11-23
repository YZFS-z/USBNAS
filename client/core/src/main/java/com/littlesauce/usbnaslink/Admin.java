package com.littlesauce.usbnaslink;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 管理设置的读写：站点名称、音乐目录、影视目录。
 *
 * 网页端 /_admin 与这里打的是同一套后端（/_admin/api），权限判定也在服务端，
 * 所以客户端这边只负责「把界面上的选择发过去」，不做任何本地校验 ——
 * 校验一旦分成两份，早晚会出现客户端放行、服务端拒绝的错位。
 */
public final class Admin {

    private Admin() {
    }

    /** 一个可选目录 */
    public static final class DirChoice {
        public String path = "";
        public String label = "";

        @Override
        public String toString() {
            return label;
        }
    }

    /** /_admin/api 的 GET 结果 */
    public static final class State {
        public boolean ok;
        public String error = "";
        /** 是否管理员；false 时下面的字段全是空，界面据此显示「需要管理员」 */
        public boolean admin;
        public boolean logged;
        public String who = "";
        public int level = -1;
        public String levelName = "";

        public String title = "";
        public final List<String> musicDirs = new ArrayList<>();
        public final List<String> videoDirs = new ArrayList<>();
        public final List<String> bookDirs = new ArrayList<>();
        /** 候选目录（各存储根下两层），由服务端枚举，客户端不必拉全盘 */
        public final List<DirChoice> choices = new ArrayList<>();
    }

    /** 一次保存的结果 */
    public static final class Saved {
        public boolean ok;
        public String error = "";
        public String msg = "";
    }

    /** GET /_admin/api */
    public static State fetch(String baseUrl, String cookie, int timeoutMs) {
        State st = new State();
        try {
            HttpURLConnection c = Music.openPublic(baseUrl + "/_admin/api", cookie, timeoutMs);
            int code = c.getResponseCode();
            if (code != 200) {
                st.error = "HTTP " + code;
                return st;
            }
            Map<String, Object> m = Music.Json.parseObject(
                    Music.readAllPublic(c.getInputStream(), 512 * 1024));
            if (m == null) {
                st.error = "bad json";
                return st;
            }
            st.ok = bool(m.get("ok"));
            st.admin = bool(m.get("admin"));
            st.logged = bool(m.get("logged"));
            st.who = str(m.get("who"));
            st.level = (int) num(m.get("level"));
            st.levelName = str(m.get("levelName"));
            st.title = str(m.get("title"));
            readStrings(m.get("musicDirs"), st.musicDirs);
            readStrings(m.get("videoDirs"), st.videoDirs);
            readStrings(m.get("bookDirs"), st.bookDirs);

            Object ds = m.get("dirs");
            if (ds instanceof List) {
                for (Object o : (List<?>) ds) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> d = (Map<?, ?>) o;
                    DirChoice ch = new DirChoice();
                    ch.path = str(d.get("p"));
                    ch.label = str(d.get("n"));
                    if (!ch.path.isEmpty()) st.choices.add(ch);
                }
            }
            return st;
        } catch (Throwable t) {
            st.error = String.valueOf(t);
            return st;
        }
    }

    /** 保存站点名称 */
    public static Saved saveTitle(String baseUrl, String cookie, String title, int timeoutMs) {
        return post(baseUrl, cookie, "title", "title", title, timeoutMs);
    }

    /** 保存音乐目录（多选，路径里不能有换行） */
    public static Saved saveMusicDirs(String baseUrl, String cookie, List<String> dirs, int timeoutMs) {
        return post(baseUrl, cookie, "music", "dirs", joinLines(dirs), timeoutMs);
    }

    /** 保存影视目录（多选，客户端按这里的顺序分块显示） */
    public static Saved saveVideoDirs(String baseUrl, String cookie, List<String> dirs, int timeoutMs) {
        return post(baseUrl, cookie, "video", "dirs", joinLines(dirs), timeoutMs);
    }

    /** 保存图书目录（多选，每个目录在客户端是一层书架） */
    public static Saved saveBookDirs(String baseUrl, String cookie, List<String> dirs, int timeoutMs) {
        return post(baseUrl, cookie, "book", "dirs", joinLines(dirs), timeoutMs);
    }

    private static Saved post(String baseUrl, String cookie, String action,
                              String field, String value, int timeoutMs) {
        Saved out = new Saved();
        try {
            String body = "action=" + URLEncoder.encode(action, "UTF-8")
                    + "&" + field + "=" + URLEncoder.encode(value == null ? "" : value, "UTF-8");
            byte[] bytes = body.getBytes("UTF-8");

            HttpURLConnection c = Music.openPublic(baseUrl + "/_admin/api", cookie, timeoutMs);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
            c.setFixedLengthStreamingMode(bytes.length);
            OutputStream os = c.getOutputStream();
            os.write(bytes);
            os.flush();
            os.close();

            int code = c.getResponseCode();
            if (code != 200) {
                out.error = "HTTP " + code;
                return out;
            }
            Map<String, Object> m = Music.Json.parseObject(
                    Music.readAllPublic(c.getInputStream(), 256 * 1024));
            if (m == null) {
                out.error = "bad json";
                return out;
            }
            out.ok = bool(m.get("ok"));
            out.error = str(m.get("error"));
            out.msg = str(m.get("msg"));
            if (!out.ok && out.error.isEmpty()) out.error = "保存失败";
            return out;
        } catch (Throwable t) {
            out.error = String.valueOf(t);
            return out;
        }
    }

    private static String joinLines(List<String> arr) {
        StringBuilder sb = new StringBuilder();
        if (arr == null) return "";
        for (String s : arr) {
            if (s == null) continue;
            String t = s.trim();
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(t);
        }
        return sb.toString();
    }

    private static void readStrings(Object o, List<String> out) {
        if (!(o instanceof List)) return;
        for (Object v : (List<?>) o) {
            String s = str(v);
            if (!s.isEmpty()) out.add(s);
        }
    }

    // 手写 JSON 解析器返回的是 Object/Map/List/String/Boolean/Double
    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static boolean bool(Object o) {
        return o instanceof Boolean && (Boolean) o;
    }

    private static double num(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0;
    }
}
