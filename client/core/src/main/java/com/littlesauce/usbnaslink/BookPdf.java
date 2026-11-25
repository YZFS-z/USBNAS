package com.littlesauce.usbnaslink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;

/**
 * 服务端渲染好的 PDF 单页。
 *
 * <p>为什么不在客户端自己解析 PDF：桌面端要引 PDFBox（安装包直接翻倍），
 * 安卓端要引 PdfRenderer（其实有，但两端各写一套翻页逻辑很浪费）。
 * 而服务端本来就是 Android，自带 {@code android.graphics.pdf.PdfRenderer}，
 * 让它把页渲成 JPEG、客户端当图片翻，一行额外依赖都不用加。
 *
 * <p>服务端端点：{@code /_books/pdf?f=<路径>&page=N&w=1240}，
 * 另有一个 {@code &meta=1} 只回页数。
 */
public final class BookPdf {

    /** 一页图的上限。1240 宽的 A4 渲染成 JPEG 一般 150–400KB，给到 16MB 足够兜住异形页面 */
    public static final int MAX_BYTES = 16 * 1024 * 1024;

    /** 页数（meta 成功时有效） */
    public int pages;
    public String error;

    /** 拿页数。开一本书先问一次，用来画页码条和总数。 */
    public static int pageCount(String baseUrl, String cookie, String filePath, int timeoutMs) {
        BookPdf r = meta(baseUrl, cookie, filePath, timeoutMs);
        return r.error == null ? r.pages : -1;
    }

    public static BookPdf meta(String baseUrl, String cookie, String filePath, int timeoutMs) {
        BookPdf r = new BookPdf();
        String body = text(baseUrl, cookie, filePath, "&meta=1", timeoutMs);
        if (body == null) {
            r.error = "连不上服务端";
            return r;
        }
        java.util.Map<String, Object> m = Music.Json.parseObject(body);
        if (m == null) {
            r.error = "返回内容不是 JSON";
            return r;
        }
        Object ok = m.get("ok");
        if (!(ok instanceof Boolean) || !((Boolean) ok)) {
            Object e = m.get("error");
            r.error = e == null ? "服务端拒绝了请求" : String.valueOf(e);
            return r;
        }
        Object pg = m.get("pages");
        r.pages = pg instanceof Number ? ((Number) pg).intValue() : 0;
        if (r.pages <= 0) r.error = "这份 PDF 一页都没有";
        return r;
    }

    /**
     * 取第 page 页（0 基）的 JPEG 字节。
     *
     * @param wantW 期望宽度，服务端会钳到 1600 以内；&lt;=0 时用服务端默认 1240
     * @return null 表示失败，原因在 {@link #lastError()} 里（照 {@code Music} 那套写法，
     *         因为这里要返回 byte[] 又要给原因，两样都塞进一个返回对象太绕）
     */
    public static byte[] fetchPage(String baseUrl, String cookie, String filePath,
                                   int page, int wantW, int timeoutMs) {
        lastError = "";
        if (baseUrl == null || baseUrl.isEmpty() || filePath == null || filePath.isEmpty()) {
            lastError = "地址为空";
            return null;
        }
        String q = "&page=" + Math.max(0, page) + (wantW > 0 ? "&w=" + wantW : "");
        HttpURLConnection c = null;
        try {
            String url = baseUrl + "/_books/pdf?f=" + Music.encodePathPublic(filePath) + q;
            c = Music.openPublic(url, cookie, timeoutMs);
            int code = c.getResponseCode();
            if (code != 200) {
                // 服务端失败时回的是 JSON 而不是图，把它的原话带出来
                InputStream es = c.getErrorStream();
                String body = es == null ? "" : Music.readAllPublic(es, 8192);
                lastError = reason(body, code);
                return null;
            }
            String ct = c.getContentType();
            if (ct != null && ct.contains("json")) {
                // 200 也可能是 JSON（比如 f 越界被 handler 拒了）
                String body = Music.readAllPublic(c.getInputStream(), 8192);
                lastError = reason(body, code);
                return null;
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(256 * 1024);
            byte[] buf = new byte[16 * 1024];
            int n;
            int got = 0;
            while ((n = in.read(buf)) > 0) {
                got += n;
                if (got > MAX_BYTES) {
                    lastError = "这一页太大了";
                    return null;
                }
                bos.write(buf, 0, n);
            }
            byte[] data = bos.toByteArray();
            if (data.length == 0) {
                lastError = "服务端回了个空页";
                return null;
            }
            return data;
        } catch (IOException e) {
            lastError = String.valueOf(e.getMessage());
            return null;
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            return null;
        } finally {
            if (c != null) {
                try { c.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    private static volatile String lastError = "";

    /** 最近一次 {@link #fetchPage} 失败的原因；成功时是空串 */
    public static String lastError() {
        return lastError;
    }

    private static String text(String baseUrl, String cookie, String filePath,
                               String extra, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            String url = baseUrl + "/_books/pdf?f=" + Music.encodePathPublic(filePath) + extra;
            c = Music.openPublic(url, cookie, timeoutMs);
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) return null;
            return Music.readAllPublic(in, 128 * 1024);
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) {
                try { c.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    /** 从 {"ok":false,"error":"…"} 里抠出人话；抠不到就用 HTTP 码 */
    private static String reason(String body, int code) {
        if (body != null && !body.isEmpty()) {
            java.util.Map<String, Object> m = Music.Json.parseObject(body);
            if (m != null) {
                Object e = m.get("error");
                if (e != null && !String.valueOf(e).isEmpty()) return String.valueOf(e);
            }
        }
        return "HTTP " + code;
    }
}
