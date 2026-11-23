package com.littlesauce.usbnaslink;

/**
 * 影视条目：一部电影，或电视剧的一集。
 *
 * 和 Track 一样，字段对齐服务端 /_videos 的短键（p/n/t/d/s/m/k/sk/se/ep），
 * 客户端不再做二次映射。
 *
 * 刮削信息（title/year/poster…）放在 {@link Meta} 里单独拉：
 * 一个电影库动辄上千条，把每个 nfo 都塞进列表 JSON 会让首页白等十几秒。
 */
public final class Video {

    /** 服务端绝对路径，直接 GET 就是文件本体（服务端支持 Range，能拖进度） */
    public final String path;
    /** 文件名（含扩展名） */
    public final String name;
    /** 去掉扩展名的显示名 */
    public final String title;
    /** 所在目录绝对路径 —— 也是「分组」的依据 */
    public final String dir;
    public long size;
    public long mtime;
    /** video / tv；电视剧单集是 tv，方便客户端分板块 */
    public final String kind;
    /** 季号，电影为 0 */
    public int season;
    /** 集号，电影为 0 */
    public int episode;

    public Video(String path, String name, String title, String dir,
                 long size, long mtime, String kind, int season, int episode) {
        this.path = path == null ? "" : path;
        this.name = name == null ? "" : name;
        this.title = (title == null || title.isEmpty()) ? this.name : title;
        this.dir = dir == null ? "" : dir;
        this.size = size;
        this.mtime = mtime;
        this.kind = (kind == null || kind.isEmpty()) ? "video" : kind;
        this.season = season;
        this.episode = episode;
    }

    public boolean isTv() {
        return "tv".equals(kind);
    }

    /** 目录名，列表和分组标题都用它 */
    public String folder() {
        String d = dir;
        if (d.endsWith("/") && d.length() > 1) d = d.substring(0, d.length() - 1);
        int i = d.lastIndexOf('/');
        return i >= 0 ? d.substring(i + 1) : d;
    }

    /** 搜索：标题、文件名、目录名任意命中 */
    public boolean matches(String q) {
        if (q == null || q.isEmpty()) return true;
        String s = q.toLowerCase(java.util.Locale.ROOT);
        return title.toLowerCase(java.util.Locale.ROOT).contains(s)
                || name.toLowerCase(java.util.Locale.ROOT).contains(s)
                || dir.toLowerCase(java.util.Locale.ROOT).contains(s);
    }

    /** S01E02 这种短标记，电影返回空串 */
    public String tag() {
        if (!isTv()) return "";
        StringBuilder sb = new StringBuilder();
        if (season > 0) sb.append('S').append(season < 10 ? "0" + season : String.valueOf(season));
        if (episode > 0) sb.append('E').append(episode < 10 ? "0" + episode : String.valueOf(episode));
        return sb.toString();
    }

    @Override
    public String toString() {
        return title;
    }

    // ==================== 刮削信息 ====================

    /**
     * 一部影视的刮削结果，对应服务端 GET /scrape?f=<路径>。
     *
     * 服务端只认「同目录下的 nfo + 海报图」，不做在线刮削，
     * 所以这里拿不到就是真的没有，客户端显示「暂无刮削信息」即可。
     */
    public static final class Meta {
        /** 是否成功取到响应（不代表有元数据） */
        public boolean ok;
        public String error = "";
        /** 这些字段来自 nfo，取不到就是空串 */
        public String title = "";
        public String originalTitle = "";
        public String year = "";
        public String premiered = "";
        public String runtime = "";
        public String rating = "";
        public String mpaa = "";
        public String director = "";
        public String writer = "";
        public String studio = "";
        public String tagline = "";
        public String outline = "";
        public String plot = "";
        public String showTitle = "";
        public String season = "";
        public String episode = "";
        public String imdbId = "";
        public String tmdbId = "";
        public java.util.List<String> genres = new java.util.ArrayList<>();
        public java.util.List<String> countries = new java.util.ArrayList<>();
        public java.util.List<String> actors = new java.util.ArrayList<>();
        /** nfo 文件名；null 表示没有 nfo */
        public String nfoFile;
        /** 海报（服务端已按 poster→folder→cover 挑好一张给客户端用） */
        public String posterUrl;
        /** 背景图（fanart/backdrop） */
        public String fanartUrl;

        public boolean hasAny() {
            return plot != null && !plot.isEmpty()
                    || title != null && !title.isEmpty()
                    || posterUrl != null;
        }

        /** 一行摘要：年份 · 类型 · 时长 · 评分 */
        public String metaLine() {
            StringBuilder sb = new StringBuilder();
            add(sb, year);
            if (!genres.isEmpty()) add(sb, join(genres, "/"));
            add(sb, runtime.isEmpty() ? "" : runtime + "分钟");
            add(sb, rating.isEmpty() ? "" : "★" + rating);
            add(sb, mpaa);
            return sb.toString();
        }

        private static void add(StringBuilder sb, String s) {
            if (s == null || s.isEmpty()) return;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(s);
        }

        private static String join(java.util.List<String> list, String sep) {
            StringBuilder sb = new StringBuilder();
            for (String s : list) {
                if (sb.length() > 0) sb.append(sep);
                sb.append(s);
            }
            return sb.toString();
        }
    }
}
