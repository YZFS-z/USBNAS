package com.littlesauce.usbnaslink.desktop;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;

/**
 * 登录凭据（用户名 / 密码）的内存 + 磁盘存放。
 *
 * <p>为什么桌面端必须手里有明文密码：libvlc 3.0 的 http 插件只认
 * {@code http-user-agent / http-referrer / http-proxy / http-reconnect / http-proxy-pwd}
 * 这几项，<b>没有</b> {@code :http-cookie}，也没有任何「加一个 Authorization 请求头」的入口
 * （拆开 libvlc 的 libhttp_plugin.dll 数过字符串，确认过）。要让 libvlc 播需要登录的文件，
 * 唯一能走通的路是 URL userinfo —— {@code http://user:pass@host:8080/...}，libvlc 会把它
 * 转成 HTTP Basic 请求头，服务端 {@code Config.auth()} 那边本来就认 Basic（WebDAV 用的同一条路）。
 *
 * <p>于是这里保存凭据有两个用途：一是给 libvlc 拼 URL，二是让「换歌不用重新登录」。
 * 磁盘上的值只做了 Base64，不是加密 —— 存的是本机 {@code %APPDATA%} 里、权限跟当前
 * 用户走的普通文件，和浏览器保存密码的威胁模型一致。不勾「记住密码」时只留在内存里，
 * 关掉程序就没了。
 */
final class Creds {

    private Creds() {
    }

    /** 内存表：authority（host:port）→ {user, pass} */
    private static final java.util.HashMap<String, String[]> MEM = new java.util.HashMap<>();

    /** 只在用户勾了「记住密码」时写盘 */
    private static final String FILE = "creds.properties";

    private static File file() {
        String appdata = System.getenv("APPDATA");
        File d = appdata != null && !appdata.isEmpty()
                ? new File(appdata, "USBNASLink")
                : new File(System.getProperty("user.home", "."), ".usbnaslink");
        if (!d.isDirectory() && !d.mkdirs()) d = new File(".").getAbsoluteFile();
        return new File(d, FILE);
    }

    /**
     * 记下一台服务器的凭据。
     *
     * @param baseUrl 形如 {@code http://172.23.41.107:8080}
     * @param persist true 时同时写盘；false 只留在内存里（本次运行有效）
     */
    static synchronized void remember(String baseUrl, String user, String pass, boolean persist) {
        String auth = authority(baseUrl);
        if (auth == null || user == null || user.isEmpty()) return;
        MEM.put(auth, new String[]{user, pass == null ? "" : pass});
        if (!persist) return;
        Properties p = load();
        p.setProperty(auth, b64(user) + ":" + b64(pass == null ? "" : pass));
        save(p);
    }

    /** 忘掉一台服务器（登出时用；已写盘的也一并抹掉） */
    static synchronized void forget(String baseUrl) {
        String auth = authority(baseUrl);
        if (auth == null) return;
        MEM.remove(auth);
        Properties p = load();
        if (p.remove(auth) != null) save(p);
    }

    /** 有没有这台服务器的凭据 */
    static synchronized boolean has(String baseUrl) {
        String auth = authority(baseUrl);
        if (auth == null) return false;
        if (MEM.containsKey(auth)) return true;
        return load().getProperty(auth) != null;
    }

    /**
     * 给一个裸地址按需补上 userinfo。
     *
     * <p>例：{@code http://172.23.41.107:8080/mnt/media_rw/…/x.mp3}
     * → {@code http://admin:123456@172.23.41.107:8080/mnt/media_rw/…/x.mp3}
     *
     * @return 没有该主机的凭据、或地址里已经带了 {@code @} 时原样返回
     */
    static synchronized String inject(String url) {
        if (url == null || url.isEmpty()) return url;
        int scheme = url.indexOf("://");
        if (scheme <= 0) return url;
        int hostStart = scheme + 3;
        int pathStart = url.indexOf('/', hostStart);
        String auth = pathStart < 0 ? url.substring(hostStart) : url.substring(hostStart, pathStart);
        if (auth.indexOf('@') >= 0) return url;          // 调用方自己拼过了
        String[] up = lookup(auth);
        if (up == null || up[0] == null || up[0].isEmpty()) return url;
        String userinfo = pct(up[0]) + ":" + pct(up[1]) + "@";
        return url.substring(0, hostStart) + userinfo + auth
                + (pathStart < 0 ? "" : url.substring(pathStart));
    }

    /** 同 {@link #inject}，但明确给出 baseUrl 以便在没有 URL 时也能问 */
    static synchronized String[] lookupFor(String baseUrl) {
        String auth = authority(baseUrl);
        return auth == null ? null : lookup(auth);
    }

    // ------------------------------------------------------------------ 内部

    private static String[] lookup(String authority) {
        String[] v = MEM.get(authority);
        if (v != null) return v;
        String raw = load().getProperty(authority);
        if (raw == null) return null;
        int c = raw.indexOf(':');
        if (c <= 0) return null;
        try {
            String[] r = {unb64(raw.substring(0, c)), unb64(raw.substring(c + 1))};
            MEM.put(authority, r);                        // 读过就缓存，免得每次翻盘
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 从 {@code http://host:port/…} 里抠出 {@code host:port} */
    private static String authority(String url) {
        if (url == null || url.isEmpty()) return null;
        int scheme = url.indexOf("://");
        int hostStart = scheme < 0 ? 0 : scheme + 3;
        int pathStart = url.indexOf('/', hostStart);
        String auth = pathStart < 0 ? url.substring(hostStart) : url.substring(hostStart, pathStart);
        int at = auth.lastIndexOf('@');
        if (at >= 0) auth = auth.substring(at + 1);        // 去掉可能已有的 userinfo
        return auth.isEmpty() ? null : auth;
    }

    private static Properties load() {
        Properties p = new Properties();
        File f = file();
        if (!f.isFile()) return p;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            p.load(in);
        } catch (Throwable ignored) {
        } finally {
            close(in);
        }
        return p;
    }

    private static void save(Properties p) {
        OutputStream out = null;
        try {
            out = new FileOutputStream(file());
            p.store(out, "USBNASLink saved credentials (base64 only)");
        } catch (Throwable ignored) {
        } finally {
            close(out);
        }
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String s) {
        return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }

    /** userinfo 里只能放 RFC 3986 的 unreserved，其余全百分号编码（密码里常有 @ : / 空格） */
    private static String pct(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xf, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return sb.toString();
    }
}
