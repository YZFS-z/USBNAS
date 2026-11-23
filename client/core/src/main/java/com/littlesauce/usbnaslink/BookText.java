package com.littlesauce.usbnaslink;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;

/**
 * 一本书解开之后的纯文本。
 *
 * <p>服务端 /_books/text 负责把 docx / doc / epub / txt 统统变成纯文本，
 * 客户端只负责排版。这样两端（Swing 与 Android）不需要各带一套 Word 解析器，
 * 也不用为了看一本 docx 去引 Apache POI 那种几十 MB 的库。
 *
 * <p>专门有一个 {@link #pdf} 标记：PDF 的文本层要完整解析器才能拿到，
 * 服务端不做，客户端应该改走「内嵌 PDF 视图 + 原始文件流」那条路。
 */
public final class BookText {

    /** 正文上限，和服务端 BOOK_TEXT_MAX/3 对齐，防一次性撑爆内存 */
    public static final int MAX_CHARS = 8 * 1024 * 1024 / 3;

    public boolean ok;
    public String error;
    /** 文件名（带扩展名） */
    public String name = "";
    /** 小写扩展名 */
    public String ext = "";
    /** 去掉扩展名的书名 */
    public String title = "";
    /** plain / docx / doc / epub —— 服务端用了哪种抽取方式 */
    public String format = "";
    /** 服务端是否因为太大而截断 */
    public boolean truncated;
    /** 这本书是 PDF，服务端不抽文本，要走 PDF 视图 */
    public boolean pdf;
    public String text = "";

    public boolean isEmpty() {
        return text == null || text.trim().isEmpty();
    }

    public int chars() {
        return text == null ? 0 : text.length();
    }

    /**
     * 取一本书的正文。
     *
     * @param baseUrl 形如 http://192.168.1.9:8080
     * @param cookie  doLogin 拿到的 sid，可为 null
     * @param filePath 服务端绝对路径，例如 /storage/emulated/0/Books/a.docx
     */
    public static BookText fetch(String baseUrl, String cookie, String filePath, int timeoutMs) {
        BookText bt = new BookText();
        if (baseUrl == null || baseUrl.isEmpty() || filePath == null || filePath.isEmpty()) {
            bt.error = "地址为空";
            return bt;
        }
        HttpURLConnection c = null;
        try {
            // 这里必须用 encodePath（按段编码、保留 '/'），不能用 pct()：
            // pct() 走 URLEncoder，会把整条路径的 / 全变成 %2F，服务端
            // resolve() 拿到的就是 "%2Fstorage%2F..." 这种怪东西，直接 404/空正文。
            // 这个坑 BookLib.fileUrl 已经踩过一次。
            String url = baseUrl + "/_books/text?f=" + Music.encodePathPublic(filePath);
            c = Music.openPublic(url, cookie, timeoutMs);
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = Music.readAllPublic(in, MAX_CHARS * 3 + 4096);
            parse(body, bt);
            if (!bt.ok && bt.error == null) bt.error = "HTTP " + code;
        } catch (IOException e) {
            bt.error = String.valueOf(e.getMessage());
        } catch (Throwable t) {
            bt.error = String.valueOf(t);
        } finally {
            if (c != null) {
                try { c.disconnect(); } catch (Throwable ignored) {}
            }
        }
        return bt;
    }

    private static void parse(String json, BookText bt) {
        if (json == null) return;
        java.util.Map<String, Object> m = Music.Json.parseObject(json);
        if (m == null) {
            bt.error = "返回内容不是 JSON";
            return;
        }
        // 注意：Music.Json.parseObject 返回的是 List<Map<String,Object>> 的第一项，
        // 不是 Map！这是踩过的坑 —— 直接当 Map 用会取到 null，
        // 表现是 ok=true 但所有字段都是空/default。
        bt.ok = bool(m.get("ok"));
        Object err = m.get("error");
        if (err != null) bt.error = String.valueOf(err);
        bt.pdf = bool(m.get("pdf"));
        bt.truncated = bool(m.get("truncated"));
        // 服务端 jsonEsc() 不包引号，值里带 '.' 之类字符时会被裸吐出来，
        // Music.Json.bare() 已经能收成字符串；这里的 str() 只做兜底，
        // 顺带把「解析成 true 的脏值」还原成空串，别把 true 显示到界面上。
        bt.name = clean(str(m.get("name")));
        bt.ext = clean(str(m.get("ext")));
        bt.title = clean(str(m.get("title")));
        bt.format = clean(str(m.get("format")));
        bt.text = str(m.get("text"));
        if (bt.text == null) bt.text = "";
    }

    private static boolean bool(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        return o != null && "true".equalsIgnoreCase(String.valueOf(o));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 把 "true"/"false" 这种被误解析出来的布尔串当作「没有值」 */
    private static String clean(String s) {
        if (s == null) return "";
        if ("true".equals(s) || "false".equals(s)) return "";
        return s;
    }
    /** 给界面上「这一章一共多少字」之类的地方用 */
    public String summary() {
        if (!ok) return error == null ? "打不开" : error;
        StringBuilder sb = new StringBuilder();
        sb.append(chars()).append(" 字");
        if (!format.isEmpty()) sb.append("  ·  ").append(format);
        if (truncated) sb.append("  ·  已截断");
        return sb.toString();
    }
}
