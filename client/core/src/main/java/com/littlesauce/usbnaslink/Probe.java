package com.littlesauce.usbnaslink;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * USBNAS 指纹探测。
 *
 * 判定依据（按可靠度从高到低）：
 *   1) {@code GET /_status} 返回 JSON 里出现 {@code "disks"} 与 {@code "uptime"} —— 这是
 *      WebServer 自己吐的私有接口，别的东西不会长这样；
 *   2) 响应头 {@code Server: <站点名>} —— USBNAS 把用户自定义的站点名写进 Server 行
 *      （HttpServer.java:1076 `h.append("Server: ").append(cfg.title)`），默认值
 *      「一只肥鼠的NAS」。光凭这个不能确定是 USBNAS，但配合 (3) 就足够；
 *   3) 首页 HTML 含 {@code /_status}、{@code usbnas_sid} 之类的本站私有标记。
 *
 * 为什么不直接看端口：8080 上跑着全世界的 Jenkins / Tomcat / Spring Boot，
 * 只认端口必然误报。所以一律发一个真实请求看内容。
 */
public final class Probe {

    private static final Charset UTF8 = StandardCharsets.UTF_8;
    private static final Charset ISO_8859_1 = StandardCharsets.ISO_8859_1;
    private static final Pattern RE_SERVER = Pattern.compile("(?im)^Server:\\s*(.+?)\\s*$");
    private static final Pattern RE_TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern RE_STATUS_CODE = Pattern.compile("^HTTP/\\d\\.\\d\\s+(\\d{3})");
    private static final Pattern RE_VER = Pattern.compile("\\b(20\\d{6})\\b");

    /** 连接超时：局域网内 300ms 足够；超过就是没这个主机或者防火墙丢包 */
    public static final int CONNECT_TIMEOUT = 350;
    /** 读超时：安卓上服务端要跑 root shell 取指标，/_status 偶尔慢到 1.5s */
    public static final int READ_TIMEOUT = 2500;

    /** 一次探测的原始结果 */
    public static final class Raw {
        public int code;
        public String server = "";
        public String body = "";
        public Map<String, String> headers = new HashMap<>();
        public long rttMs;
    }

    /**
     * 只做 TCP 连通性判断（扫描阶段用，代价最小）。
     * 用 connect 成功与否判定，不写任何字节 —— 不给对端留下半个请求。
     */
    public static boolean isOpen(String ip, int port, int timeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            close(s);
        }
    }

    /**
     * 发一个 HTTP/1.0 GET，读到连接关闭或超长为止。
     *
     * 故意用 HTTP/1.0 + Connection: close：USBNAS 的 readRequest 对这个组合是
     * 直接按 Content-Length 吐完就关（HttpServer.java 里全是 Connection: close），
     * 不会进 keep-alive 分支，省得我们在客户端操心复用。
     */
    public static Raw fetch(String ip, int port, String path, int readLimit) throws IOException {
        Socket s = new Socket();
        long t0 = System.nanoTime();
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT);
            s.setSoTimeout(READ_TIMEOUT);
            OutputStream out = s.getOutputStream();
            String req = "GET " + path + " HTTP/1.0\r\n"
                    + "Host: " + ip + ":" + port + "\r\n"
                    + "User-Agent: USBNASLink/1.0\r\n"
                    + "Accept: */*\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(req.getBytes(UTF8));
            out.flush();

            Raw r = new Raw();
            InputStream in = new BufferedInputStream(s.getInputStream(), 4096);
            ByteArrayOutputStream head = new ByteArrayOutputStream(2048);
            int state = 0; // 0 找头部结束, 1 已过头部
            int c;
            ByteArrayOutputStream body = new ByteArrayOutputStream(8192);
            // 头部结束判定：连续两个换行。必须同时认 "\n\n" 和 "\r\n\r\n"。
            //
            // 这里原本写的是 `prev == '\n' && c == '\n'`，即只认 "\n\n"。但 USBNAS
            // 服务端吐的是标准 CRLF 分隔（"\r\n\r\n"），于是这个条件永远不成立，
            // 整个响应（头 + 正文）被一股脑写进 head 缓冲，body 永远是 0 字节。
            // 后果不是"崩"，而是更难查的"静默降级"：looksLikeStatus("") 为 false，
            // classify 退到首页分支，站点能被发现、名字也对，只是 disksText 永远空，
            // 桌面端就一直显示「（未取到，登录后可看实时状态）」。
            // 所以计数连续换行时要跳过 '\r'，让两种行尾都能收尾。
            int nlRun = 0;
            while ((c = in.read()) != -1) {
                if (state == 0) {
                    head.write(c);
                    if (head.size() > 16384) break; // 头部异常大，放弃
                    if (c == '\n') {
                        nlRun++;
                        if (nlRun == 2) {
                            state = 1;
                            parseHead(head.toString("ISO-8859-1"), r);
                            if (r.code == 0) r.code = -1;
                            // 无 body 的响应直接结束
                            if (r.code == 204 || r.code == 304 || (r.code >= 100 && r.code < 200)) break;
                        }
                    } else if (c != '\r') {
                        nlRun = 0; // 正常字符，重新计数
                    }
                    // '\r' 不重置计数：\r\n\r\n → nlRun 只在两个 '\n' 上各加一次
                } else {
                    body.write(c);
                    if (body.size() >= readLimit) break;
                }
            }
            if (state == 0) parseHead(head.toString("ISO-8859-1"), r);
            r.body = decode(body.toByteArray());
            r.rttMs = (System.nanoTime() - t0) / 1_000_000L;
            return r;
        } finally {
            close(s);
        }
    }

    /**
     * 正文解码。
     *
     * USBNAS 服务端（HttpServer.java）写响应头时把 String 直接 getBytes()（默认 UTF-8），
     * 但没有带 charset，而且头部用的是 ISO-8859-1 逐字节透传，结果是：
     *   - 头部 Server: 的值，中文已被拆成「每个 UTF-8 字节 = 一个 latin-1 字符」；
     *   - 正文是干净的 UTF-8。
     * 所以这里先把正文按 UTF-8 解；如果解出来像「Ã¤Â¸Â€」这种双重编码，再回炉一次。
     */
    private static String decode(byte[] bytes) {
        String s = new String(bytes, UTF8);
        if (s.indexOf('\uFFFD') >= 0) {
            // 非法 UTF-8，退回 latin-1 至少不丢字节
            s = new String(bytes, ISO_8859_1);
        }
        return fixMojibake(s);
    }

    /**
     * 把「UTF-8 字节被当 latin-1 读成 String」的乱码还原。
     * 判据：字符串里出现 U+0080..U+00FF 的字符，且这些字符按 latin-1 取字节后
     * 能被严格 UTF-8 解码，且解码结果包含 CJK 或比原串短。
     */
    static String fixMojibake(String s) {
        if (s == null || s.isEmpty()) return s;
        boolean suspect = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0080 && c <= 0x00FF) {
                suspect = true;
                break;
            }
        }
        if (!suspect) return s;
        try {
            byte[] raw = s.getBytes(ISO_8859_1);
            java.nio.charset.CharsetDecoder dec = UTF8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            String fixed = dec.decode(java.nio.ByteBuffer.wrap(raw)).toString();
            // 只在确实变好时才采纳
            if (hasCjk(fixed) || (fixed.length() < s.length() && !hasCjk(s))) return fixed;
        } catch (Exception ignored) {
            // 不是合法 UTF-8，说明原串本来就该那么显示
        }
        return s;
    }

    private static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.UnicodeScript.of(s.charAt(i)) == Character.UnicodeScript.HAN) return true;
        }
        return false;
    }

    private static void parseHead(String head, Raw r) {        String[] lines = head.split("\r?\n");
        if (lines.length == 0) return;
        Matcher m = RE_STATUS_CODE.matcher(lines[0]);
        if (m.find()) {
            try {
                r.code = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        for (int i = 1; i < lines.length; i++) {
            int idx = lines[i].indexOf(':');
            if (idx <= 0) continue;
            String k = lines[i].substring(0, idx).trim().toLowerCase(Locale.ROOT);
            String v = lines[i].substring(idx + 1).trim();
            r.headers.put(k, v);
            if (k.equals("server")) r.server = v;
        }
    }

    // ------------------------------------------------------------------ 判定

    /** 探测结果是否是 USBNAS；不是返回 null，是则填好 ServiceInfo */
    public static ServiceInfo classify(String ip, int port) {
        // 第一枪：/_status。这是最硬的证据。
        ServiceInfo si = null;
        try {
            Raw st = fetch(ip, port, "/_status", 64 * 1024);
            if (st.code == 200 && looksLikeStatus(st.body)) {
                si = new ServiceInfo();
                si.ip = ip;
                si.port = port;
                si.baseUrl = "http://" + ip + ":" + port;
                si.rttMs = st.rttMs;
                si.source = "SCAN";
                si.title = fixMojibake(st.server);
                si.disksText = disksSummary(st.body);
                String v = findVersion(st.body);
                if (!v.isEmpty()) si.version = v;
                return si;
            }
        } catch (Throwable ignored) {
        }

        // 第二枪：首页。服务在跑但 /_status 因为权限（匿名关闭）返回 401 时走这里。
        try {
            Raw idx = fetch(ip, port, "/", 96 * 1024);
            String serverLine = idx.server == null ? "" : idx.server;
            boolean serverOk = serverLine.toLowerCase(Locale.ROOT).contains("nas")
                    || "一只肥鼠的NAS".equals(serverLine);
            boolean bodyOk = idx.body.contains("/_status") || idx.body.contains("usbnas_sid");
            String t = titleOf(idx.body);
            if ((serverOk || bodyOk) && (idx.code == 200 || idx.code == 401 || idx.code == 403)) {
                si = new ServiceInfo();
                si.ip = ip;
                si.port = port;
                si.baseUrl = "http://" + ip + ":" + port;
                si.rttMs = idx.rttMs;
                si.source = "SCAN";
                si.title = fixMojibake(serverLine);
                si.pageTitle = t;
                si.needLogin = idx.code == 401 || idx.code == 403;
                return si;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** /_status 的 JSON 至少要有 disks 和 uptime 两个键，别的接口不会这么长 */
    public static boolean looksLikeStatus(String body) {
        if (body == null) return false;
        String b = body.trim();
        if (!b.startsWith("{")) return false;
        return b.contains("\"disks\"") && (b.contains("\"uptime\"") || b.contains("serviceUptimeText"));
    }

    public static String titleOf(String html) {
        if (html == null) return "";
        Matcher m = RE_TITLE.matcher(html);
        if (!m.find()) return "";
        String t = m.group(1).trim();
        return t.length() > 80 ? t.substring(0, 80) : t;
    }

    /** 从 /_status JSON 里抠磁盘名 + 容量，做成一行字给列表显示 */
    public static String disksSummary(String json) {
        if (json == null) return "";
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("\\{\"name\":\"(.*?)\".*?\"total\":(\\d+),\"free\":(\\d+)\\}").matcher(json);
        while (m.find() && sb.length() < 160) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(m.group(1)).append(' ').append(human(Long.parseLong(m.group(2))))
                    .append("（剩 ").append(human(Long.parseLong(m.group(3)))).append('）');
        }
        return sb.toString();
    }

    public static String findVersion(String json) {
        if (json == null) return "";
        Matcher m = RE_VER.matcher(json);
        return m.find() ? m.group(1) : "";
    }

    public static String human(long bytes) {
        if (bytes <= 0) return "0";
        double b = bytes;
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (b >= 1024 && i < u.length - 1) {
            b /= 1024;
            i++;
        }
        return String.format(Locale.ROOT, i == 0 ? "%.0f%s" : "%.1f%s", b, u[i]);
    }

    static void close(Socket s) {
        try {
            if (s != null) s.close();
        } catch (Throwable ignored) {
        }
    }
}
