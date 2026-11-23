package com.littlesauce.usbnaslink;

/**
 * 图书条目：书库里的一个文件。
 *
 * 字段对齐服务端 /_books 的短键（p/n/t/d/x/s/m），与 Track / Video 的做法一致：
 * 服务端怎么吐，客户端就怎么存，中间不再做一层缩写映射。
 *
 * 与影音不同的一点是 {@link #ext}：书没有「刮削」这回事，读者认的是格式 ——
 * epub 能排版、pdf 靠内置阅读器、txt/md 要当纯文本画出来。
 * 所以扩展名是这里的头等字段，客户端据此决定用哪种方式打开。
 */
public final class Book {

    /** 服务端绝对路径。阅读页是 /view?f=<这个路径>，下载是直接 GET */
    public final String path;
    /** 文件名（含扩展名） */
    public final String name;
    /** 去掉扩展名的显示名 */
    public final String title;
    /** 所在目录绝对路径 —— 这是「书架」的依据 */
    public final String dir;
    /** 小写扩展名（epub / pdf / txt / md …），决定打开方式 */
    public final String ext;
    public long size;
    public long mtime;

    public Book(String path, String name, String title, String dir, String ext,
                long size, long mtime) {
        this.path = path == null ? "" : path;
        this.name = name == null ? "" : name;
        this.title = (title == null || title.isEmpty()) ? this.name : title;
        this.dir = dir == null ? "" : dir;
        this.ext = ext == null ? "" : ext.toLowerCase(java.util.Locale.ROOT);
        this.size = size;
        this.mtime = mtime;
    }

    /** 浏览器内置阅读器能直接呈现的：交给 iframe，缩放/翻页/选字全有 */
    public boolean isPdf() {
        return "pdf".equals(ext);
    }

    /**
     * EPUB 家族。
     *
     * 服务端把这些放进了 OFFICE_DOC 那条渲染链里，页面上会先提示
     * 「浏览器多半不认识」，再给下载入口 —— 这是实话：Chrome 不认 epub，
     * 只有 Firefox 有内置阅读器。客户端这边仍然把它标出来，
     * 好在列表里显示成一本书而不是一团未知格式。
     */
    public boolean isEpub() {
        return "epub".equals(ext) || "ibooks".equals(ext);
    }

    /** Kindle 的几种容器，服务端只标格式不做解析 */
    public boolean isKindle() {
        return "mobi".equals(ext) || "azw".equals(ext) || "azw3".equals(ext);
    }

    /** 纯文本：服务端 renderDoc 会直接当 <pre> 画出来 */
    public boolean isText() {
        return "txt".equals(ext) || "md".equals(ext) || "fb2".equals(ext);
    }

    /** 漫画/扫描书：cbz/cbr/djvu，服务端同样只能提供下载 */
    public boolean isComic() {
        return "cbz".equals(ext) || "cbr".equals(ext) || "cb7".equals(ext)
                || "djvu".equals(ext);
    }

    /** 目录名，列表和书架标题都用它 */
    public String folder() {
        String d = dir;
        if (d.endsWith("/") && d.length() > 1) d = d.substring(0, d.length() - 1);
        int i = d.lastIndexOf('/');
        return i >= 0 ? d.substring(i + 1) : d;
    }

    /** 格式标签，列表右侧那个小字用它 */
    public String tag() {
        if (ext.isEmpty()) return "书";
        return ext.toUpperCase(java.util.Locale.ROOT);
    }

    /** 搜索：标题、文件名、目录名任意命中 */
    public boolean matches(String q) {
        if (q == null || q.isEmpty()) return true;
        String s = q.toLowerCase(java.util.Locale.ROOT);
        return title.toLowerCase(java.util.Locale.ROOT).contains(s)
                || name.toLowerCase(java.util.Locale.ROOT).contains(s)
                || dir.toLowerCase(java.util.Locale.ROOT).contains(s);
    }

    @Override
    public String toString() {
        return title + " [" + ext + "]";
    }
}
