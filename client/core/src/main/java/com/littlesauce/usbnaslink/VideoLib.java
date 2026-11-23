package com.littlesauce.usbnaslink;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 影视库与刮削信息的拉取。
 *
 * 与 Music 的分工一样：服务端 /_videos 一次给全量列表，
 * 刮削信息由 /_videos/meta 按需一部一部拉 —— 上千条 nfo 塞进列表
 * 会让客户端首页白等十几秒，而这台设备还要同时当文件服务器用。
 */
public final class VideoLib {

    /** 一次拉取的条目上限保护 */
    public static final int MAX_VIDEOS = 20000;

    private VideoLib() {
    }

    /** 影视库里的一块：一个预设根目录，或该根下真实出现的一个子目录 */
    public static final class Group {
        public String path = "";
        /** 显示名：内部存储 / 盘名 / 目录名 */
        public String label = "";
        public int count;
        /** 是否管理员预设的根（客户端据此决定要不要加分隔标题） */
        public boolean isRoot;

        @Override
        public String toString() {
            return label;
        }
    }

    /** 一次影视库查询的结果 */
    public static final class Lib {
        public final List<Video> videos = new ArrayList<>();
        /** 管理员预设的根，正序；对应「电影板块里的盘0、盘1」 */
        public final List<Group> roots = new ArrayList<>();
        /** 当前结果里真实出现的目录（含下钻后的子目录） */
        public final List<Group> groups = new ArrayList<>();
        public boolean ok;
        public String error = "";
        public long scannedAt;

        public boolean isEmpty() {
            return videos.isEmpty();
        }

        /** 取出某个目录下的全部条目，保持服务端给的季/集顺序 */
        public List<Video> inDir(String dir) {
            List<Video> out = new ArrayList<>();
            if (dir == null) return out;
            for (Video v : videos) {
                if (dir.equals(v.dir)) out.add(v);
            }
            return out;
        }
    }

    /** GET /_videos?dir=可选子目录 */
    public static Lib fetch(String baseUrl, String cookie, String dir, int timeoutMs) {
        Lib lib = new Lib();
        HttpURLConnection c = null;
        try {
            String url = baseUrl + "/_videos";
            if (dir != null && !dir.isEmpty()) url += "?dir=" + pct(dir);
            c = open(url, cookie, timeoutMs);
            int code = c.getResponseCode();
            if (code != 200) {
                lib.error = "HTTP " + code;
                return lib;
            }
            Map<String, Object> m = Music.Json.parseObject(Music.readAllPublic(c.getInputStream(), 32 * 1024 * 1024));
            if (m == null) {
                lib.error = "bad json";
                return lib;
            }
            lib.ok = Boolean.TRUE.equals(m.get("ok"));
            Object sa = m.get("scannedAt");
            if (sa instanceof Number) lib.scannedAt = ((Number) sa).longValue();

            parseGroups(m.get("dirs"), lib.roots, true);
            parseGroups(m.get("groups"), lib.groups, false);

            Object arr = m.get("videos");
            if (arr instanceof List) {
                for (Object o : (List<?>) arr) {
                    if (lib.videos.size() >= MAX_VIDEOS) break;
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> vm = (Map<?, ?>) o;
                    String p = str(vm.get("p"));
                    if (p == null || p.isEmpty()) continue;
                    lib.videos.add(new Video(p, str(vm.get("n")), str(vm.get("t")), str(vm.get("d")),
                            num(vm.get("s")), num(vm.get("m")), str(vm.get("k")),
                            (int) num(vm.get("sk")), (int) num(vm.get("se"))));
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
     * GET /_videos/meta?f=<路径>
     *
     * 返回的 nfo 字段与 {@link Video.Meta} 的字段名一一对应（服务端用 camelCase，
     * 这里不再做一层缩写映射 —— 元数据一部就一份，键名长一点不影响体积）。
     */
    public static Video.Meta fetchMeta(String baseUrl, String cookie, String filePath, int timeoutMs) {
        Video.Meta meta = new Video.Meta();
        HttpURLConnection c = null;
        try {
            String url = baseUrl + "/_videos/meta?f=" + pct(filePath);
            c = open(url, cookie, timeoutMs);
            int code = c.getResponseCode();
            if (code != 200) {
                meta.error = "HTTP " + code;
                return meta;
            }
            Map<String, Object> m = Music.Json.parseObject(
                    Music.readAllPublic(c.getInputStream(), 4 * 1024 * 1024));
            if (m == null) {
                meta.error = "bad json";
                return meta;
            }
            if (!Boolean.TRUE.equals(m.get("ok"))) {
                meta.error = str(m.get("error"));
                return meta;
            }
            meta.ok = true;
            Object nfoFile = m.get("nfoFile");
            if (nfoFile instanceof String) meta.nfoFile = (String) nfoFile;

            Object nfo = m.get("nfo");
            if (nfo instanceof Map) {
                Map<?, ?> n = (Map<?, ?>) nfo;
                meta.title = nz(str(n.get("title")));
                meta.originalTitle = nz(str(n.get("originalTitle")));
                meta.year = nz(str(n.get("year")));
                meta.premiered = nz(str(n.get("premiered")));
                meta.runtime = nz(str(n.get("runtime")));
                meta.rating = nz(str(n.get("rating")));
                meta.mpaa = nz(str(n.get("mpaa")));
                meta.director = nz(str(n.get("director")));
                meta.writer = nz(str(n.get("writer")));
                meta.studio = nz(str(n.get("studio")));
                meta.tagline = nz(str(n.get("tagline")));
                meta.outline = nz(str(n.get("outline")));
                meta.plot = nz(str(n.get("plot")));
                meta.showTitle = nz(str(n.get("showTitle")));
                meta.season = nz(str(n.get("season")));
                meta.episode = nz(str(n.get("episode")));
                meta.imdbId = nz(str(n.get("imdbId")));
                meta.tmdbId = nz(str(n.get("tmdbId")));
                collect(n.get("genres"), meta.genres);
                collect(n.get("countries"), meta.countries);
                Object actors = n.get("actors");
                if (actors instanceof List) {
                    for (Object a : (List<?>) actors) {
                        if (!(a instanceof Map)) continue;
                        String nm = str(((Map<?, ?>) a).get("name"));
                        if (nm != null && !nm.isEmpty()) meta.actors.add(nm);
                    }
                }
            }
            Object poster = m.get("poster");
            if (poster instanceof String) meta.posterUrl = (String) poster;
            Object fan = m.get("fanart");
            if (fan instanceof String) meta.fanartUrl = (String) fan;
        } catch (Throwable t) {
            meta.error = t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (c != null) c.disconnect();
        }
        return meta;
    }

    /** 视频的直链（服务端支持 Range，播放器可以拖进度） */
    public static String videoUrl(String baseUrl, String filePath) {
        return Music.audioUrl(baseUrl, filePath);
    }

    /** 海报/背景的直链；服务端给的是站内绝对路径，拼上 baseUrl 即可 */
    public static String artUrl(String baseUrl, String pathOnServer) {
        if (pathOnServer == null || pathOnServer.isEmpty()) return null;
        return baseUrl + pathOnServer;
    }

    // ---------- 内部工具 ----------

    private static void parseGroups(Object o, List<Group> out, boolean isRoot) {
        if (!(o instanceof List)) return;
        for (Object e : (List<?>) o) {
            if (!(e instanceof Map)) continue;
            Map<?, ?> gm = (Map<?, ?>) e;
            String p = str(gm.get("p"));
            if (p == null || p.isEmpty()) continue;
            Group g = new Group();
            g.path = p;
            g.label = nz(str(gm.get("n")));
            if (g.label.isEmpty()) g.label = p;
            g.count = (int) num(gm.get("count"));
            g.isRoot = isRoot;
            out.add(g);
        }
    }

    private static void collect(Object o, List<String> out) {
        if (!(o instanceof List)) return;
        for (Object e : (List<?>) o) {
            if (e instanceof String && !((String) e).isEmpty()) out.add((String) e);
        }
    }

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
