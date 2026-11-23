package com.littlesauce.usbnaslink;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 相册库的拉取与图片地址拼装。
 *
 * <p>与 {@link BookLib} 同构：一次请求拿全量清单，没有「一张图一个请求」。
 * 相册的特殊之处是总量大 —— 服务端已经做了 4000 张的上限与 60 秒缓存，
 * 客户端这边只负责把 JSON 摊平，不重复做二次裁剪（否则分页会错位）。
 *
 * <p>为什么要单独一个类、不复用 BookLib：书的字段是「文件名 / 标题 / 格式 / 大小」，
 * 图是「宽高 / 拍摄时间 / 所属相册」，两者只在 p/n/d 三个键上撞巧相同，
 * 硬塞进一个模型会让两边都长出一堆恒为空的字段。
 */
public final class PhotoLib {

    /** 与服务端 PHOTO_MAX_FILES 对齐 */
    public static final int MAX_PHOTOS = 4000;

    private PhotoLib() {
    }

    /** 一个相册（= 一个目录）。count 是该目录下的张数，用来画封面墙的分组头 */
    public static final class Album {
        public String path = "";
        public String label = "";
        public int count;

        @Override
        public String toString() {
            return label;
        }
    }

    /** 一次相册查询的结果 */
    public static final class Lib {
        /** 按服务端顺序（mtime 倒序，最新的在前） */
        public final List<Photo> photos = new ArrayList<>();
        /** 清单里真实出现的目录 */
        public final List<Album> albums = new ArrayList<>();
        public boolean ok;
        public String error = "";
        public long scannedAt;

        public boolean isEmpty() {
            return photos.isEmpty();
        }

        /**
         * 管理员一个相册目录都没设。
         *
         * 这个状态必须和「设了但目录里没图」分开：前者要提示
         * 「去管理设置里指定相册目录」，后者说「这个相册还是空的」。
         * 服务端在没配目录时 total 直接是 0、albums 也是空，所以只能靠这个判断。
         */
        public boolean notConfigured() {
            return ok && photos.isEmpty() && albums.isEmpty();
        }

        /** 取出某个目录下的全部图，保持服务端给的顺序 */
        public List<Photo> inDir(String dir) {
            List<Photo> out = new ArrayList<>();
            if (dir == null) return out;
            for (Photo p : photos) {
                if (dir.equals(p.dir)) out.add(p);
            }
            return out;
        }

        /** 当前清单里出现过的目录数 —— 分组头要显示「共 N 个相册」 */
        public int albumCount() {
            LinkedHashSet<String> set = new LinkedHashSet<>();
            for (Photo p : photos) set.add(p.dir);
            return set.size();
        }
    }

    /** GET /_photos?dir=可选子目录 */
    public static Lib fetch(String baseUrl, String cookie, String dir, int timeoutMs) {
        Lib lib = new Lib();
        HttpURLConnection c = null;
        try {
            String url = baseUrl + "/_photos";
            if (dir != null && !dir.isEmpty()) url += "?dir=" + pct(dir);
            c = open(url, cookie, timeoutMs);
            int code = c.getResponseCode();
            if (code != 200) {
                lib.error = "HTTP " + code;
                return lib;
            }
            Map<String, Object> m = Music.Json.parseObject(
                    Music.readAllPublic(c.getInputStream(), 32 * 1024 * 1024));
            if (m == null) {
                lib.error = "bad json";
                return lib;
            }
            lib.ok = Boolean.TRUE.equals(m.get("ok"));
            Object sa = m.get("scannedAt");
            if (sa instanceof Number) lib.scannedAt = ((Number) sa).longValue();

            Object dirs = m.get("dirs");
            if (dirs instanceof List) {
                for (Object o : (List<?>) dirs) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> dm = (Map<?, ?>) o;
                    String p = str(dm.get("p"));
                    if (p == null || p.isEmpty()) continue;
                    Album a = new Album();
                    a.path = p;
                    a.label = nz(str(dm.get("n")));
                    if (a.label.isEmpty()) a.label = p;
                    a.count = (int) num(dm.get("c"));
                    lib.albums.add(a);
                }
            }

            Object arr = m.get("photos");
            if (arr instanceof List) {
                for (Object o : (List<?>) arr) {
                    if (lib.photos.size() >= MAX_PHOTOS) break;
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> pm = (Map<?, ?>) o;
                    String p = str(pm.get("p"));
                    if (p == null || p.isEmpty()) continue;
                    lib.photos.add(new Photo(p, str(pm.get("n")), str(pm.get("d")),
                            num(pm.get("s")), num(pm.get("m")),
                            (int) num(pm.get("w")), (int) num(pm.get("h"))));
                }
            }
        } catch (Throwable t) {
            lib.error = t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (c != null) c.disconnect();
        }
        return lib;
    }

    /**
     * 原图直链。
     *
     * <p>必须走 {@link Music#audioUrl}：它按「段」编码、路径里的 '/' 保留，
     * 服务端 resolve() 才能把它当路径解开。用整串 pct 编码会得到 %2F，
     * 服务端认不出文件、浏览器转成下载。
     */
    public static String imageUrl(String baseUrl, String filePath) {
        if (baseUrl == null || baseUrl.isEmpty()) return "";
        return Music.audioUrl(baseUrl, filePath);
    }

    /** 删除结果：deleted 是服务端真删掉的张数，skipped 是被越界/非图片挡下来的 */
    public static final class Deleted {
        public boolean ok;
        public String error = "";
        public int deleted;
        public int skipped;

        @Override
        public String toString() {
            return ok ? ("deleted=" + deleted + " skipped=" + skipped) : ("error=" + error);
        }
    }

    /**
     * 批量删除照片（POST /_photos/delete，form 参数 {@code f} 每行一个绝对路径）。
     *
     * <p>为什么不复用 Admin.post：那边的动作名是固定的四个（title/music/video/book/photo），
     * 而且返回的是 {@code {"ok":..,"msg":..}}；删除要拿到 deleted 计数，形状不一样。
     * 但编码规则必须一致 —— 路径里的 '/' 要保留，所以这里用 {@code pctPath}
     * 而不是整串 pct，否则服务端 resolve() 解不出文件。
     */
    public static Deleted delete(String baseUrl, String cookie, List<String> paths, int timeoutMs) {
        Deleted r = new Deleted();
        if (baseUrl == null || baseUrl.isEmpty()) {
            r.error = "没有服务器地址";
            return r;
        }
        if (paths == null || paths.isEmpty()) {
            r.ok = true;
            return r;
        }
        StringBuilder body = new StringBuilder(64 + paths.size() * 96);
        body.append("f=");
        boolean first = true;
        for (String p : paths) {
            if (p == null || p.trim().isEmpty()) continue;
            if (!first) body.append("%0A"); // 换行分隔，服务端按行切
            first = false;
            body.append(pctPath(p.trim()));
        }
        HttpURLConnection c = null;
        try {
            c = open(baseUrl + "/_photos/delete", cookie, timeoutMs);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            byte[] bs = body.toString().getBytes("UTF-8");
            c.setFixedLengthStreamingMode(bs.length);
            java.io.OutputStream os = c.getOutputStream();
            os.write(bs);
            os.flush();
            os.close();
            int code = c.getResponseCode();
            java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = Music.readAllPublic(in, 1 << 20);
            java.util.Map<String, Object> m = Music.Json.parseObject(text);            if (m == null) {
                r.error = "服务端返回看不懂：" + (text == null ? "" : text.length() > 80
                        ? text.substring(0, 80) : text);
                return r;
            }
            r.ok = !"false".equals(String.valueOf(m.get("ok")));
            Object er = m.get("error");
            if (er instanceof String) r.error = (String) er;
            r.deleted = (int) num(m.get("deleted"));
            r.skipped = (int) num(m.get("skipped"));
            if (code >= 400 && r.error.isEmpty()) r.error = "HTTP " + code;
            return r;
        } catch (Throwable t) {
            r.ok = false;
            r.error = String.valueOf(t.getMessage());
            return r;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 按段保留 '/' 的百分号编码（路径当整条 URL 用，不是查询参数值） */
    private static String pctPath(String path) {
        return Music.encodePathPublic(path);
    }

    // ---------- 内部工具 ----------

    private static String str(Object o) {
        return o instanceof String ? (String) o : null;
    }

    private static long num(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static HttpURLConnection open(String url, String cookie, int timeoutMs) throws java.io.IOException {
        return Music.openPublic(url, cookie, timeoutMs);
    }

    private static String pct(String s) {
        return Music.pctPublic(s);
    }
}
