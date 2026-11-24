package com.littlesauce.usbnaslink;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录会话。
 *
 * USBNAS 的登录是表单 POST {@code /_login}，字段是 {@code u} / {@code p} / {@code back}，
 * 成功时 303 并下发 {@code Set-Cookie: usbnas_sid=<token>; Path=/; Max-Age=86400}。
 * （HttpServer.java:718-751）
 *
 * 这里手写 Cookie 而不用 CookieManager：
 *   - 桌面端和安卓端都想在 WebView 里复用同一个 sid，CookieManager 在两侧行为不一致；
 *   - 服务端只有一个 cookie，手写反而更好控制、便于落盘/清理。
 */
public final class Session {

    public static final String COOKIE = "usbnas_sid";

    public final String baseUrl;
    private volatile String sid = "";
    private volatile String user = "";

    public Session(String baseUrl) {
        this.baseUrl = stripSlash(baseUrl);
    }

    public String sid() {
        return sid;
    }

    public String user() {
        return user;
    }

    public boolean hasSid() {
        return sid != null && !sid.isEmpty();
    }

    /** 把 sid 交给 WebView 前用它拼 Cookie 头 */
    public String cookieString() {
        return hasSid() ? COOKIE + "=" + sid : "";
    }

    public void setSid(String s) {
        this.sid = s == null ? "" : s;
    }

    public void clear() {
        sid = "";
        user = "";
    }

    /**
     * 表单登录。
     *
     * @return null 表示成功；否则是人类可读的失败原因
     */
    public String login(String u, String p) {
        return login(u, p, false);
    }

    /**
     * 表单登录，可要求「记住该设备」。
     *
     * @param remember true 时表单带上 remember=1，服务端把 Cookie 的 Max-Age
     *                 从一天放成一年。注意这只是「少敲一次密码」——
     *                 服务端会话仍是内存态，服务端一重启照样要重新登录。
     * @return null 表示成功；否则是人类可读的失败原因
     */
    public String login(String u, String p, boolean remember) {
        if (u == null || u.isEmpty()) return "用户名不能为空";
        Map<String, String> form = new LinkedHashMap<>();
        form.put("u", u);
        form.put("p", p == null ? "" : p);
        form.put("back", "/");
        if (remember) form.put("remember", "1");

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(baseUrl + "/_login").openConnection();
            c.setRequestMethod("POST");
            c.setInstanceFollowRedirects(false);          // 303 要自己接，好读 Set-Cookie
            c.setConnectTimeout(Probe.CONNECT_TIMEOUT * 4);
            c.setReadTimeout(Probe.READ_TIMEOUT * 2);
            c.setDoOutput(true);
            byte[] body = encode(form).getBytes(StandardCharsets.UTF_8);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
            c.setRequestProperty("Content-Length", String.valueOf(body.length));
            OutputStream o = c.getOutputStream();
            o.write(body);
            o.flush();
            o.close();

            int code = c.getResponseCode();
            if (code == 303 || code == 302 || code == 200) {
                String sc = c.getHeaderField("Set-Cookie");
                String token = extractSid(sc);
                if (!token.isEmpty()) {
                    sid = token;
                    user = u;
                    return null;
                }
                // 200 也可能只是「已登录」的纯文本；没 cookie 就是失败
                return "服务端没有下发会话（可能是版本不匹配）";
            }
            if (code == 401) return "用户名或密码不对";
            return "登录失败，HTTP " + code;
        } catch (Throwable t) {
            return "连不上 " + baseUrl + "：" + t.getClass().getSimpleName();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * 取一张「一次性交接票」，用来把浏览器带进已登录状态。
     *
     * 服务端 {@code /_login/handoff} 只认 Cookie / Basic Auth 里已经生效的会话，
     * 返回 {@code {"ok":true,"token":"...","ttlMs":120000,"user":"admin"}}。
     * 拿到票之后浏览器只要访问一次
     * {@code /_login/sso?t=<token>&back=<目标页>}，服务端就会种下 Cookie 并 303 跳过去，
     * 票在那个瞬间作废 —— 所以这张票可以安全地放进地址栏、书签、剪贴板。
     *
     * @return 票字符串；取不到（没登录 / 服务端版本旧 / 网络断了）返回 null
     */
    public String handoff() {
        if (!hasSid()) return null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(baseUrl + "/_login/handoff").openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Cookie", cookieString());
            c.setConnectTimeout(Probe.CONNECT_TIMEOUT * 4);
            c.setReadTimeout(Probe.READ_TIMEOUT * 2);
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() < 64 * 1024) bo.write(buf, 0, n);
            in.close();
            String body = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            if (body.indexOf("\"ok\":true") < 0) return null;
            int i = body.indexOf("\"token\":\"");
            if (i < 0) return null;
            i += 9;
            int j = body.indexOf('"', i);
            if (j <= i) return null;
            return body.substring(i, j);
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 用现有 sid 请求一个需要登录的页面，判断会话还活着 */
    public boolean alive() {
        if (!hasSid()) return false;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(baseUrl + "/").openConnection();
            c.setRequestProperty("Cookie", cookieString());
            c.setConnectTimeout(Probe.CONNECT_TIMEOUT * 4);
            c.setReadTimeout(Probe.READ_TIMEOUT * 2);
            int code = c.getResponseCode();
            // 有账号且未登录时首页会 401；只要不是 401 就说明 cookie 有效
            return code != 401;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * 会话到底还认不认这个 sid。
     *
     * {@link #alive()} 只看首页是否 401，可服务端允许匿名读取首页时，首页永远 200，
     * 过期 sid 与有效 sid 分不出来 —— 于是保存歌词之类的写操作会以匿名身份被拒，
     * 用户看到的是「没有该目录的写权限」，而不是「登录已过期」。
     *
     * 这里直接问 /_admin/api 的 logged 字段（匿名访问也返回 200 + logged:false），
     * 状态码无所谓，只看登录态。返回 null 表示问不到（网络不通/服务端太老）。
     */
    public Boolean logged() {
        if (!hasSid()) return Boolean.FALSE;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(baseUrl + "/_admin/api").openConnection();
            c.setRequestProperty("Cookie", cookieString());
            c.setRequestProperty("User-Agent", "USBNASLink/1.0");
            c.setConnectTimeout(Probe.CONNECT_TIMEOUT * 4);
            c.setReadTimeout(Probe.READ_TIMEOUT * 2);
            int code = c.getResponseCode();
            if (code != 200) return null;
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > 8192) break;
            }
            in.close();
            String body = new String(bo.toByteArray(), "UTF-8");
            int i = body.indexOf("\"logged\":");
            if (i < 0) return null;
            int j = i + 9;
            while (j < body.length() && (body.charAt(j) == ' ')) j++;
            return body.startsWith("true", j);
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 退出登录：服务端会 kill session 并让浏览器删 cookie */
    public void logout() {
        if (!hasSid()) {
            clear();
            return;
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(baseUrl + "/_logout").openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("Cookie", cookieString());
            c.setConnectTimeout(Probe.CONNECT_TIMEOUT * 4);
            c.setReadTimeout(Probe.READ_TIMEOUT * 2);
            c.getResponseCode();
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.disconnect();
            clear();
        }
    }

    static String extractSid(String setCookie) {
        if (setCookie == null) return "";
        for (String part : setCookie.split(";")) {
            String t = part.trim();
            if (t.startsWith(COOKIE + "=")) return t.substring(COOKIE.length() + 1).trim();
        }
        return "";
    }

    /** 手写 URL 编码：安卓上 URLEncoder 会把空格编成 +，而本站服务端有一堆 +
     *  兼容补丁，走 %20 更干净（HttpServer.java 里 readForm 专门修过 \\+） */
    static String encode(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(pct(e.getKey())).append('=').append(pct(e.getValue()));
        }
        return sb.toString();
    }

    static String pct(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 255;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '*') {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
            }
        }
        return sb.toString();
    }

    private static String stripSlash(String u) {
        if (u == null) return "";
        String s = u.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** 读一个 URL 的全部内容（调试 / 断言用） */
    public static String get(String url, String cookie) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
            c.setConnectTimeout(Probe.CONNECT_TIMEOUT * 4);
            c.setReadTimeout(Probe.READ_TIMEOUT * 2);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() < 512 * 1024) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
