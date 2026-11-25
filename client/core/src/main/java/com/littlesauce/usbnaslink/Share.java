package com.littlesauce.usbnaslink;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文件分享短链（三端共用的服务端能力）。
 *
 * <p>协议在服务端 HttpServer.java：
 * <ul>
 *   <li>{@code POST /_share/new}（form: p=服务端绝对路径）→
 *       {@code {"ok":true,"code":..,"path":..,"dir":bool,"url":..}}</li>
 *   <li>{@code POST /_share/drop}（form: code=分享码）→ {@code {"ok":true,"removed":bool}}</li>
 *   <li>{@code GET /_share/list}（仅管理员）→
 *       {@code {"ok":true,"shares":[{"code","path","dir","name","url"}]}}</li>
 * </ul>
 *
 * <p>分享码是服务端内存表、重启即失效；创建要 canWrite，列表要管理员。
 * 这里只做 HTTP  Transport + JSON 解析，UI 在三端各自实现。
 */
public final class Share {

    /** 一条分享 */
    public static final class Item {
        public String code = "";
        public String path = "";
        public String name = "";
        public String url = "";
        public boolean dir;

        /** 管理列表里显示的一行 */
        public String line() {
            String n = name == null || name.isEmpty() ? (path == null ? "" : path) : name;
            return (dir ? "[目录] " : "") + n + "   " + code;
        }
    }

    /** 创建分享的结果 */
    public static final class Created {
        public boolean ok;
        public String error = "";
        public String code = "";
        public String path = "";
        public String url = "";
        public boolean dir;
    }

    /** 列表结果 */
    public static final class ListResult {
        public boolean ok;
        public String error = "";
        public final List<Item> items = new ArrayList<>();
    }

    /** 撤销结果 */
    public static final class Dropped {
        public boolean ok;
        public boolean removed;
        public String error = "";
    }

    private static volatile String lastError = "";

    /** 最近一次调用的错误原因（比 ok=false 更具体，便于 UI 显示） */
    public static String lastError() {
        return lastError;
    }

    private Share() {
    }

    /** 创建分享链接 */
    public static Created create(String baseUrl, String cookie, String path, int timeoutMs) {
        Created r = new Created();
        if (baseUrl == null || baseUrl.isEmpty() || path == null || path.isEmpty()) {
            r.error = "地址或路径为空";
            lastError = r.error;
            return r;
        }
        String form = "p=" + Session.pct(path);
        Map<String, Object> m = post(baseUrl + "/_share/new", cookie, form, timeoutMs);
        if (m == null) {
            r.error = lastError.isEmpty() ? "服务端返回的不是 JSON" : lastError;
            return r;
        }
        r.ok = isTrue(m.get("ok"));
        r.code = nz(str(m.get("code")));
        r.path = nz(str(m.get("path")));
        r.url = nz(str(m.get("url")));
        r.dir = isTrue(m.get("dir"));
        if (!r.ok) {
            String e = str(m.get("error"));
            r.error = e == null || e.isEmpty() ? "创建分享失败" : e;
        }
        return r;
    }

    /** 撤销分享（刷新链接即作废） */
    public static Dropped drop(String baseUrl, String cookie, String code, int timeoutMs) {
        Dropped r = new Dropped();
        if (baseUrl == null || baseUrl.isEmpty() || code == null || code.isEmpty()) {
            r.error = "地址或分享码为空";
            lastError = r.error;
            return r;
        }
        String form = "code=" + Session.pct(code);
        Map<String, Object> m = post(baseUrl + "/_share/drop", cookie, form, timeoutMs);
        if (m == null) {
            r.error = lastError.isEmpty() ? "服务端返回的不是 JSON" : lastError;
            return r;
        }
        r.ok = isTrue(m.get("ok"));
        r.removed = isTrue(m.get("removed"));
        if (!r.ok) {
            String e = str(m.get("error"));
            r.error = e == null || e.isEmpty() ? "撤销失败" : e;
        }
        return r;
    }

    /** 列出全部分享（仅管理员） */
    public static ListResult list(String baseUrl, String cookie, int timeoutMs) {
        ListResult r = new ListResult();
        if (baseUrl == null || baseUrl.isEmpty()) {
            r.error = "地址为空";
            lastError = r.error;
            return r;
        }
        HttpURLConnection c = null;
        try {
            c = Music.openPublic(baseUrl + "/_share/list", cookie, timeoutMs);
            String body = Music.readAllPublic(c.getInputStream(), 512 * 1024);
            Map<String, Object> m = Music.Json.parseObject(body);
            if (m == null) {
                r.error = "服务端返回的不是 JSON";
                return r;
            }
            r.ok = isTrue(m.get("ok"));
            if (!r.ok) {
                String e = str(m.get("error"));
                r.error = e == null || e.isEmpty() ? "列分享失败" : e;
                return r;
            }
            Object arr = m.get("shares");
            if (arr instanceof List) {
                for (Object o : (List<?>) arr) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> im = (Map<?, ?>) o;
                    Item it = new Item();
                    it.code = nz(str(im.get("code")));
                    it.path = nz(str(im.get("path")));
                    it.name = nz(str(im.get("name")));
                    it.url = nz(str(im.get("url")));
                    it.dir = isTrue(im.get("dir"));
                    r.items.add(it);
                }
            }
            return r;
        } catch (Throwable t) {
            r.ok = false;
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            lastError = r.error;
            return r;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** POST 一个 form，返回解析后的 JSON 对象；失败置 lastError 并返回 null */
    private static Map<String, Object> post(String url, String cookie, String form, int timeoutMs) {
        lastError = "";
        HttpURLConnection c = null;
        try {
            byte[] data = form.getBytes(StandardCharsets.UTF_8);
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(Probe.CONNECT_TIMEOUT * 4);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
            c.setRequestProperty("User-Agent", "USBNASLink/1.0");
            if (cookie != null && !cookie.isEmpty()) {
                c.setRequestProperty("Cookie", Session.cookieHeader(cookie));
            }
            c.setFixedLengthStreamingMode(data.length);
            OutputStream out = c.getOutputStream();
            out.write(data);
            out.flush();
            out.close();
            String body = Music.readAllPublic(c.getInputStream(), 512 * 1024);
            Map<String, Object> m = Music.Json.parseObject(body);
            if (m == null) lastError = "服务端返回的不是 JSON";
            return m;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String str(Object o) {
        return o instanceof String ? (String) o : null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static boolean isTrue(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Number) return ((Number) o).intValue() != 0;
        if (o instanceof String) return "true".equalsIgnoreCase((String) o) || "1".equals(o);
        return false;
    }
}
