package com.littlesauce.usbnaslink;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 在线搜歌词 / 保存歌词。
 *
 * <p>协议在服务端 HttpServer.java：
 * <ul>
 *   <li>{@code GET /lyrics/search?f=<音频绝对路径>[&q=关键词]} →
 *       {@code {"ok":true,"query":..,"artist":..,"items":[{"idx","src","title","artist","album","dur","synced","tok"}]}}</li>
 *   <li>{@code POST /lyrics/save}（form: f / tok / overwrite）→
 *       {@code {"ok":true,"file":..,"bytes":N[,"replaced":true]}} 或
 *       {@code {"ok":false,"alreadyExists":true,"error":"同名歌词已存在"}}</li>
 * </ul>
 *
 * <p>为什么搜索在服务端做：lrclib 与网易云的接口都被墙/限流，服务端是同一个
 * 网络环境但只跑一份代码；客户端各写一份，三端（网页/桌面/手机）行为会漂。
 * 而且服务端已经写好「按歌名+歌手打分排序、砍到 12 条」的逻辑。
 *
 * <p>为什么保存也是服务端做：歌词要落在音频同目录，只有服务端有那个路径的
 * 写权限；客户端把 tok 递回去，服务端自己从源头重取正文 —— 前端改的歌词不算数。
 */
public final class Lyrics {

    /** 搜索结果一条 */
    public static final class Cand {
        public String src = "";
        public String title = "";
        public String artist = "";
        public String album = "";
        public long durMs = -1;
        public boolean synced;
        public String token = "";

        /** 列表里显示的一行 */
        public String line() {
            StringBuilder sb = new StringBuilder();
            sb.append(title == null || title.isEmpty() ? "(无标题)" : title);
            if (artist != null && !artist.isEmpty()) sb.append("  —  ").append(artist);
            sb.append(synced ? "   [逐行]" : "   [纯文本]");
            if (album != null && !album.isEmpty()) sb.append("   ").append(album);
            return sb.toString();
        }
    }

    /** 一次搜索的结果 */
    public static final class Result {
        public boolean ok;
        public String error;
        public String query = "";
        public String artist = "";
        public final List<Cand> items = new ArrayList<>();
    }

    /** 保存结果 */
    public static final class Saved {
        public boolean ok;
        public boolean alreadyExists;
        public String error;
        public String file = "";
        public int bytes;
        public boolean replaced;
    }

    private Lyrics() {
    }

    /**
     * 搜歌词。
     *
     * @param filePath 音频在服务端的绝对路径（服务端会拿它推歌名/歌手）
     * @param keyword  额外关键词，可为 null（null 时服务端按文件名猜）
     */
    public static Result search(String baseUrl, String cookie, String filePath,
                                String keyword, int timeoutMs) {
        Result r = new Result();
        if (baseUrl == null || baseUrl.isEmpty() || filePath == null || filePath.isEmpty()) {
            r.error = "地址为空";
            return r;
        }
        String url = baseUrl + "/lyrics/search?f=" + Music.pctPublic(filePath);
        if (keyword != null && !keyword.trim().isEmpty()) {
            url += "&q=" + Music.pctPublic(keyword.trim());
        }
        HttpURLConnection c = null;
        try {
            c = Music.openPublic(url, cookie, timeoutMs);
            String body = Music.readAllPublic(c.getInputStream(), 512 * 1024);
            Map<String, Object> m = Music.Json.parseObject(body);
            if (m == null) {
                r.error = "服务端返回的不是 JSON";
                return r;
            }
            r.ok = isTrue(m.get("ok"));
            r.query = nz(str(m.get("query")));
            r.artist = nz(str(m.get("artist")));
            if (!r.ok) {
                String e = str(m.get("error"));
                r.error = e == null || e.isEmpty() ? "搜不到歌词" : e;
                return r;
            }
            Object arr = m.get("items");
            if (arr instanceof List) {
                for (Object o : (List<?>) arr) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> im = (Map<?, ?>) o;
                    Cand cd = new Cand();
                    cd.src = nz(str(im.get("src")));
                    cd.title = nz(str(im.get("title")));
                    cd.artist = nz(str(im.get("artist")));
                    cd.album = nz(str(im.get("album")));
                    cd.token = nz(str(im.get("tok")));
                    cd.synced = isTrue(im.get("synced"));
                    Object d = im.get("dur");
                    if (d instanceof Number) cd.durMs = ((Number) d).longValue();
                    if (!cd.token.isEmpty()) r.items.add(cd);
                }
            }
            return r;
        } catch (Throwable t) {
            r.ok = false;
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return r;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * 保存一首歌词到音频同目录。
     *
     * @param overwrite true 时覆盖同名 .lrc（服务端会先删旧文件再写）
     */
    public static Saved save(String baseUrl, String cookie, String filePath,
                             String token, boolean overwrite, int timeoutMs) {
        Saved s = new Saved();
        if (baseUrl == null || baseUrl.isEmpty() || filePath == null || filePath.isEmpty()) {
            s.error = "地址为空";
            return s;
        }
        if (token == null || token.isEmpty()) {
            s.error = "没有可保存的歌词源";
            return s;
        }
        String form = "f=" + Session.pct(filePath)
                + "&tok=" + Session.pct(token)
                + (overwrite ? "&overwrite=1" : "");
        byte[] data = form.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(baseUrl + "/lyrics/save").openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(Probe.CONNECT_TIMEOUT * 4);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
            c.setRequestProperty("User-Agent", "USBNASLink/1.0");
            if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
            c.setFixedLengthStreamingMode(data.length);
            OutputStream out = c.getOutputStream();
            out.write(data);
            out.flush();
            out.close();
            InputStream in = c.getInputStream();
            String body = Music.readAllPublic(in, 256 * 1024);
            Map<String, Object> m = Music.Json.parseObject(body);
            if (m == null) {
                s.error = "服务端返回的不是 JSON";
                return s;
            }
            s.ok = isTrue(m.get("ok"));
            s.alreadyExists = isTrue(m.get("alreadyExists"));
            s.file = nz(str(m.get("file")));
            s.replaced = isTrue(m.get("replaced"));
            Object b = m.get("bytes");
            if (b instanceof Number) s.bytes = ((Number) b).intValue();
            if (!s.ok) {
                String e = str(m.get("error"));
                s.error = e == null || e.isEmpty() ? "保存失败" : e;
            }
            return s;
        } catch (Throwable t) {
            s.ok = false;
            s.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return s;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ---------- 内部工具 ----------

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
