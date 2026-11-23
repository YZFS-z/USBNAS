package com.littlesauce.usbnaslink;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 图书库的拉取与阅读地址拼装。
 *
 * 与 VideoLib 同构（服务端 /_books 也是照 /_videos 的形状吐的），
 * 但少了「刮削」这一整套：书没有 nfo、没有海报，读者要的是
 * 「有哪些书 + 怎么打开」。所以这里只做两件事：
 *
 *   1) {@link #fetch} 拿书单；
 *   2) {@link #readUrl} / {@link #fileUrl} 拼出阅读页和原文件地址。
 *
 * 没有「一本书一个请求」的按需接口 —— 书单里带的已经够了。
 */
public final class BookLib {

    /** 一次拉取的条目上限保护，与服务端 BOOK_MAX_FILES 对齐 */
    public static final int MAX_BOOKS = 20000;

    private BookLib() {
    }

    /** 书库里的一块：一个预设根目录，或该根下真实出现的一个子目录（书架） */
    public static final class Group {
        public String path = "";
        /** 显示名：内部存储 / 盘名 / 目录名 */
        public String label = "";
        public int count;
        /** 是否管理员预设的根 */
        public boolean isRoot;

        @Override
        public String toString() {
            return label;
        }
    }

    /** 一次书库查询的结果 */
    public static final class Lib {
        public final List<Book> books = new ArrayList<>();
        /** 管理员预设的根，正序 */
        public final List<Group> roots = new ArrayList<>();
        /** 当前结果里真实出现的目录（书架） */
        public final List<Group> groups = new ArrayList<>();
        public boolean ok;
        public String error = "";
        public long scannedAt;

        public boolean isEmpty() {
            return books.isEmpty();
        }

        /**
         * 管理员一个书目录都没设。
         *
         * 这个状态要和「设了但目录是空的」分开 —— 前者要提示
         * 「请管理员先设置图书目录」，后者说「这个书架上还没有书」，
         * 两种话对用户的下一步动作是不一样的。
         */
        public boolean notConfigured() {
            return ok && roots.isEmpty();
        }

        /** 取出某个目录下的全部条目，保持服务端给的标题顺序 */
        public List<Book> inDir(String dir) {
            List<Book> out = new ArrayList<>();
            if (dir == null) return out;
            for (Book b : books) {
                if (dir.equals(b.dir)) out.add(b);
            }
            return out;
        }
    }

    /** GET /_books?dir=可选子目录 */
    public static Lib fetch(String baseUrl, String cookie, String dir, int timeoutMs) {
        Lib lib = new Lib();
        HttpURLConnection c = null;
        try {
            String url = baseUrl + "/_books";
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

            parseGroups(m.get("dirs"), lib.roots, true);
            parseGroups(m.get("groups"), lib.groups, false);

            Object arr = m.get("books");
            if (arr instanceof List) {
                for (Object o : (List<?>) arr) {
                    if (lib.books.size() >= MAX_BOOKS) break;
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> bm = (Map<?, ?>) o;
                    String p = str(bm.get("p"));
                    if (p == null || p.isEmpty()) continue;
                    lib.books.add(new Book(p, str(bm.get("n")), str(bm.get("t")),
                            str(bm.get("d")), str(bm.get("x")),
                            num(bm.get("s")), num(bm.get("m"))));
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
     * 阅读页地址：服务端 /view?f=<路径>。
     *
     * 让客户端打开这个页面而不是直接开文件，是因为服务端那边已经把
     * 「pdf 塞 iframe / txt 当纯文本 / 其他给下载」这套分流写好了，
     * 客户端再抄一遍只会两边不一致。
     */
    public static String readUrl(String baseUrl, String filePath) {
        if (baseUrl == null || baseUrl.isEmpty()) return "";
        return baseUrl + "/view?f=" + pct(filePath);
    }

    /**
     * 原文件直链（下载、或交给系统外部应用打开时用）。
     *
     * 必须走 Music.audioUrl：它按「段」做百分号编码，路径里的 '/' 保留成 '/'。
     * 早期这里直接调 pct()，那是对整串编码，'/' 全变成 %2F，
     * 服务端解析不出文件路径、浏览器转而当成下载链接 —— 手机上就是弹「创建下载任务」。
     */
    public static String fileUrl(String baseUrl, String filePath) {
        if (baseUrl == null || baseUrl.isEmpty()) return "";
        return Music.audioUrl(baseUrl, filePath);
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
