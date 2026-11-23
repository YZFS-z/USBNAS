package com.littlesauce.usbnaslink;

/**
 * 曲库里的一首歌。
 *
 * 字段名故意短：一个曲库几千首，JSON 里每首歌的键名会乘以几千。
 * 服务端 /_music 返回的就是这一套短键（p/n/t/d/s/m/l），
 * 客户端照抄，不再做一层映射 —— 少一次转换就少一处能出错的代码。
 */
public final class Track {

    /** 服务端绝对路径，播放时原样塞进 /play/music?f= 或直接 GET 音频 */
    public final String path;
    /** 文件名（含扩展名） */
    public final String name;
    /** 去掉扩展名的显示名 */
    public final String title;
    /** 所在目录绝对路径 */
    public final String dir;
    /** 是否有同名歌词文件；有则这里是歌词名，没有为 null */
    public String lrcName;
    public long size;
    public long mtime;

    /**
     * 服务端从 .lrc 的 [ti:]/[ar:]/[al:] 里读出来的标签。
     * 文件本身没有 lrc 时为空串；空串表示「不知道」，不能当成「没有歌手」。
     */
    public String tagTitle = "";
    public String tagArtist = "";
    public String tagAlbum = "";

    /**
     * 封面图的服务端绝对路径（同名图或目录通用图），没有为 null。
     * 客户端不缓存这个路径的有效性：文件被删了拉图会 404，播放页退回自绘贴纸。
     */
    public String cover = null;

    public Track(String path, String name, String title, String dir, long size, long mtime) {
        this.path = path == null ? "" : path;
        this.name = name == null ? "" : name;
        this.title = (title == null || title.isEmpty()) ? this.name : title;
        this.dir = dir == null ? "" : dir;
        this.size = size;
        this.mtime = mtime;
    }

    /** 列表里显示的副标题：优先目录名，曲库只有一层时目录名就等于根名，也还行 */
    public String subtitle() {
        String d = dir;
        if (d.endsWith("/") && d.length() > 1) d = d.substring(0, d.length() - 1);
        int i = d.lastIndexOf('/');
        return i >= 0 ? d.substring(i + 1) : d;
    }

    /** 目录名（不带结尾斜杠） */
    public String folderName() {
        String d = dir;
        if (d.endsWith("/") && d.length() > 1) d = d.substring(0, d.length() - 1);
        int i = d.lastIndexOf('/');
        return i >= 0 ? d.substring(i + 1) : d;
    }

    /**
     * 歌手：优先 lrc 标签，其次按「歌手 - 歌名」拆文件名。
     * 拆不出来返回空串 —— 空串在分类界面要归到「未知歌手」，
     * 绝不能拿整段文件名冒充歌手（会把每首歌都变成一个歌手）。
     */
    public String artist() {
        if (tagArtist != null && !tagArtist.trim().isEmpty()) return tagArtist.trim();
        String base = stripExt(name);
        int i = base.indexOf(" - ");
        if (i > 0) {
            String a = base.substring(0, i).trim();
            if (!a.isEmpty() && a.length() <= 40) return a;
        }
        // 专辑目录下的「01.mp3」：歌手在目录名里，比文件名靠谱
        int j = base.indexOf('-');
        if (i < 0 && j > 0) {
            String a = base.substring(0, j).trim();
            if (!a.isEmpty() && a.length() <= 40) return a;
        }
        return "";
    }

    /** 专辑：优先 lrc 标签，其次目录名 */
    public String album() {
        if (tagAlbum != null && !tagAlbum.trim().isEmpty()) return tagAlbum.trim();
        return folderName();
    }

    /**
     * 猜这张专辑叫什么：从文件名里去掉歌手前缀和常见的「01.」「02 」序号。
     *
     * 为什么需要：常见布局是「一路平铺的 歌手 - 歌名.mp3」，没有专辑目录，
     * 也没有带 [al:] 的 lrc。这时按目录分组会得到唯一一个桶「音乐」，
     * 等于没分类。歌名本身是真正的区分依据 —— 一张专辑里的歌名自然聚成组，
     * 同一歌手不同专辑也不会混在一起。
     *
     * 规则保守：只去前缀（歌手 + 分隔符、纯数字序号），不动歌名中间的词，
     * 免得把「2002年的第一场雪」拆成「年的第一场雪」。
     */
    public String guessAlbum() {
        if (tagAlbum != null && !tagAlbum.trim().isEmpty()) return tagAlbum.trim();
        String s = displayTitle();
        if (s == null) return "";
        s = s.trim();
        // 去「歌手 - 」前缀
        String ar = artist();
        if (!ar.isEmpty() && s.startsWith(ar)) {
            String rest = s.substring(ar.length()).trim();
            while (rest.startsWith("-") || rest.startsWith("–") || rest.startsWith("—")
                    || rest.startsWith("_") || rest.startsWith("·")) {
                rest = rest.substring(1).trim();
            }
            if (!rest.isEmpty()) s = rest;
        }
        // 去开头的纯数字序号「01.」「02 」「3、」
        int k = 0;
        while (k < s.length() && Character.isDigit(s.charAt(k))) k++;
        if (k > 0 && k < s.length()) {
            char c = s.charAt(k);
            if (c == '.' || c == '、' || c == ' ' || c == '-' || c == '_') {
                String rest = s.substring(k + 1).trim();
                if (!rest.isEmpty()) s = rest;
            }
        }
        return s;
    }

    /** 显示用标题：lrc 的 [ti:] 比文件名可靠（文件名常带序号/码率） */
    public String displayTitle() {
        if (tagTitle != null && !tagTitle.trim().isEmpty()) return tagTitle.trim();
        return title;
    }

    private String stripExt(String n) {
        if (n == null) return "";
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    public boolean hasLyrics() {
        return lrcName != null && !lrcName.isEmpty();
    }

    /** 有没有可用的封面图（服务端扫到了同名图或目录通用图） */
    public boolean hasCover() {
        return cover != null && !cover.isEmpty();
    }

    /**
     * 封面图直链。跟 {@link Music#audioUrl} 同一套路径编码，
     * 图片走服务端普通的文件 GET，支持 Range/Cache-Control，客户端可以直接喂给图片库。
     */
    public String coverUrl(String baseUrl) {
        if (!hasCover()) return null;
        return Music.audioUrl(baseUrl, cover);
    }

    /** 搜索：标题、文件名、目录名、歌手、专辑任意命中 */
    public boolean matches(String q) {
        if (q == null || q.isEmpty()) return true;
        String s = q.toLowerCase(java.util.Locale.ROOT);
        return displayTitle().toLowerCase(java.util.Locale.ROOT).contains(s)
                || name.toLowerCase(java.util.Locale.ROOT).contains(s)
                || dir.toLowerCase(java.util.Locale.ROOT).contains(s)
                || artist().toLowerCase(java.util.Locale.ROOT).contains(s)
                || album().toLowerCase(java.util.Locale.ROOT).contains(s);
    }

    @Override
    public String toString() {
        return title;
    }
}
