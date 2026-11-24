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
        /** 相册目录（服务端 1.0.2 起）。旧版服务端不会返回这个字段，这里就留空。 */
        public final List<String> photoDirs = new ArrayList<>();
        /** 候选目录（各存储根下两层），由服务端枚举，客户端不必拉全盘 */
        public final List<DirChoice> choices = new ArrayList<>();
        /**
         * 账号清单（服务端 1.0.5 起，只有管理员拿得到）。
         * 旧服务端不返回这个字段，列表留空即可，界面自然显示「没有其他账号」。
         */
        public final List<Account> accounts = new ArrayList<>();
    }

    /** 服务端上的一个账号 */
    public static final class Account {
        public String name = "";
        public int level = -1;
        public String levelName = "";
        /** 这个账号当前有几个活着的会话（网页/客户端各算一个） */
        public int online;
        /** 是不是当前登录的这个人 */
        public boolean me;
        /** 是否被限定在部分目录里（只对读写等级有意义） */
        public boolean scoped;

        @Override
        public String toString() {
            return name;
        }
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
            readStrings(m.get("photoDirs"), st.photoDirs);

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

            Object us = m.get("users");
            if (us instanceof List) {
                for (Object o : (List<?>) us) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> d = (Map<?, ?>) o;
                    Account a = new Account();
                    a.name = str(d.get("name"));
                    a.level = (int) num(d.get("level"));
                    a.levelName = str(d.get("levelName"));
                    a.online = (int) num(d.get("online"));
                    a.me = bool(d.get("me"));
                    a.scoped = bool(d.get("scoped"));
                    if (!a.name.isEmpty()) st.accounts.add(a);
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

    /**
     * 保存相册目录（多选，每个目录在客户端是一个相册）。
     *
     * 相册是唯一「不配就彻底没有」的库：全盘扫图在手机上会把图标、缓存缩略图、
     * 聊天保存图全卷进来，几千张里没有一张是用户想看的。所以服务端默认空、
     * 客户端也不做任何兜底扫描。
     */
    public static Saved savePhotoDirs(String baseUrl, String cookie, List<String> dirs, int timeoutMs) {
        return post(baseUrl, cookie, "photo", "dirs", joinLines(dirs), timeoutMs);
    }

    // ==================== 账号管理 ====================
    // 三条保护（不能删自己 / 不能把自己降级 / 不能删掉最后一个管理员）都在
    // 服务端做，客户端只负责把服务端的原话显示出来 —— 保护逻辑只有一份。

    /** 建号。password 必须非空。 */
    public static Saved addAccount(String baseUrl, String cookie, String name,
                                   String password, int level, int timeoutMs) {
        return postMulti(baseUrl, cookie, timeoutMs, new String[][]{
                {"action", "useradd"}, {"name", name}, {"password", password},
                {"level", String.valueOf(level)}});
    }

    /** 改等级 / 改密码。password 传空表示不改密码。 */
    public static Saved setAccount(String baseUrl, String cookie, String name,
                                   String password, int level, int timeoutMs) {
        return postMulti(baseUrl, cookie, timeoutMs, new String[][]{
                {"action", "userset"}, {"name", name}, {"password", password},
                {"level", String.valueOf(level)}});
    }

    /** 删号 */
    public static Saved removeAccount(String baseUrl, String cookie, String name, int timeoutMs) {
        return postMulti(baseUrl, cookie, timeoutMs,
                new String[][]{{"action", "userdel"}, {"name", name}});
    }

    /** 踢下线（保留账号，只让已登录的会话失效） */
    public static Saved kickAccount(String baseUrl, String cookie, String name, int timeoutMs) {
        return postMulti(baseUrl, cookie, timeoutMs,
                new String[][]{{"action", "userkick"}, {"name", name}});
    }

    /** 多字段表单 POST；失败一律折成 Saved.error，调用方只判 ok */
    private static Saved postMulti(String baseUrl, String cookie, int timeoutMs, String[][] kv) {
        Saved out = new Saved();
        try {
            StringBuilder body = new StringBuilder();
            for (String[] p : kv) {
                if (body.length() > 0) body.append('&');
                body.append(URLEncoder.encode(p[0], "UTF-8")).append('=')
                    .append(URLEncoder.encode(p[1] == null ? "" : p[1], "UTF-8"));
            }
            byte[] bytes = body.toString().getBytes("UTF-8");

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
            if (!out.ok && out.error.isEmpty()) out.error = "操作失败";
            return out;
        } catch (Throwable t) {
            out.error = String.valueOf(t);
            return out;
        }
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
