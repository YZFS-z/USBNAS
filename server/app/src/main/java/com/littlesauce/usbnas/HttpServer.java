package com.littlesauce.usbnas;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import android.os.SystemClock;

/**
 * 极简 HTTP/1.1 服务器，专为局域网文件共享 + 在线播放设计。
 *
 * 核心能力：
 *   - 完整 Range 支持（206 / 416 / multipart byte-ranges），视频拖动进度条依赖此项
 *   - HEAD 支持（播放器先探测是否支持 range）
 *   - 目录列表（HTML，带搜索/排序/面包屑/缩略图）
 *   - 单文件流式下载（8MB buffer，内存恒定）
 *   - 多文件打包 ZIP（流式 STORED，不压缩，CPU 零负担）
 *   - WebDAV 子集（PROPFIND / GET / PUT / DELETE / MKCOL / MOVE），Windows 可映射网络驱动器
 *   - Basic Auth
 *   - 音乐播放页（内置播放器 + LRC 歌词同步滚动）
 *
 * 用法：
 *   HttpServer s = new HttpServer(config, roots, listener);
 *   s.start();
 *   ...
 *   s.stop();
 */
public class HttpServer {

    // ==================== 配置 ====================

    public static class Config {
        public int port = 8080;
        /**
         * 站点标题：网页 title / 顶栏 h1 / 登录页 / HTTP Server 头全都用它。
         * App 里改标题后由 {@link #setTitle} 热更新，不必重启服务。
         */
        public String title = "USBNAS";
        /** 是否为每个文件自动探测同名 .lrc 歌词 */
        public boolean detectLyrics = true;

        // ---- 账号与权限 ----
        /** 账号快照，null 表示不鉴权（也无写权限） */
        public Users.Snapshot users;
        /** 允许未登录访问（只读） */
        public boolean anonymousRead = true;

        // ---- 运行时状态 ----
        /** 由 NasService 注入，提供手机状态 JSON 与实时传输速率 */
        public SysInfo sys;
        /**
         * 服务开始监听的墙钟时刻（毫秒），0 表示没在跑。
         * 网页端「已运行」用它算，而不是用开机时长。
         */
        public long startedAt = 0L;
        /** 所属服务器，登录会话存在它身上 */
        HttpServer owner;

        /**
         * App 上下文。只为读 assets 里的离线渲染库（docx-preview / SheetJS / jszip）。
         * 由 NasService 注入；为 null 时 Office 预览自动降级为服务端解析。
         */
        public android.content.Context appContext;

        /** 共享目录来源：每次调用重新扫卷，U 盘热插拔能立刻反映到网页 */
        public interface RootsSupplier {
            List<File> get();
        }

        /** 为 null 时用构造 HttpServer 时传入的固定列表 */
        public RootsSupplier rootsSupplier;

        /**
         * 音乐库根目录：管理员在 App 里预设的绝对路径列表。
         *
         * 为什么要服务端存而不是客户端扫：曲库几千首，客户端每次启动都遍历
         * 全盘的话机械盘要转很久、手机也跟着耗电。管理员指一次，
         * 所有客户端（EXE/APK）拉同一份曲库列表即可，开箱就是完整列表。
         *
         * App 里改完由 {@link #setMusicDirs} 热更新，不必重启服务。
         */
        public java.util.List<String> musicDirs = new java.util.ArrayList<>();

        /**
         * 影视库根目录：管理员在 App 里预设的绝对路径列表，允许多个。
         *
         * 为什么必须支持多个：常见布局是「盘0/电影」和「盘1/电影」各放一部分，
         * 只给一个根就做不到「电影板块里两块并存」。
         * 与音乐目录分开存，因为两者往往是完全不同的目录。
         */
        public java.util.List<String> videoDirs = new java.util.ArrayList<>();

        /**
         * 图书库根目录：管理员预设的绝对路径列表，允许多个。
         *
         * 单独一份而不是复用影视目录：书库和影音库的形态差得远 ——
         * 书目录里动辄几万个小文件（散装 txt、按作者/出版社分层），
         * 扫一次比影音贵得多，必须由管理员明确指路，不能让客户端对着全盘瞎找。
         */
        public java.util.List<String> bookDirs = new java.util.ArrayList<>();

        /**
         * 相册根目录：管理员预设的绝对路径列表，可多个，默认空。
         *
         * 默认空着（相册页什么都不显示）是刻意的：手机里 jpg/png 满地都是
         * （图标、WebView 缓存、聊天截图），默认扫全盘会让相册变成垃圾场。
         * 这一条和书库同一套取舍 —— 宁可一开始是空的，也不要一屏噪点。
         */
        public java.util.List<String> photoDirs = new java.util.ArrayList<>();

        /** 解析 Cookie 会话，返回登录用户；未登录返回 null */
        public Users.User session(Request req) {
            Users.Snapshot snap = users;
            if (snap == null) return null;
            if (owner == null) return null;
            String ck = req.headers.get("cookie");
            if (ck == null) return null;
            for (String part : ck.split(";")) {
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                if (!part.substring(0, eq).trim().equals(COOKIE_NAME)) continue;
                String name = owner.sessionGet(part.substring(eq + 1).trim());
                return name == null ? null : snap.byName.get(name);
            }
            return null;
        }

        /** 解析登录身份：Cookie 会话优先，其次 Basic Auth（给 WebDAV 客户端用） */
        public Users.User auth(Request req) {
            Users.User u = session(req);
            if (u != null) return u;
            Users.Snapshot snap = users;
            if (snap == null) return null;
            String h = req.headers.get("authorization");
            if (h == null || !h.startsWith("Basic ")) return null;
            try {
                byte[] dec = android.util.Base64.decode(h.substring(6),
                        android.util.Base64.DEFAULT);
                String raw = new String(dec, StandardCharsets.UTF_8);
                int c = raw.indexOf(':');
                if (c <= 0) return null;
                return snap.verify(raw.substring(0, c), raw.substring(c + 1));
            } catch (Exception e) {
                return null;
            }
        }

        /** 是否设置了任何账号 */
        public boolean hasAccounts() {
            Users.Snapshot snap = users;
            return snap != null && !snap.byName.isEmpty();
        }

        /** 账号在 App 里改动后热替换快照，无需重启服务 */
        public void reload(android.content.Context c) {
            this.users = Users.Snapshot.of(c);
            this.anonymousRead = Users.allowAnonymous(c);
            this.title = AppPrefs.title(c);
            this.musicDirs = AppPrefs.musicDirs(c);
            this.videoDirs = AppPrefs.videoDirs(c);
            this.bookDirs = AppPrefs.bookDirs(c);
            this.photoDirs = AppPrefs.photoDirs(c);
        }

        /** App 里改完标题后调用：立刻生效，不必重启服务 */
        public void setTitle(String t) {
            if (t != null && !t.trim().isEmpty()) this.title = t.trim();
        }

        /**
         * App 里改完音乐目录后调用：立刻生效并清掉曲库缓存，
         * 否则客户端会继续拿到旧目录列表。
         */
        public void setMusicDirs(List<String> dirs) {
            List<String> copy = new ArrayList<>();
            if (dirs != null) {
                for (String d : dirs) {
                    if (d == null) continue;
                    String t = d.trim();
                    if (!t.isEmpty() && !copy.contains(t)) copy.add(t);
                }
            }
            this.musicDirs = copy;
            HttpServer o = owner;
            if (o != null) o.invalidateMusicLib();
        }

        /**
         * App 里改完影视目录后调用：立刻生效并清掉影视库缓存。
         * 允许多个目录同时存在，客户端按目录分组渲染。
         */
        public void setVideoDirs(List<String> dirs) {
            List<String> copy = new ArrayList<>();
            if (dirs != null) {
                for (String d : dirs) {
                    if (d == null) continue;
                    String t = d.trim();
                    if (!t.isEmpty() && !copy.contains(t)) copy.add(t);
                }
            }
            this.videoDirs = copy;
            HttpServer o = owner;
            if (o != null) o.invalidateVideoLib();
        }

        /**
         * 管理员改完图书目录后调用：立刻生效并清掉书库缓存。
         * 与影视目录同构 —— 多个目录并存，客户端按目录分组渲染。
         */
        public void setBookDirs(List<String> dirs) {
            List<String> copy = new ArrayList<>();
            if (dirs != null) {
                for (String d : dirs) {
                    if (d == null) continue;
                    String t = d.trim();
                    if (!t.isEmpty() && !copy.contains(t)) copy.add(t);
                }
            }
            this.bookDirs = copy;
            HttpServer o = owner;
            if (o != null) o.invalidateBookLib();
        }

        /**
         * 管理员改完相册目录后调用：立刻生效并清掉相册缓存。
         * 相册和图书一样是「多目录并存、源目录形态未知」，所以构造方式与 setBookDirs 同构。
         */
        public void setPhotoDirs(List<String> dirs) {
            List<String> copy = new ArrayList<>();
            if (dirs != null) {
                for (String d : dirs) {
                    if (d == null) continue;
                    String t = d.trim();
                    if (!t.isEmpty() && !copy.contains(t)) copy.add(t);
                }
            }
            this.photoDirs = copy;
            HttpServer o = owner;
            if (o != null) o.invalidatePhotoLib();
        }
    }

    // ==================== 登录会话 ====================

    /** token → 用户名 */
    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    /** token → 过期时刻（elapsedRealtime） */
    private final Map<String, Long> sessionExp = new ConcurrentHashMap<>();
    private final SecureRandom rng = new SecureRandom();

    private String sessionCreate(String user) {
        String tok = newToken();
        sessions.put(tok, user);
        sessionExp.put(tok, SystemClock.elapsedRealtime() + SESSION_TTL_MS);
        return tok;
    }

    private String sessionGet(String token) {
        Long exp = sessionExp.get(token);
        if (exp == null) return null;
        if (SystemClock.elapsedRealtime() > exp) {
            sessionKill(token);
            return null;
        }
        return sessions.get(token);
    }

    private void sessionKill(String token) {
        sessions.remove(token);
        sessionExp.remove(token);
    }

    // ---- 跨端免登录的「交接票」 ----
    //
    // 场景：桌面客户端 / 手机客户端里已经用管理员登录过一次，用户点「在浏览器
    // 打开」时希望浏览器直接就是登录态，不用再敲一遍密码；反过来在网页里登录
    // 过，客户端连上去也应该直接带上身份。
    //
    // 做法上刻意不把 sid 直接塞进 URL：sid 是要写到 Cookie 里的长期凭据，
    // 进了地址栏就会留在浏览器历史、被 Referer 带到外站、被截图、被聊天软件
    // 存一份，等于一次泄露就长期失守。这里改成发一张一次性的票（handoff
    // token），换个 Cookie 就作废：
    //   · 票只能用一次，用完立刻从表里删掉；
    //   · 票有效期 120 秒，够走完一次跳转，过时就捡不回来了；
    //   · 票只换得到「同一台机器、同一个账号」的会话，换不到更高权限；
    //   · 票本身不是 Cookie，拿到也无从直接访问别的接口。
    private static final long HANDOFF_TTL_MS = 120_000L;

    /** handoff token → 用户名 */
    private final Map<String, String> handoffs = new ConcurrentHashMap<>();
    /** handoff token → 过期时刻（elapsedRealtime） */
    private final Map<String, Long> handoffExp = new ConcurrentHashMap<>();

    private String newToken() {
        byte[] r = new byte[18];
        rng.nextBytes(r);
        StringBuilder sb = new StringBuilder();
        for (byte b : r) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    private String handoffCreate(String user) {
        // 顺手清掉过期的，免得一年不点「在浏览器打开」就攒一堆死票
        long now = SystemClock.elapsedRealtime();
        for (Map.Entry<String, Long> e : handoffExp.entrySet()) {
            if (now > e.getValue()) {
                handoffs.remove(e.getKey());
                handoffExp.remove(e.getKey());
            }
        }
        String tok = newToken();
        handoffs.put(tok, user);
        handoffExp.put(tok, now + HANDOFF_TTL_MS);
        return tok;
    }

    private void handoffKill(String token) {
        handoffs.remove(token);
        handoffExp.remove(token);
    }

    /** 取走票：取到即作废。过期或不存在返回 null。这就是「一次性」的全部实现。 */
    private String handoffTake(String token) {
        if (token == null || token.isEmpty()) return null;
        Long exp = handoffExp.get(token);
        if (exp == null) return null;
        String user = handoffs.get(token);
        handoffKill(token);
        if (SystemClock.elapsedRealtime() > exp) return null;
        return user;
    }

    public interface Listener {
        void onLog(String line);
    }

    // ==================== 常量 ====================

    private static final int SOCKET_TIMEOUT_MS = 60_000;
    /** 共享目录列表缓存时长：U 盘热插拔 2 秒内反映到网页，又不至于每次请求都扫卷 */
    private static final long ROOTS_TTL_MS = 2_000L;
    /**
     * 空闲多久后冻结目录扫描。默认 3 分钟：
     * 网页播放视频时不会有目录请求，但也不会被误判成"没人用"——
     * 播放本身是持续的文件读取，硬盘本来就不该停。
     */
    private static final long IDLE_FREEZE_MS = 180_000L;
    private static final int CHUNK = 64 * 1024;
    private static final int STREAM_BUFFER = 8 * 1024 * 1024; // 8MB，播放不卡顿
    /** 登录会话 Cookie 名 */
    private static final String COOKIE_NAME = "usbnas_sid";
    /** 会话有效期：24 小时 */
    private static final long SESSION_TTL_MS = 24 * 60 * 60 * 1000L;

    private static final Map<String, String> MIME = new HashMap<>();
    static {
        // 视频
        MIME.put("mp4", "video/mp4");
        MIME.put("m4v", "video/mp4");
        MIME.put("webm", "video/webm");
        MIME.put("mkv", "video/x-matroska");
        MIME.put("avi", "video/x-msvideo");
        MIME.put("mov", "video/quicktime");
        MIME.put("ts", "video/mp2t");
        MIME.put("flv", "video/x-flv");
        MIME.put("wmv", "video/x-ms-wmv");
        MIME.put("3gp", "video/3gpp");
        MIME.put("mpg", "video/mpeg");
        MIME.put("mpeg", "video/mpeg");
        MIME.put("rmvb", "video/vnd.rn-realvideo");
        // 音频
        MIME.put("mp3", "audio/mpeg");
        MIME.put("m4a", "audio/mp4");
        MIME.put("aac", "audio/aac");
        MIME.put("flac", "audio/flac");
        MIME.put("wav", "audio/wav");
        MIME.put("ogg", "audio/ogg");
        MIME.put("opus", "audio/opus");
        MIME.put("wma", "audio/x-ms-wma");
        MIME.put("ape", "audio/x-ape");
        MIME.put("amr", "audio/amr");
        // 图片
        MIME.put("jpg", "image/jpeg");
        MIME.put("jpeg", "image/jpeg");
        MIME.put("png", "image/png");
        MIME.put("gif", "image/gif");
        MIME.put("webp", "image/webp");
        MIME.put("bmp", "image/bmp");
        MIME.put("svg", "image/svg+xml");
        MIME.put("heic", "image/heic");
        // 文本 / 文档
        MIME.put("txt", "text/plain; charset=utf-8");
        MIME.put("log", "text/plain; charset=utf-8");
        MIME.put("lrc", "text/plain; charset=utf-8");
        MIME.put("srt", "text/plain; charset=utf-8");
        MIME.put("ass", "text/plain; charset=utf-8");
        MIME.put("vtt", "text/vtt; charset=utf-8");
        MIME.put("json", "application/json; charset=utf-8");
        MIME.put("xml", "application/xml; charset=utf-8");
        MIME.put("html", "text/html; charset=utf-8");
        MIME.put("htm", "text/html; charset=utf-8");
        MIME.put("css", "text/css; charset=utf-8");
        MIME.put("js", "application/javascript; charset=utf-8");
        MIME.put("pdf", "application/pdf");
        MIME.put("epub", "application/epub+zip");
        // Office 文档的 MIME。预览服务靠 URL 后缀判断格式，不靠这个头，
        // 但「下载 / 直连打开」时需要它，系统才知道该派给哪个应用
        MIME.put("doc", "application/msword");
        MIME.put("docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        MIME.put("xls", "application/vnd.ms-excel");
        MIME.put("xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        MIME.put("ppt", "application/vnd.ms-powerpoint");
        MIME.put("pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        MIME.put("rtf", "application/rtf");
        MIME.put("odt", "application/vnd.oasis.opendocument.text");
        MIME.put("ods", "application/vnd.oasis.opendocument.spreadsheet");
        MIME.put("odp", "application/vnd.oasis.opendocument.presentation");
        MIME.put("csv", "text/csv; charset=utf-8");
        MIME.put("zip", "application/zip");
        MIME.put("rar", "application/vnd.rar");
        MIME.put("7z", "application/x-7z-compressed");
        MIME.put("apk", "application/vnd.android.package-archive");
    }

    /**
     * 可直接在浏览器 &lt;video&gt;/&lt;audio&gt; 里播的扩展名。
     *
     * mkv 放进来是「先试再劝退」：Chrome 不认识 Matroska 这个容器名，
     * 但里面的轨如果是 H.264/AAC，直接交给原生 video 往往能出声出画，
     * 还能拖进度（走的是我们自己的 Range 实现，不依赖分片）。
     * 探不通才落回「下载 / VLC 直连」的提示，不乱承诺。
     *
     * 纯 H.265 的 mkv 依旧放不了——浏览器根本没有 HEVC 解码器，
     * 那是编码问题不是容器问题，页面会把原因写清楚。
     */
    private static final List<String> BROWSER_VIDEO = Arrays.asList(
            "mp4", "m4v", "webm", "mov", "mkv");
    private static final List<String> BROWSER_AUDIO = Arrays.asList(
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus");

    /**
     * 浏览器自己能渲染的文档：pdf 交给内置的 PDF viewer（Chrome/Edge/Firefox
     * 都有，带缩放、翻页、文字选择，比自己写个 canvas 渲染器强得多）。
     * epub 只有 Firefox 认（内置 epub 阅读器），Chrome 会直接下载 —— 拦不住，
     * 所以 epub 走 Office 那套预览页：先提示，再给浏览器试的机会。
     */
    private static final List<String> BROWSER_PDF = Arrays.asList("pdf");

    /**
     * Office 文档。浏览器原生一个都不认识（doc/xls/ppt 是二进制复合文档，
     * docx/xlsx/pptx 是 zip），必须靠外部服务或在线转换。
     *
     * 这里只做「就地预览页」：iframe 指向可配置的在线预览服务（默认微软
     * Office Online Viewer），失败时给出下载和换服务的入口。
     * 不外发任何用户凭据 —— 预览服务拿到的只是我们自己拼的一条公网地址。
     */
    private static final List<String> OFFICE_DOC = Arrays.asList(
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf", "odt", "ods", "odp",
            "csv", "epub");

    /** 能就地当纯文本读出来显示的扩展名（不含 html/svg，避免同源脚本执行） */
    private static final List<String> PLAIN_TEXT = Arrays.asList(
            "txt", "log", "lrc", "srt", "ass", "vtt", "json", "xml", "css", "js",
            "md", "ini", "conf", "cfg", "yaml", "yml", "sh", "bat", "java", "kt",
            "py", "c", "h", "cpp", "go", "rs", "sql");

    private static final String[][] ICON = {
            // {扩展名, 图标名}
            // 图标名对应 SVG 表里的键，不再用 emoji —— emoji 在不同系统上
            // 由各自的字体渲染，Android 上是 Noto、Windows 上是 Segoe UI Emoji、
            // iOS 上是 Apple Color Emoji，同一个 📄 能长出三种完全不同的样子，
            // 而且彩色字形不受 CSS color 控制，日夜切换时永远是一片彩色。
            {"mp4", "video"}, {"mkv", "video"}, {"avi", "video"}, {"mov", "video"},
            {"webm", "video"}, {"flv", "video"}, {"ts", "video"}, {"wmv", "video"},
            {"rmvb", "video"}, {"mpg", "video"}, {"mpeg", "video"}, {"3gp", "video"},
            {"mp3", "music"}, {"flac", "music"}, {"m4a", "music"}, {"wav", "music"},
            {"aac", "music"}, {"ogg", "music"}, {"ape", "music"}, {"wma", "music"},
            {"lrc", "lyric"}, {"srt", "lyric"}, {"ass", "lyric"}, {"vtt", "lyric"},
            {"jpg", "image"}, {"jpeg", "image"}, {"png", "image"}, {"gif", "image"},
            {"webp", "image"}, {"bmp", "image"}, {"heic", "image"},
            {"zip", "zip"}, {"rar", "zip"}, {"7z", "zip"},
            {"apk", "android"}, {"pdf", "pdf"}, {"epub", "book"},
            {"txt", "text"}, {"log", "text"}, {"json", "text"}, {"xml", "text"},
            {"doc", "word"}, {"docx", "word"}, {"rtf", "word"}, {"odt", "word"},
            {"xls", "sheet"}, {"xlsx", "sheet"}, {"csv", "sheet"}, {"ods", "sheet"},
            {"ppt", "slide"}, {"pptx", "slide"}, {"odp", "slide"},
    };

    // ==================== 字段 ====================

    private final Config cfg;
    /** 启动时传入的共享目录，作为 supplier 缺席时的兜底 */
    private final List<File> roots;
    private final Listener listener;

    /** 共享目录缓存：U 盘热插拔要能及时反映，又不能每个请求都扫一遍 /storage */
    private volatile List<File> rootsCache;
    private volatile long rootsCacheAt;

    /**
     * 最近一次有客户端来访的时刻（elapsedRealtime）。
     * 空闲超过 IDLE_FREEZE_MS 就冻结目录扫描 —— 机械硬盘只要没人读就不会被唤醒，
     * 才有机会靠 USB runtime PM 停转。任何新请求都会把它刷新，并立刻解冻。
     */
    private volatile long lastAccessAt = SystemClock.elapsedRealtime();
    /** 目录扫描是否处于冻结状态（只是省掉扫描，不影响读写文件） */
    private volatile boolean rootsFrozen;

    /**
     * 上一次吐页面时用的主题（"light" / "dark"）。
     *
     * 有了它，服务端就能自己决定 <head> 里那份 LIGHT_CSS 要不要生效 ——
     * 不再把「该显示日间还是夜间」这件事全押在浏览器脚本上。老 WebView 里
     * localStorage、matchMedia、cssRules 任何一样瘸了，?theme=light 就不会应验。
     *
     * 语义是「同一个访客最近一次明确表达过的偏好」：请求带 ?theme= 时立刻改写，
     * 之后的普通请求沿用同一个值，所以点完按钮再点进目录，颜色不会跳回夜间。
     */
    private volatile String prefTheme = "dark";

    private ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService pool;
    private Thread acceptThread;

    private final AtomicLong reqCount = new AtomicLong(0);
    private final AtomicLong bytesOut = new AtomicLong(0);
    private final AtomicLong bytesIn = new AtomicLong(0);
    private final Object bpsLock = new Object();
    private long bpsLastAt, bpsLastVal, bpsOut;
    private long inLastAt, inLastVal, bpsIn;

    public HttpServer(Config cfg, List<File> roots, Listener listener) {
        this.cfg = cfg;
        this.cfg.owner = this;
        this.roots = roots;
        this.listener = listener;
    }

    /**
     * 当前共享目录列表。
     * 启动后插入/拔出 U 盘必须立刻反映到网页端，所以这里按需重新取，
     * 并用 ROOTS_TTL_MS 做短缓存，避免一次页面刷新扫多次 /storage。
     */
    private List<File> liveRoots() {
        Config.RootsSupplier sup = cfg.rootsSupplier;
        if (sup == null) return roots;
        long now = SystemClock.elapsedRealtime();
        List<File> cached = rootsCache;
        if (cached != null && now - rootsCacheAt < ROOTS_TTL_MS) return cached;

        // 空闲冻结：没人来访问就别去扫卷，让机械盘安心停转。
        // 注意这里只是"不主动扫"，缓存里的目录仍然可用，
        // 真要取文件时 resolve() 走的是路径拼接，不受影响。
        if (cached != null && now - lastAccessAt > IDLE_FREEZE_MS) {
            rootsFrozen = true;
            return cached;
        }
        rootsFrozen = false;

        List<File> fresh;
        try {
            fresh = sup.get();
        } catch (Throwable e) {
            fresh = null;
        }
        if (fresh == null || fresh.isEmpty()) {
            // 拿不到就退回启动快照，别让页面空白
            if (cached != null) return cached;
            return roots;
        }
        rootsCache = fresh;
        rootsCacheAt = now;
        return fresh;
    }

    /** 强制下次访问时重新扫描（U 盘插拔广播进来时调用） */
    public void invalidateRoots() {
        rootsCacheAt = 0;
        rootsFrozen = false;
    }

    /** 目录扫描当前是否被冻结（空闲省电中） */
    public boolean isRootsFrozen() { return rootsFrozen; }

    /** 最近一次客户端来访距今多少毫秒 */
    public long idleMs() { return SystemClock.elapsedRealtime() - lastAccessAt; }

    public int getPort() { return cfg.port; }
    public long getReqCount() { return reqCount.get(); }
    public long getBytesOut() { return bytesOut.get(); }
    public boolean isRunning() { return running.get(); }

    // ==================== 生命周期 ====================

    public void start() throws IOException {
        if (running.get()) return;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("0.0.0.0", cfg.port));
        running.set(true);

        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "usbnas-http");
            t.setDaemon(true);
            return t;
        });

        acceptThread = new Thread(() -> {
            while (running.get()) {
                try {
                    Socket s = serverSocket.accept();
                    pool.execute(() -> handle(s));
                } catch (IOException e) {
                    if (running.get()) log("accept 异常: " + e.getMessage());
                }
            }
        }, "usbnas-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        log("HTTP 服务已启动 0.0.0.0:" + cfg.port);
    }

    public void stop() {
        running.set(false);
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        if (pool != null) pool.shutdownNow();
        log("HTTP 服务已停止");
    }

    private void log(String s) {
        if (listener != null) listener.onLog(s);
    }

    // ==================== 连接处理 ====================

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(SOCKET_TIMEOUT_MS);
            sock.setTcpNoDelay(true);

            InputStream in = new BufferedInputStream(sock.getInputStream(), CHUNK);
            OutputStream out = new BufferedOutputStream(sock.getOutputStream(), CHUNK);

            Request req = readRequest(in);
            if (req == null) return;
            // 记下对端地址：/_login/quick 靠它判断是不是本机来的
            try {
                if (s.getInetAddress() != null) {
                    req.remoteAddr = s.getInetAddress().getHostAddress();
                }
            } catch (Exception ignored) {
            }
            reqCount.incrementAndGet();
            lastAccessAt = SystemClock.elapsedRealtime();
            // ?theme=light / ?theme=dark / ?theme=ink：老浏览器上脚本靠不住时，这是唯一
            // 能真正生效的切换入口。请求走到这里就把偏好记下，本页和后续
            // 页面都由服务端直接吐对应的覆盖样式。
            String tq = req.param("theme");
            if ("light".equals(tq) || "dark".equals(tq) || "ink".equals(tq)) prefTheme = tq;
            req.user = cfg.auth(req);

            // ---- 认证 ----
            if (!authorized(req)) {
                sendUnauthorized(out, req);
                return;
            }

            dispatch(req, in, out);
            out.flush();

        } catch (Exception e) {
            // 客户端断开 / 超时 属于正常噪声
            String m = e.getMessage();
            if (m != null && !m.contains("Broken pipe") && !m.contains("reset by peer")
                    && !m.contains("Socket closed")) {
                log("连接异常: " + m);
            }
        }
    }

    /**
     * 访问控制：
     *   无账号 → 全部放行（等同单机模式）
     *   匿名开启 → GET/HEAD 放行，写操作必须登录
     *   匿名关闭 → 所有请求必须登录
     * 例外：/_login、/_logout、/favicon.ico 永远放行，否则连登录页都进不去。
     * /_del、/_delmulti、/lyrics/save 放行到 handler 内部自查 —— 它们用的是
     * 更细的「该目录写/删权限」判定，在这里按登录态一刀切会挡住合法的
     * 「匿名可读 + 有账号但未登录时点保存歌词」这种场景（结果仍是拒绝，
     * 但提示从「需要登录」变成「没有该目录的写权限」，用户才知道缺什么）。
     */
    private boolean authorized(Request req) {
        String p = req.path;
        // 这几个不让 authorized() 一眼挡死，是为了让各自的 handler 能回
        // 一句有信息量的话（比如「没有这个目录的写权限」而不是笼统的「需要
        // 登录」）。真正的权限判定都在 handler 里，绕过这里并不等于放行。
        if (p != null && (p.equals("/_login") || p.equals("/_login/quick") || p.equals("/_logout")
                || p.equals("/_login/handoff") || p.equals("/_login/sso")
                || p.equals("/favicon.ico") || p.equals("/_del")
                || p.equals("/_delmulti") || p.equals("/lyrics/save")
                || p.equals("/_rename") || p.equals("/_move")
                || p.equals("/_dirlist"))) {
            return true;
        }
        // 曲库清单与 /_dirlist 同性质：只吐文件路径，不吐内容。
        // 放了匿名读就跟着放匿名读，没放就仍然要登录 —— 否则等于开了个
        // 不需要账号就能枚举全盘的洞。
        if (p != null && p.equals("/_admin")) {
            // 管理页自己要能回一句「需要管理员」，所以不在这一层一刀切挡掉；
            // 真正的等级判定在 handleAdmin 里，匿名一律进不来。
            return true;
        }
        if (p != null && p.equals("/_admin/api")) {
            // 同上：让客户端拿到 {"admin":false,...} 而不是一个 401，
            // 好去显示「用管理员登录」而不是「连不上」。
            return true;
        }
        if (p != null && p.equals("/_music")) {
            return !cfg.hasAccounts() || req.user != null || cfg.anonymousRead;
        }
        // 内嵌封面：性质和「列表里能看到的图」一样，判定与曲库一致
        if (p != null && p.equals("/cover")) {
            return !cfg.hasAccounts() || req.user != null || cfg.anonymousRead;
        }
        // 影视库清单与刮削信息：同样只吐路径和元数据，判定与曲库一致
        if (p != null && (p.equals("/_videos") || p.equals("/_videos/meta"))) {
            return !cfg.hasAccounts() || req.user != null || cfg.anonymousRead;
        }
        // 书库清单：同性质，只吐书名和路径，不吐正文
        if (p != null && p.equals("/_books")) {
            return !cfg.hasAccounts() || req.user != null || cfg.anonymousRead;
        }
        // 相册清单：只吐路径和尺寸，判定与曲库一致
        if (p != null && p.equals("/_photos")) {
            return !cfg.hasAccounts() || req.user != null || cfg.anonymousRead;
        }
        // 相册删除：写操作，一律先落到 handler 里判管理员等级，
        // 在这一层放行（否则匿名会拿到 401，客户端没法区分「没登录」和「不是管理员」）
        if (p != null && p.equals("/_photos/delete")) {
            return true;
        }
        // 书库正文抽取：这是真·正文，但它和 /view、/play/* 是同一层东西 ——
        // 能看列表就能看内容（文件本身也一直是这么发的）。差别在于这里给了
        // resolve() + inDirs() 两道约束，只能抽书库目录里的书。
        if (p != null && p.equals("/_books/text")) {
            return !cfg.hasAccounts() || req.user != null || cfg.anonymousRead;
        }
        if (!cfg.hasAccounts()) return true;

        if (req.user != null) return true;

        boolean readMethod = "GET".equals(req.method) || "HEAD".equals(req.method)
                || "OPTIONS".equals(req.method) || "PROPFIND".equals(req.method);

        return cfg.anonymousRead && readMethod;
    }

    /** 写操作（PUT / MKCOL / MOVE）权限；target 为 null 时只查等级 */
    private boolean canWrite(Request req) {
        return canWrite(req, (File) null);
    }

    private boolean canWrite(Request req, File target) {
        if (!cfg.hasAccounts()) return false;
        if (req.user == null || !Users.canWrite(req.user.level)) return false;
        if (target == null) return true;
        return Users.inScope(req.user, target);
    }

    /** 删除权限；target 为 null 时只查等级 */
    private boolean canDelete(Request req) {
        return canDelete(req, (File) null);
    }

    private boolean canDelete(Request req, File target) {
        if (!cfg.hasAccounts()) return false;
        if (req.user == null || !Users.canDelete(req.user.level)) return false;
        if (target == null) return true;
        return Users.inScope(req.user, target);
    }

    /** 表单体上限：/_login 只有两个短字段，/_del 批量删除要装几百条绝对路径 */
    private static final int MAX_FORM_BYTES = 256 * 1024;

    /**
     * 读取 application/x-www-form-urlencoded 表单体。
     * 给 /_login 和 /_del 用，最多读 256KB，防止有人拿它当上传通道。
     */
    private Map<String, String> readForm(InputStream in, long len) {
        Map<String, String> m = new HashMap<>();
        if (len <= 0 || len > MAX_FORM_BYTES) return m;
        byte[] buf = new byte[(int) len];
        int off = 0;
        while (off < len) {
            int r;
            try {
                r = in.read(buf, off, (int) len - off);
            } catch (IOException e) {
                return m;
            }
            if (r < 0) break;
            off += r;
        }
        String body = new String(buf, 0, off, StandardCharsets.UTF_8);
        for (String kv : body.split("&")) {
            if (kv.isEmpty()) continue;
            int e = kv.indexOf('=');
            String k, v;
            if (e < 0) { k = kv; v = ""; }
            else { k = kv.substring(0, e); v = kv.substring(e + 1); }
            m.put(formDecode(k), formDecode(v));
        }
        return m;
    }

    /**
     * 表单值解码。
     *
     * URLDecoder.decode 是给 application/x-www-form-urlencoded 用的，它会把
     * 裸加号当成空格 —— 这恰好是文件名里的加号（a+b.txt 变成 "a b.txt"），
     * 于是「勾选删除」报「找不到选中的项目」，而直接点单个文件又没事。
     * 这里先把加号换成 %2B 再解，加号就永远是加号。
     */
    private static String formDecode(String s) {
        if (s == null || s.isEmpty()) return s;
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s.replace("+", "%2B");
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    /** 统一的 403 输出 */
    private void sendForbidden(OutputStream out, String need) throws IOException {        String body = "<!doctype html><meta charset=utf-8><body style='font-family:sans-serif;"
                + "background:#0e1116;color:#e6e9ef;text-align:center;padding:80px'>"
                + "<h2>权限不足</h2><p>需要「" + esc(need) + "」权限，请用更高权限账号登录</p>"
                + "</body>";
        sendBytes(out, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8),
                403, "Forbidden");
    }

    private void sendUnauthorized(OutputStream out, Request req) throws IOException {
        // 401 一定要走 renderLogin 那张表单页，不能只回一句「需要登录」。
        //
        // 原来这里是一段极简 HTML，问题有两个：
        // 1. 带 WWW-Authenticate: Basic —— 浏览器（Via 这类 WebView 壳尤其明显）
        //    一看到它就去弹原生认证框，用户在那个框里登录成功了，但那个框不写
        //    cookie，页面照样是匿名态。这就是「登录后还是匿名」的直接原因之一。
        // 2. 页面里连一个输入框都没有 —— 匿名关闭时，除了手动敲 /_login，
        //    没有任何登录入口。
        //
        // 现在回完整登录表单，并把当前访问地址带进 back，登完跳回原处。
        // 状态码仍保留 401，让 WebDAV 客户端能识别出「需要凭据」，
        // 但不带 WWW-Authenticate，浏览器就不会弹原生框。
        String back = (req == null || req.path == null) ? "/" : req.path;
        // params 是按 key 拆开存的，没有原始 query 串可以还原；
        // 只保留 path 就够了 —— 登录后的跳转目标是目录，不是某次筛选。
        String html = renderLogin("", "需要登录才能访问这个页面", back);
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 401 Unauthorized\r\n");
        sb.append("Content-Type: text/html; charset=utf-8\r\n");
        sb.append("Content-Length: ").append(b.length).append("\r\n");
        sb.append("Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    // ==================== 路由 ====================

    private void dispatch(Request req, InputStream in, OutputStream out) throws Exception {
        String path = req.path;

        // 内置页面
        if (path.equals("/") || path.equals("/index.html")) {
            sendHtml(out, renderIndex(req));
            return;
        }
        if (path.equals("/favicon.ico")) {
            sendBytes(out, "image/x-icon", new byte[0]);
            return;
        }
        if (path.equals("/_status")) {
            sendJson(out, statusJson(req));
            return;
        }
        if (path.equals("/_admin")) {
            // 网页端管理页：曲库/影视目录、站点名称这些「设一次就不再动」的东西
            // 放在这里，而不是全塞进 App 里 —— 用别人的电脑打开网页就能配。
            handleAdmin(req, in, out);
            return;
        }
        if (path.equals("/_admin/api")) {
            // 同一套设置的 JSON 版：exe / apk 客户端的管理界面走这里
            handleAdminApi(req, in, out);
            return;
        }
        if (path.equals("/_idle")) {
            // 给网页端和 App 查空闲状态用：是否已冻结目录扫描、空闲了多久
            sendJson(out, "{\"ok\":true,\"idleMs\":" + idleMs()
                    + ",\"frozen\":" + rootsFrozen
                    + ",\"freezeAfterMs\":" + IDLE_FREEZE_MS + "}");
            return;
        }
        if (path.equals("/_login")) {
            // 表单登录：不再让浏览器弹 Basic 认证框
            if ("POST".equalsIgnoreCase(req.method)) {
                // readRequest 只解析 query string，表单体要在这里自己读
                Map<String, String> form = readForm(in, req.contentLength());
                String u = form.containsKey("u") ? form.get("u") : "";
                String p = form.containsKey("p") ? form.get("p") : "";
                Users.User ok = cfg.users == null ? null : cfg.users.verify(u, p);
                if (ok != null) {
                    String token = sessionCreate(u);
                    byte[] body = "已登录".getBytes(StandardCharsets.UTF_8);
                    // 表单里带了 back 就跳回去（匿名关闭时登完继续待在原目录）。
                    // 只放行站内相对路径：'//evil.com' 这种协议相对 URL 会把
                    // 用户带着 cookie 甩到外站去。
                    String back = form.containsKey("back") ? form.get("back") : "/";
                    if (back == null || back.isEmpty() || back.charAt(0) != '/'
                            || back.startsWith("//")) {
                        back = "/";
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append("HTTP/1.1 303 See Other\r\n");
                    sb.append("Location: ").append(back).append("\r\n");
                    sb.append("Set-Cookie: ").append(COOKIE_NAME).append('=').append(token)
                            .append("; Path=/; Max-Age=86400; SameSite=Lax\r\n");
                    sb.append("Content-Type: text/plain; charset=utf-8\r\n");
                    sb.append("Content-Length: ").append(body.length).append("\r\n");
                    sb.append("Connection: close\r\n\r\n");
                    out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                    out.write(body);
                    return;
                }
                sendHtml(out, 401, renderLogin(u, "用户名或密码不对", "/"));
                return;
            }
            // GET：把登录表单画出来。
            //
            // 这里原来只有 `return;` —— 浏览器用 <a href='/_login'> 点进来是 GET，
            // 走到这儿一个字节都不写就 return，dispatch 收尾时 socket 被静默关掉，
            // 客户端拿到的是「Empty reply from server」。Via 上表现为
            // 「当前无法使用此页面」，边上的首页却好好的，特别容易误判成服务端崩了。
            // 注意状态码用 200：401 会让某些浏览器弹 Basic 认证框。
            String pre = req.param("u");
            String backq = req.param("back");
            sendHtml(out, renderLogin(pre == null ? "" : pre, null,
                    backq == null || backq.isEmpty() ? "/" : backq));
            return;
        }
        if (path.equals("/_login/quick")) {
                // GET 快捷登录：只给调试用。
                // 起因是这台手机的浏览器（Via，WebView 套壳）里 <input> 拿不到
                // IME 的 commitText —— input text / keyevent 全试过，EditText 的
                // focused 变 true、文字始终是空串。于是没法在浏览器里登录验证
                // 涉及写权限的界面。这里开一条 GET 通道，把凭据放 query string 里，
                // 直接种 cookie 跳回首页。
                // 限制：只认本机回环（RemoteAddr 必须是 127.0.0.1 / ::1），
                // 局域网里别人打这个地址会被拒；凭据照样走 cfg.users.verify，
                // 没有绕开密码校验。
                String ra = req.remoteAddr == null ? "" : req.remoteAddr;
                boolean loopback = ra.startsWith("127.") || ra.equals("::1") || ra.equals("0:0:0:0:0:0:0:1");
                if (!loopback) {
                    sendHtml(out, 403, errorPage("这个入口只允许从手机本机访问"));
                    return;
                }
                String u = req.param("u") == null ? "" : req.param("u");
                String p = req.param("p") == null ? "" : req.param("p");
                Users.User ok = cfg.users == null ? null : cfg.users.verify(u, p);
                if (ok == null) {
                    sendHtml(out, 401, renderLogin(u, "用户名或密码不对", "/"));
                    return;
                }
                String token = sessionCreate(u);
                String back = req.param("back") == null ? "/" : req.param("back");
                byte[] body = "已登录".getBytes(StandardCharsets.UTF_8);
                StringBuilder sb = new StringBuilder();
                sb.append("HTTP/1.1 303 See Other\r\n");
                sb.append("Location: ").append(back).append("\r\n");
                sb.append("Set-Cookie: ").append(COOKIE_NAME).append('=').append(token)
                        .append("; Path=/; Max-Age=86400; SameSite=Lax\r\n");
                sb.append("Content-Type: text/plain; charset=utf-8\r\n");
                sb.append("Content-Length: ").append(body.length).append("\r\n");
                sb.append("Connection: close\r\n\r\n");
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                out.write(body);
                return;
        }

        if (path.equals("/_login/handoff")) {
            // 客户端已经登录过（桌面端/手机端自己验过密码），要打开浏览器却不想
            // 再敲一遍密码时走这里：把服务端会话换一张一次性票，拿票去
            // /_login/sso 换 Cookie。
            //
            // 凭据来源只认 req.user —— 也就是 Cookie 或者 Basic Auth 里已经生效的
            // 会话，不接受 query 里的用户名密码。这样即使票泄露，攻击面也只是
            // 「这个已经登录的会话能做什么」，而不是「拿着密码永久登录」。
            if (req.user == null) {
                sendJson(out, "{\"ok\":false,\"error\":\"先登录再取票\"}");
                return;
            }
            String t = handoffCreate(req.user.name);
            // jsonEsc 只转义不包引号，这里得自己补上，否则吐出去的是一段非法 JSON
            sendJson(out, "{\"ok\":true,\"token\":\"" + jsonEsc(t)
                    + "\",\"ttlMs\":" + HANDOFF_TTL_MS
                    + ",\"user\":\"" + jsonEsc(req.user.name)
                    + "\",\"anonymousRead\":" + cfg.anonymousRead + "}");
            return;
        }

        if (path.equals("/_login/sso")) {
            // 拿一次性票换 Cookie 并跳回目标页。票据在 handoffTake 里取走即作废，
            // 所以浏览器刷新这个地址只会得到一句「票已用过」，不会变成可反复
            // 使用的登录链接。
            String t = req.param("t");
            String back = req.param("back");
            if (back == null || back.isEmpty() || back.charAt(0) != '/' || back.startsWith("//")) {
                back = "/";
            }
            String user = handoffTake(t);
            if (user == null) {
                sendHtml(out, 401, renderLogin("", "这个免登录链接已经用过或已过期，请重新点一次", back));
                return;
            }
            String token = sessionCreate(user);
            byte[] body = "已登录".getBytes(StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 303 See Other\r\n");
            sb.append("Location: ").append(back).append("\r\n");
            sb.append("Set-Cookie: ").append(COOKIE_NAME).append('=').append(token)
                    .append("; Path=/; Max-Age=86400; SameSite=Lax\r\n");
            // 交接地址本身就带票据，别让浏览器缓存它，也别把它写进历史之外的地方
            sb.append("Cache-Control: no-store\r\n");
            sb.append("Referrer-Policy: no-referrer\r\n");
            sb.append("Content-Type: text/plain; charset=utf-8\r\n");
            sb.append("Content-Length: ").append(body.length).append("\r\n");
            sb.append("Connection: close\r\n\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.write(body);
            return;
        }

        if (path.equals("/_logout")) {
            // 清掉服务端会话 + 让浏览器删掉 cookie
            String ck = req.headers.get("cookie");
            if (ck != null) {
                for (String part : ck.split(";")) {
                    int eq = part.indexOf('=');
                    if (eq > 0 && part.substring(0, eq).trim().equals(COOKIE_NAME)) {
                        sessionKill(part.substring(eq + 1).trim());
                    }
                }
            }
            byte[] body = "已退出".getBytes(StandardCharsets.UTF_8);
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 303 See Other\r\n");
            sb.append("Location: /\r\n");
            sb.append("Set-Cookie: ").append(COOKIE_NAME).append("=; Path=/; Max-Age=0\r\n");
            sb.append("Content-Type: text/plain; charset=utf-8\r\n");
            sb.append("Content-Length: ").append(body.length).append("\r\n");
            sb.append("Connection: close\r\n\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.write(body);
            return;
        }

        // WebDAV
        if (path.equals("/dav") || path.startsWith("/dav/")) {
            handleDav(req, in, out, path.substring(4));
            return;
        }

        // 音乐播放页
        if (path.equals("/play/music")) {
            String p = req.param("f");
            sendHtml(out, renderMusic(req, p));
            return;
        }

        // 视频播放页
        if (path.equals("/play/video")) {
            String p = req.param("f");
            sendHtml(out, renderVideo(req, p));
            return;
        }

        // 文档预览页（PDF / Office / 文本）
        if (path.equals("/view")) {
            String p = req.param("f");
            sendHtml(out, renderDoc(req, p));
            return;
        }

        // 预览渲染器的脚本。从 APK 的 assets 里读，不走 CDN ——
        // 这台设备很可能挂在一个没有外网的热点上。
        if (path.startsWith("/docview/")) {
            sendAsset(out, path.substring("/docview/".length()), "application/javascript");
            return;
        }

        // 歌词接口
        if (path.equals("/lyrics")) {
            handleLyrics(req, out);
            return;
        }

        // 封面：按音频文件路径吐出内嵌封面（ID3 APIC / FLAC PICTURE）。
        // 客户端播放页拿它贴到唱片中心；目录里没有同名图时全靠这条腿。
        if (path.equals("/cover")) {
            handleCover(req, out);
            return;
        }

        // 相册清单：客户端「相册」页签一次拿一屏缩略图，按目录分组
        if (path.equals("/_photos")) {
            handlePhotoLib(req, out);
            return;
        }

        // 批量删除照片：客户端相册多选后提交。只动相册目录里的图片文件，
        // 且要求管理员（删盘上文件是写操作，和改目录配置同级）。
        if (path.equals("/_photos/delete")) {
            handlePhotoDelete(req, in, out);
            return;
        }

        // 曲库清单：客户端播放器一次性拿全量列表，而不是像网页端一首一首点
        if (path.equals("/_music")) {
            handleMusicLib(req, out);
            return;
        }

        // 影视库清单：客户端影视板块按 dirs/groups 分组显示（盘0、盘1 并存）
        if (path.equals("/_videos")) {
            handleVideoLib(req, out);
            return;
        }

        // 影视刮削信息按需拉，一次一部（列表里不带，省流量也省首页等待）
        if (path.equals("/_videos/meta")) {
            handleVideoMeta(req, out);
            return;
        }

        // 图书库清单：与影视库同构，客户端书库板块按 dirs/groups 分组显示
        if (path.equals("/_books")) {
            handleBookLib(req, out);
            return;
        }
        // 书库正文抽取：客户端内嵌阅读用，docx/doc/epub/txt 都转成纯文本
        if (path.equals("/_books/text")) {
            handleBookText(req, out);
            return;
        }

        // 在线搜歌词（只在用户主动点击时触发，不批量跑）
        if (path.equals("/lyrics/search")) {
            handleLyricSearch(req, out);
            return;
        }

        // 把选中的歌词写到音频同目录
        if (path.equals("/lyrics/save")) {
            handleLyricSave(req, in, out);
            return;
        }

        // 影视刮削信息（Kodi/Emby/Jellyfin 的 nfo + 海报同人图）
        if (path.equals("/scrape")) {
            handleScrape(req, out);
            return;
        }

        // 网页端删除（浏览器页面上的 🗑 按钮走这里，POST 表单）
        if (path.equals("/_del")) {
            handleWebDelete(req, in, out);
            return;
        }

        // 复选框批量删除。单独一个路由：/del 是单文件语义，混在一起会让
        // 「误点一次删掉一整个目录」变得可能。
        if (path.equals("/_delmulti")) {
            handleBatchDelete(req, in, out);
            return;
        }

        // 重命名 / 移动。两个都只接受 POST，都走 canWrite（不是 canDelete：
        // 改名和搬运都属于「改这一块目录」，只读账号不该能做）。
        if (path.equals("/_rename")) {
            handleRename(req, in, out);
            return;
        }
        if (path.equals("/_move")) {
            handleMove(req, in, out);
            return;
        }
        // 移动对话框要选目标目录，这个接口回目录列表（只回子目录，不回文件）
        if (path.equals("/_dirlist")) {
            handleDirList(req, out);
            return;
        }

        // 打包下载
        if (path.equals("/zip")) {
            handleZip(req, out);
            return;
        }

        // 文件操作
        if (path.equals("/upload") && "POST".equals(req.method)) {
            handleUpload(req, in, out);
            return;
        }

        switch (req.method) {
            case "GET":
            case "HEAD":
                serveFileOrDir(req, out);
                break;
            case "PUT":
                handlePut(req, in, out);
                break;
            case "DELETE":
                handleDelete(req, out);
                break;
            default:
                sendError(out, 405, "不支持的方法: " + req.method);
        }
    }

    // ==================== 路径解析（防目录穿越） ====================

    /**
     * 把 URL 路径安全地映射到某个 root 下的真实文件。
     * 拒绝 .. 穿越、符号链接逃逸。
     */
    private File resolve(String urlPath) {
        if (urlPath == null || urlPath.isEmpty()) urlPath = "/";
        // 解码。
        //
        // 表单里的 p 已经在 readForm 阶段解过一次码了，这里是第二次 ——
        // 而 URLDecoder.decode 会把裸加号当成空格，于是文件名 a+b.txt
        // 变成 "a b.txt"，resolve 返回 null，页面报「找不到选中的项目」。
        // 加号在文件名里是合法字符，不该在这一层被吃掉，所以先护住。
        String decoded;
        try {
            decoded = URLDecoder.decode(urlPath.replace("+", "%2B"), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            decoded = urlPath.replace("+", "%2B");
        } catch (IllegalArgumentException e) {
            decoded = urlPath;
        }
        // 归一化：干掉 // 和结尾 /
        decoded = decoded.replaceAll("/{2,}", "/");
        while (decoded.length() > 1 && decoded.endsWith("/")) {
            decoded = decoded.substring(0, decoded.length() - 1);
        }

        // 先按绝对路径找：/browse/storage/emulated/0 传进来就是 /storage/emulated/0，
        // 必须命中根目录本身，不能再拼到别的根下面去。
        for (File root : liveRoots()) {
            if (!root.exists() || !root.canRead()) continue;
            String rootCanon = canonical(root);
            String absCanon = canonical(new File(decoded));
            if (absCanon.equals(rootCanon) || absCanon.startsWith(rootCanon + File.separator)) {
                File abs = new File(decoded);
                if (abs.exists()) return abs;
            }
        }

        for (File root : liveRoots()) {
            if (!root.exists() || !root.canRead()) continue;
            File f = new File(root, decoded);
            if (!f.exists()) continue;
            String rootCanon = canonical(root);
            String fCanon = canonical(f);
            if (fCanon.equals(rootCanon) || fCanon.startsWith(rootCanon + File.separator)) {
                return f;
            }
        }
        return null;
    }

    private static String canonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            return f.getAbsolutePath();
        }
    }

    // ==================== 文件 / 目录 ====================

    private void serveFileOrDir(Request req, OutputStream out) throws IOException {
        File f = resolve(req.path);
        if (f == null) {
            sendError(out, 404, "找不到: " + req.path);
            return;
        }
        if (f.isDirectory()) {
            sendHtml(out, renderListing(req, f));
        } else {
            sendFile(req, out, f);
        }
    }

    /**
     * 核心：带 Range 的文件下发。
     * 这是"免下载看视频"能成立的全部秘密。
     */
    private void sendFile(Request req, OutputStream out, File f) throws IOException {
        long total = f.length();
        String mime = mimeOf(f.getName());
        String range = req.headers.get("range");

        boolean head = "HEAD".equals(req.method);

        if (range != null && range.startsWith("bytes=")) {
            // ---- 解析 Range ----
            String spec = range.substring(6).trim();
            long start, end;

            int dash = spec.indexOf('-');
            if (dash < 0) {
                sendError(out, 416, "Range 格式错误");
                return;
            }
            String s1 = spec.substring(0, dash).trim();
            String s2 = spec.substring(dash + 1).trim();

            try {
                if (s1.isEmpty()) {
                    // suffix: bytes=-500 → 最后 500 字节
                    long suffix = Long.parseLong(s2);
                    if (suffix <= 0) { sendError(out, 416, "Range 非法"); return; }
                    start = Math.max(0, total - suffix);
                    end = total - 1;
                } else {
                    start = Long.parseLong(s1);
                    if (s2.isEmpty()) {
                        end = total - 1;
                    } else {
                        end = Long.parseLong(s2);
                    }
                }
            } catch (NumberFormatException e) {
                sendError(out, 416, "Range 非法");
                return;
            }

            if (end >= total) end = total - 1;
            if (start > end || start < 0) {
                out.write(("HTTP/1.1 416 Range Not Satisfiable\r\n"
                        + "Content-Range: bytes */" + total + "\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }

            long len = end - start + 1;

            StringBuilder h = new StringBuilder();
            h.append("HTTP/1.1 206 Partial Content\r\n");
            h.append("Server: ").append(cfg.title).append("\r\n");
            h.append("Date: ").append(httpDate()).append("\r\n");
            h.append("Content-Type: ").append(mime).append("\r\n");
            h.append("Accept-Ranges: bytes\r\n");
            h.append("Content-Length: ").append(len).append("\r\n");
            h.append("Content-Range: bytes ").append(start).append('-').append(end)
                    .append('/').append(total).append("\r\n");
            h.append("Last-Modified: ").append(httpDate(f.lastModified())).append("\r\n");
            h.append("Cache-Control: public, max-age=3600\r\n");
            h.append("Connection: close\r\n\r\n");
            out.write(h.toString().getBytes(StandardCharsets.UTF_8));

            if (head) { out.flush(); return; }

            streamRange(out, f, start, len);
            return;
        }

        // ---- 完整文件 200 ----
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 200 OK\r\n");
        h.append("Server: ").append(cfg.title).append("\r\n");
        h.append("Date: ").append(httpDate()).append("\r\n");
        h.append("Content-Type: ").append(mime).append("\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        h.append("Content-Length: ").append(total).append("\r\n");
        h.append("Last-Modified: ").append(httpDate(f.lastModified())).append("\r\n");
        h.append("Cache-Control: public, max-age=3600\r\n");
        h.append("Content-Disposition: inline; filename*=UTF-8''")
                .append(encode(f.getName())).append("\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));

        if (head) { out.flush(); return; }

        streamRange(out, f, 0, total);
    }

    /** 从 offset 起输出 len 字节 */
    private void streamRange(OutputStream out, File f, long offset, long len) throws IOException {
        if (len <= 0) return;
        try (FileInputStream fis = new FileInputStream(f)) {
            long skipped = 0;
            while (skipped < offset) {
                long n = fis.skip(offset - skipped);
                if (n <= 0) break;
                skipped += n;
            }
            byte[] buf = new byte[STREAM_BUFFER > len ? (int) Math.max(4096, len) : STREAM_BUFFER];
            long left = len;
            while (left > 0) {
                int want = (int) Math.min(buf.length, left);
                int r = fis.read(buf, 0, want);
                if (r <= 0) break;
                try {
                    out.write(buf, 0, r);
                } catch (IOException e) {
                    // 客户端拖动进度条 / 关页面 → 主动断开，计数照记
                    bytesOut.addAndGet(r);
                    throw e;
                }
                left -= r;
                bytesOut.addAndGet(r);
                // 大文件时主动 flush，避免缓冲堆积导致播放首帧延迟
                if (left > 0 && (len - left) % (4L * 1024 * 1024) < r) {
                    out.flush();
                }
            }
        }
    }

    // ==================== 歌词 ====================

    /**
     * GET /lyrics?f=/Music/a.mp3
     * 查找同名 .lrc（同目录，大小写不敏感，兼容 .LRC），
     * 也尝试 {basename}.lrc / {basename}.zh.lrc / {basename}.chs.lrc
     * 编码：先 UTF-8，失败回落 GBK（中文 LRC 常见）
     */
    private void handleLyrics(Request req, OutputStream out) throws IOException {
        String p = req.param("f");
        File audio = resolve(p);
        if (audio == null || !audio.isFile()) {
            sendJson(out, "{\"ok\":false,\"error\":\"audio not found\"}");
            return;
        }

        File dir = audio.getParentFile();
        String name = audio.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;

        List<String> cands = new ArrayList<>();
        cands.add(base + ".lrc");
        cands.add(base + ".LRC");
        cands.add(base + ".zh.lrc");
        cands.add(base + ".chs.lrc");
        cands.add(base + ".cn.lrc");
        cands.add(base + ".zh-CN.lrc");

        File found = null;
        if (dir != null) {
            for (String c : cands) {
                File t = new File(dir, c);
                if (t.isFile()) { found = t; break; }
            }
            // 兜底：目录里扫描，忽略大小写
            if (found == null) {
                File[] list = dir.listFiles();
                if (list != null) {
                    for (File t : list) {
                        if (!t.isFile()) continue;
                        String n = t.getName().toLowerCase(Locale.ROOT);
                        if (n.equals((base + ".lrc").toLowerCase(Locale.ROOT))
                                || n.startsWith(base.toLowerCase(Locale.ROOT) + ".")
                                && n.endsWith(".lrc")) {
                            found = t;
                            break;
                        }
                    }
                }
            }
        }

        if (found == null) {
            sendJson(out, "{\"ok\":false,\"error\":\"no lyrics\"}");
            return;
        }

        String text = readTextSmart(found);
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"file\":\"").append(jsonEsc(found.getName()))
                .append("\",\"lrc\":\"").append(jsonEsc(text)).append("\"}");
        sendJson(out, sb.toString());
    }

    // ==================== 音乐库 ====================

    /** 曲库扫描的硬上限：一部手机上的曲库再大也就几万首，超过这个数说明选错了目录 */
    private static final int MUSIC_MAX_FILES = 20000;
    /** 曲库缓存有效期：目录里加歌后最多这么久就能被客户端看到 */
    private static final long MUSIC_TTL_MS = 30000L;
    /** 单次曲库扫描的墙钟上限，超时就先返回已扫到的部分 */
    private static final long MUSIC_SCAN_BUDGET_MS = 4000L;

    private static final Set<String> AUDIO_EXT = new HashSet<>(Arrays.asList(
            "mp3", "flac", "m4a", "wav", "aac", "ogg", "ape", "wma", "opus", "amr"));

    /**
     * 封面图扩展名。只认能被客户端直接解码的位图 —— svg 要额外依赖，
     * heic 在 Android 上要看系统版本，都不进这个表。
     */
    private static final Set<String> COVER_EXT = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "webp", "bmp"));

    /**
     * 「这张图代表整个目录」的通用名。刮削器几乎只用这几个词，
     * 大小写不敏感比较（low 是转小写后的主干）。
     */
    private static final Set<String> COVER_GENERIC = new HashSet<>(Arrays.asList(
            "cover", "folder", "front", "album", "albumart", "albumartsmall",
            "thumb", "artwork", "albumartlarge"));

    /** 曲库缓存：'\0' 哨兵表示「还没扫过」，任何配置的 hashCode 都不会等于它 */
    private volatile List<MusicTrack> musicCache;
    private volatile long musicCacheAt;
    private String musicCacheKey = "\u0000";

    private static final class MusicTrack {
        String path;   // 服务端绝对路径
        String name;   // 文件名（含扩展名）
        String title;  // 去掉扩展名的显示名
        String dir;    // 所在目录绝对路径
        String lrc;    // 是否有同名歌词，有就用歌词名
        long size;
        long mtime;
        // 从 .lrc 头部标签里读出来的（客户端按歌手/专辑分类要用）。
        // 没歌词文件、或歌词没写这些标签，就是 null —— 客户端据此退回文件名/目录名推断。
        String tagArtist;
        String tagAlbum;
        String tagTitle;
        // 封面文件绝对路径（同名图或目录通用图）；找不到就是 null，客户端退回自绘贴纸。
        // 只在 walkMusic 里扫目录时顺手判一次存在性，不做任何解码 —— 曲库几千首也不能卡在这。
        String cover;
    }

    /** App 里改了音乐目录后调用：清缓存，下次请求重扫 */
    void invalidateMusicLib() {
        musicCache = null;
        musicCacheAt = 0L;
        musicCacheKey = "\u0000";
    }

    /**
     * GET /_music?dir=可选子目录
     * 返回服务端预设音乐目录下的全部音频，客户端拿它一次性铺播放列表。
     *
     * 为什么要有这个端点：网页端 /play/music?f= 一次只认一首歌，
     * 想听上一首得先返回上级目录再点，做成播放器根本没法用。
     * 播放器需要的是「整个曲库一次给全」，所以单独开一个 JSON 接口。
     */
    private void handleMusicLib(Request req, OutputStream out) throws IOException {
        List<MusicTrack> all = musicLibrary();
        String sub = req.param("dir");
        File subDir = (sub == null || sub.isEmpty()) ? null : resolve(sub);
        // 只允许在预设音乐目录内部下钻，防止拿这个接口当全盘文件浏览器用
        if (subDir != null && !inMusicDirs(subDir)) subDir = null;

        StringBuilder sb = new StringBuilder(1024 + all.size() * 160);
        sb.append("{\"ok\":true,\"total\":").append(all.size());

        List<String> dirs = cfg.musicDirs;
        sb.append(",\"dirs\":[");
        for (int i = 0; i < dirs.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"p\":\"").append(jsonEsc(dirs.get(i)))
                    .append("\",\"n\":\"").append(jsonEsc(new File(dirs.get(i)).getName()))
                    .append("\"}");
        }
        sb.append(']');

        sb.append(",\"tracks\":[");
        boolean first = true;
        for (MusicTrack t : all) {
            if (subDir != null) {
                String p = t.path;
                String pre = subDir.getAbsolutePath();
                if (!p.startsWith(pre.endsWith("/") ? pre : pre + "/")) continue;
            }
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"p\":\"").append(jsonEsc(t.path))
                    .append("\",\"n\":\"").append(jsonEsc(t.name))
                    .append("\",\"t\":\"").append(jsonEsc(t.title))
                    .append("\",\"d\":\"").append(jsonEsc(t.dir))
                    .append("\",\"s\":").append(t.size)
                    .append(",\"m\":").append(t.mtime)
                    .append(",\"l\":").append(t.lrc == null ? "null" : "\"" + jsonEsc(t.lrc) + "\"")
                    // ar/al/ti：客户端要按歌手和专辑分类。值和 l 一样是 2200 字节内的小标签，
                    // 每个键只占几个字节，几千首的曲库多出来的体积可以忽略。
                    .append(",\"ar\":").append(t.tagArtist == null ? "null" : "\"" + jsonEsc(t.tagArtist) + "\"")
                    .append(",\"al\":").append(t.tagAlbum == null ? "null" : "\"" + jsonEsc(t.tagAlbum) + "\"")
                    .append(",\"ti\":").append(t.tagTitle == null ? "null" : "\"" + jsonEsc(t.tagTitle) + "\"")
                    // c：封面绝对路径，客户端拼成 /raw?f= 直接拉图。没有封面就是 null，
                    // 播放页会退回自绘的蓝贴纸 —— 而不是留一块空白。
                    // 注意 c 只表示「目录里有张图」，内嵌在音频标签里的封面走 /cover 端点，
                    // 由客户端按 p 去要 —— 见 i 键。
                    .append(",\"c\":").append(t.cover == null ? "null" : "\"" + jsonEsc(t.cover) + "\"")
                    // i：1 表示「这首有内嵌封面可选」。这里不做真正的抽取（那是解码，
                    // 上千首会拖慢列表），只按文件头几 KB 判断是否存在，见 hasEmbeddedCover()。
                    .append(",\"i\":").append(hasEmbeddedCover(t.path) ? 1 : 0)
                    .append('}');
        }
        sb.append("],\"scannedAt\":").append(musicCacheAt).append('}');
        sendJson(out, sb.toString());
    }

    /**
     * GET /cover?f=&lt;音频绝对路径&gt;
     *
     * 把内嵌在音频文件里的封面图吐出来。客户端播放页拿到曲目后会先试曲库里的
     * {@code c}（目录里的同名图 / cover.jpg），再退回这条 —— 实测库里绝大多数
     * 专辑图是钉在 MP3 的 ID3 标签里的，目录里一张图都没有。
     *
     * 只服务曲库目录里的音频文件（{@code inDirs} + {@code AUDIO_EXT} 双重约束），
     * 否则这个接口就成了「用任意路径读文件内容」的通道。
     * 抽不到封面时返回 404 而不是空 200，客户端据此退回自绘贴纸。
     */
    private void handleCover(Request req, OutputStream out) throws IOException {
        String p = req.param("f");
        File f = resolve(p);
        if (f == null || !f.isFile()) {
            sendBytes(out, "text/plain; charset=utf-8",
                    "no cover".getBytes(StandardCharsets.UTF_8), 404, "Not Found");
            return;
        }
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String ext = dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (!AUDIO_EXT.contains(ext) || !inDirs(f, cfg.musicDirs)) {
            sendBytes(out, "text/plain; charset=utf-8",
                    "not a track".getBytes(StandardCharsets.UTF_8), 404, "Not Found");
            return;
        }
        CoverArt.Art art = CoverArt.get(f);
        if (art == null || art.mime == null || art.data == null) {
            sendBytes(out, "text/plain; charset=utf-8",
                    "no cover".getBytes(StandardCharsets.UTF_8), 404, "Not Found");
            return;
        }
        // 缓存一天：封面跟文件一起变，文件没变就不用重抽。ETag 用长度+时间，
        // 客户端（OkHttp）自己会带 If-None-Match 回来问一次。
        String tag = "\"" + art.data.length + "-" + f.lastModified() + "\"";
        String inm = req.headers.get("if-none-match");
        if (inm != null && inm.contains(tag.replace("\"", ""))) {
            out.write(("HTTP/1.1 304 Not Modified\r\n"
                    + "Server: " + cfg.title + "\r\n"
                    + "ETag: " + tag + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return;
        }
        byte[] head = ("HTTP/1.1 200 OK\r\n"
                + "Server: " + cfg.title + "\r\n"
                + "Content-Type: " + art.mime + "\r\n"
                + "Content-Length: " + art.data.length + "\r\n"
                + "ETag: " + tag + "\r\n"
                + "Cache-Control: private, max-age=86400\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        out.write(head);
        out.write(art.data);
        out.flush();
    }

    /**
     * 只在文件头几 KB 里判断「有没有内嵌封面」，不真的把图解出来。
     *
     * 列表接口要跑上千次，真解码会慢到不可用；这里只找特征串：
     * ID3v2 的 {@code APIC}、ID3v2.2 的 {@code PIC}、FLAC 的 {@code PICTURE}
     * 与 mime 串、MP4 的 {@code covr}。命中就算有，客户端再去 /cover 真取。
     */
    private static boolean hasEmbeddedCover(String path) {
        if (path == null) return false;
        int dot = path.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        // 只有这几种容器能内嵌图；wav/amr 这几类历史上没有标准位置
        if (!(ext.equals("mp3") || ext.equals("flac") || ext.equals("m4a")
                || ext.equals("mp4") || ext.equals("ogg") || ext.equals("opus")
                || ext.equals("ape") || ext.equals("wma"))) {
            return false;
        }
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(path, "r");
            int n = (int) Math.min(raf.length(), 64 * 1024);
            if (n < 200) return false;
            byte[] b = new byte[n];
            int off = 0;
            while (off < n) {
                int got = raf.read(b, off, n - off);
                if (got <= 0) break;
                off += got;
            }
            if (b.length < 4) return false;
            // FLAC 直接看块链，别被音频数据里的巧合字符串骗
            if (b.length > 8 && b[0] == 'f' && b[1] == 'L' && b[2] == 'a' && b[3] == 'C') {
                int p = 4;
                for (int guard = 0; guard < 32 && p + 4 <= off; guard++) {
                    int head = b[p] & 0xff;
                    boolean last = (head & 0x80) != 0;
                    int type = head & 0x7f;
                    if (type == 6) return true;
                    int len = ((b[p + 1] & 0xff) << 16) | ((b[p + 2] & 0xff) << 8) | (b[p + 3] & 0xff);
                    if (last || len < 0 || p + 4 + len > off) break;
                    p += 4 + len;
                }
                return false;
            }
            String s = new String(b, 0, off, StandardCharsets.ISO_8859_1);
            // 逐个判，别写成组合条件 —— 运算符优先级一错就会把「image/ 出现在
            // 任意位置」当成有封面，误判成 i=1 后客户端每次都要白跑一趟 /cover
            if (s.contains("APIC")) return true;
            if (s.contains("PIC") && s.contains("image/")) return true;
            if (s.contains("covr")) return true;
            if (s.contains("PICTURE")) return true;
            if (s.contains("image/jpeg") && s.contains("ID3")) return true;
            return false;
        } catch (Throwable t) {
            return false;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 曲库列表，带 30 秒缓存。
     *
     * 不加缓存的话，客户端每换一个筛选项就要重扫几千个目录，
     * 机械盘会一直转、手机上还会发热 —— 这正是网页端当初只肯一次查一首的原因。
     */
    private List<MusicTrack> musicLibrary() {
        String key = cfg.musicDirs == null ? "" : String.valueOf(cfg.musicDirs.hashCode());
        long now = System.currentTimeMillis();
        List<MusicTrack> c = musicCache;
        if (c != null && now - musicCacheAt < MUSIC_TTL_MS && key.equals(musicCacheKey)) return c;

        List<MusicTrack> out = new ArrayList<>();
        long deadline = now + MUSIC_SCAN_BUDGET_MS;
        List<String> dirs = cfg.musicDirs;
        if (dirs == null || dirs.isEmpty()) {
            // 没配目录：退回扫全部共享根，保证客户端拿到的不是空列表
            for (File r : liveRoots()) walkMusic(r, out, deadline, 0);
        } else {
            for (String d : dirs) {
                File f = new File(d);
                if (!f.isDirectory()) continue;
                if (!inMusicDirs(f)) continue;
                walkMusic(f, out, deadline, 0);
            }
        }
        out.sort((a, b) -> {
            int r = a.dir.compareToIgnoreCase(b.dir);
            return r != 0 ? r : a.name.compareToIgnoreCase(b.name);
        });

        musicCache = out;
        musicCacheAt = now;
        musicCacheKey = key;
        return out;
    }

    /** 路径是否落在预设音乐目录里（含目录本身） */
    // ==================== 相册 ====================

    /** 会被当图片收进来的扩展名。heic 收了但客户端要系统支持才解得开，转不动就显示占位。 */
    private static final Set<String> PHOTO_EXT = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif"));

    /** 图片清单缓存 60 秒：相册目录动辄上万张，每翻一页都重扫会一直转盘 */
    private static final long PHOTO_TTL_MS = 60000L;

    /** 一次最多列这么多张。超过的目录按修改时间取最新的那部分（相册通常是「最近的想看」） */
    private static final int PHOTO_MAX_FILES = 4000;

    /** 递归深度上限，防止有人把相册目录指到 /storage 根 */
    private static final int PHOTO_MAX_DEPTH = 8;

    private static final class Photo {
        String path;   // 服务端绝对路径
        String name;   // 文件名（含扩展名）
        String dir;    // 所在目录绝对路径
        long size;
        long mtime;
        int w;         // 宽高：只在列表里读 JPEG/PNG 的头部，解不出就 0
        int h;
    }

    /** 相册缓存：'\0' 哨兵表示「还没扫过」 */
    private volatile List<Photo> photoCache;
    private volatile long photoCacheAt;
    private String photoCacheKey = "\u0000";

    /** App 里改了相册目录后调用：清缓存，下次请求重扫 */
    void invalidatePhotoLib() {
        photoCache = null;
        photoCacheAt = 0L;
        photoCacheKey = "\u0000";
    }

    /**
     * GET /_photos?dir=可选子目录
     *
     * <p>返回结构刻意和 /_music 平行：
     * <pre>
     * {"ok":true,"total":N,"dirs":[{"p":..,"n":..,"c":张数}],
     *  "photos":[{"p":..,"n":..,"d":..,"s":字节,"m":mtime,"w":宽,"h":高}],
     *  "scannedAt":ms}
     * </pre>
     *
     * <p>为什么单独开一个端点而不是复用 /_dirlist：那个接口是给「移动/复制文件」的
     * 对话框用的，只列子目录、还要求写权限；相册要的是读权限、只列图片、还要带
     * 尺寸和修改时间，两套需求凑不到一起。
     *
     * <p>为什么没有配置目录时返回空：手机上 jpg/png 满地都是（图标、缓存、截图），
     * 默认扫全盘会让「相册」里全是垃圾。这一条和书库同一套取舍。
     */
    private void handlePhotoLib(Request req, OutputStream out) throws IOException {
        List<Photo> all = photoLibrary();
        String sub = req.param("dir");
        File subDir = (sub == null || sub.isEmpty()) ? null : resolve(sub);
        if (subDir != null && !inPhotoDirs(subDir)) subDir = null;

        StringBuilder sb = new StringBuilder(1024 + all.size() * 140);
        sb.append("{\"ok\":true,\"total\":").append(all.size());

        // dirs：只统计当前层下一级的图片数，供客户端画「相册封面墙」的分组头
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (Photo p : all) {
            if (subDir != null && !isUnder(p, subDir)) continue;
            counts.put(p.dir, counts.containsKey(p.dir) ? counts.get(p.dir) + 1 : 1);
        }
        sb.append(",\"dirs\":[");
        boolean firstDir = true;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (!firstDir) sb.append(',');
            firstDir = false;
            String dp2 = e.getKey();
            // 显示名用「相对当前层」的末段：绝对路径在手机上长得看不出是啥
            String name = lastSeg(dp2);
            sb.append("{\"p\":\"").append(jsonEsc(dp2))
                    .append("\",\"n\":\"").append(jsonEsc(name))
                    .append("\",\"c\":").append(e.getValue()).append('}');
        }
        sb.append(']');

        sb.append(",\"photos\":[");
        boolean first = true;
        for (Photo p : all) {
            if (subDir != null && !isUnder(p, subDir)) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"p\":\"").append(jsonEsc(p.path))
                    .append("\",\"n\":\"").append(jsonEsc(p.name))
                    .append("\",\"d\":\"").append(jsonEsc(p.dir))
                    .append("\",\"s\":").append(p.size)
                    .append(",\"m\":").append(p.mtime)
                    .append(",\"w\":").append(p.w)
                    .append(",\"h\":").append(p.h).append('}');
        }
        sb.append(']');
        sb.append(",\"scannedAt\":").append(System.currentTimeMillis()).append('}');
        sendJson(out, sb.toString());
    }

    private static boolean isUnder(Photo p, File dir) {
        String root;
        try {
            root = dir.getCanonicalPath();
        } catch (IOException e) {
            root = dir.getAbsolutePath();
        }
        String s = p.path;
        return s.equals(root) || s.startsWith(root.endsWith("/") ? root : root + "/");
    }

    private static String lastSeg(String p) {
        if (p == null) return "";
        int i = p.endsWith("/") ? p.lastIndexOf('/', p.length() - 2) : p.lastIndexOf('/');
        return i < 0 ? p : p.substring(i + 1);
    }

    /** 当前目录是否落在预设的相册目录里（没配就等于「什么都不许」） */
    /**
     * 批量删除照片（POST /_photos/delete）。
     *
     * 边界三件套，缺一不可：
     *   1. 必须是管理员 —— 删的是盘上真文件，不是配置项；
     *   2. 只认 GET/POST 里的每个 f 参数，逐个 resolve() 后必须在相册目录内
     *      （resolve 会拦 ../，inPhotoDirs 会拦「配了相册目录之外的路径」）；
     *   3. 只删 PHOTO_EXT 里的图片，绝不因为一个畸形参数把 .txt/.exe 删掉。
     *
     * 一次最多 200 个：客户端按 40 一批发，给个上限是防止某个脚本一把梭。
     */
    private void handlePhotoDelete(Request req, InputStream in, OutputStream out) throws IOException {
        boolean logged = req.user != null;
        boolean isAdmin = logged && req.user.level >= Users.LEVEL_ADMIN;
        String json;
        if (!isAdmin) {
            json = "{\"ok\":false,\"error\":\"需要管理员账号\",\"deleted\":0}";
        } else if (!"POST".equalsIgnoreCase(req.method)) {
            json = "{\"ok\":false,\"error\":\"只接受 POST\",\"deleted\":0}";
        } else {
            Map<String, String> form = readForm(in, req.contentLength());
            List<String> raw = new ArrayList<>();
            // 兼容两种提交方式：f 一个参数多行（\n 分隔），或 f 重复多次
            String single = form.get("f");
            if (single != null) {
                for (String line : single.split("\n")) {
                    if (!line.trim().isEmpty()) raw.add(line.trim());
                }
            }
            Set<String> seen = new java.util.LinkedHashSet<>();
            int deleted = 0;
            int skipped = 0;
            String lastErr = null;
            for (String s : raw) {
                if (deleted + skipped >= 200) break;
                if (!seen.add(s)) continue;
                File f = resolve(s);
                if (f == null || !f.isFile() || !inPhotoDirs(f) || !isPhotoName(f.getName())) {
                    skipped++;
                    continue;
                }
                try {
                    if (f.delete()) {
                        deleted++;
                    } else {
                        skipped++;
                        lastErr = "删不掉：" + f.getName();
                    }
                } catch (SecurityException e) {
                    skipped++;
                    lastErr = "没有权限删 " + f.getName();
                }
            }
            if (deleted > 0) {
                invalidatePhotoLib();
                cfg.setPhotoDirs(cfg.photoDirs); // 触发一次配置回写，保持和磁盘一致
                log("相册：删除 " + deleted + " 张，跳过 " + skipped);
            }
            StringBuilder sb = new StringBuilder(192);
            sb.append("{\"ok\":true,\"deleted\":").append(deleted)
                    .append(",\"skipped\":").append(skipped);
            if (lastErr != null) sb.append(",\"error\":\"").append(jsonEsc(lastErr)).append('"');
            sb.append('}');
            json = sb.toString();
        }
        sendJson(out, json);
    }

    /** 扩展名判断：只认 PHOTO_EXT 里的扩展名（小写比较） */
    private static boolean isPhotoName(String name) {
        int i = name.lastIndexOf('.');
        if (i < 0 || i == name.length() - 1) return false;
        return PHOTO_EXT.contains(name.substring(i + 1).toLowerCase(Locale.ROOT));
    }

    private boolean inPhotoDirs(File f) {
        List<String> dirs = cfg.photoDirs;
        if (dirs == null || dirs.isEmpty()) return false;
        String p;
        try {
            p = f.getCanonicalPath();
        } catch (IOException e) {
            p = f.getAbsolutePath();
        }
        for (String d : dirs) {
            String root;
            try {
                root = new File(d).getCanonicalPath();
            } catch (IOException e) {
                root = d;
            }
            if (p.equals(root) || p.startsWith(root.endsWith("/") ? root : root + "/")) return true;
        }
        return false;
    }

    /**
     * 相册清单，带 60 秒缓存。
     *
     * <p>排序按 mtime 倒序：相册的默认心智是「最近的放最前」，这和曲库按目录名排是
     * 两种场景，所以这里不跟音乐那套比。
     */
    private List<Photo> photoLibrary() {
        String key = cfg.photoDirs == null ? "" : String.valueOf(cfg.photoDirs.hashCode());
        long now = System.currentTimeMillis();
        List<Photo> c = photoCache;
        if (c != null && now - photoCacheAt < PHOTO_TTL_MS && key.equals(photoCacheKey)) return c;

        List<Photo> out = new ArrayList<>();
        List<String> dirs = cfg.photoDirs;
        if (dirs != null) {
            for (String d : dirs) {
                File f = new File(d);
                if (!f.isDirectory()) continue;
                walkPhoto(f, out, 0);
            }
        }
        out.sort((a, b) -> Long.compare(b.mtime, a.mtime));
        if (out.size() > PHOTO_MAX_FILES) out = out.subList(0, PHOTO_MAX_FILES);

        photoCache = out;
        photoCacheAt = now;
        photoCacheKey = key;
        return out;
    }

    /**
     * 递归收图片。跳过隐藏目录与常见的非相册重目录（缩略图缓存、Android/data），
     * 后两个在手机上一进去就是几万个图标文件，且用户从不想在相册里看到它们。
     */
    private void walkPhoto(File dir, List<Photo> out, int depth) {
        if (dir == null || depth > PHOTO_MAX_DEPTH) return;
        if (out.size() >= PHOTO_MAX_FILES) return;
        File[] list;
        try {
            list = dir.listFiles();
        } catch (SecurityException e) {
            return;
        }
        if (list == null) return;
        for (File f : list) {
            if (out.size() >= PHOTO_MAX_FILES) return;
            String nm = f.getName();
            if (nm.startsWith(".")) continue;
            if (f.isDirectory()) {
                // 这两类目录进相册只是噪音，而且数量巨大
                if (nm.equalsIgnoreCase("Android") || nm.equalsIgnoreCase("LOST.DIR")) continue;
                if (nm.equalsIgnoreCase(".thumbnails") || nm.equalsIgnoreCase("thumbnails")) continue;
                walkPhoto(f, out, depth + 1);
                continue;
            }
            int dot = nm.lastIndexOf('.');
            if (dot < 0) continue;
            String ext = nm.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (!PHOTO_EXT.contains(ext)) continue;
            Photo p = new Photo();
            p.path = f.getAbsolutePath();
            p.name = nm;
            p.dir = dir.getAbsolutePath();
            try {
                p.size = f.length();
                p.mtime = f.lastModified();
            } catch (SecurityException e) {
                continue;
            }
            // 尺寸只读头部，不解码 —— 上万张图真解码会把手机拖死。
            // 读不出来（坏文件、格式不认识）就留 0，客户端按 0 显示占位。
            int[] wh = imageSize(f);
            p.w = wh[0];
            p.h = wh[1];
            out.add(p);
        }
    }

    /**
     * 只读文件头拿宽高。支持 JPEG（SOF 段）、PNG（IHDR）、GIF、BMP、WebP（VP8/VP8L/VP8X）。
     * 其它格式或坏了返回 {0,0}。
     */
    private static int[] imageSize(File f) {
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(f, "r");
            byte[] b = new byte[32];
            int n = raf.read(b, 0, 32);
            if (n < 24) return new int[]{0, 0};
            // PNG：8 字节签名 + 4 长度 + "IHDR" + 宽 + 高
            if ((b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
                return new int[]{be32(b, 16), be32(b, 20)};
            }
            // GIF：'G','I','F' + 宽(LE16) + 高(LE16)
            if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
                return new int[]{le16(b, 6), le16(b, 8)};
            }
            // BMP：'B','M' + 文件长 + 保留 + 数据偏移 + 头长 + 宽(LE32, 有符号) + 高
            if (b[0] == 'B' && b[1] == 'M') {
                return new int[]{Math.abs(le32(b, 18)), Math.abs(le32(b, 22))};
            }
            // WebP：RIFF....WEBP + 子块类型
            if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                    && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
                String sub = new String(b, 12, 4, StandardCharsets.ISO_8859_1);
                if ("VP8X".equals(sub)) {
                    return new int[]{le24(b, 24) + 1, le24(b, 27) + 1};
                }
                if ("VP8 ".equals(sub)) {
                    return new int[]{le16(b, 26) & 0x3fff, le16(b, 28) & 0x3fff};
                }
                if ("VP8L".equals(sub)) {
                    int bits = le32(b, 21);
                    return new int[]{(bits & 0x3fff) + 1, ((bits >> 14) & 0x3fff) + 1};
                }
                return new int[]{0, 0};
            }
            // JPEG：扫 SOF0..SOF15（跳过 C4/C8/CC 这三个非 SOF 标记）
            if ((b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8) {
                return jpegSize(f);
            }
            return new int[]{0, 0};
        } catch (Throwable t) {
            return new int[]{0, 0};
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static int[] jpegSize(File f) {
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(f, "r");
            long len = raf.length();
            long p = 2;
            byte[] hdr = new byte[4];
            while (p + 4 < len) {
                raf.seek(p);
                if (raf.read(hdr, 0, 4) < 4) break;
                if ((hdr[0] & 0xff) != 0xFF) {
                    p++;            // 对齐到下一个标记
                    continue;
                }
                int marker = hdr[1] & 0xff;
                // 段长为 0 的独立标记，不含长度字段
                if (marker == 0xD8 || marker == 0xD9 || (marker >= 0xD0 && marker <= 0xD7)) {
                    p += 2;
                    continue;
                }
                int segLen = ((hdr[2] & 0xff) << 8) | (hdr[3] & 0xff);
                boolean isSof = marker >= 0xC0 && marker <= 0xCF
                        && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
                if (isSof) {
                    byte[] sof = new byte[5];
                    raf.seek(p + 4);
                    if (raf.read(sof, 0, 5) < 5) break;
                    int h = ((sof[1] & 0xff) << 8) | (sof[2] & 0xff);
                    int w = ((sof[3] & 0xff) << 8) | (sof[4] & 0xff);
                    return new int[]{w, h};
                }
                if (segLen < 2) break;
                p += 2 + segLen;
            }
            return new int[]{0, 0};
        } catch (Throwable t) {
            return new int[]{0, 0};
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static int be32(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }

    private static int le16(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8);
    }

    private static int le24(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8) | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }

    private boolean inMusicDirs(File f) {
        List<String> dirs = cfg.musicDirs;
        if (dirs == null || dirs.isEmpty()) return true; // 没配就等于不限
        String p;
        try {
            p = f.getCanonicalPath();
        } catch (IOException e) {
            p = f.getAbsolutePath();
        }
        for (String d : dirs) {
            String root;
            try {
                root = new File(d).getCanonicalPath();
            } catch (IOException e) {
                root = d;
            }
            if (p.equals(root) || p.startsWith(root.endsWith("/") ? root : root + "/")) return true;
        }
        return false;
    }

    /**
     * 递归收集音频。深度压到 12 层、总量压到 MUSIC_MAX_FILES，
     * 是为了防止有人把音乐目录设成整个 /storage 后把手机拖死。
     */
    private void walkMusic(File dir, List<MusicTrack> out, long deadline, int depth) {
        if (dir == null || depth > 12) return;
        if (out.size() >= MUSIC_MAX_FILES) return;
        if (System.currentTimeMillis() > deadline) return;
        File[] list;
        try {
            list = dir.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (list == null) return;

        // 同一层里先收集歌词名，后面给音频挂上去，省一次目录遍历
        Set<String> lrcBase = null;
        // 封面：同名图（歌名.jpg）优先，目录通用图（cover.jpg / folder.jpg / front.jpg）兜底。
        // 跟 walkMusic 里 lrc 的收集方式一样，只在这一层扫一遍目录。
        Map<String, File> covers = null;   // 小写主干 -> 同名封面文件
        File dirCover = null;              // 目录通用封面
        for (File f : list) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (!f.isFile()) continue;
            int dot = n.lastIndexOf('.');
            if (dot <= 0) continue;
            String ext = n.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (ext.equals("lrc")) {
                if (lrcBase == null) lrcBase = new HashSet<>();
                lrcBase.add(n.substring(0, dot));
            } else if (COVER_EXT.contains(ext)) {
                String base = n.substring(0, dot);
                String low = base.toLowerCase(Locale.ROOT);
                if (COVER_GENERIC.contains(low)) {
                    // 通用封面只认第一个，顺序按 listFiles() 给的来，够用了
                    if (dirCover == null) dirCover = f;
                } else {
                    if (covers == null) covers = new HashMap<>();
                    if (!covers.containsKey(low)) covers.put(low, f);
                }
            }
        }

        List<File> subDirs = null;
        for (File f : list) {
            if (out.size() >= MUSIC_MAX_FILES) return;
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (subDirs == null) subDirs = new ArrayList<>();
                subDirs.add(f);
                continue;
            }
            int dot = n.lastIndexOf('.');
            if (dot <= 0) continue;
            String ext = n.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (!AUDIO_EXT.contains(ext)) continue;

            MusicTrack t = new MusicTrack();
            t.path = f.getAbsolutePath();
            t.name = n;
            t.title = n.substring(0, dot);
            t.dir = dir.getAbsolutePath();
            t.size = f.length();
            t.mtime = f.lastModified();
            String base = n.substring(0, dot);
            if (lrcBase != null && lrcBase.contains(base)) {
                t.lrc = base + ".lrc";
                readLrcTags(new File(dir, t.lrc), t);
            }
            // 封面：先找同名，再退回目录通用图
            if (covers != null) {
                File c = covers.get(base.toLowerCase(Locale.ROOT));
                if (c != null) t.cover = c.getAbsolutePath();
            }
            if (t.cover == null && dirCover != null) t.cover = dirCover.getAbsolutePath();
            out.add(t);
        }

        if (subDirs != null) {
            for (File d : subDirs) {
                if (out.size() >= MUSIC_MAX_FILES) return;
                if (System.currentTimeMillis() > deadline) return;
                walkMusic(d, out, deadline, depth + 1);
            }
        }
    }

    /**
     * 从同目录的 .lrc 头部读 [ti:]/[ar:]/[al:] 三个标签，填进 MusicTrack。
     *
     * 为什么找歌词文件要标签，而不是直接用文件名：
     * 曲库里大量文件叫「01.flac」「Track03.mp3」，文件名本身没有歌手和专辑；
     * 而 lrc 是按曲刮下来的，头部标签通常是对的，还能被客户端的「刮削歌词」修正。
     *
     * 只读前 LRC_TAG_MAX 字节、只扫前 LRC_TAG_LINES 行：一个 200KB 的整轨歌词
     * 也可能只有开头带标签，读全文件在机械盘上是白花时间。
     */
    private static final int LRC_TAG_MAX = 4096;
    private static final int LRC_TAG_LINES = 40;
    private static final int LRC_TAG_LEN = 200;

    private static void readLrcTags(File lrc, MusicTrack t) {
        if (lrc == null || !lrc.isFile()) return;
        try (java.io.InputStream in = new java.io.FileInputStream(lrc)) {
            byte[] buf = new byte[LRC_TAG_MAX];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r <= 0) break;
                n += r;
            }
            String text = new String(buf, 0, n, "UTF-8");
            int line = 0;
            int from = 0;
            while (from < text.length() && line < LRC_TAG_LINES) {
                int nl = text.indexOf('\n', from);
                String ln = (nl < 0 ? text.substring(from) : text.substring(from, nl)).trim();
                from = nl < 0 ? text.length() : nl + 1;
                line++;
                if (ln.length() < 6) continue;
                if (ln.charAt(0) != '[') continue;
                int colon = ln.indexOf(':');
                if (colon < 3 || colon > 4) continue;
                // [00:12.34] 这种时间轴也有冒号，靠第二个字符是不是数字排掉
                if (Character.isDigit(ln.charAt(1))) continue;
                int end = ln.indexOf(']', colon);
                if (end < 0) continue;
                String key = ln.substring(1, colon).toLowerCase(Locale.ROOT);
                String val = ln.substring(colon + 1, end).trim();
                if (val.isEmpty()) continue;
                if (val.length() > LRC_TAG_LEN) val = val.substring(0, LRC_TAG_LEN);
                if (key.equals("ar") || key.equals("artist") || key.equals("singer")) {
                    if (t.tagArtist == null) t.tagArtist = val;
                } else if (key.equals("al") || key.equals("album")) {
                    if (t.tagAlbum == null) t.tagAlbum = val;
                } else if (key.equals("ti") || key.equals("title")) {
                    if (t.tagTitle == null) t.tagTitle = val;
                }
            }
        } catch (Throwable ignored) {
            // 歌词坏了不该让整首曲子从曲库里消失，标签留空即可
        }
    }


    // ==================== 影视库 ====================
    //
    // 和音乐库同一套思路，但目标不同：客户端的影视板块要「盘0、盘1 两块并存」，
    // 所以 /_videos 除了给列表，还要把「每个预设目录里有几条」一起吐出来，
    // 客户端照着这个分组渲染，管理员加一个盘前端自动多一块，不用改代码。

    /** 一次拉取的条目上限：防止管理员把目录设成整个 /storage 之后把客户端撑死 */
    private static final int VIDEO_MAX_FILES = 20000;
    /** 影视列表缓存时间；比音乐长一些，因为影视库目录更深、机械盘扫一次更贵 */
    private static final long VIDEO_TTL_MS = 60000L;
    /** 单次扫描的时间预算，超了就先把扫到的返回去 */
    private static final long VIDEO_SCAN_BUDGET_MS = 6000L;

    private static final Set<String> VIDEO_EXT = new HashSet<>(Arrays.asList(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts",
            "m2ts", "mpg", "mpeg", "rmvb", "rm", "vob", "asf", "3gp", "ogv"));

    /** S01E02 / s1e2 / 第01集 / EP01 这些命名都要认出来，否则电视剧没法排序 */
    private static final java.util.regex.Pattern TV_PATTERN = java.util.regex.Pattern.compile(
            "(?i)(?:^|[^a-z0-9])s(\\d{1,2})[\\s._-]*e(\\d{1,3})(?:[^0-9]|$)"
                    + "|(?:^|[^a-z0-9])e[\\s._-]?(\\d{1,3})(?:[^0-9]|$)"
                    + "|第\\s*(\\d{1,3})\\s*[集话]"
                    + "|(?:^|[^a-z0-9])ep[\\s._-]?(\\d{1,3})(?:[^0-9]|$)");

    private volatile List<VideoItem> videoCache;
    private volatile long videoCacheAt;
    private String videoCacheKey = "\u0000";

    private static final class VideoItem {
        String path;    // 服务端绝对路径
        String name;    // 文件名
        String title;   // 去扩展名
        String dir;     // 所在目录
        long size;
        long mtime;
        String kind;    // video / tv
        int season;
        int episode;
    }

    /** App 里改了影视目录后调用：清缓存，下次请求重扫 */
    void invalidateVideoLib() {
        videoCache = null;
        videoCacheAt = 0L;
        videoCacheKey = "\u0000";
    }

    /**
     * GET /_videos?dir=可选子目录
     *
     * 返回：
     *   { ok, total, dirs:[{p,n,count}], groups:[{p,n,count}], videos:[{p,n,t,d,s,m,k,sk,se}] }
     *
     * dirs 是管理员预设的根（客户端拿它画「盘0 / 盘1」两个板块标题），
     * groups 是当前结果里真实出现过的目录（含下钻后的子目录），
     * 客户端用 groups 就能把「一个盘里多个影片文件夹」也分开显示。
     */
    private void handleVideoLib(Request req, OutputStream out) throws IOException {
        List<VideoItem> all = videoLibrary();
        String sub = req.param("dir");
        File subDir = (sub == null || sub.isEmpty()) ? null : resolve(sub);
        // 与音乐库同样的限制：只许在预设影视目录里下钻，避免变成全盘浏览器
        if (subDir != null && !inDirs(subDir, cfg.videoDirs)) subDir = null;

        String subPre = null;
        if (subDir != null) {
            String ap = subDir.getAbsolutePath();
            subPre = ap.endsWith("/") ? ap : ap + "/";
        }

        StringBuilder sb = new StringBuilder(2048 + all.size() * 180);
        sb.append("{\"ok\":true,\"total\":").append(all.size());

        List<String> roots = cfg.videoDirs;
        sb.append(",\"dirs\":[");
        for (int i = 0; i < roots.size(); i++) {
            if (i > 0) sb.append(',');
            String d = roots.get(i);
            sb.append("{\"p\":\"").append(jsonEsc(d))
                    .append("\",\"n\":\"").append(jsonEsc(dirLabel(d)))
                    .append("\",\"count\":").append(countUnder(all, d))
                    .append('}');
        }
        sb.append(']');

        // 先过一遍，把要吐的条目收进 kept，顺便统计 groups
        List<VideoItem> kept = new ArrayList<>();
        java.util.LinkedHashMap<String, Integer> groups = new java.util.LinkedHashMap<>();
        for (VideoItem v : all) {
            if (subPre != null && !v.path.startsWith(subPre)) continue;
            kept.add(v);
            Integer n = groups.get(v.dir);
            groups.put(v.dir, n == null ? 1 : n + 1);
        }

        sb.append(",\"groups\":[");
        boolean first = true;
        for (Map.Entry<String, Integer> e : groups.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            String p = e.getKey();
            sb.append("{\"p\":\"").append(jsonEsc(p))
                    .append("\",\"n\":\"").append(jsonEsc(dirLabel(p)))
                    .append("\",\"count\":").append(e.getValue())
                    .append('}');
        }
        sb.append(']');

        sb.append(",\"videos\":[");
        first = true;
        for (VideoItem v : kept) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"p\":\"").append(jsonEsc(v.path))
                    .append("\",\"n\":\"").append(jsonEsc(v.name))
                    .append("\",\"t\":\"").append(jsonEsc(v.title))
                    .append("\",\"d\":\"").append(jsonEsc(v.dir))
                    .append("\",\"s\":").append(v.size)
                    .append(",\"m\":").append(v.mtime)
                    .append(",\"k\":\"").append(v.kind)
                    .append("\",\"sk\":").append(v.season)
                    .append(",\"se\":").append(v.episode)
                    .append('}');
        }
        sb.append("],\"scannedAt\":").append(videoCacheAt).append('}');
        sendJson(out, sb.toString());
    }

    /** 目录给人的名字：内部存储 / 盘名 / 最后一段路径 */
    private static String dirLabel(String absPath) {
        if (absPath == null || absPath.isEmpty()) return "";
        File f = new File(absPath);
        if (SysInfo.isInternal(f)) return "内部存储";
        String n = f.getName();
        return (n == null || n.isEmpty()) ? absPath : n;
    }

    /** 某个预设根之下有多少条 */
    private static int countUnder(List<VideoItem> all, String root) {
        if (root == null || root.isEmpty()) return 0;
        String pre = root.endsWith("/") ? root : root + "/";
        int n = 0;
        for (VideoItem v : all) {
            if (v.path.startsWith(pre)) n++;
        }
        return n;
    }

    /**
     * 影视列表，带缓存。
     *
     * 分两份：配置了目录就只扫这些目录（这是常态，也是管理员预设的意义）；
     * 一个都没配时退回扫全部共享根，保证客户端不会拿到空列表干瞪眼。
     */
    private List<VideoItem> videoLibrary() {
        String key = cfg.videoDirs == null ? "" : String.valueOf(cfg.videoDirs.hashCode());
        long now = System.currentTimeMillis();
        List<VideoItem> c = videoCache;
        if (c != null && now - videoCacheAt < VIDEO_TTL_MS && key.equals(videoCacheKey)) return c;

        List<VideoItem> out = new ArrayList<>();
        long deadline = now + VIDEO_SCAN_BUDGET_MS;
        List<String> dirs = cfg.videoDirs;
        if (dirs == null || dirs.isEmpty()) {
            for (File r : liveRoots()) walkVideo(r, out, deadline, 0);
        } else {
            for (String d : dirs) {
                File f = new File(d);
                if (!f.isDirectory()) continue;
                if (!inDirs(f, dirs)) continue;
                walkVideo(f, out, deadline, 0);
            }
        }
        // 同一部剧的多集要按季/集排在一起，所以先目录、再季、再集、最后名字
        out.sort((a, b) -> {
            int r = a.dir.compareToIgnoreCase(b.dir);
            if (r != 0) return r;
            if (a.season != b.season) return a.season - b.season;
            if (a.episode != b.episode) return a.episode - b.episode;
            return a.name.compareToIgnoreCase(b.name);
        });

        videoCache = out;
        videoCacheAt = now;
        videoCacheKey = key;
        return out;
    }

    /** 路径是否落在给定的目录列表内（含目录本身）；列表为空等于不限 */
    private static boolean inDirs(File f, List<String> dirs) {
        if (dirs == null || dirs.isEmpty()) return true;
        String p;
        try {
            p = f.getCanonicalPath();
        } catch (IOException e) {
            p = f.getAbsolutePath();
        }
        for (String d : dirs) {
            String root;
            try {
                root = new File(d).getCanonicalPath();
            } catch (IOException e) {
                root = d;
            }
            if (p.equals(root) || p.startsWith(root.endsWith("/") ? root : root + "/")) return true;
        }
        return false;
    }

    /**
     * 递归收集视频。
     *
     * sample / 花絮 / 预告 这些目录跳过 —— 它们数量大、体积小、
     * 混进正片列表后电视剧的集号排序会乱成一团。
     */
    private void walkVideo(File dir, List<VideoItem> out, long deadline, int depth) {
        if (dir == null || depth > 12) return;
        if (out.size() >= VIDEO_MAX_FILES) return;
        if (System.currentTimeMillis() > deadline) return;
        File[] list;
        try {
            list = dir.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (list == null) return;

        List<File> subDirs = null;
        for (File f : list) {
            if (out.size() >= VIDEO_MAX_FILES) return;
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                String low = n.toLowerCase(Locale.ROOT);
                if (low.equals("sample") || low.equals("samples")
                        || low.equals("featurettes") || low.equals("extras")
                        || low.equals("trailers") || low.equals("behind the scenes")
                        || low.equals("花絮") || low.equals("预告")) {
                    continue;
                }
                if (subDirs == null) subDirs = new ArrayList<>();
                subDirs.add(f);
                continue;
            }
            int dot = n.lastIndexOf('.');
            if (dot <= 0) continue;
            String ext = n.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (!VIDEO_EXT.contains(ext)) continue;

            VideoItem v = new VideoItem();
            v.path = f.getAbsolutePath();
            v.name = n;
            v.title = n.substring(0, dot);
            v.dir = dir.getAbsolutePath();
            v.size = f.length();
            v.mtime = f.lastModified();
            v.kind = "video";
            java.util.regex.Matcher m = TV_PATTERN.matcher(v.title);
            if (m.find()) {
                v.kind = "tv";
                if (m.group(1) != null) {
                    v.season = parseIntSafe(m.group(1));
                    v.episode = parseIntSafe(m.group(2));
                } else if (m.group(3) != null) {
                    v.episode = parseIntSafe(m.group(3));
                } else if (m.group(4) != null) {
                    v.episode = parseIntSafe(m.group(4));
                } else if (m.group(5) != null) {
                    v.episode = parseIntSafe(m.group(5));
                }
            }
            out.add(v);
        }

        if (subDirs != null) {
            for (File d : subDirs) {
                if (out.size() >= VIDEO_MAX_FILES) return;
                if (System.currentTimeMillis() > deadline) return;
                walkVideo(d, out, deadline, depth + 1);
            }
        }
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 把文件名主干里的「技术标记」去掉，只留片名。
     *
     * 例：`TestVid.2024` → `TestVid`；`Some.Movie.2023.1080p.BluRay` → `Some Movie`。
     * 只用于 nfo 兜底匹配，不参与列表显示 —— 去标记难免误伤片名里的数字
     * （《1917》《2012》），所以只在「唯一命中」时才采纳结果。
     */
    private static String looseStem(String stem) {
        if (stem == null || stem.isEmpty()) return "";
        String s = stem;
        // 年份
        s = s.replaceAll("(?i)[\\s._\\-]*(19|20)\\d{2}[\\s._\\-]*$", " ");
        // 画质 / 片源 / 编码 / 音轨这些标记，出现即从该处截断
        s = s.split("(?i)[\\s._\\-]+(?:2160p|1080p|720p|480p|4k|uhd|bluray|blu-ray|bdrip|"
                + "webrip|web-dl|webdl|hdtv|dvdrip|remux|x264|x265|h264|h265|hevc|avc|"
                + "aac|dts|ac3|truehd|atmos|hdr|10bit|proper|repack)\\b")[0];
        return s.replaceAll("[\\s._\\-]+", " ").trim();
    }

    /**
     * GET /_videos/meta?f=<路径>
     *
     * 影视列表不带刮削信息 —— 上千条 nfo 会在列表接口里堆成几十 MB，
     * 客户端首页就得等十几秒。所以元数据单独按需拉，一次一部。
     *
     * 内容上直接复用 /scrape 的那套逻辑（nfo + 同名海报图），
     * 但字段收敛成客户端真正要显示的：标题/年份/简介/海报/背景。
     */
    private void handleVideoMeta(Request req, OutputStream out) throws IOException {
        String p = req.param("f");
        File video = resolve(p);
        if (video == null || !video.isFile()) {
            sendJson(out, "{\"ok\":false,\"error\":\"video not found\"}");
            return;
        }
        if (!inDirs(video, cfg.videoDirs)) {
            sendJson(out, "{\"ok\":false,\"error\":\"out of scope\"}");
            return;
        }

        File dir = video.getParentFile();
        String name = video.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;

        String nfoText = null;
        String nfoName = null;
        for (String c : new String[]{base + ".nfo", "movie.nfo", "tvshow.nfo"}) {
            File t = new File(dir, c);
            if (t.isFile()) { nfoText = readTextSmart(t); nfoName = t.getName(); break; }
        }
        if (nfoText == null) {
            File[] ls = dir.listFiles();
            if (ls != null) {
                String want = (base + ".nfo").toLowerCase(Locale.ROOT);
                for (File t : ls) {
                    if (!t.isFile()) continue;
                    String n = t.getName().toLowerCase(Locale.ROOT);
                    if (n.equals(want) || n.equals("movie.nfo") || n.equals("tvshow.nfo")) {
                        nfoText = readTextSmart(t);
                        nfoName = t.getName();
                        break;
                    }
                }
            }
        }
        // 最后一步兜底：刮削器常把「片名.年份.nfo」写成「片名.nfo」，
        // 也就是把文件名里的年份、分辨率这些标记去掉后再命名。
        // 这里按「去标记后的主干」再找一遍，唯一命中才采纳 ——
        // 多条命中说明这个目录里放了好几个片子，宁可返回空也不猜错。
        if (nfoText == null) {
            String loose = looseStem(base);
            File[] ls = dir.listFiles();
            if (ls != null && !loose.isEmpty()) {
                File hit = null;
                int hits = 0;
                for (File t : ls) {
                    if (!t.isFile()) continue;
                    String n = t.getName();
                    if (!n.toLowerCase(Locale.ROOT).endsWith(".nfo")) continue;
                    String stem = n.substring(0, n.length() - 4);
                    if (looseStem(stem).equalsIgnoreCase(loose)) { hit = t; hits++; }
                }
                if (hits == 1 && hit != null) {
                    nfoText = readTextSmart(hit);
                    nfoName = hit.getName();
                }
            }
        }

        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"ok\":true,\"file\":\"").append(jsonEsc(name)).append('"');
        if (nfoText != null) {
            sb.append(",\"nfoFile\":\"").append(jsonEsc(nfoName))
                    .append("\",\"nfo\":").append(nfoToJson(nfoText));
        } else {
            sb.append(",\"nfo\":null");
        }

        // 海报/背景各挑一张；客户端不再自己拼文件名，各家刮削器的命名差异全由服务端吸收
        String poster = null, fanart = null;
        String[] posterKinds = {"poster", "folder", "cover", "post", "movie"};
        String[] fanKinds = {"fanart", "backdrop", "background", "thumb", "landscape"};
        for (String k : posterKinds) {
            File hit = findArt(dir, base, k);
            // 再试一次去掉年份/画质标记的主干：刮削器生成海报时普遍也会去掉这些
            if (hit == null) hit = findArt(dir, looseStem(base), k);
            if (hit != null) { poster = encode(hit.getAbsolutePath()); break; }
        }
        for (String k : fanKinds) {
            File hit = findArt(dir, base, k);
            if (hit == null) hit = findArt(dir, looseStem(base), k);
            if (hit != null) { fanart = encode(hit.getAbsolutePath()); break; }
        }
        sb.append(",\"poster\":").append(poster == null ? "null" : "\"" + jsonEsc(poster) + "\"")
                .append(",\"fanart\":").append(fanart == null ? "null" : "\"" + jsonEsc(fanart) + "\"")
                .append('}');
        sendJson(out, sb.toString());
    }


    // ==================== 图书库 ====================
    //
    // 与音乐/影视库同一套思路，但「读」这件事交给已有的 /view 渲染链：
    // PDF 走浏览器内置阅读器，EPUB/TXT 走 renderDoc 里现成的分支。
    // 这个接口只负责「有哪些书、在哪一层书架」，不负责正文，
    // 这样书库再大也不会把一次响应撑爆。

    /** 一次拉取的条目上限：书库文件数比影音多一个量级，但也不能无限 */
    private static final int BOOK_MAX_FILES = 20000;
    /** 书库缓存时间：书不常变，比影视再长一些，省得反复转机械盘 */
    private static final long BOOK_TTL_MS = 90000L;
    /** 单次扫描时间预算 */
    private static final long BOOK_SCAN_BUDGET_MS = 6000L;

    /**
     * 认作「书」的扩展名。
     *
     * 刻意不含 doc/docx 这些：它们是办公文档，混进来会把「我的书」变成
     * 「我的杂项文件」。要放也行 —— 直接放到书目录里，只是列表里不显示。
     */
    private static final Set<String> BOOK_EXT = new HashSet<>(Arrays.asList(
            "epub", "pdf", "mobi", "azw", "azw3", "fb2", "djvu", "cbz", "cbr",
            "cb7", "ibooks", "lit", "pdb", "tcr", "txt", "md",
            // doc/docx：服务端自己解包抽文本（见 /_books/text），客户端内嵌渲染，
            // 不外发到任何在线预览服务。doc 是老式 CFB 复合文档，按正文流扫
            // UTF-16LE/GBK 片段；docx 其实是 zip，读 word/document.xml 即可。
            "doc", "docx"));

    private volatile List<BookItem> bookCache;
    private volatile long bookCacheAt;
    private String bookCacheKey = "\u0000";

    private static final class BookItem {
        String path;    // 服务端绝对路径
        String name;    // 文件名
        String title;   // 去扩展名
        String dir;     // 所在目录（书架）
        String ext;     // 小写扩展名，客户端据此决定用什么打开
        long size;
        long mtime;
    }

    /** 管理员改完图书目录后调用：清缓存，下次请求重扫 */
    void invalidateBookLib() {
        bookCache = null;
        bookCacheAt = 0L;
        bookCacheKey = "\u0000";
    }

    /**
     * GET /_books?dir=可选子目录
     *
     * 返回：
     *   { ok, total, dirs:[{p,n,count}], groups:[{p,n,count}], books:[{p,n,t,d,x,s,m}] }
     *
     * 与 /_videos 的结构刻意保持一致 —— 客户端两套列表代码几乎能照抄，
     * 后续加「最近在读」「收藏」也不用改协议形状。
     */
    private void handleBookLib(Request req, OutputStream out) throws IOException {
        List<BookItem> all = bookLibrary();
        String sub = req.param("dir");
        File subDir = (sub == null || sub.isEmpty()) ? null : resolve(sub);
        if (subDir != null && !inDirs(subDir, cfg.bookDirs)) subDir = null;

        String subPre = null;
        if (subDir != null) {
            String ap = subDir.getAbsolutePath();
            subPre = ap.endsWith("/") ? ap : ap + "/";
        }

        StringBuilder sb = new StringBuilder(2048 + all.size() * 160);
        sb.append("{\"ok\":true,\"total\":").append(all.size());

        List<String> roots = cfg.bookDirs;
        sb.append(",\"dirs\":[");
        for (int i = 0; i < roots.size(); i++) {
            if (i > 0) sb.append(',');
            String d = roots.get(i);
            sb.append("{\"p\":\"").append(jsonEsc(d))
                    .append("\",\"n\":\"").append(jsonEsc(dirLabel(d)))
                    .append("\",\"count\":").append(countBooksUnder(all, d))
                    .append('}');
        }
        sb.append(']');

        List<BookItem> kept = new ArrayList<>();
        java.util.LinkedHashMap<String, Integer> groups = new java.util.LinkedHashMap<>();
        for (BookItem b : all) {
            if (subPre != null && !b.path.startsWith(subPre)) continue;
            kept.add(b);
            Integer n = groups.get(b.dir);
            groups.put(b.dir, n == null ? 1 : n + 1);
        }

        sb.append(",\"groups\":[");
        boolean first = true;
        for (Map.Entry<String, Integer> e : groups.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            String p = e.getKey();
            sb.append("{\"p\":\"").append(jsonEsc(p))
                    .append("\",\"n\":\"").append(jsonEsc(dirLabel(p)))
                    .append("\",\"count\":").append(e.getValue())
                    .append('}');
        }
        sb.append(']');

        sb.append(",\"books\":[");
        first = true;
        for (BookItem b : kept) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"p\":\"").append(jsonEsc(b.path))
                    .append("\",\"n\":\"").append(jsonEsc(b.name))
                    .append("\",\"t\":\"").append(jsonEsc(b.title))
                    .append("\",\"d\":\"").append(jsonEsc(b.dir))
                    .append("\",\"x\":\"").append(jsonEsc(b.ext))
                    .append("\",\"s\":").append(b.size)
                    .append(",\"m\":").append(b.mtime)
                    .append('}');
        }
        sb.append("],\"scannedAt\":").append(bookCacheAt).append('}');
        sendJson(out, sb.toString());
    }

    private static int countBooksUnder(List<BookItem> all, String root) {
        if (root == null || root.isEmpty()) return 0;
        String pre = root.endsWith("/") ? root : root + "/";
        int n = 0;
        for (BookItem b : all) {
            if (b.path.startsWith(pre)) n++;
        }
        return n;
    }

    /**
     * 图书列表，带缓存。
     *
     * 未配置目录时**不像音乐/影视那样回落全盘**：书库里 txt/md 满地都是，
     * 一回落就会把整个存储的日志、说明、配置全当书列出来。
     * 没配就返回空列表，客户端提示「请管理员先设置图书目录」。
     */
    private List<BookItem> bookLibrary() {
        String key = cfg.bookDirs == null ? "" : String.valueOf(cfg.bookDirs.hashCode());
        long now = System.currentTimeMillis();
        List<BookItem> c = bookCache;
        if (c != null && now - bookCacheAt < BOOK_TTL_MS && key.equals(bookCacheKey)) return c;

        List<BookItem> out = new ArrayList<>();
        long deadline = now + BOOK_SCAN_BUDGET_MS;
        List<String> dirs = cfg.bookDirs;
        if (dirs != null && !dirs.isEmpty()) {
            for (String d : dirs) {
                File f = new File(d);
                if (!f.isDirectory()) continue;
                if (!inDirs(f, dirs)) continue;
                walkBooks(f, out, deadline, 0);
            }
        }
        out.sort((a, b) -> {
            int r = a.dir.compareToIgnoreCase(b.dir);
            if (r != 0) return r;
            return a.title.compareToIgnoreCase(b.title);
        });

        bookCache = out;
        bookCacheAt = now;
        bookCacheKey = key;
        return out;
    }

    /**
     * 递归收集书。
     *
     * 这里和 walkVideo 有一处关键差别：**不跳过任何目录**。
     * 视频那边要躲 sample/花絮/预告，因为那些会搞乱集号排序；
     * 书这边目录名本身就是书架（「科幻」「已读」「作者名」），一个都不能略过。
     */
    private void walkBooks(File dir, List<BookItem> out, long deadline, int depth) {
        if (dir == null || depth > 12) return;
        if (out.size() >= BOOK_MAX_FILES) return;
        if (System.currentTimeMillis() > deadline) return;
        File[] list;
        try {
            list = dir.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (list == null) return;

        List<File> subDirs = null;
        for (File f : list) {
            if (out.size() >= BOOK_MAX_FILES) return;
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (subDirs == null) subDirs = new ArrayList<>();
                subDirs.add(f);
                continue;
            }
            int dot = n.lastIndexOf('.');
            if (dot <= 0) continue;
            String ext = n.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (!BOOK_EXT.contains(ext)) continue;

            BookItem b = new BookItem();
            b.path = f.getAbsolutePath();
            b.name = n;
            b.title = n.substring(0, dot);
            b.dir = dir.getAbsolutePath();
            b.ext = ext;
            b.size = f.length();
            b.mtime = f.lastModified();
            out.add(b);
        }

        if (subDirs != null) {
            for (File d : subDirs) {
                if (out.size() >= BOOK_MAX_FILES) return;
                if (System.currentTimeMillis() > deadline) return;
                walkBooks(d, out, deadline, depth + 1);
            }
        }
    }


    // ==================== 书库正文抽取（客户端内嵌阅读） ====================

    /**
     * 单本抽出来的纯文本上限。一本书正文超过 8MB 基本不是给人读的，
     * 而且客户端 WebView/JavaFX 渲染几十万字会明显卡，先截断并告知。
     */
    private static final int BOOK_TEXT_MAX = 8 * 1024 * 1024;
    /** docx 的 word/document.xml 解压后上限，防 zip 炸弹 */
    private static final int BOOK_XML_MAX = 24 * 1024 * 1024;

    /**
     * GET /_books/text?f=/Books/a.docx
     *
     * 给客户端「内嵌阅读」用：不跳浏览器、不外发任何在线预览服务，
     * 由服务端把书抽成纯文本，客户端自己排版。
     *
     * 支持的抽取方式（都不需要第三方库）：
     *  - txt/md/log/fb2 等纯文本 → readTextSmart 直接读（自动识别 UTF-8/GBK/BOM）
     *  - docx → 它本质是 zip，解 word/document.xml，去标签留段落
     *  - doc  → 老式 CFB 复合文档，没有解析器就按正文流扫 UTF-16LE / GBK 片段
     *  - epub → zip，按 OPF 的 spine 顺序拼各个 xhtml 的正文
     *
     * 返回 {"ok":true,"name":..,"ext":..,"title":..,"chars":N,"truncated":bool,
     *       "text":"…","format":"plain|docx|doc|epub"}
     */
    private void handleBookText(Request req, OutputStream out) throws IOException {
        String p = req.param("f");
        File f = resolve(p);
        if (f == null || !f.isFile()) {
            sendJson(out, "{\"ok\":false,\"error\":\"file not found\"}");
            return;
        }
        // 与 /_videos/meta 同一条防线：只肯抽书库目录里的文件，
        // 否则这就成了「任意文件读成文本」的口子。
        if (!inDirs(f, cfg.bookDirs)) {
            sendJson(out, "{\"ok\":false,\"error\":\"out of scope\"}");
            return;
        }
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        String ext = dot > 0 ? name.substring(dot + 1).toLowerCase() : "";
        if (!BOOK_EXT.contains(ext)) {
            sendJson(out, "{\"ok\":false,\"error\":\"不支持的格式：" + jsonEsc(ext) + "\"}");
            return;
        }

        String text;
        String format;
        try {
            if (ext.equals("docx")) {
                text = extractDocx(f);
                format = "docx";
            } else if (ext.equals("doc")) {
                text = extractDoc(f);
                format = "doc";
            } else if (ext.equals("epub") || ext.equals("ibooks")) {
                text = extractEpub(f);
                format = "epub";
            } else if (ext.equals("pdf")) {
                // PDF 的文本层要完整解析器，服务端不做；客户端有内嵌 PDF 能力就自己取原文件。
                sendJson(out, "{\"ok\":false,\"error\":\"pdf 请用内嵌 PDF 视图打开\",\"pdf\":true}");
                return;
            } else {
                text = readTextSmart(f);
                format = "plain";
            }
        } catch (OutOfMemoryError ome) {
            sendJson(out, "{\"ok\":false,\"error\":\"这本书太大，服务端内存不够解\"}");
            return;
        } catch (Throwable t) {
            sendJson(out, "{\"ok\":false,\"error\":\"抽取失败：" + jsonEsc(String.valueOf(t)) + "\"}");
            return;
        }

        if (text == null) text = "";
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        boolean truncated = false;
        if (text.length() > BOOK_TEXT_MAX / 3) {   // 每字符最多 3 字节（UTF-8）
            text = text.substring(0, BOOK_TEXT_MAX / 3);
            truncated = true;
        }

        String title = dot > 0 ? name.substring(0, dot) : name;
        StringBuilder sb = new StringBuilder(text.length() + 256);
        sb.append("{\"ok\":true,\"name\":").append(jsonEsc(name))
          .append(",\"ext\":").append(jsonEsc(ext))
          .append(",\"title\":").append(jsonEsc(title))
          .append(",\"format\":").append(jsonEsc(format))
          .append(",\"chars\":").append(text.length())
          .append(",\"truncated\":").append(truncated)
          .append(",\"text\":").append(jsonEsc(text))
          .append('}');
        sendJson(out, sb.toString());
    }

    /** docx：zip 里读 word/document.xml，段落标签换成换行，其余标签丢掉 */
    private String extractDocx(File f) throws IOException {
        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(f);
        try {
            // 不能直接 getEntry("word/document.xml")：有些打包工具（.NET 的
            // ZipFile.CreateFromDirectory 就是）会把分隔符写成反斜杠，得到
            // "word\document.xml"；Java 的 Entry 名区分这两者，直查就漏。
            // 所以扫一遍条目名，把 \ 统一成正斜杠再比。
            java.util.zip.ZipEntry hit = null;
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                String nm = e.getName().replace('\\', '/');
                if (nm.equalsIgnoreCase("word/document.xml")) { hit = e; break; }
            }
            if (hit == null) return "";
            String xml = readAllLimited(zf.getInputStream(hit), BOOK_XML_MAX);
            return docXmlToText(xml);
        } finally {
            try { zf.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * WordprocessingML 转文本。
     *
     * 只认 <w:p> 段落、<w:br/> 换行、<w:tab/> 制表，其余标签一律丢。
     * 不用 XML 解析器是因为 document.xml 经常带未声明前缀、还会内联一堆
     * 命名空间，扫一遍字符串既快又不会被拒绝。
     */
    private static String docXmlToText(String xml) {
        StringBuilder sb = new StringBuilder(xml.length() / 3);
        int i = 0, n = xml.length();
        while (i < n) {
            char c = xml.charAt(i);
            if (c == '<') {
                int gt = xml.indexOf('>', i);
                if (gt < 0) break;
                String tag = xml.substring(i + 1, gt);
                if (tag.equals("/w:p") || tag.equals("/w:p ")) sb.append('\n');
                else if (tag.startsWith("w:br") || tag.startsWith("w:cr")) sb.append('\n');
                else if (tag.startsWith("w:tab")) sb.append('\t');
                else if (tag.equals("/w:tc")) sb.append('\t');
                else if (tag.equals("/w:tr")) sb.append('\n');
                i = gt + 1;
                continue;
            }
            if (c == '&') {
                int semi = xml.indexOf(';', i);
                if (semi > 0 && semi - i < 12) {
                    String ent = xml.substring(i + 1, semi);
                    if (ent.equals("amp")) sb.append('&');
                    else if (ent.equals("lt")) sb.append('<');
                    else if (ent.equals("gt")) sb.append('>');
                    else if (ent.equals("quot")) sb.append('"');
                    else if (ent.equals("apos")) sb.append('\'');
                    else if (ent.startsWith("#")) {
                        try {
                            int cp = ent.startsWith("#x") || ent.startsWith("#X")
                                    ? Integer.parseInt(ent.substring(2), 16)
                                    : Integer.parseInt(ent.substring(1));
                            sb.appendCodePoint(cp);
                        } catch (Throwable ignored) {}
                    }
                    i = semi + 1;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /**
     * epub：zip → 先找 OPF，按 spine 的 itemref 顺序把 xhtml 拼起来。
     *
     * 找不到 OPF 就退化成「所有 .xhtml/.html 按文件名排序全拼一遍」，
     * 反正阅读器看到的顺序也就是个大概。
     */
    private String extractEpub(File f) throws IOException {
        java.util.zip.ZipFile zf = new java.util.zip.ZipFile(f);
        try {
            // 同 extractDocx：条目名可能用反斜杠，先建一张「规范化名 → 真实条目」的映射
            java.util.Map<String, java.util.zip.ZipEntry> byName = new java.util.HashMap<>();
            String opfPath = null;
            java.util.List<String> names = new java.util.ArrayList<>();
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                String norm = e.getName().replace('\\', '/');
                byName.put(norm, e);
                String low = norm.toLowerCase();
                if (opfPath == null && low.endsWith(".opf")) opfPath = norm;
                if (low.endsWith(".xhtml") || low.endsWith(".html") || low.endsWith(".htm")) {
                    names.add(norm);
                }
            }
            java.util.List<String> order = new java.util.ArrayList<>();
            if (opfPath != null) {
                java.util.zip.ZipEntry oe = byName.get(opfPath);
                if (oe != null) {
                    String opf = readAllLimited(zf.getInputStream(oe), BOOK_XML_MAX);
                    String base = opfPath.indexOf('/') > 0
                            ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("(?is)<itemref[^>]*?idref\\s*=\\s*[\"']([^\"']+)[\"']")
                            .matcher(opf);
                    java.util.List<String> idrefs = new java.util.ArrayList<>();
                    while (m.find()) idrefs.add(m.group(1));
                    java.util.regex.Matcher mi = java.util.regex.Pattern
                            .compile("(?is)<item\\b[^>]*>").matcher(opf);
                    java.util.Map<String, String> idToHref = new java.util.HashMap<>();
                    while (mi.find()) {
                        String tag = mi.group();
                        String id = attr(tag, "id");
                        String href = attr(tag, "href");
                        if (id != null && href != null) idToHref.put(id, base + href);
                    }
                    for (String id : idrefs) {
                        String href = idToHref.get(id);
                        if (href != null && byName.containsKey(href)) order.add(href);
                    }
                }
            }
            if (order.isEmpty()) {
                java.util.Collections.sort(names);
                order.addAll(names);
            }
            StringBuilder sb = new StringBuilder();
            for (String nm : order) {
                if (sb.length() > BOOK_TEXT_MAX / 3) break;
                java.util.zip.ZipEntry e = byName.get(nm);
                if (e == null) continue;
                String page = readAllLimited(zf.getInputStream(e), BOOK_XML_MAX);
                String t = docXmlToText(page);
                t = t.replaceAll("(?m)^[ \\t]+$", "").replaceAll("\n{3,}", "\n\n").trim();
                if (!t.isEmpty()) {
                    sb.append(t).append("\n\n");
                }
            }
            return sb.toString();
        } finally {
            try { zf.close(); } catch (Throwable ignored) {}
        }
    }

    private static String attr(String tag, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?is)\\b" + java.util.regex.Pattern.quote(key) + "\\s*=\\s*[\"']([^\"']*)[\"']")
                .matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    /**
     * doc：老式 Word 二进制（CFB 复合文档）。
     *
     * 完整解析要做 FAT 链、目录项、piece table，工程量太大且容易崩。
     * 实践上正文总是以 UTF-16LE 或本地代码页（这里按 GBK 试）成片出现，
     * 所以直接用「按编码扫可打印片段」的粗糙办法：命中率对普通文档足够，
     * 遇到排版特殊的会丢格式（这是有意接受的取舍）。
     */
    private String extractDoc(File f) throws IOException {
        byte[] raw = readAllBytesLimited(f, BOOK_TEXT_MAX);
        // 三个候选各自打分，取分最高的那个。
        // 早先版本是「取字符串最长」，结果同一批字节用 ISO-8859-1 解出来的
        // 乱码永远比正确解码更长，于是把乱码选中了（实测 68 个字符全是 ÐÏà¡±á）。
        // 真正的判据不是长度，而是「像不像人能读的文字」。
        String s16 = tidyExtracted(scanUtf16(raw));
        String sGb = tidyExtracted(new String(raw, java.nio.charset.Charset.forName("GBK")));
        String sAscii = tidyExtracted(new String(raw, java.nio.charset.Charset.forName("ISO-8859-1")));

        String best = s16;
        double bestScore = readableScore(s16);
        double sGB = readableScore(sGb);
        if (sGB > bestScore) { best = sGb; bestScore = sGB; }
        double sAsc = readableScore(sAscii);
        if (sAsc > bestScore) { best = sAscii; }
        return best;
    }

    /**
     * UTF-16LE 里挑可打印片段。
     *
     * doc 正文在二进制流里通常是 UTF-16LE：ASCII 字符后跟 0x00，中文是
     * 两个非零字节。按码点扫描连续 ≥RUN 个可打印字符的片段，其余当噪声丢掉。
     */
    private static String scanUtf16(byte[] raw) {
        final int RUN = 8;
        StringBuilder out = new StringBuilder(raw.length / 2);
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i + 1 < raw.length; i += 2) {
            int lo = raw[i] & 0xFF, hi = raw[i + 1] & 0xFF;
            int cp = lo | (hi << 8);
            boolean okChar = (cp >= 0x20 && cp != 0x7F && cp < 0xD800) || cp == 0xD800;
            okChar = okChar || cp == '\n' || cp == '\r' || cp == '\t';
            if (okChar) {
                cur.append((char) cp);
            } else {
                if (cur.length() >= RUN) out.append(cur).append('\n');
                cur.setLength(0);
            }
        }
        if (cur.length() >= RUN) out.append(cur);
        return out.toString();
    }

    /**
     * 「像不像正文」打分。
     *
     * 权重来自实际踩的坑：中文/英文/数字/常见中文标点是正分，控制字符和
     * Latin-1 高位区的怪符号（Ð Ï à ¡ ± á 这类乱码典型长相）是负分。
     * 返回的是「可读字符占总字符数的比例」，落在 0..1。
     */
    private static double readableScore(String s) {
        if (s == null || s.isEmpty()) return 0.0;
        int good = 0, bad = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\t') { good++; continue; }
            if (c >= 0x20 && c < 0x7F) { good++; continue; }             // ASCII 可见字符
            if (c >= 0x4E00 && c <= 0x9FFF) { good += 2; continue; }     // 中日韩统一汉字，权重加倍
            if (c >= 0x3000 && c <= 0x303F) { good++; continue; }        // 中文标点
            if (c >= 0xFF00 && c <= 0xFFEF) { good++; continue; }        // 全角
            if (c >= 0x00A0 && c <= 0x024F) { bad += 2; continue; }      // Latin 扩展：乱码重灾区
            if (c < 0x20) { bad += 3; continue; }                        // 控制字符
            bad++;
        }
        // 分母用「加权后的总字符量」，好字符占比越高越可信
        double total = good + bad;
        if (total <= 0) return 0.0;
        return good / total;
    }

    /** 丢掉控制字符、把连续空行压成一个，留着也读不下去 */
    private static String tidyExtracted(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int blank = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                blank++;
                if (blank <= 2) sb.append(c);
                continue;
            }
            blank = 0;
            if (c >= 0x20 || c == '\t') sb.append(c);
        }
        return sb.toString().trim();
    }

    private static String readAllLimited(InputStream in, int limit) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        try {
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                total += n;
                if (total >= limit) break;
            }
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
        return new String(bos.toByteArray(), java.nio.charset.Charset.forName("UTF-8"));
    }

    private static byte[] readAllBytesLimited(File f, int limit) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.io.FileInputStream fis = new java.io.FileInputStream(f);
        try {
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = fis.read(buf)) > 0) {
                bos.write(buf, 0, n);
                total += n;
                if (total >= limit) break;
            }
        } finally {
            try { fis.close(); } catch (Throwable ignored) {}
        }
        return bos.toByteArray();
    }


    // ==================== 在线搜歌词 ====================

    /** 单次请求超时，服务器不回就别一直挂着 */
    private static final int LRC_TIMEOUT_MS = 8000;
    /** 单次返回的候选上限 */
    private static final int LRC_MAX_ITEMS = 12;

    /**
     * 候选歌词（只放元信息，正文等用户选定后再由 /lyrics/save 按 idx 去取）。
     *
     * saveToken 是「服务端记住的那份查询参数」，前端只拿 idx，
     * 这样正文永远从源头重新拉，前端没法把任意内容塞进磁盘。
     */
    private static final class LrcCand {
        String src;      // lrclib / qq / netease
        String title;
        String artist;
        String album;
        long durMs;      // -1 表示未知
        boolean synced;  // 是否带时间轴
        String saveToken; // 服务端重取用的定位串
    }

    /**
     * GET /lyrics/search?f=/Music/a.mp3
     *
     * 只在用户点「去网上找」时触发，不做任何后台批量 —— 一个上千首的曲子目录
     * 挨个查会让手机发烫、还会把宽带打满，所以这里严格一次一首。
     *
     * 返回候选列表，只带元信息（来源/标题/歌手/专辑/时长/是否逐行），
     * 不把歌词正文塞进列表：一条 LRC 动辄 6~20KB，十条候选就是几百 KB，
     * 只为了给用户看一眼列表不值当。选中哪个再由 /lyrics/save 按 idx 去取。
     *
     * 另外 idx 不是全局数组下标，而是「本次请求内按 score 排过序后的位置」，
     * 服务端会把每个候选的 saveToken 一起回给前端 —— 保存时前端把 token 原样带回来。
     */
    private void handleLyricSearch(Request req, OutputStream out) throws IOException {
        String p = req.param("f");
        File audio = resolve(p);
        if (audio == null || !audio.isFile()) {
            sendJson(out, "{\"ok\":false,\"error\":\"audio not found\"}");
            return;
        }

        String base = baseName(audio.getName());
        String[] hint = splitArtistTitle(base);
        String kw = hint[0] == null ? cleanSongName(base) : hint[1];
        String ar = hint[0] == null ? "" : hint[0];

        // 用户可以用「换个关键词」覆盖自动拆出来的结果 —— 文件名乱写时
        // 猜测一定不准，这时候人的一句话比任何启发式都靠谱
        String q = req.param("q");
        if (q != null && !q.trim().isEmpty()) {
            String t = q.trim();
            String[] h2 = splitArtistTitle(t);
            if (h2[0] != null) {
                ar = h2[0];
                kw = h2[1];
            } else {
                kw = t;
            }
        }

        List<LrcCand> all = new ArrayList<>();

        // 1) LRCLIB：最省事，直接给 syncedLyrics，不需要任何 key。
        //    中文冷门曲子它常常没有，所以下面两条兜底必须无条件跑 ——
        //    早先设的「已有 4 条就不查别家」会让人误以为源挂了。
        try {
            all.addAll(searchLrclib(kw, ar));
        } catch (Throwable ignored) { }

        // 2) 网易云：中文曲库最全
        try {
            all.addAll(searchNetease(kw, ar));
        } catch (Throwable ignored) { }

        // 3) QQ音乐：现已停用。
        //    歌词端点（fcg_query_lyric_new.fcg）还能用，但搜索端点
        //    （client_search_cargo / musicu.fcg）都要签名或直接返回空 list，
        //    拿不到 songmid 就没法定位 —— 留着这段只会让用户看到永远搜不到的源。
        //    代码先保留在 searchQQ 里，等 QQ 那边恢复可用再把它接回来。

        // 排序：先按「跟查询词像不像」，再按有无时间轴。
        // 三家源的命中质量差很多（网易云连 zzz 都能返回 6 首无关歌），
        // 不做这一步的话用户得在十几条噪音里翻。
        final String fkw = kw == null ? "" : kw.trim();
        final String far = ar == null ? "" : ar.trim();
        Collections.sort(all, new Comparator<LrcCand>() {
            @Override
            public int compare(LrcCand a, LrcCand b) {
                int sa = score(a, fkw, far), sb = score(b, fkw, far);
                if (sa != sb) return sb - sa;
                if (a.synced != b.synced) return a.synced ? -1 : 1;
                return 0;
            }
        });

        if (all.size() > LRC_MAX_ITEMS) all = all.subList(0, LRC_MAX_ITEMS);

        // 把排好序的位置固化进 saveToken，前端拿着走
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"query\":\"").append(jsonEsc(kw))
                .append("\",\"artist\":\"").append(jsonEsc(ar))
                .append("\",\"items\":[");
        for (int i = 0; i < all.size(); i++) {
            LrcCand c = all.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"idx\":").append(i)
                    .append(",\"src\":\"").append(jsonEsc(c.src))
                    .append("\",\"title\":\"").append(jsonEsc(c.title))
                    .append("\",\"artist\":\"").append(jsonEsc(c.artist))
                    .append("\",\"album\":\"").append(jsonEsc(c.album))
                    .append("\",\"dur\":").append(c.durMs)
                    .append(",\"synced\":").append(c.synced)
                    .append(",\"tok\":\"").append(jsonEsc(c.saveToken))
                    .append("\"}");
        }
        sb.append("]}");
        sendJson(out, sb.toString());
    }

    /** 去掉扩展名 */
    private static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * 候选与查询词的贴合度打分（越大越靠前）。
     *
     * 只做四档粗判，不追求语义 —— 目标是「同名同歌手」顶到最上面，
     * 顺带把「晴天 (女声版)」「晴天 - Live」这类排到后面，但别把它们踢掉
     * （用户有时要的就是 Live 版）。标题完全相等的给最高分。
     */
    private static int score(LrcCand c, String kw, String ar) {
        int s = 0;
        String t = c.title == null ? "" : c.title.trim();
        String n = c.artist == null ? "" : c.artist.trim();
        if (!kw.isEmpty()) {
            if (t.equalsIgnoreCase(kw)) s += 6;
            else if (t.toLowerCase(Locale.ROOT).contains(kw.toLowerCase(Locale.ROOT))) s += 4;
            else if (kw.toLowerCase(Locale.ROOT).contains(t.toLowerCase(Locale.ROOT))
                    && !t.isEmpty()) s += 2;
        }
        if (!ar.isEmpty() && !n.isEmpty()
                && (n.equalsIgnoreCase(ar) || n.toLowerCase(Locale.ROOT)
                        .contains(ar.toLowerCase(Locale.ROOT)))) {
            s += 5;
        }
        // 带「伴奏 / 纯音乐 / 抖音 / 翻唱」的多半不是用户想要的
        String low = (t + " " + (c.album == null ? "" : c.album)).toLowerCase(Locale.ROOT);
        if (low.contains("伴奏") || low.contains("instrumental")
                || low.contains("纯音乐") || low.contains("karaoke")) {
            s -= 3;
        }
        if (c.synced) s += 1;
        return s;
    }

    /**
     * 从「01. 歌手 - 歌名 (Live)」这类文件名里拆出歌手和歌名。
     * 拆不出来就返回 {null, 原串}。
     *
     * 只看第一个 " - "：欧美曲名里常有 "A - B - C"，取第一段当歌手最稳。
     */
    private static String[] splitArtistTitle(String base) {
        String s = base.trim();
        // 去掉开头的序号：01. / 01、 / 01_ / 01-
        s = s.replaceFirst("^\\d{1,3}\\s*[\\.、_\\-\\s]\\s*", "").trim();
        int i = s.indexOf(" - ");
        if (i <= 0 || i >= s.length() - 3) return new String[]{null, s};
        String a = s.substring(0, i).trim();
        String t = s.substring(i + 3).trim();
        if (a.isEmpty() || t.isEmpty()) return new String[]{null, s};
        return new String[]{a, t};
    }

    /**
     * 把歌名里的噪声削掉：括号内容、音质标签、副本标记。
     * 这些留在查询串里会把搜索引向翻唱、纯伴奏、甚至完全无关的结果。
     */
    private static String cleanSongName(String s) {
        String t = s;
        t = t.replaceAll("[（(\\[【][^）)\\]】]*[）)\\]】]", " ");
        t = t.replaceAll("(?i)\\b(320k|128k|flac|ape|wav|hires|hq|sq|mp3|aac|m4a)\\b", " ");
        t = t.replaceAll("-\\s*副本", " ");
        t = t.replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? s.trim() : t;
    }

    /** LRCLIB 搜索：/api/search?q= 或 ?track_name=&artist_name= */
    private List<LrcCand> searchLrclib(String kw, String ar) throws Exception {
        List<LrcCand> outList = new ArrayList<>();
        String q;
        if (ar != null && !ar.isEmpty()) {
            q = "track_name=" + urlEnc(kw) + "&artist_name=" + urlEnc(ar);
        } else {
            q = "q=" + urlEnc(kw);
        }
        String json = httpGet("https://lrclib.net/api/search?" + q, null);
        if (json == null) return outList;

        for (String obj : splitJsonArray(json)) {
            String synced = jsonStr(obj, "syncedLyrics");
            String plain = jsonStr(obj, "plainLyrics");
            if ((synced == null || synced.isEmpty()) && (plain == null || plain.isEmpty())) continue;
            LrcCand c = new LrcCand();
            c.src = "lrclib";
            c.title = nz(jsonStr(obj, "trackName"));
            c.artist = nz(jsonStr(obj, "artistName"));
            c.album = nz(jsonStr(obj, "albumName"));
            c.durMs = (long) (jsonNum(obj, "duration") * 1000);
            c.synced = synced != null && !synced.isEmpty();
            // lrclib 的 id 就是稳定定位串
            c.saveToken = "lrclib:" + (long) jsonNum(obj, "id");
            outList.add(c);
            if (outList.size() >= 6) break;
        }
        return outList;
    }

    /**
     * 网易云搜索：歌曲 ID + 名字，正文另外拉。
     *
     * 注意不能直接对整个 song 对象用 jsonStr 抠字段 —— 里面嵌套着
     * album.artist.name 和 album.artist.id（全是空串 / 0），用 indexOf
     * 找第一个命中就会把空字符串和 0 当成结果。必须逐层定位。
     */
    private List<LrcCand> searchNetease(String kw, String ar) throws Exception {
        List<LrcCand> outList = new ArrayList<>();
        String q = (ar != null && !ar.isEmpty()) ? (kw + " " + ar) : kw;
        String url = "https://music.163.com/api/search/get/web?s=" + urlEnc(q)
                + "&type=1&offset=0&total=true&limit=8";
        Map<String, String> h = new HashMap<>();
        h.put("Referer", "https://music.163.com/");
        h.put("User-Agent", "Mozilla/5.0");
        String json = httpGet(url, h);
        if (json == null) return outList;

        int i = json.indexOf("\"songs\"");
        if (i < 0) return outList;
        int lb = json.indexOf('[', i);
        if (lb < 0) return outList;
        String arr = jsonArrayAt(json, lb);
        if (arr == null) return outList;

        for (String obj : splitJsonArray(arr)) {
            LrcCand c = new LrcCand();
            c.src = "netease";

            // 顶层 name 紧跟在 album/artists 之后，取「最后一个同层的 name」最稳：
            // 这里改成剥掉 album 与 artists 两个子对象后再取，避免误命中内层
            String flat = stripKey(obj, "album");
            flat = stripKey(flat, "artists");
            c.title = nz(jsonStr(flat, "name"));
            c.durMs = (long) jsonNum(obj, "duration");
            // 歌曲 id：同样要在剥掉子对象后取，否则拿到 album.artist.id = 0
            long id = (long) jsonNum(flat, "id");

            // 歌手从 artists 数组的第一个对象拿
            int ai = obj.indexOf("\"artists\"");
            if (ai >= 0) {
                int al = obj.indexOf('[', ai);
                if (al >= 0) {
                    String aarr = jsonArrayAt(obj, al);
                    if (aarr != null && !aarr.isEmpty()) c.artist = nz(jsonStr(aarr, "name"));
                }
            }
            // 专辑从 album 对象拿
            int bi = obj.indexOf("\"album\"");
            if (bi >= 0) {
                int bl = obj.indexOf('{', bi);
                if (bl >= 0) {
                    String bobj = jsonObjectAt(obj, bl);
                    if (bobj != null) c.album = nz(jsonStr(bobj, "name"));
                }
            }

            if (id <= 0 || c.title.isEmpty()) continue;
            c.synced = true; // 有 lrc 就一定有轴，实际以 save 时为准
            c.saveToken = "netease:" + id;
            outList.add(c);
            if (outList.size() >= 6) break;
        }
        return outList;
    }

    /**
     * 把 obj 里 "key":{...} 或 "key":[...] 这段子结构整段删掉。
     *
     * 目的不是真解析 JSON，而是让后续 jsonStr 只看得见本层字段 ——
     * 网易云/QQ 的响应里 album.artist 这种隐藏嵌套特别多，
     * 直接 indexOf 必然先命中内层。
     */
    private static String stripKey(String obj, String key) {
        String pat = "\"" + key + "\"";
        int i = obj.indexOf(pat);
        if (i < 0) return obj;
        int colon = obj.indexOf(':', i + pat.length());
        if (colon < 0) return obj;
        int p = colon + 1;
        while (p < obj.length() && Character.isWhitespace(obj.charAt(p))) p++;
        if (p >= obj.length()) return obj;
        char open = obj.charAt(p);
        if (open != '{' && open != '[') return obj;
        String sub = open == '{' ? jsonObjectAt(obj, p) : jsonArrayAt(obj, p);
        if (sub == null) return obj;
        int end = obj.indexOf(sub, p);
        if (end < 0) return obj;
        return obj.substring(0, i) + obj.substring(end + sub.length());
    }

    /** 从 pos 处的 '{' 找出配平的整个对象文本 */
    private static String jsonObjectAt(String s, int pos) {
        if (pos < 0 || pos >= s.length() || s.charAt(pos) != '{') return null;
        int depth = 0;
        boolean inStr = false;
        for (int i = pos; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return s.substring(pos, i + 1);
            }
        }
        return null;
    }

    /**
     * QQ音乐搜索：songmid 定位，词从 lyric fcg 拉。
     *
     * 目前没有调用点 —— QQ 的搜索端点要签名，未签名时歌单恒为空。
     * 保留实现是为了哪天恢复可用时直接接回去（fetchLyricByToken 里的
     * qq: 分支和它配套，那边实测是好的）。
     */
    private List<LrcCand> searchQQ(String kw, String ar) throws Exception {
        List<LrcCand> outList = new ArrayList<>();
        String q = (ar != null && !ar.isEmpty()) ? (kw + " " + ar) : kw;
        String url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cargo?format=json"
                + "&n=8&p=1&w=" + urlEnc(q) + "&cr=1&g_tk=5381&t=0&flag_qc=0";
        Map<String, String> h = new HashMap<>();
        h.put("Referer", "https://y.qq.com/");
        h.put("User-Agent", "Mozilla/5.0");
        String json = httpGet(url, h);
        if (json == null) return outList;

        int i = json.indexOf("\"list\"");
        if (i < 0) return outList;
        int lb = json.indexOf('[', i);
        if (lb < 0) return outList;
        String arr = jsonArrayAt(json, lb);
        if (arr == null) return outList;

        for (String obj : splitJsonArray(arr)) {
            LrcCand c = new LrcCand();
            c.src = "qq";
            c.title = nz(jsonStr(obj, "songname"));
            c.artist = "";
            int si = obj.indexOf("\"singer\"");
            if (si >= 0) {
                int sl = obj.indexOf('[', si);
                if (sl >= 0) {
                    String sarr = jsonArrayAt(obj, sl);
                    if (sarr != null && !sarr.isEmpty()) c.artist = nz(jsonStr(sarr, "name"));
                }
            }
            c.album = nz(jsonStr(obj, "albumname"));
            c.durMs = (long) jsonNum(obj, "interval") * 1000;
            c.synced = true;
            c.saveToken = "qq:" + nz(jsonStr(obj, "songmid"));
            if (c.saveToken.length() <= 3) continue;
            outList.add(c);
            if (outList.size() >= 6) break;
        }
        return outList;
    }

    /**
     * POST /lyrics/save  (f=<音频绝对路径>&tok=<搜索时回传的 tok>)
     *
     * 按 tok 重新拉一遍正文再落盘，不复用前端传上来的内容 ——
     * 前端能改的东西一律不可信，何况接口本身是公开的。
     *
     * 落盘规则：
     *  - 写 <音频同名>.lrc，UTF-8 无 BOM
     *  - 已存在时按请求体里的 overwrite 决定：
     *      overwrite=1 → 覆盖（原来的 .lrc 若是另一个大小写，先删掉再写，
     *                    否则 NTFS/FUSE 上会出现两套只差大小写的同名文件）
     *      其它         → 回 alreadyExists，让用户自己决定删不删
     *  - 必须有该目录的写权限
     */
    private void handleLyricSave(Request req, InputStream in, OutputStream out)
            throws IOException {
        if (!"POST".equals(req.method)) {
            sendJson(out, "{\"ok\":false,\"error\":\"POST only\"}");
            return;
        }
        Map<String, String> form = readForm(in, req.contentLength());

        String f = form.get("f");
        String tok = form.get("tok");
        if (f == null || f.isEmpty() || tok == null || tok.isEmpty()) {
            sendJson(out, "{\"ok\":false,\"error\":\"缺少参数\"}");
            return;
        }
        // overwrite=1 表示用户已经确认过要替换旧歌词（前端弹过确认框）
        String ov = form.get("overwrite");
        boolean overwrite = "1".equals(ov) || "true".equalsIgnoreCase(ov);

        File audio = resolve(f);
        if (audio == null || !audio.isFile()) {
            sendJson(out, "{\"ok\":false,\"error\":\"audio not found\"}");
            return;
        }
        File dir = audio.getParentFile();
        if (dir == null || !canWrite(req, dir)) {
            sendJson(out, "{\"ok\":false,\"error\":\"没有该目录的写权限\"}");
            return;
        }

        // 取正文（服务端重取，绝不信任前端）
        String lrc = null;
        try {
            lrc = fetchLyricByToken(tok);
        } catch (Throwable ignored) { }
        if (lrc == null || lrc.trim().isEmpty()) {
            sendJson(out, "{\"ok\":false,\"error\":\"这个候选没有可用歌词\"}");
            return;
        }

        String base = baseName(audio.getName());
        File target = new File(dir, base + ".lrc");

        // 大小写不敏感的已存在检查：Windows 拷过来常见 .LRC
        File existing = null;
        File[] siblings = dir.listFiles();
        if (siblings != null) {
            for (File t : siblings) {
                if (t.isFile() && t.getName().equalsIgnoreCase(base + ".lrc")) {
                    existing = t;
                    break;
                }
            }
        }
        if (existing != null) {
            if (!overwrite) {
                sendJson(out, "{\"ok\":false,\"alreadyExists\":true,\"file\":\""
                        + jsonEsc(existing.getName()) + "\",\"error\":\"同名歌词已存在\"}");
                return;
            }
            // 覆盖模式：先删旧文件。
            // 如果旧文件叫 .LRC 而新写的叫 .lrc，在大小写敏感的文件系统上
            // 会同时留下两份，播放页的 findLyric 只认其中一个，另一份就成了幽灵。
            // 删不掉（只读盘、被占用）就退回「拒绝覆盖」，别留个半吊子状态。
            if (!existing.delete()) {
                sendJson(out, "{\"ok\":false,\"error\":\"旧歌词删除失败，无法覆盖\"}");
                return;
            }
        }

        // 落盘前最后一道门：拿到的必须真是带时间轴的歌词。
        // 歌词源偶尔会返回 JSON 残片或一句「暂无歌词」，那种东西写进去
        // 比没有更糟 —— 播放页会显示一堆乱码行，看着像服务器坏了。
        int timedLines = 0;
        for (String ln : lrc.split("\r?\n")) {
            if (ln.trim().matches("^\\[\\d{1,3}:\\d{1,2}(\\.\\d{1,3})?\\].*")) timedLines++;
        }
        if (timedLines < 2) {
            sendJson(out, "{\"ok\":false,\"error\":\"这份内容没有可用的逐行时间轴\"}");
            return;
        }

        byte[] bytes = lrc.getBytes(StandardCharsets.UTF_8);
        java.io.FileOutputStream fos = new java.io.FileOutputStream(target);
        try {
            fos.write(bytes);
            fos.flush();
        } finally {
            fos.close();
        }

        sendJson(out, "{\"ok\":true,\"file\":\"" + jsonEsc(target.getName())
                + "\",\"bytes\":" + bytes.length
                + (existing != null ? ",\"replaced\":true" : "")
                + "}");
    }

    /** 按搜索时回传的 tok 去源头重新取歌词正文 */
    private String fetchLyricByToken(String tok) throws Exception {
        if (tok.startsWith("lrclib:")) {
            long id = Long.parseLong(tok.substring(7));
            String json = httpGet("https://lrclib.net/api/get/" + id, null);
            if (json == null) return null;
            String s = jsonStr(json, "syncedLyrics");
            if (s == null || s.isEmpty()) s = jsonStr(json, "plainLyrics");
            return s;
        }
        if (tok.startsWith("netease:")) {
            long id = Long.parseLong(tok.substring(8));
            Map<String, String> h = new HashMap<>();
            h.put("Referer", "https://music.163.com/");
            h.put("User-Agent", "Mozilla/5.0");
            String json = httpGet("https://music.163.com/api/song/lyric?id=" + id
                    + "&lv=1&kv=1&tv=-1", h);
            if (json == null) return null;
            // 注意：网易云的 "lrc" 是对象（{"version":2,"lyric":"..."}），
            // 不是字符串。直接 jsonStr(json,"lrc") 会把 "{" 后面的版本号当值，
            // 结果存下来只有一个 {"version":2。必须先定位对象再取内层 lyric。
            String lrcObj = jsonObjectForKey(json, "lrc");
            if (lrcObj != null) {
                String s = jsonStr(lrcObj, "lyric");
                if (s != null && !s.isEmpty()) return s;
            }
            String k = jsonObjectForKey(json, "klyric");
            if (k != null) {
                String s = jsonStr(k, "lyric");
                if (s != null && !s.isEmpty()) return s;
            }
            String t = jsonObjectForKey(json, "tlyric");
            if (t != null) {
                String s = jsonStr(t, "lyric");
                if (s != null && !s.isEmpty()) return s;
            }
            return null;
        }
        if (tok.startsWith("qq:")) {
            String mid = tok.substring(3);
            Map<String, String> h = new HashMap<>();
            h.put("Referer", "https://y.qq.com/");
            h.put("User-Agent", "Mozilla/5.0");
            String json = httpGet("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
                    + "?songmid=" + urlEnc(mid) + "&format=json&nobase64=0&g_tk=5381", h);
            if (json == null) return null;
            String s = jsonStr(json, "lyric");
            if (s == null || s.isEmpty()) return null;
            // QQ 返回 base64 + HTML 实体
            String raw = new String(java.util.Base64.getDecoder().decode(s),
                    StandardCharsets.UTF_8);
            return unescapeHtml(raw);
        }
        return null;
    }

    /** 词库返回的 &amp; 之类解回真字符 */
    private static String unescapeHtml(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
    }

    // ---------- 极简 HTTP / JSON 工具（不引第三方库，设备上跑） ----------

    private static String urlEnc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /**
     * 极简 GET。HttpURLConnection，8 秒超时，解 gzip。
     * 任何异常都返回 null —— 歌词搜不到是常态，不该把整个请求弄挂。
     */
    private String httpGet(String url, Map<String, String> headers) {
        java.net.HttpURLConnection c = null;
        try {
            java.net.URL u = new java.net.URL(url);
            c = (java.net.HttpURLConnection) u.openConnection();
            c.setConnectTimeout(LRC_TIMEOUT_MS);
            c.setReadTimeout(LRC_TIMEOUT_MS);
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            c.setRequestProperty("Accept-Encoding", "gzip");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    c.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return null;

            InputStream raw = c.getInputStream();
            InputStream is = raw;
            String enc = c.getContentEncoding();
            if (enc != null && enc.toLowerCase(Locale.ROOT).contains("gzip")) {
                is = new java.util.zip.GZIPInputStream(raw);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = is.read(buf)) > 0) {
                total += n;
                // 歌词 JSON 再大也就几百 KB，超过 2MB 直接断，防止被灌爆内存
                if (total > 2 * 1024 * 1024) break;
                bos.write(buf, 0, n);
            }
            is.close();
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    /**
     * 取 "key":{...} 那个子对象的整段文本。
     *
     * 网易云的 "lrc":{...} 就是这种形状，必须先把对象抠出来再往里取字段，
     * 否则外层 jsonStr 会把对象开头的 "{" 当成字符串值的起点。
     */
    private static String jsonObjectForKey(String json, String key) {
        String pat = "\"" + key + "\"";
        int i = json.indexOf(pat);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + pat.length());
        if (colon < 0) return null;
        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        if (p >= json.length() || json.charAt(p) != '{') return null;
        return jsonObjectAt(json, p);
    }

    /** 从 JSON 文本里取 "key":"value"（只处理字符串值，够用） */    private static String jsonStr(String json, String key) {
        String pat = "\"" + key + "\"";
        int i = json.indexOf(pat);
        if (i < 0) return "";
        int colon = json.indexOf(':', i + pat.length());
        if (colon < 0) return "";
        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        if (p >= json.length()) return "";
        if (json.charAt(p) == '"') {
            StringBuilder sb = new StringBuilder();
            for (int k = p + 1; k < json.length(); k++) {
                char ch = json.charAt(k);
                if (ch == '\\') {
                    k++;
                    if (k >= json.length()) break;
                    char e = json.charAt(k);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'u':
                            if (k + 4 < json.length()) {
                                try {
                                    sb.append((char) Integer.parseInt(
                                            json.substring(k + 1, k + 5), 16));
                                    k += 4;
                                } catch (NumberFormatException ignored) { }
                            }
                            break;
                        default: sb.append(e);
                    }
                } else if (ch == '"') {
                    break;
                } else {
                    sb.append(ch);
                }
            }
            return sb.toString();
        }
        // 数字 / true / false / null
        int k = p;
        while (k < json.length() && ",}] \n\r\t".indexOf(json.charAt(k)) < 0) k++;
        return json.substring(p, k);
    }

    /** 取数值字段，取不到返回 0 */
    private static double jsonNum(String json, String key) {
        String v = jsonStr(json, key);
        if (v == null || v.isEmpty()) return 0;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 从 pos 处的 '[' 找出配平的整个数组文本 */
    private static String jsonArrayAt(String s, int pos) {
        if (pos < 0 || pos >= s.length() || s.charAt(pos) != '[') return null;
        int depth = 0;
        boolean inStr = false;
        for (int i = pos; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return s.substring(pos, i + 1);
            }
        }
        return null;
    }

    /** 把 "[{...},{...}]" 拆成单个对象串；元素不是对象就跳过 */
    private static List<String> splitJsonArray(String arr) {
        List<String> outList = new ArrayList<>();
        if (arr == null) return outList;
        int i = 0;
        while (i < arr.length()) {
            if (arr.charAt(i) == '{') {
                int depth = 0;
                boolean inStr = false;
                int start = i;
                for (int k = i; k < arr.length(); k++) {
                    char c = arr.charAt(k);
                    if (inStr) {
                        if (c == '\\') k++;
                        else if (c == '"') inStr = false;
                        continue;
                    }
                    if (c == '"') inStr = true;
                    else if (c == '{') depth++;
                    else if (c == '}') {
                        depth--;
                        if (depth == 0) {
                            outList.add(arr.substring(start, k + 1));
                            i = k + 1;
                            break;
                        }
                    }
                }
                if (i == start) break;
            } else {
                i++;
            }
        }
        return outList;
    }

    // ==================== 影视刮削信息 ====================

    /**
     * 播放页底部要显示的图片候选名（不含扩展名），顺序即优先级。
     *
     * 各家刮削器命名不统一，这里按实际会被写出来的名字排一遍：
     *  - poster / folder / cover：通用海报
     *  - fanart / backdrop / background：背景图（Kodi 叫 fanart，Emby 叫 backdrop）
     *  - thumb / landscape：横版缩略
     *  - logo / clearlogo：透明标题图
     *  - banner：横幅
     */
    private static final String[] ART_BASES = {
            "poster", "folder", "cover", "fanart", "backdrop", "background",
            "thumb", "landscape", "logo", "clearlogo", "banner",
            "post", "movie", "show", "art"
    };

    /** 图片扩展名，按优先级 */
    private static final String[] ART_EXTS = {
            "jpg", "jpeg", "png", "webp", "bmp"
    };

    /**
     * GET /scrape?f=/Movies/xxx.mkv
     *
     * 返回同目录下影视刮削出来的元数据与图片：
     *   { ok, nfo:{...}, images:[{kind, url, base, ext}], base }
     *
     * 图片不是直接读字节而是回给「静态路径」，让浏览器自己缓存 ——
     * 海报动辄几百 KB，每次播放都从 Java 里流一遍没必要。
     *
     * 查找策略（两种常见命名都认）：
     *   1. 影片同名：xxx-poster.jpg / xxx.poster.jpg / xxx_poster.jpg / xxx-poster.png
     *      以及刮削器最爱的 xxx-poster.jpg 变体 xxx.jpg
     *   2. 目录通用：folder.jpg / poster.jpg / fanart.jpg / backdrop.jpg
     *      —— 电视剧单集目录里通常只放一套通用图，靠这个兜底。
     */
    private void handleScrape(Request req, OutputStream out) throws IOException {
        String p = req.param("f");
        File video = resolve(p);
        if (video == null || !video.isFile()) {
            sendJson(out, "{\"ok\":false,\"error\":\"video not found\"}");
            return;
        }
        File dir = video.getParentFile();
        String name = video.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;

        // ---- nfo ----
        String nfoText = null;
        String nfoName = null;
        for (String c : new String[]{base + ".nfo", "movie.nfo", "tvshow.nfo"}) {
            File t = new File(dir, c);
            if (t.isFile()) { nfoText = readTextSmart(t); nfoName = t.getName(); break; }
        }
        // 目录里可能大小写不同，兜底扫一遍
        if (nfoText == null) {
            File[] list = dir.listFiles();
            if (list != null) {
                for (File t : list) {
                    if (!t.isFile()) continue;
                    String n = t.getName().toLowerCase(Locale.ROOT);
                    if (n.equals((base + ".nfo").toLowerCase(Locale.ROOT))
                            || n.equals("movie.nfo") || n.equals("tvshow.nfo")) {
                        nfoText = readTextSmart(t);
                        nfoName = t.getName();
                        break;
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"base\":\"").append(jsonEsc(base)).append("\"");
        if (nfoText != null) {
            sb.append(",\"nfoFile\":\"").append(jsonEsc(nfoName)).append("\",\"nfo\":")
                    .append(nfoToJson(nfoText));
        } else {
            sb.append(",\"nfo\":null");
        }

        // ---- images ----
        sb.append(",\"images\":[");
        List<String> seen = new ArrayList<>();
        boolean first = true;
        for (String kind : ART_BASES) {
            File hit = findArt(dir, base, kind);
            if (hit == null) continue;
            String abs = hit.getAbsolutePath();
            if (seen.contains(abs)) continue;
            seen.add(abs);
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"kind\":\"").append(jsonEsc(kind)).append("\"")
                    .append(",\"base\":\"").append(jsonEsc(base)).append("\"")
                    .append(",\"file\":\"").append(jsonEsc(hit.getName())).append("\"")
                    .append(",\"url\":\"").append(jsonEsc(encode(abs))).append("\"}");
        }
        sb.append("]}");
        sendJson(out, sb.toString());
    }

    /**
     * 找一张图。先按「影片同名 + 分隔符 + 类型 + 扩展名」，再按「类型 + 扩展名」通用名。
     *
     * 分隔符三种都试：`-` `.` `_`，因为 tinyMediaManager 用 `-`，
     * Kodi 官方约定用 `-`，Emby 跟 Jellyfin 有些版本用 `.`。
     */
    private File findArt(File dir, String base, String kind) {
        if (dir == null) return null;
        String[] seps = {"-", ".", "_", ""};
        // 1. 影片同名优先
        for (String sep : seps) {
            for (String ext : ART_EXTS) {
                File t = new File(dir, base + sep + kind + "." + ext);
                if (t.isFile()) return t;
            }
            // 大写扩展名也认
            for (String ext : ART_EXTS) {
                File t = new File(dir, base + sep + kind + "." + ext.toUpperCase(Locale.ROOT));
                if (t.isFile()) return t;
            }
        }
        // 2. 目录通用名兜底（电视剧一集一个目录时的常见布局）
        for (String ext : ART_EXTS) {
            File t = new File(dir, kind + "." + ext);
            if (t.isFile()) return t;
        }
        // 3. 最后扫一遍，忽略大小写（U 盘是 NTFS，Windows 写出来的名字大小写很随意）
        File[] list = dir.listFiles();
        if (list != null) {
            String wantA = (base + "-" + kind).toLowerCase(Locale.ROOT);
            String wantB = (base + "." + kind).toLowerCase(Locale.ROOT);
            String wantC = (base + "_" + kind).toLowerCase(Locale.ROOT);
            String wantD = kind.toLowerCase(Locale.ROOT);
            for (File t : list) {
                if (!t.isFile()) continue;
                String n = t.getName().toLowerCase(Locale.ROOT);
                int d = n.lastIndexOf('.');
                if (d <= 0) continue;
                String stem = n.substring(0, d);
                if (stem.equals(wantA) || stem.equals(wantB)
                        || stem.equals(wantC) || stem.equals(wantD)) {
                    return t;
                }
            }
        }
        return null;
    }

    /**
     * 把 Kodi 风格的 nfo XML 抠成 JSON。
     *
     * 不引 XML 解析器 —— 这些文件经常是刮削器手拼的，格式脏（未转义 &、缺闭合、
     * BOM 混编码），Scanner 级正则反而更皮实。只取播放页真正要显示的字段。
     */
    static String nfoToJson(String xml) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        String[][] simple = {
                {"title", "title"}, {"originaltitle", "originalTitle"},
                {"sorttitle", "sortTitle"}, {"year", "year"},
                {"premiered", "premiered"}, {"releasedate", "releaseDate"},
                {"runtime", "runtime"}, {"rating", "rating"},
                {"votes", "votes"}, {"mpaa", "mpaa"},
                {"director", "director"}, {"writer", "writer"},
                {"studio", "studio"}, {"tagline", "tagline"},
                {"outline", "outline"}, {"plot", "plot"},
                {"showtitle", "showTitle"}, {"season", "season"},
                {"episode", "episode"}, {"displayseason", "displaySeason"},
                {"displayepisode", "displayEpisode"}, {"id", "id"},
                {"imdbid", "imdbId"}, {"tmdbid", "tmdbId"},
                {"tvdbid", "tvdbId"},
        };
        for (String[] kv : simple) {
            String v = tagText(xml, kv[0]);
            if (v == null || v.isEmpty()) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(kv[1]).append("\":\"").append(jsonEsc(v)).append("\"");
        }
        // 多值：genre / country / actor(只取 name)
        String[] genres = tagAll(xml, "genre");
        if (genres.length > 0) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"genres\":[");
            for (int i = 0; i < genres.length; i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(jsonEsc(genres[i])).append("\"");
            }
            sb.append("]");
        }
        String[] countries = tagAll(xml, "country");
        if (countries.length > 0) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"countries\":[");
            for (int i = 0; i < countries.length; i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(jsonEsc(countries[i])).append("\"");
            }
            sb.append("]");
        }
        // 演员：<actor><name>X</name><role>Y</role></actor>
        List<String[]> actors = new ArrayList<>();
        java.util.regex.Matcher am = java.util.regex.Pattern
                .compile("<actor>(.*?)</actor>", java.util.regex.Pattern.DOTALL
                        | java.util.regex.Pattern.CASE_INSENSITIVE).matcher(xml);
        while (am.find() && actors.size() < 12) {
            String blk = am.group(1);
            String nm = tagText(blk, "name");
            String role = tagText(blk, "role");
            if (nm != null && !nm.isEmpty()) actors.add(new String[]{nm, role});
        }
        if (!actors.isEmpty()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"actors\":[");
            for (int i = 0; i < actors.size(); i++) {
                if (i > 0) sb.append(",");
                String[] a = actors.get(i);
                sb.append("{\"name\":\"").append(jsonEsc(a[0])).append("\"");
                if (a[1] != null && !a[1].isEmpty()) {
                    sb.append(",\"role\":\"").append(jsonEsc(a[1])).append("\"");
                }
                sb.append("}");
            }
            sb.append("]");
        }
        sb.append("}");
        return sb.toString();
    }

    /** 取第一个标签的文本，去 CDATA 与首尾空白 */
    private static String tagText(String xml, String tag) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<" + tag + "(?:\\s[^>]*)?>(.*?)</" + tag + ">",
                        java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(xml);
        if (!m.find()) return null;
        return cleanXmlText(m.group(1));
    }

    /** 取同名标签全部文本 */
    private static String[] tagAll(String xml, String tag) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<" + tag + "(?:\\s[^>]*)?>(.*?)</" + tag + ">",
                        java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(xml);
        while (m.find()) {
            String v = cleanXmlText(m.group(1));
            if (v != null && !v.isEmpty()) out.add(v);
        }
        return out.toArray(new String[0]);
    }

    /** 去掉 CDATA、XML 实体，压掉换行 */
    private static String cleanXmlText(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("<![CDATA[") && s.endsWith("]]>")) {
            s = s.substring(9, s.length() - 3);
        }
        s = s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
        return s.replaceAll("\\s+", " ").trim();
    }

    private String readTextSmart(File f) {        byte[] data;
        try {
            long len = f.length();
            if (len > 2 * 1024 * 1024) len = 2 * 1024 * 1024;
            data = new byte[(int) len];
            try (FileInputStream fis = new FileInputStream(f)) {
                int off = 0;
                while (off < data.length) {
                    int r = fis.read(data, off, data.length - off);
                    if (r <= 0) break;
                    off += r;
                }
                if (off < data.length) data = Arrays.copyOf(data, off);
            }
        } catch (IOException e) {
            return "";
        }
        // BOM 检测
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF
                && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return new String(data, 3, data.length - 3, StandardCharsets.UTF_8);
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE);
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16BE);
        }
        // 先试 UTF-8
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(data)).toString();
        } catch (Exception ignored) {}
        // 回落 GBK
        try {
            return new String(data, "GBK");
        } catch (UnsupportedEncodingException e) {
            return new String(data, StandardCharsets.ISO_8859_1);
        }
    }

    // ==================== ZIP 打包 ====================

    private void handleZip(Request req, OutputStream out) throws IOException {
        List<String> items = req.params.get("p");
        List<File> files = new ArrayList<>();
        if (items != null) {
            for (String it : items) {
                File f = resolve(it);
                if (f != null) files.add(f);
            }
        }
        if (files.isEmpty()) {
            sendError(out, 400, "没有选中任何文件");
            return;
        }

        String zipName = files.size() == 1
                ? stripExt(files.get(0).getName()) + ".zip"
                : cfg.title + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".zip";

        out.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/zip\r\n"
                + "Content-Disposition: attachment; filename*=UTF-8''" + encode(zipName) + "\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));

        ChunkedOut co = new ChunkedOut(out);
        ZipStreamWriter zw = new ZipStreamWriter(co);

        int seq = 0;
        for (File f : files) {
            if (f.isDirectory()) {
                writeZipDir(zw, f, f.getName() + "/", seq == 0);
                seq++;
            } else {
                zw.addFile(f.getName(), f);
                seq++;
            }
        }
        zw.finish();
        co.finish();
        out.flush();
    }

    private void writeZipDir(ZipStreamWriter zw, File dir, String prefix, boolean first) throws IOException {
        File[] list = dir.listFiles();
        if (list == null) return;
        Arrays.sort(list, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File f : list) {
            if (f.isDirectory()) {
                writeZipDir(zw, f, prefix + f.getName() + "/", false);
            } else {
                zw.addFile(prefix + f.getName(), f);
            }
        }
    }

    private static String stripExt(String s) {
        int i = s.lastIndexOf('.');
        return i > 0 ? s.substring(0, i) : s;
    }

    // ==================== 上传 ====================

    private void handlePut(Request req, InputStream in, OutputStream out) throws IOException {
        String p = req.path;
        if (p.startsWith("/dav")) p = p.substring(4);
        File target = resolveForWrite(p);
        if (target == null) { sendError(out, 403, "路径不合法"); return; }
        // 目标解析出来之后再判权限：账号可能只被授权写某个子目录
        if (!canWrite(req, target)) { sendForbidden(out, "上传"); return; }

        long len = req.contentLength();
        if (target.getParentFile() != null && !target.getParentFile().exists()) {
            //noinspection ResultOfMethodCallIgnored
            target.getParentFile().mkdirs();
        }
        long written = 0;
        try (FileOutputStreamSafe fos = new FileOutputStreamSafe(target)) {
            byte[] buf = new byte[CHUNK];
            long left = len < 0 ? Long.MAX_VALUE : len;
            while (left > 0) {
                int want = (int) Math.min(buf.length, left);
                int r = in.read(buf, 0, want);
                if (r <= 0) break;
                fos.write(buf, 0, r);
                written += r;
                bytesIn.addAndGet(r);
                left -= r;
            }
        }
        if (len >= 0 && written != len) {
            sendError(out, 500, "写入不完整: " + written + "/" + len);
            return;
        }
        out.write(("HTTP/1.1 201 Created\r\nServer: " + cfg.title
                + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
        log("PUT " + target.getName() + " (" + human(written) + ")");
    }

    /**
     * 网页端上传（multipart/form-data）。
     *
     * 为什么不用「先把整个请求读进内存再解析」：一个 4GB 的电影会直接把
     * 堆撑爆。这里做的是**边读边写**的流式解析 —— 边扫边界边把文件内容
     * 直接倒进目标文件的输出流，内存里只有一块 64KB 的缓冲。
     *
     * 目录从表单字段 dir 取（绝对路径），文件从多个 file 字段取。
     * 权限门和 PUT 完全一样：canWrite(req, 目标文件)，即
     * 「有写等级」+「目标落在自己的授权目录里」两条都必须满足。
     *
     * 重名策略：加 (1)(2) 后缀，不覆盖已有文件。上传本来是「多个东西丢进
     * 来」的粗暴操作，静默覆盖比留下重名更难收拾。
     */
    private void handleUpload(Request req, InputStream in, OutputStream out) throws IOException {
        if (!"POST".equalsIgnoreCase(req.method)) {
            sendError(out, 405, "上传只接受 POST");
            return;
        }
        if (!canWrite(req)) { sendForbidden(out, "上传"); return; }

        String ct = req.headers.get("content-type");
        if (ct == null || !ct.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
            sendError(out, 400, "需要 multipart/form-data");
            return;
        }
        String boundary = multipartBoundary(ct);
        if (boundary == null) {
            sendError(out, 400, "缺少 boundary");
            return;
        }

        long len = req.contentLength();
        if (len <= 0) {
            sendError(out, 411, "缺少 Content-Length");
            return;
        }

        int saved = 0, failed = 0;
        List<String> savedNames = new ArrayList<>();
        String errorMsg = null;
        File dir = null;

        MultipartReader mp = new MultipartReader(in, boundary, len);
        try {
            MultipartReader.Part part;
            while ((part = mp.next()) != null) {
                if ("dir".equals(part.fieldName) && part.fileName == null) {
                    // 目录字段：先读出来（最多几 KB）
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    mp.drainTo(part, bos, 8192);
                    String dp = new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
                    dir = resolveForWrite(dp);
                    if (dir == null || !dir.isDirectory()) {
                        errorMsg = "目标目录不存在：" + dp;
                        break;
                    }
                    if (!canWrite(req, dir)) {
                        errorMsg = "没有这个目录的写权限：" + dp;
                        break;
                    }
                } else if (part.fileName != null && !part.fileName.isEmpty()) {
                    // 文件字段：必须有 dir 先到
                    if (dir == null) { errorMsg = "缺少目标目录"; break; }
                    // 文件名只取最后一段，剥掉浏览器可能给的路径与穿越
                    String base = safeFileName(part.fileName);
                    if (base.isEmpty()) { mp.drainQuiet(part); failed++; continue; }
                    File target = uniqueIn(dir, base);
                    if (!canWrite(req, target)) {
                        mp.drainQuiet(part);
                        failed++;
                        continue;
                    }
                    try (FileOutputStreamSafe fos = new FileOutputStreamSafe(target)) {
                        long n = mp.drainTo(part, fos, 65536);
                        fos.flush();
                        saved++;
                        savedNames.add(target.getName() + " (" + human(n) + ")");
                        log("⬆ 上传 " + target.getName() + "（" + human(n) + "，" + req.who() + "）");
                    } catch (IOException io) {
                        // 写盘炸了（空间满、只读挂载）：删掉半截文件，别留垃圾
                        //noinspection ResultOfMethodCallIgnored
                        target.delete();
                        throw io;
                    }
                } else {
                    mp.drainQuiet(part);
                }
            }
        } catch (Exception e) {
            errorMsg = "上传中断：" + (e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage());
        }

        String back = dir != null ? backTo(dir) : "/";
        if (errorMsg != null) {
            redirect303(out, back + errSep(back) + "err=" + encode(errorMsg));
            return;
        }
        if (saved == 0 && failed == 0) {
            redirect303(out, back + errSep(back) + "err=" + encode("没有选择任何文件"));
            return;
        }
        StringBuilder msg = new StringBuilder("已上传 " + saved + " 个文件");
        if (failed > 0) msg.append("，").append(failed).append(" 个跳过（无权限或名字非法）");
        if (!savedNames.isEmpty() && savedNames.size() <= 5) {
            msg.append("：").append(String.join("、", savedNames));
        }
        redirect303(out, back + errSep(back) + "ok=" + encode(msg.toString()));
    }

    /** 从 Content-Type 里抠 boundary，抠不到返回 null */
    private static String multipartBoundary(String ct) {
        for (String seg : ct.split(";")) {
            String s = seg.trim();
            if (s.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
                String b = s.substring("boundary=".length()).trim();
                if (b.startsWith("\"") && b.endsWith("\"") && b.length() >= 2) {
                    b = b.substring(1, b.length() - 1);
                }
                return b.isEmpty() ? null : b;
            }
        }
        return null;
    }

    /**
     * 把浏览器送来的文件名洗干净。
     *
     * 只取最后一段路径（IE 会带 `C:\foo\bar.txt`，老浏览器带 `path/to/x`），
     * 再把 `\ / : * ? " < > |` 与控制字符换成下划线。
     * 不做「宁缺毋滥」的空值返回，除了整串都是非法字符的情况。
     */
    static String safeFileName(String raw) {
        if (raw == null) return "";
        String s = raw.replace('\\', '/');
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        s = s.trim();
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|' || c == '/') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String r = sb.toString().trim();
        // . 和 .. 是目录引用，不能当文件名
        if (r.isEmpty() || ".".equals(r) || "..".equals(r)) return "";
        return r;
    }

    /** 目录内不重名的目标文件：foo.txt → foo(1).txt → foo(2).txt … */
    static File uniqueIn(File dir, String base) {
        File f = new File(dir, base);
        if (!f.exists()) return f;
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String ext = dot > 0 ? base.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File c = new File(dir, stem + "(" + i + ")" + ext);
            if (!c.exists()) return c;
        }
        // 兜到 1000 个还在撞，加时间戳收工
        return new File(dir, stem + "(" + System.currentTimeMillis() + ")" + ext);
    }

    /**
     * 流式 multipart 解析器。
     *
     * 只做一件事：按 `--boundary` 切段，每段先读头部（Content-Disposition），
     * 然后让调用方决定把正文倒到哪儿。正文不进内存。
     *
     * 实现方式是「单字节状态机 + 环形前瞻」：逐字节读，遇到 CRLF 后面
     * 可能跟 boundary 时先按住不写，确认是边界就切段、不是就把它吐进输出。
     * 这样不会因为文件内容里恰好出现 "--" 就误切。
     */
    static final class MultipartReader {
        static final class Part {
            String fieldName;
            String fileName;
            String contentType;
        }

        private final InputStream in;
        private final byte[] dashBoundary;   // "--" + boundary
        private final byte[] crlfDashBoundary;
        private long left;
        private int pushback = -1;

        MultipartReader(InputStream in, String boundary, long totalLen) throws IOException {
            this.in = in;
            this.dashBoundary = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
            this.crlfDashBoundary = ("\r\n--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
            this.left = totalLen;
            // 先吃掉第一个边界（前面可能还有前导 CRLF）
            scanToFirstBoundary();
        }

        private int readByte() throws IOException {
            // pushback 里的字节是「已经读过并扣过 left」的，直接吐出
            if (pushback >= 0) {
                int b = pushback;
                pushback = -1;
                return b;
            }
            if (left <= 0) return -1;
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }

        /** 把刚读出的字节退回去（只支持退一个） */
        private void unread(int b) {
            if (b >= 0 && pushback < 0) pushback = b;
        }

        /** 跳到第一个 --boundary，丢掉之前的一切 */
        private void scanToFirstBoundary() throws IOException {
            int matched = 0;
            while (true) {
                int b = readByte();
                if (b < 0) return;
                if (b == (dashBoundary[matched] & 0xFF)) {
                    matched++;
                    if (matched == dashBoundary.length) return;
                } else {
                    matched = (b == (dashBoundary[0] & 0xFF)) ? 1 : 0;
                }
            }
        }

        /** 读下一段的头；到达结束边界返回 null */
        Part next() throws IOException {
            // 边界之后紧跟 "--"（结束）或者 CRLF（还有下一段）
            int b = readByte();
            if (b == '-') {
                readByte();                        // 第二个 '-'
                return null;                       // --boundary-- 结束
            }
            // 只吃掉紧跟边界的那一个 CRLF。**不能**用 while 一直跳换行，
            // 否则会把头部首字节（'C'）当成空白一起吞掉，
            // 结果每个 part 的 fieldName 全是 null —— 上传会报「没有选择任何文件」。
            if (b == '\r') {
                int b2 = readByte();
                if (b2 == '\n') {
                    b = readByte();                // 跳过 CRLF，b = 头部首字节
                } else {
                    unread(b2);
                    b = '\r';
                }
            } else if (b == '\n') {
                b = readByte();
            }

            // 按行读头部，遇到空行结束（原实现用 prev/c 窗口判 CRLF，
            // 会把第一行末尾的 CRLF 误当成空行，导致只解析到第一条头）
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            while (b >= 0) {
                int c = b;
                b = readByte();
                if (c == '\n') {
                    byte[] lb = line.toByteArray();
                    int len = lb.length;
                    if (len > 0 && lb[len - 1] == '\r') len--;   // 去掉行尾 CR
                    if (len == 0) {
                        // 空行 → 头部结束。此刻 b 已经是**正文的第一个字节**
                        // （循环顶部先读 c 再读 b），必须退回去，
                        // 否则每段正文都会丢首字节（文件内容变成 "econd file"）。
                        unread(b);
                        b = -1;
                        break;
                    }
                    head.write(lb, 0, len);
                    head.write('\r');
                    head.write('\n');
                    line.reset();
                } else {
                    line.write(c);
                }
            }
            Part p = new Part();
            String hs = new String(head.toByteArray(), StandardCharsets.UTF_8);
            for (String one : hs.split("\r?\n")) {
                int colon = one.indexOf(':');
                if (colon <= 0) continue;
                String k = one.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String v = one.substring(colon + 1).trim();
                if ("content-disposition".equals(k)) {
                    p.fieldName = dispositionValue(v, "name");
                    p.fileName = dispositionValue(v, "filename");
                } else if ("content-type".equals(k)) {
                    p.contentType = v;
                }
            }
            return p;
        }

        private static String dispositionValue(String v, String key) {
            // 形如: form-data; name="file"; filename="a.txt"
            String lower = v.toLowerCase(Locale.ROOT);
            int i = lower.indexOf(key + "=");
            if (i < 0) return null;
            int start = i + key.length() + 1;
            if (start >= v.length()) return null;
            char q = v.charAt(start);
            if (q == '"') {
                int end = v.indexOf('"', start + 1);
                return end < 0 ? null : v.substring(start + 1, end);
            }
            int end = v.indexOf(';', start);
            return (end < 0 ? v.substring(start) : v.substring(start, end)).trim();
        }

        /**
         * 把当前段的正文泵进 sink，直到遇到下一个边界。返回写入字节数。
         * 关键在「前瞻」：读到 CRLF 时先停手，看后面是不是 boundary，
         * 是就不写（那是分隔符），不是就把 CRLF 补回去继续。
         */
        long drainTo(Part part, OutputStream sink, int bufSize) throws IOException {
            byte[] buf = new byte[bufSize];
            int n = 0;
            long total = 0;

            // 分隔符整体是 "\r\n--boundary"。用一个「前缀窗口」逐字节比对：
            // tail 里始终存着「可能是分隔符开头」的那一小段，只有确认它
            // 不再是分隔符前缀时才吐进 sink。
            //
            // 这样才处理得对正文本身以 CRLF 结尾的情况：
            //   ...line2\r\n  +  \r\n--boundary
            // 流里是 "\r\n\r\n--boundary"，第一个 CRLF 属于正文，
            // 第二个才是分隔符的起点。早先的写法只在 "\r\n" 之后查一次，
            // 失配后把 '\r' 写进正文，接下来的 "\n--boundary" 首字节不是
            // '\r' 就再也不会触发检查，结果整段分隔符被塞进了文件里。
            final byte[] sep = crlfDashBoundary;
            final int sl = sep.length;
            byte[] tail = new byte[sl];
            int tn = 0;
            int b;
            while ((b = readByte()) >= 0) {
                tail[tn] = (byte) b;
                int cand = tn + 1;
                boolean prefix = true;
                for (int i = 0; i < cand; i++) {
                    if (tail[i] != sep[i]) { prefix = false; break; }
                }
                if (prefix) {
                    tn = cand;
                    if (tn == sl) {
                        // 完整命中分隔符 → 本段正文到此为止
                        if (n > 0) { sink.write(buf, 0, n); total += n; }
                        return total;
                    }
                    continue;
                }
                // 不是前缀：tail 里已攒的字节都属于正文，先吐出去
                for (int i = 0; i < tn; i++) {
                    if (n >= buf.length) { sink.write(buf, 0, n); total += n; n = 0; }
                    buf[n++] = tail[i];
                }
                tn = 0;
                // 当前字节可能是新分隔符的起点（sep[0] 是 '\r'）
                if ((byte) b == sep[0]) {
                    tail[tn++] = (byte) b;
                } else {
                    if (n >= buf.length) { sink.write(buf, 0, n); total += n; n = 0; }
                    buf[n++] = (byte) b;
                }
            }
            if (n > 0) { sink.write(buf, 0, n); total += n; }
            // 读到 EOF：窗口里剩的也是正文
            if (tn > 0) { sink.write(tail, 0, tn); total += tn; }
            return total;
        }

        /** 丢弃当前段正文 */
        void drainQuiet(Part part) {
            try {
                drainTo(part, new OutputStream() {
                    @Override public void write(int b) { }
                    @Override public void write(byte[] b, int off, int len) { }
                }, 8192);
            } catch (IOException ignored) {
                // 丢弃阶段出错无需上报
            }
        }
    }

    /**
     * 网页端删除。
     * 浏览器只能发 GET/POST，发不出 DELETE，所以单独开一个 /_del 接口，
     * 表单带 p（绝对路径）和 back（删完跳回哪个目录）。
     * 校验顺序：登录 → 等级 → 目录范围 → 是不是共享根本身。
     */
    private void handleWebDelete(Request req, InputStream in, OutputStream out) throws IOException {
        String p;
        String back;
        String error = null;

        if ("POST".equalsIgnoreCase(req.method)) {
            Map<String, String> form = readForm(in, req.contentLength());
            p = form.containsKey("p") ? form.get("p") : "";
            back = form.containsKey("back") ? form.get("back") : "";
        } else {
            // GET 只用来做兼容；仍要求有删除权限，不做任何隐式放行
            p = req.param("p");
            back = req.param("back");
        }
        if (back == null || back.isEmpty()) back = "/";

        File f = resolve(p);
        if (f == null) {
            error = "找不到这个文件：" + (p == null ? "" : p);
        } else if (!canDelete(req, f)) {
            error = "没有删除权限，或这个文件不在你的授权目录里。";
        } else if (isRootItself(f)) {
            // 允许删根目录等于一键清空整块盘，不做
            error = "不允许删除共享目录本身。";
        } else {
            String name = f.getName();
            String why = deleteRecWhy(f);
            if (why == null) {
                log("🗑 删除 " + f.getAbsolutePath() + "（" + req.who() + "）");
                redirect303(out, back);
                return;
            }
            error = "删除失败：" + why;
        }

        // 出错就回列表页，用 query 把提示带过去
        String sep = back.contains("?") ? "&" : "?";
        redirect303(out, back + sep + "err=" + encode(error));
    }

    /**
     * 重命名。
     *
     * 表单：p=源绝对路径，name=新名字，back=回到哪。
     * 只改名字，不换目录 —— 想换目录请用 /_move（两个动作合成一个接口的话，
     * 参数一错就可能把文件搬到意料之外的地方）。
     *
     * 几个必须自己兜住的边界：
     * 1. 新名字只允许是「一个名字」，不允许带 / 或 \ —— 否则 abc/../x 就能
     *    跳出当前目录，甚至绕开目录授权。同时也拒绝 . 和 ..。
     * 2. 目标已存在就明确拒绝，不做静默覆盖（覆盖是不可逆的，改名一步
     *    失误就把原文件毁了）。
     * 3. 大小写改名（a.txt → A.txt）在 FAT/NTFS 上 getAbsolutePath 比较
     *    会「相等」，所以先比是否同一个 File 再比名字，不靠 exists() 拦。
     */
    private void handleRename(Request req, InputStream in, OutputStream out)
            throws IOException {
        if (!"POST".equalsIgnoreCase(req.method)) {
            sendError(out, 405, "重命名只接受 POST");
            return;
        }
        Map<String, String> form = readForm(in, req.contentLength());
        String p = form.containsKey("p") ? form.get("p") : "";
        String nn = form.containsKey("name") ? form.get("name").trim() : "";
        String back = form.containsKey("back") ? form.get("back") : "";
        if (back == null || back.isEmpty()) back = "/";

        File src = resolve(p);
        if (src == null) {
            backErr(out, back, "找不到这个文件：" + p);
            return;
        }
        if (!canWrite(req, src)) {
            backErr(out, back, "没有这个目录的写权限");
            return;
        }
        if (isRootItself(src)) {
            backErr(out, back, "不允许重命名共享目录本身");
            return;
        }
        String bad = checkPlainName(nn);
        if (bad != null) {
            backErr(out, back, bad);
            return;
        }
        if (nn.equals(src.getName())) {
            // 名字没变，什么都不做，也不用报错
            redirect303(out, back);
            return;
        }

        File parent = src.getParentFile();
        if (parent == null) {
            backErr(out, back, "拿不到上级目录");
            return;
        }
        File dst = new File(parent, nn);
        // 目标必须仍在原目录里（checkPlainName 已挡住路径分隔符，这里是双保险）
        if (!canonical(parent).equals(canonical(dst.getParentFile()))) {
            backErr(out, back, "新名字不能跨目录");
            return;
        }

        // 重名判定要区分两种情况：目录里已经有一个「同名」项是纯冲突；
        // 但大小写改名（a.txt → A.txt）时，某些文件系统上 listFiles 会把
        // 源文件自己报成新名字，那不叫冲突，得放行。
        String srcCanon = canonical(src);
        for (File sib : parentList(parent)) {
            if (!sib.getName().equals(nn)) continue;
            if (!canonical(sib).equals(srcCanon)) {
                backErr(out, back, "已经有同名的项目了：" + nn);
                return;
            }
        }
        if (!src.renameTo(dst)) {
            // 大小写改名在某些 FUSE 实现上 renameTo 会失败，退化成「先改中间名」
            File tmp = new File(parent, nn + ".~rn" + System.nanoTime());
            if (!src.renameTo(tmp) || !tmp.renameTo(dst)) {
                tmp.delete();
                backErr(out, back, "系统拒绝重命名（可能是只读挂载或名字不合法）");
                return;
            }
        }
        log("✏ 重命名 " + src.getAbsolutePath() + " → " + nn + "（" + req.who() + "）");
        redirect303(out, back + (back.contains("?") ? "&" : "?") + "ok="
                + encode("已重命名为 " + nn));
    }

    /**
     * 移动。
     *
     * 表单：p=源绝对路径，dest=目标目录绝对路径，back=回到哪。
     * 语义借鉴 WebDAV MOVE：目标目录里已有同名项就拒绝，不静默覆盖。
     *
     * 跨盘移动（内部存储 ↔ U 盘，或两块 U 盘之间）renameTo 一定失败，
     * 因为那本质是复制+删除。这里自己实现：先复制到 `名字.part` 临时文件，
     * 复制完再改名，最后删源 —— 中途失败时源文件还在，目标只有一个
     * .part 残留，用户能看出发生了什么，不会出现「半个文件冒充完整文件」。
     */
    private void handleMove(Request req, InputStream in, OutputStream out)
            throws IOException {
        if (!"POST".equalsIgnoreCase(req.method)) {
            sendError(out, 405, "移动只接受 POST");
            return;
        }
        Map<String, String> form = readForm(in, req.contentLength());
        String p = form.containsKey("p") ? form.get("p") : "";
        String dp = form.containsKey("dest") ? form.get("dest") : "";
        String back = form.containsKey("back") ? form.get("back") : "";
        if (back == null || back.isEmpty()) back = "/";

        File src = resolve(p);
        if (src == null) {
            backErr(out, back, "找不到这个文件：" + p);
            return;
        }
        if (!canWrite(req, src)) {
            backErr(out, back, "没有这个目录的写权限");
            return;
        }
        if (isRootItself(src)) {
            backErr(out, back, "不允许移动共享目录本身");
            return;
        }
        File dest = resolve(dp);
        if (dest == null || !dest.isDirectory()) {
            backErr(out, back, "目标目录不存在：" + dp);
            return;
        }
        if (!canWrite(req, dest)) {
            backErr(out, back, "没有目标目录的写权限");
            return;
        }
        // 不能把目录搬进它自己或它的子孙里 —— 那会把整棵树弄丢
        String sc = canonical(src);
        String dc = canonical(dest);
        if (src.isDirectory() && (dc.equals(sc) || dc.startsWith(sc + File.separator))) {
            backErr(out, back, "不能把目录移动到自己里面");
            return;
        }
        File srcParent = src.getParentFile();
        if (srcParent != null && canonical(srcParent).equals(dc)) {
            // 已经在这个目录里了，没什么可做
            backErr(out, back, "它已经在这个目录里了");
            return;
        }

        File target = new File(dest, src.getName());
        if (target.exists()) {
            backErr(out, back, "目标目录里已经有同名的项目了：" + src.getName());
            return;
        }

        boolean ok;
        if (src.renameTo(target)) {
            ok = true;
        } else {
            // 跨文件系统，只能复制 + 删除
            ok = copyRecursive(src, target);
            if (ok) {
                deleteRecWhy(src);
            }
        }
        if (!ok) {
            backErr(out, back, "移动失败（跨盘复制中断，源文件仍在原处）");
            return;
        }
        log("📂 移动 " + src.getAbsolutePath() + " → " + dest.getAbsolutePath()
                + "（" + req.who() + "）");
        redirect303(out, back + (back.contains("?") ? "&" : "?") + "ok="
                + encode("已移动到 " + dest.getName() +
                (dest.getAbsolutePath().equals("/") ? "/" : "")));
    }

    /**
     * 递归复制。给跨盘移动用。
     *
     * 文件先写 `名字.part` 再改名，这样中途断电/断线不会留下一个大小
     * 正确但内容残缺的同名文件骗过后续操作。
     */
    private boolean copyRecursive(File src, File dst) {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) return false;
            File[] kids = src.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    if (!copyRecursive(k, new File(dst, k.getName()))) return false;
                }
            }
            return true;
        }
        File part = new File(dst.getParentFile(), dst.getName() + ".part");
        FileInputStream is = null;
        FileOutputStream os = null;
        try {
            is = new FileInputStream(src);
            os = new FileOutputStream(part);
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
            os.close();
            os = null;
            is.close();
            is = null;
            if (!part.renameTo(dst)) {
                part.delete();
                return false;
            }
            return true;
        } catch (IOException e) {
            log("复制失败 " + src.getAbsolutePath() + "：" + e);
            if (part.exists()) part.delete();
            return false;
        } finally {
            closeQuiet(is);
            closeQuiet(os);
        }
    }

    private static void closeQuiet(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // 关闭失败没有补救动作，忽略
            }
        }
    }

    /**
     * 校验一个「纯名字」：不能含路径分隔符，不能是 . 或 ..，不能为空。
     * 返回 null 表示合格，否则返回给人看的原因。
     */
    private static String checkPlainName(String n) {
        if (n == null || n.isEmpty()) return "名字不能为空";
        if (n.equals(".") || n.equals("..")) return "这个名字不能用";
        if (n.indexOf('/') >= 0 || n.indexOf('\\') >= 0) return "名字里不能有斜杠";
        if (n.length() > 200) return "名字太长了";
        // NUL 等控制字符：某些文件系统上能塞进去但后续谁也别想正常处理
        for (int i = 0; i < n.length(); i++) {
            if (n.charAt(i) < 0x20) return "名字里有不可见字符";
        }
        return null;
    }

    /** 列一个目录的子项；目录读不出来时返回空数组，绝不返回 null */
    private static File[] parentList(File dir) {
        File[] a = dir.listFiles();
        return a == null ? new File[0] : a;
    }

    /**
     * 移动对话框的目录浏览器：列出 dir 下的子目录（外加可选的上级）。
     * 只回目录不回文件 —— 这个接口唯一的用户是「选个落地点」。
     */
    private void handleDirList(Request req, OutputStream out) throws IOException {
        String dp = req.param("d");
        // d 为空 = 要「根列表」：把共享目录当成虚拟的第一级。
        // 前端移动对话框第一次 load('') 就是这一下，早期把它当成路径去 resolve，
        // 于是整框只能看到「目录不存在」。用户在 m08617 反馈的「少了一级」也在这里：
        // 原来点进来的第一屏直接是 /storage/emulated/0 的子目录，没有「内部存储」这一行。
        if (dp == null || dp.trim().isEmpty()) {
            sendRootList(req, out);
            return;
        }
        File dir = resolve(dp);
        if (dir == null || !dir.isDirectory()) {
            sendJson(out, "{\"ok\":false,\"error\":\"目录不存在\"}");
            return;
        }
        if (!canWrite(req, dir)) {
            sendJson(out, "{\"ok\":false,\"error\":\"没有这个目录的写权限\"}");
            return;
        }
        List<File> subs = new ArrayList<>();
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File k : kids) {
                if (!k.isDirectory()) continue;
                if (k.getName().startsWith(".")) continue;
                // 只列授权范围内的目录，免得对话框里点进去才发现没权限
                if (req.user != null && !Users.inScope(req.user, k)) continue;
                subs.add(k);
            }
        }
        subs.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));

        File up = dir.getParentFile();
        String upPath = null;
        boolean dirIsRoot = false;
        for (File r : liveRoots()) {
            if (canonical(r).equals(canonical(dir))) { dirIsRoot = true; break; }
        }
        // 卷根（内部存储 / U 盘挂载点）的上一级不是真目录，而是那个虚拟的根列表。
        // 用空串 "" 表示「回到根列表」，前端据此 load('') 并把当前目录清空。
        if (dirIsRoot) {
            upPath = "";
        } else if (up != null && isUnderAnyRoot(up) && (req.user == null
                || Users.inScope(req.user, up))) {
            upPath = up.getAbsolutePath();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"dir\":\"").append(jsonEsc(dir.getAbsolutePath()))
                .append("\",\"name\":\"").append(jsonEsc(dirNameOf(dir)))
                .append("\",\"up\":");
        if (upPath == null) sb.append("null");
        else sb.append('"').append(jsonEsc(upPath)).append('"');
        sb.append(",\"dirs\":[");
        for (int i = 0; i < subs.size(); i++) {
            File s = subs.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"n\":\"").append(jsonEsc(s.getName()))
                    .append("\",\"p\":\"").append(jsonEsc(s.getAbsolutePath()))
                    .append("\"}");
        }
        sb.append("],\"roots\":[");
        List<File> roots = liveRoots();
        for (int i = 0; i < roots.size(); i++) {
            File r = roots.get(i);
            if (req.user != null && !Users.inScope(req.user, r)) continue;
            if (i > 0 && sb.charAt(sb.length() - 1) != '[') sb.append(',');
            sb.append("{\"n\":\"").append(jsonEsc(volumeLabel(r)))
                    .append("\",\"p\":\"").append(jsonEsc(r.getAbsolutePath()))
                    .append("\"}");
        }
        sb.append("]}");
        sendJson(out, sb.toString());
    }

    /** 目录在界面上显示什么名字；根目录用卷名而不是空串 */
    private String dirNameOf(File dir) {
        String n = dir.getName();
        if (!n.isEmpty() && !isRootItself(dir)) return n;
        return volumeLabel(dir);
    }

    /**
     * 移动对话框的第一屏：把所有共享目录当成一层虚拟的「根」列出来。
     *
     * 为什么需要它：真实路径里 /storage/emulated/0 的上一级是 /storage/emulated，
     * 再上一级是 /storage，都不是用户眼里的「上一层」。在对话框里往上退的时候，
     * 用户期望看到的是「内部存储 / 盘1 / 盘2」这样一排可选项，而不是一个空目录。
     * 所以约定 up="" 表示「回到这一屏」，前端看到空串就 load('') 并清掉当前选中。
     */
    private void sendRootList(Request req, OutputStream out) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"dir\":\"\",\"name\":\"位置\",\"up\":null,\"dirs\":[");
        List<File> roots = liveRoots();
        boolean first = true;
        for (File r : roots) {
            if (req.user != null && !Users.inScope(req.user, r)) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"n\":\"").append(jsonEsc(volumeLabel(r)))
                    .append("\",\"p\":\"").append(jsonEsc(r.getAbsolutePath()))
                    .append("\"}");
        }
        sb.append("],\"roots\":[");
        first = true;
        for (File r : roots) {
            if (req.user != null && !Users.inScope(req.user, r)) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"n\":\"").append(jsonEsc(volumeLabel(r)))
                    .append("\",\"p\":\"").append(jsonEsc(r.getAbsolutePath()))
                    .append("\"}");
        }
        sb.append("]}");
        sendJson(out, sb.toString());
    }

    /** 卷的显示名（内部存储 / 盘1 …），跟首页、列表页保持一致 */
    private String volumeLabel(File root) {
        String p = root.getAbsolutePath();
        if (p.startsWith("/storage/emulated") || p.startsWith("/data/media")) return "内部存储";
        return root.getName();
    }

    /**
     * 目录落在哪个共享根下面。
     *
     * 面包屑第一段要用它：根可能不止一个（内部存储 + 两块 U 盘），
     * 光靠路径字符串猜不出「内部存储」这种展示名。找不到返回 null，
     * 调用方就只画相对层级，不至于整条面包屑塌掉。
     */
    private File rootOf(File dir) {
        if (dir == null) return null;
        String dc = canonical(dir);
        File best = null;
        for (File r : liveRoots()) {
            String rc = canonical(r);
            if (dc.equals(rc) || dc.startsWith(rc + File.separator)) {
                // 嵌套根（极端情况）取最深的那个，否则会先命中外层根把路径切歪
                if (best == null || rc.length() > canonical(best).length()) best = r;
            }
        }
        return best;
    }

    private boolean isDirRoot(File dir, File root) {
        return canonical(dir).equals(canonical(root));
    }

    /**
     * 标题下面那行小字：卷名 + 相对路径。
     *
     * 原来直接显示 relPath(dir)，卷根上是空串、盘内目录上是从根往下的一截，
     * 两种情况都看不出「这是哪个盘」。现在统一拼成「内部存储 / Android / data」。
     */
    private String subPathText(File dir) {
        if (dir == null) return "";
        File r = rootOf(dir);
        String rel = relPath(dir);
        if (r == null) return rel;
        String name = volumeLabel(r);
        return rel.isEmpty() ? name : name + " / " + rel.replace("/", " / ");
    }

    private boolean isUnderAnyRoot(File f) {
        String c = canonical(f);
        for (File r : liveRoots()) {
            String rc = canonical(r);
            if (c.equals(rc) || c.startsWith(rc + File.separator)) return true;
        }
        return false;
    }


    /**
     * 复选框批量删除。
     *
     * 表单里每勾一个就是一对 p（绝对路径），back 是删完跳回哪。
     * 校验逐个走和单删完全一样的四道关，任何一个不合格就整批拒绝——
     * 宁可一次都不删，也不要删一半留下半截状态，用户分不清哪些没了。
     */
    private void handleBatchDelete(Request req, InputStream in, OutputStream out)
            throws IOException {
        if (!"POST".equalsIgnoreCase(req.method)) {
            sendError(out, 405, "批量删除只接受 POST");
            return;
        }
        Map<String, List<String>> form = readFormMulti(in, req.contentLength());
        List<String> paths = form.get("p");
        String back = "/";
        List<String> bl = form.get("back");
        if (bl != null && !bl.isEmpty() && !bl.get(0).isEmpty()) back = bl.get(0);

        if (paths == null || paths.isEmpty()) {
            redirect303(out, back + (back.contains("?") ? "&" : "?")
                    + "err=" + encode("没有勾选任何项目"));
            return;
        }
        if (!canDelete(req)) {
            sendForbidden(out, "删除");
            return;
        }

        // 先整体校验，一个不合格就整批不动
        List<File> targets = new ArrayList<>();
        for (String p : paths) {
            File f = resolve(p);
            if (f == null) {
                redirect303(out, back + errSep(back) + "err="
                        + encode("找不到选中的项目：" + p));
                return;
            }
            if (!canDelete(req, f)) {
                redirect303(out, back + errSep(back) + "err="
                        + encode("有项目不在你的授权目录里：" + f.getName()));
                return;
            }
            if (isRootItself(f)) {
                redirect303(out, back + errSep(back) + "err="
                        + encode("不允许删除共享目录本身：" + f.getName()));
                return;
            }
            targets.add(f);
        }

        int ok = 0;
        List<String> failed = new ArrayList<>();
        for (File f : targets) {
            String why = deleteRecWhy(f);
            if (why == null) {
                ok++;
                log("🗑 批量删除 " + f.getAbsolutePath() + "（" + req.who() + "）");
            } else {
                failed.add(why);
            }
        }
        if (failed.isEmpty()) {
            redirect303(out, back + errSep(back) + "ok=" + encode("已删除 " + ok + " 项"));
            return;
        }
        String msg = "删除 " + ok + " 项，失败 " + failed.size() + " 项："
                + String.join("、", failed);
        if (msg.length() > 400) msg = msg.substring(0, 400) + "…";
        redirect303(out, back + errSep(back) + "err=" + encode(msg));
    }

    private static String errSep(String back) {
        return back.contains("?") ? "&" : "?";
    }

    /** 出错后跳回列表页，用 query 带一句提示 */
    private void backErr(OutputStream out, String back, String msg) throws IOException {
        redirect303(out, back + errSep(back) + "err=" + encode(msg));
    }

    /**
     * 同名参数可以出现多次的读法（批量删除要收多对 p）。
     * readForm 用 Map&lt;String,String&gt; 会把重复键覆盖掉，所以另外开一个。
     */
    private Map<String, List<String>> readFormMulti(InputStream in, long len) {
        Map<String, List<String>> m = new HashMap<>();
        if (len <= 0 || len > MAX_FORM_BYTES) return m;
        byte[] buf = new byte[(int) len];
        int off = 0;
        while (off < len) {
            int r;
            try {
                r = in.read(buf, off, (int) len - off);
            } catch (IOException e) {
                return m;
            }
            if (r < 0) break;
            off += r;
        }
        String body = new String(buf, 0, off, StandardCharsets.UTF_8);
        for (String kv : body.split("&")) {
            if (kv.isEmpty()) continue;
            int e = kv.indexOf('=');
            String k, v;
            if (e < 0) { k = kv; v = ""; }
            else { k = kv.substring(0, e); v = kv.substring(e + 1); }
            k = formDecode(k);
            v = formDecode(v);
            List<String> bucket = m.get(k);
            if (bucket == null) {
                bucket = new ArrayList<>();
                m.put(k, bucket);
            }
            bucket.add(v);
        }
        return m;
    }

    /** 这个 File 是不是某个共享根本身 */
    private boolean isRootItself(File f) {
        String c = canonical(f);
        for (File root : liveRoots()) {
            if (canonical(root).equals(c)) return true;
        }
        return false;
    }

    private void redirect303(OutputStream out, String location) throws IOException {
        out.write(("HTTP/1.1 303 See Other\r\n"
                + "Location: " + location + "\r\n"
                + "Server: " + cfg.title + "\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void handleDelete(Request req, OutputStream out) throws IOException {
        String p = req.path;
        if (p.startsWith("/dav")) p = p.substring(4);
        File f = resolve(p);
        if (f == null) { sendError(out, 404, "找不到"); return; }
        // 先解析目标再判权限，删子目录也要落在授权范围内
        if (!canDelete(req, f)) { sendForbidden(out, "删除"); return; }
        boolean ok = deleteRec(f);
        out.write(("HTTP/1.1 " + (ok ? "204 No Content" : "500 Internal Server Error")
                + "\r\nServer: " + cfg.title + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private boolean deleteRec(File f) {
        if (f.isDirectory()) {
            File[] l = f.listFiles();
            if (l != null) for (File c : l) deleteRec(c);
        }
        return f.delete();
    }

    /**
     * 删除，失败时把「为什么失败」带出来。
     *
     * 原来只有 deleteRec 的 true/false，用户看到的就是「失败 1 项」四个字，
     * 完全没办法判断是只读属性、还是文件被占用、还是磁盘只读挂载。
     * 这里在 delete 之前把可诊断的事实记下来，失败后拼一句人话。
     *
     * @return null 表示成功，否则是失败原因
     */
    private String deleteRecWhy(File f) {
        String why = deleteInner(f);
        return why;
    }

    private String deleteInner(File f) {
        if (f.isDirectory()) {
            File[] l = f.listFiles();
            if (l != null) for (File c : l) deleteInner(c);
        }
        // 删之前先取样：删失败之后这些状态可能已经读不到了
        boolean exists = f.exists();
        boolean canWrite = f.canWrite();
        boolean canRead = f.canRead();
        String path = f.getAbsolutePath();
        boolean ok = f.delete();
        if (ok || !exists) return null;

        StringBuilder sb = new StringBuilder();
        sb.append(f.getName()).append("（");
        if (!canWrite) sb.append("没有写权限");
        else if (!canRead) sb.append("读不到（权限或挂载问题）");
        else sb.append("系统拒绝删除");
        sb.append("：").append(path).append("）");
        return sb.toString();
    }

    private File resolveForWrite(String urlPath) {
        // 与 resolve() 一致：护住文件名里的加号，别让 URLDecoder 当空格吃掉
        String decoded;
        try { decoded = URLDecoder.decode(urlPath.replace("+", "%2B"), "UTF-8"); }
        catch (UnsupportedEncodingException e) { decoded = urlPath.replace("+", "%2B"); }
        catch (IllegalArgumentException e) { decoded = urlPath; }
        decoded = decoded.replaceAll("/{2,}", "/");
        // 和 resolve() 同一套规则：写成绝对路径时先按绝对路径认，
        // 否则 /mnt/media_rw/XXX 会被拼到 /storage/emulated/0 下面写成
        // /storage/emulated/0/mnt/media_rw/XXX —— 文件看着 201 成功其实落错盘。
        for (File root : liveRoots()) {
            if (!root.exists()) continue;
            String rc = canonical(root);
            String ac = canonical(new File(decoded));
            if (ac.equals(rc) || ac.startsWith(rc + File.separator)) {
                return new File(decoded);
            }
        }
        return null;
    }

    // ==================== WebDAV ====================

    private void handleDav(Request req, InputStream in, OutputStream out, String sub)
            throws IOException {
        if (sub.isEmpty()) sub = "/";
        switch (req.method) {
            case "OPTIONS":
                out.write(("HTTP/1.1 200 OK\r\n"
                        + "DAV: 1,2\r\n"
                        + "MS-Author-Via: DAV\r\n"
                        + "Allow: OPTIONS,GET,HEAD,PUT,DELETE,PROPFIND,MKCOL,MOVE,COPY\r\n"
                        + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            case "PROPFIND":
                davPropfind(req, out, sub);
                return;
            case "GET":
            case "HEAD": {
                File f = resolve(sub);
                if (f == null) { sendError(out, 404, "找不到"); return; }
                if (f.isDirectory()) davPropfind(req, out, sub);
                else sendFile(req, out, f);
                return;
            }
            case "PUT":
                handlePut(req, in, out);
                return;
            case "DELETE":
                handleDelete(req, out);
                return;
            case "MKCOL": {
                File f = resolveForWrite(sub);
                if (f == null) { sendError(out, 403, "路径非法"); return; }
                if (!canWrite(req, f)) { sendForbidden(out, "上传"); return; }
                boolean ok = f.mkdirs();
                out.write(("HTTP/1.1 " + (ok ? "201 Created" : "405 Method Not Allowed")
                        + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }
            case "MOVE": {
                String dest = req.headers.get("destination");
                if (dest == null) { sendError(out, 400, "缺少 Destination"); return; }
                int idx = dest.indexOf("/dav/");
                if (idx < 0) { sendError(out, 400, "Destination 非法"); return; }
                String dp = dest.substring(idx + 4);
                File src = resolve(sub);
                File dst = resolveForWrite(dp);
                if (src == null || dst == null) { sendError(out, 404, "路径非法"); return; }
                // 改名要求「源」和「目标」都在授权范围内，否则等于能绕过范围搬文件
                if (!canWrite(req, src) || !canWrite(req, dst)) { sendForbidden(out, "改名"); return; }
                boolean ok = src.renameTo(dst);
                out.write(("HTTP/1.1 " + (ok ? "201 Created" : "409 Conflict")
                        + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                return;
            }
            default:
                sendError(out, 405, "WebDAV 不支持: " + req.method);
        }
    }

    private void davPropfind(Request req, OutputStream out, String sub) throws IOException {
        File f = resolve(sub);
        if (f == null) { sendError(out, 404, "找不到"); return; }

        boolean depth1 = !"0".equals(req.headers.get("depth"));
        String base = req.headers.getOrDefault("host", "localhost");
        String prefixPath = "/dav" + (sub.equals("/") ? "" : sub);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n");
        sb.append("<D:multistatus xmlns:D=\"DAV:\">\n");
        appendDavEntry(sb, f, prefixPath, base);

        if (depth1 && f.isDirectory()) {
            File[] list = f.listFiles();
            if (list != null) {
                Arrays.sort(list, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
                for (File c : list) {
                    appendDavEntry(sb, c, prefixPath + "/" + c.getName(), base);
                }
            }
        }
        sb.append("</D:multistatus>");

        byte[] b = sb.toString().getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 207 Multi-Status\r\n"
                + "Content-Type: application/xml; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private void appendDavEntry(StringBuilder sb, File f, String href, String host) {
        boolean dir = f.isDirectory();
        String h = href.replace(" ", "%20");
        sb.append("  <D:response>\n");
        sb.append("    <D:href>http://").append(host).append(xmlEsc(h)).append(dir ? "/" : "").append("</D:href>\n");
        sb.append("    <D:propstat><D:prop>\n");
        sb.append("      <D:displayname>").append(xmlEsc(f.getName())).append("</D:displayname>\n");
        if (dir) {
            sb.append("      <D:resourcetype><D:collection/></D:resourcetype>\n");
        } else {
            sb.append("      <D:resourcetype/>\n");
            sb.append("      <D:getcontentlength>").append(f.length()).append("</D:getcontentlength>\n");
            sb.append("      <D:getcontenttype>").append(mimeOf(f.getName())).append("</D:getcontenttype>\n");
        }
        sb.append("      <D:getlastmodified>").append(httpDate(f.lastModified())).append("</D:getlastmodified>\n");
        sb.append("    </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>\n");
        sb.append("  </D:response>\n");
    }

    // ==================== HTML 页面 ====================

    private String renderIndex(Request req) {
        StringBuilder sb = new StringBuilder();
        String siteTitle = cfg.title;
        // 首页 title 就用站名本身，不要拼「站名 · 站名」
        htmlHeadRaw(sb, siteTitle, prefTheme);
        sb.append("<div class='wrap'>\n");
        sb.append("<header><h1>").append(esc(siteTitle))
                .append("</h1><div class='sub'>局域网媒体库</div>")
                .append(userBar(req)).append("</header>\n");
        // 主题开关：独立一行贴右，不塞进 header。
        // 塞 header 里试过三种写法都失败（.who 内 / 独立 div + flex-wrap / 负 margin），
        // 手机上 ul0 徽章把这一排撑满后按钮直接被裁到屏幕外，看着像「按钮不存在」。


        // 实时状态面板（由 /_status 轮询更新）
        sb.append("<section class='live' id='live'>\n");
        sb.append("<div class='lt'>手机状态</div>\n");
        // 顶部的不再是手机本地时间，而是「服务已运行多久」——
        // 与 App 首页的「服务已运行」同一个数据源（/_status 的 serviceUptimeText），
        // 这样网页和手机看到的是同一个数字。
        sb.append("<div class='lclock'><b id='l-up2'>--</b>")
                .append("<i>服务已运行</i></div>\n");
        sb.append("<div class='lg'>\n");
        sb.append(liveCard("cpu", "CPU", "—", "—"));
        sb.append(liveCard("gpu", "GPU", "—", "—"));
        sb.append(liveCard("mem", "内存", "—", "—"));
        sb.append(liveCard("disk", "存储", "—", "—"));
        sb.append(liveCard("net", "网络", "—", "—"));
        sb.append(liveCard("temp", "温度", "—", "—"));
        sb.append("</div>\n");
        sb.append("<div class='lb' id='diskBars'>")
                .append(liveBar("disk0", "存储")).append("</div>\n");
        sb.append("<div class='lf'><span id='l-up'>已运行 —</span>")
                .append("<span id='l-io'>↑ — · ↓ —</span>")
                .append("<span id='l-req'>请求 0 · 已发送 0</span></div>\n");
        sb.append("</section>\n");

        sb.append("<section class='roots'>\n");
        // 可移除卷按顺序编号成 盘0/盘1/…，UUID 太难认
        int diskIdx = 0;
        for (File r : liveRoots()) {
            if (!r.exists()) continue;
            int idx = SysInfo.isInternal(r) ? -1 : diskIdx++;
            sb.append("<a class='root' href='/browse").append(encode(r.getAbsolutePath()))
                    .append("'><span class='ri'>").append(SysInfo.isInternal(r) ? svgIcon("phone","svgi-root") : svgIcon("drive","svgi-root"))
                    .append("</span><div><div class='rn'>")
                    .append(esc(SysInfo.volumeName(r, idx)))
                    .append("</div><div class='rp'>").append(esc(r.getAbsolutePath()))
                    .append("</div></div></a>\n");
        }
        sb.append("</section>\n");

        sb.append("<section class='stats'>\n");
        sb.append("<div>请求数 <b>").append(reqCount.get()).append("</b></div>\n");
        sb.append("<div>已发送 <b>").append(human(bytesOut.get())).append("</b></div>\n");
        sb.append("</section>\n");

        sb.append("<footer>").append(esc(cfg.title)).append("</footer>\n");
        sb.append("</div>\n");
        sb.append("<script>\n").append(LIVE_JS).append("</script>\n");
        htmlFoot(sb);
        return sb.toString();
    }

    /** 顶栏右侧的身份标识 */
    private String userBar(Request req) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='who'>");
        if (req.user != null) {
            sb.append("<span class='un'>").append(esc(req.user.name)).append("</span>");
            sb.append("<span class='ul ul").append(req.user.level).append("'>")
                    .append(esc(Users.levelName(req.user.level))).append("</span>");
            // 限定了目录的账号给个徽章，好让人知道自己只管哪一块
            if (!req.user.scopes.isEmpty()) {
                sb.append("<span class='sc' title='")
                        .append(esc(req.user.scopeText())).append("'>目录 ")
                        .append(req.user.scopes.size()).append("</span>");
            }
            sb.append("<a class='lo' href='/_logout'>退出</a>");
        } else if (cfg.hasAccounts()) {
            sb.append("<span class='un'>匿名</span>");
            sb.append("<span class='ul ul0'>只读</span>");
            sb.append("<a class='lo' href='/_login'>登录</a>");
        }
        // 管理入口。加在身份栏里而不是页脚，是因为页脚在几千行的文件列表里
        // 要滚很久才看得到；而「改站点名 / 指目录」这件事只有管理员会做，
        // 放在自己名字旁边最顺手。文案统一叫「管理设置」，与客户端那个页签同名 ——
        // 之前客户端叫「设置」、网页叫「管理」，同一件事两个名字，用户会以为是两处。
        if (req.user != null && req.user.level >= Users.LEVEL_ADMIN) {
            sb.append("<a class='lo adm' href='/_admin'>管理设置</a>");
        }
        // 主题按钮回到这一排里（跟在「登录」后面）。
        //
        // 之前它被挪到 header 下方独立一行，原因是手机上 匿名+只读+登录+日间
        // 四个块总宽超过视口、按钮被裁到屏幕外。这次不是靠换行解决的，是靠
        // 把这一整块换成 CSS grid —— 见 .who 的注释。grid 会让每个格子按内容
        // 宽度摆放，且整排宽度上限是 100%，不依赖 flex-wrap（那个属性在
        // Android 4.3 之类的老 WebView 上根本不生效）。
        sb.append(themeButton(" sm"));
        // 水墨屏单独一颗，紧跟在日夜按钮后面。两颗都放在 .who 的格子里，
        // 老 WebView 上 grid 退化时它们会一起换行，不会叠在一起。
        sb.append(inkButton(" sm"));
        sb.append("</div>");
        return sb.toString();
    }

    /** 删完跳回的地址：目录的浏览页 */
    private String backTo(File dir) {
        return "/browse" + encode(dir.getAbsolutePath());
    }

    /**
     * 删除按钮。用内联 form 而不是 <a>：
     * 删除必须走 POST，GET 会被人用图片标签、预取、爬虫误触发。
     */
    private String delButton(String absPath, String back) {
        // 这里刻意不发 <form>：整页列表已经被 <form id='batch'> 包住（批量删除要用），
        // HTML 不允许 form 嵌套，浏览器会把内层 <form> 标签整个丢掉，只留下里面的
        // input 和 button —— 结果就是这些隐藏 p 全被算进 batch 表单，点「删除所选」
        // 会一口气带上别的路径。所以单删改成「按钮 + JS 自己发 POST」。
        String pv = esc(absPath).replace("\"", "&quot;");
        String bv = esc(back).replace("\"", "&quot;");
        String nm = esc(new File(absPath).getName()).replace("\"", "&quot;")
                .replace("'", "&#39;").replace("\\", "\\\\");
        return "<button type='button' class='delone' title='删除'"
                + " data-p=\"" + pv + "\" data-back=\"" + bv + "\""
                + " data-n=\"" + nm + "\">" + svgIcon("trash", "svgi-act") + "</button>";
    }

    /**
     * 表单里「一行能做的动作」按钮。
     *
     * 跟 🗑 一样刻意不发 <form>：整页列表被 <form id='batch'> 包着，内层
     * form 会被浏览器丢掉标签，隐藏域全漏进 batch 表单。所以这里也是
     * 纯 button + data-*，点的时候由 RNMOVE_JS 建游离表单提交。
     */
    private String renameButton(String absPath, String back) {
        return rowActButton("rn", svgIcon("edit", "svgi-act"), "重命名", absPath, back);
    }

    private String moveButton(String absPath, String back) {
        return rowActButton("mv", svgIcon("move", "svgi-act"), "移动到…", absPath, back);
    }

    private String rowActButton(String kind, String glyph, String title,
                                String absPath, String back) {
        String pv = esc(absPath).replace("\"", "&quot;");
        String bv = esc(back).replace("\"", "&quot;");
        String nm = esc(new File(absPath).getName()).replace("\"", "&quot;")
                .replace("'", "&#39;").replace("\\", "\\\\");
        boolean isDir = new File(absPath).isDirectory();
        return "<button type='button' class='rowact " + kind + "' title='" + title + "'"
                + " data-k='" + kind + "'"
                + " data-p=\"" + pv + "\" data-back=\"" + bv + "\""
                + " data-n=\"" + nm + "\""
                + " data-d='" + (isDir ? "1" : "0") + "'>" + glyph + "</button>";
    }

    /**
     * 重命名 / 移动的前端逻辑。
     *
     * 两个都用页面内自绘的对话框，而不是 window.prompt：
     * - prompt 在 Via/老 WebView 上没法预填默认值、没法做输入校验，
     *   而且移动需要「选目录」，prompt 根本做不到；
     * - 自绘对话框可以在提交前就把非法名字挡下来（斜杠、空名、同名），
     *   少一次来回。
     *
     * 移动的目录浏览器走 /_dirlist 逐层加载，不用一次拉整棵树 ——
     * U 盘上几万个目录一次序列化出来会卡死。
     */
    private static final String RNMOVE_JS =
            "<script>\n"
            + "(function(){\n"
            + "  var ROOTS=window.USBNAS_ROOTS||[];\n"
            + "  function el(tag,cls,txt){\n"
            + "    var e=document.createElement(tag);\n"
            + "    if(cls)e.className=cls; if(txt!=null)e.textContent=txt; return e;\n"
            + "  }\n"
            + "  function postForm(action,pairs){\n"
            + "    var f=document.createElement('form');\n"
            + "    f.method='post';f.action=action;f.style.display='none';\n"
            + "    for(var i=0;i<pairs.length;i++){\n"
            + "      var a=document.createElement('input');\n"
            + "      a.type='hidden';a.name=pairs[i][0];a.value=pairs[i][1];\n"
            + "      f.appendChild(a);\n"
            + "    }\n"
            + "    document.body.appendChild(f); f.submit();\n"
            + "  }\n"
            + "  function checkName(n){\n"
            + "    if(!n||!n.length) return '名字不能为空';\n"
            + "    if(n==='.'||n==='..') return '这个名字不能用';\n"
            + "    if(n.indexOf('/')>=0||n.indexOf('\\\\')>=0) return '名字里不能有斜杠';\n"
            + "    return null;\n"
            + "  }\n"
            // ---- 通用对话框骨架 ----
            + "  function dialog(title,onOk){\n"
            + "    var mask=el('div','nmmask');\n"
            + "    var box=el('div','nmbox');\n"
            + "    var hd=el('div','nmhd',title);\n"
            + "    var bd=el('div','nmbd');\n"
            + "    var err=el('div','nmerr');\n"
            + "    var tgt=el('div','nmtgt');\n"
            + "    var ft=el('div','nmft');\n"
            + "    var cancel=el('button','nmbtn','取消');\n"
            + "    var ok=el('button','nmbtn primary','确定');\n"
            + "    cancel.type='button'; ok.type='button';\n"
            + "    ft.appendChild(cancel); ft.appendChild(ok);\n"
            + "    box.appendChild(hd); box.appendChild(bd); box.appendChild(tgt); box.appendChild(err);\n"
            + "    box.appendChild(ft); mask.appendChild(box);\n"
            + "    document.body.appendChild(mask);\n"
            + "    function close(){ if(mask.parentNode)mask.parentNode.removeChild(mask); }\n"
            + "    cancel.onclick=close;\n"
            + "    ok.onclick=function(){ onOk(close,err,ok); };\n"
            + "    return {mask:mask,bd:bd,err:err,tgt:tgt,ok:ok,close:close};\n"
            + "  }\n"
            // ---- 重命名 ----
            + "  function doRename(p,back,nm,isDir){\n"
            + "    var d=dialog('重命名',function(close,err,ok){\n"
            + "      var v=inp.value;\n"
            + "      if(!v||v===nm){close();return;}\n"
            + "      var bad=checkName(v);\n"
            + "      if(bad){err.textContent=bad;return;}\n"
            + "      if(!isDir){\n"
            + "        var oe=nm.lastIndexOf('.'),ne=v.lastIndexOf('.');\n"
            + "        if(oe>0&&ne<=0&&!confirm('新名字没有扩展名，打开时可能认不出类型。\\n继续吗？'))return;\n"
            + "      }\n"
            + "      ok.disabled=true; ok.textContent='处理中…';\n"
            + "      postForm('/_rename',[['p',p],['name',v],['back',back]]);\n"
            + "    });\n"
            + "    var lab=el('div','nmlab','新名字');\n"
            + "    var inp=el('input','nminp');\n"
            + "    inp.type='text'; inp.value=nm; inp.maxLength=200;\n"
            + "    var cur=el('div','nmcur','当前：'+nm);\n"
            + "    d.bd.appendChild(lab); d.bd.appendChild(inp); d.bd.appendChild(cur);\n"
            + "    inp.onkeydown=function(e){\n"
            + "      if(e.keyCode===13){e.preventDefault();d.ok.click();}\n"
            + "      if(e.keyCode===27){e.preventDefault();d.close();}\n"
            + "    };\n"
            + "    setTimeout(function(){ inp.focus(); inp.select(); },50);\n"
            + "  }\n"
            // ---- 移动 ----
            + "  function doMove(p,back,nm){\n"
            // 三个状态变量必须在最前面声明。
            // 底下 load() 每读一层都要 setCurDir / setSel（改的就是这三个），而
            // load('',null) 是在函数体末尾立刻执行的 —— 那一刻 curDir 和 sel 还
            // 处在「暂时性死区」（`var` 声明在函数作用域里被提升，赋值语句却排在
            // load 之后），写进去的值会被随后的 `var curDir=null` 抹回 null。
            // 表现就是：浏览器里面包屑和列表都刷出来了，但 curDir 一直是 null，
            // 点「移动到此处」永远提醒「先在列表里点一个目录」，
            // 而面包屑那一级也拿不到根名（走 root=null 分支拼出 Android › data）。
            + "    var curDir=null,sel=null,reqSeq=0;\n"
            + "    function setCurDir(v){ curDir=v; }\n"
            + "    function setSel(v){ sel=v; }\n"
            // 面包屑：根列表之外，每一段都可点，末段是当前目录。
            + "    var d=dialog('移动「'+nm+'」',function(close,err,ok){\n"
            + "      if(!sel){err.textContent='先选一个目标目录';return;}\n"
            + "      ok.disabled=true; ok.textContent='移动中…';\n"
            + "      postForm('/_move',[['p',p],['dest',sel],['back',back]]);\n"
            + "    });\n"
            // 面包屑：根列表之外，每一段都可点，末段是当前目录。
            + "    var pathline=el('div','nmpath','');\n"
            + "    var list=el('div','nmlist');\n"
            + "    var pickbar=el('div','nmpick');\n"
            + "    var usebtn=el('button','nmbtn primary','移动到此处');\n"
            + "    var curPath=[];\n"
            + "    usebtn.type='button';\n"
            + "    pickbar.appendChild(usebtn);\n"
            + "    d.bd.appendChild(pathline); d.bd.appendChild(list); d.bd.appendChild(pickbar);\n"
            + "    usebtn.onclick=function(){\n"
            + "      if(!curDir){d.err.textContent='先在列表里点一个目录，或者点「上一级」回到位置列表重新选';return;}\n"
            + "      setSel(curDir); d.ok.click();\n"
            + "    };\n"
            // 点「📂」钻进去，点目录名只选中不改层级 —— 浏览和选中必须分开。
            // 早先点目录名同时做了「选中它」和「钻进它」，于是钻进去以后
            // 目标变成了它的子层，多级目录（卷根这一层）根本回不去，
            // 用户看到的就是「少了内部存储 / 盘0 / 盘1 那一级」。
            + "    function row(label,path,icon,isUp,dv){\n"
            + "      var b=el('button','nmrow');\n"
            + "      b.type='button';\n"
            + "      var ic=el('span','nmic',icon);\n"
            + "      b.appendChild(ic);\n"
            + "      b.appendChild(el('span','nmtx',label));\n"
            + "      if(dv){ b.appendChild(el('span','nmgo','›')); }\n"
            + "      b.onclick=function(){\n"
            + "        if(isUp===1){ load(path,null); return; }\n"
            + "        setSel(path); mark();\n"
            + "      };\n"
            + "      if(dv){\n"
            + "        b.setAttribute('data-p',path);\n"
            + "        ic.onclick=function(e){\n"
            + "          e=e||window.event; if(e&&e.stopPropagation)e.stopPropagation();\n"
            + "          load(path,null);\n"
            + "        };\n"
            + "      }\n"
            + "      return b;\n"
            + "    }\n"
            + "    function mark(){\n"
            + "      var rs=list.getElementsByClassName('nmrow');\n"
            + "      for(var i=0;i<rs.length;i++){\n"
            + "        var on=(rs[i].getAttribute('data-p')===sel);\n"
            + "        rs[i].className=on?'nmrow on':'nmrow';\n"
            + "      }\n"
            // 目标放在专门的提示条里，别占用错误行 —— 否则 mark() 每次
            // 都把「目标：xxx」写进 err，看似正常，其实把真正的错误挤掉了。
            + "      if(sel){ d.err.textContent=''; d.tgt.textContent='移动到：'+sel; }\n"
            + "      else { d.tgt.textContent=''; }\n"
            + "    }\n"
            // 面包屑：只显示「共享根 + 根下面真正的那几级」。
            // /storage/emulated/0 的祖先段（storage、emulated）和
            // /mnt/media_rw/<UUID> 的祖先段（mnt、media_rw）都是实现细节，
            // 露出来既啰嗦又点不得（服务端会回「目录不存在」），所以剥掉。
            + "    function crumbs(dir,name){\n"
            + "      pathline.textContent='';\n"
            + "      var home=el('a','nmcr','<svg class=\\'svgi svgi-inline\\' viewBox=\\'0 0 24 24\\'><path d=\\'M3.5 10.5L12 3.6l8.5 6.9\\'/><path d=\\'M6 9.4V19a1.4 1.4 0 0 0 1.4 1.4h9.2A1.4 1.4 0 0 0 18 19V9.4\\'/></svg> 位置');\n"
            + "      home.href='javascript:void 0';\n"
            + "      home.onclick=function(){ load('',null); };\n"
            + "      pathline.appendChild(home);\n"
            + "      curPath=[];\n"
            + "      if(!dir){ return; }\n"
            + "      var root=null, i;\n"
            + "      for(i=0;i<ROOTS.length;i++){\n"
            + "        var rp=ROOTS[i].p;\n"
            + "        if(dir===rp||dir.indexOf(rp+'/')===0){ if(!root||rp.length>root.p.length) root=ROOTS[i]; }\n"
            + "      }\n"
            + "      var rest=dir;\n"
            + "      if(root){\n"
            + "        curPath.push(root.p);\n"
            + "        pathline.appendChild(el('i','nmsep','›'));\n"
            + "        var rb=el('a','nmcr',root.n);\n"
            + "        rb.href='javascript:void 0'; rb.setAttribute('data-d',root.p);\n"
            + "        rb.onclick=function(){ load(this.getAttribute('data-d'),null); };\n"
            + "        pathline.appendChild(rb);\n"
            + "        rest=(dir.length>root.p.length)?dir.substring(root.p.length+1):'';\n"
            + "      }\n"
            + "      if(!rest){ return; }\n"
            + "      var parts=rest.split('/'), acc=root?root.p:'';\n"
            + "      for(i=0;i<parts.length;i++){\n"
            + "        if(!parts[i]) continue;\n"
            + "        acc=acc+'/'+parts[i];\n"
            + "        curPath.push(acc);\n"
            + "        pathline.appendChild(el('i','nmsep','›'));\n"
            + "        var last=(i===parts.length-1);\n"
            + "        var t=el(last?'b':'a', last?'nmcr last':'nmcr', parts[i]);\n"
            + "        if(!last){\n"
            + "          t.href='javascript:void 0'; t.setAttribute('data-d',acc);\n"
            + "          t.onclick=function(){ load(this.getAttribute('data-d'),null); };\n"
            + "        }\n"
            + "        pathline.appendChild(t);\n"
            + "      }\n"
            + "    }\n"
            + "    function load(path,forceSel){\n"
            + "      var seq=++reqSeq;\n"
            + "      list.textContent='';\n"
            + "      var ld=el('div','nmld','读取中…'); list.appendChild(ld);\n"
            + "      var x=new XMLHttpRequest();\n"
            // 带上时间戳参数 t：同一个目录连点两次时，Via 这类套壳浏览器会
            // 直接命中 GET 缓存回旧响应（表现为「点图标没反应，列表还是旧的」）。
            + "      x.open('GET','/_dirlist?t='+((+new Date())%100000000)+'&d='+encodeURIComponent(path||''),true);\n"
            + "      x.onreadystatechange=function(){\n"
            + "        if(seq!==reqSeq)return;\n"
            + "        if(x.readyState!==4)return;\n"
            + "        if(x.status!==200){ list.textContent=''; list.appendChild(el('div','nmerr','列目录失败')); return; }\n"
            + "        var j; try{ j=JSON.parse(x.responseText); }catch(e){ return; }\n"
            + "        if(!j.ok){ list.textContent=''; list.appendChild(el('div','nmerr',j.error||'列目录失败')); return; }\n"
            // 只切层级，不动 sel —— 用户可能已经选中了别处，换层不该把它清掉。
            + "        setCurDir(j.dir);\n"
            + "        crumbs(j.dir,j.name);\n"
            + "        list.textContent='';\n"
            // up 为 null=没有上一级；up 为空串=上一级是那个虚拟的位置列表（卷根的情况）。
            // 早先写成 if(j.up)，空串是 falsy，卷根就再也回不到位置列表了。
            + "        if(j.up!==null&&j.up!==undefined) list.appendChild(row('上一级',j.up,'<svg class=\\'svgi svgi-act\\' viewBox=\\'0 0 24 24\\'><path d=\\'M12 20V5\\'/><path d=\\'M5.6 11.4L12 5l6.4 6.4\\'/></svg>',1,null));\n"
            + "        for(var i=0;i<j.dirs.length;i++){\n"
            + "          var dv=j.dirs[i];\n"
            + "          var rb=row(dv.n,dv.p,'<svg class=\\'svgi svgi-act\\' viewBox=\\'0 0 24 24\\'><path d=\\'M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z\\'/></svg>',0,dv);\n"
            + "          rb.setAttribute('data-p',dv.p);\n"
            + "          if(dv.p===sel) rb.className='nmrow on';\n"
            + "          list.appendChild(rb);\n"
            + "        }\n"
            + "        if(!j.up&&j.dirs.length===0&&j.dir){\n"
            + "          list.appendChild(el('div','nmempty','这个目录下没有子目录，可直接移动到此处'));\n"
            + "        }\n"
            + "        if(forceSel){ setSel(forceSel); }\n"
            + "        mark();\n"
            + "        if(sel) d.tgt.textContent='移动到：'+sel;\n"
            + "        usebtn.disabled=!sel;\n"
            + "      };\n"
            + "      x.send(null);\n"
            + "    }\n"
            // 关掉对话框就把未完成的请求作废，免得回来时往已删除的节点里写。
            + "    load('',null);\n"
            + "  }\n"            // ---- 事件绑定 ----
            + "  document.addEventListener('click',function(e){\n"
            + "    var t=e.target;\n"
            + "    while(t&&t!==document&&(''+t.className).indexOf('rowact')<0)t=t.parentNode;\n"
            + "    if(!t||t===document)return;\n"
            + "    e.preventDefault(); e.stopPropagation();\n"
            + "    var k=t.getAttribute('data-k');\n"
            + "    var p=t.getAttribute('data-p');\n"
            + "    var back=t.getAttribute('data-back');\n"
            + "    var nm=t.getAttribute('data-n');\n"
            + "    var isDir=t.getAttribute('data-d')==='1';\n"
            + "    if(k==='rn') doRename(p,back,nm,isDir);\n"
            + "    else if(k==='mv') doMove(p,back,nm);\n"
            + "  },true);\n"
            + "  document.addEventListener('keydown',function(e){\n"
            + "    if(e.keyCode===27){\n"
            + "      var m=document.getElementsByClassName('nmmask');\n"
            + "      for(var i=m.length-1;i>=0;i--){ if(m[i].parentNode)m[i].parentNode.removeChild(m[i]); }\n"
            + "    }\n"
            + "  });\n"
            + "})();\n"
            + "</script>\n";

    /**
     * 单个删除的提交逻辑。
     *
     * 动态建一个游离表单（不挂在 batch 里，避免上面说的嵌套问题），
     * 填两个隐藏域后 submit 到 /_del。确认框保留。
     */
    private static final String DELONE_JS =
            "<script>\n"
            + "(function(){\n"
            + "  function post(p,back){\n"
            + "    var f=document.createElement('form');\n"
            + "    f.method='post';f.action='/_del';f.style.display='none';\n"
            + "    var a=document.createElement('input');a.type='hidden';a.name='p';a.value=p;\n"
            + "    var b=document.createElement('input');b.type='hidden';b.name='back';b.value=back;\n"
            + "    f.appendChild(a);f.appendChild(b);document.body.appendChild(f);\n"
            + "    f.submit();\n"
            + "  }\n"
            + "  document.addEventListener('click',function(e){\n"
            + "    var t=e.target;while(t&&t!==document&&!t.className)t=t.parentNode;\n"
            + "    while(t&&t!==document&&(''+t.className).indexOf('delone')<0)t=t.parentNode;\n"
            + "    if(!t||t===document)return;\n"
            + "    e.preventDefault();e.stopPropagation();\n"
            + "    if(!confirm('确定删除？此操作不可撤销。\\n'+t.getAttribute('data-n')))return;\n"
            + "    post(t.getAttribute('data-p'),t.getAttribute('data-back'));\n"
            + "  },true);\n"
            + "})();\n"
            + "</script>\n";

    /** 状态卡：主数值 + 副标题 */
    private String liveCard(String id, String label, String value, String sub) {
        return "<div class='lc'><div class='lk'>" + esc(label) + "</div>"
                + "<div class='lv' id='v-" + id + "'>" + esc(value) + "</div>"
                + "<div class='lu' id='u-" + id + "'>" + esc(sub) + "</div></div>\n";
    }

    /** 进度条行：id 用于 JS 填宽度，label 是左边的名字 */
    private String liveBar(String id, String label) {
        return "<div class='lrow'><span class='ln'>" + esc(label) + "</span>"
                + "<div class='lbar'><i id='b-" + id + "' style='width:0'></i></div>"
                + "<span class='lp' id='p-" + id + "'></span></div>\n";
    }

    private String renderListing(Request req, File dir) {
        String dirPath = dir.getAbsolutePath();
        String q = req.param("q");
        File[] raw = dir.listFiles();
        List<File> list = new ArrayList<>();
        if (raw != null) {
            for (File f : raw) {
                if (f.getName().startsWith(".")) continue;
                if (q != null && !q.isEmpty()
                        && !f.getName().toLowerCase(Locale.ROOT)
                        .contains(q.toLowerCase(Locale.ROOT))) continue;
                list.add(f);
            }
        }
        list.sort((a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        StringBuilder sb = new StringBuilder();
        htmlHead(sb, dir.getName(), prefTheme);
        sb.append("<div class='wrap'>\n");
        sb.append("<header><h1>").append(esc(dir.getName().isEmpty() ? "/" : dir.getName()))
                .append("</h1><div class='sub'>").append(esc(subPathText(dir)))
                .append("</div>").append(userBar(req)).append("</header>\n");


        // 面包屑
        //
        // 第一段必须是「这一级属于哪个盘」：内部存储 / U 盘卷名。
        // 早先只从 relPath(dir) 起拼，而 relPath 对卷根返回空串、对盘内目录返回
        // 去掉根之后的那一截 —— 于是 /storage/emulated/0/Android 只画出 🏠 › Android，
        // 「内部存储」这一级整个消失；卷根更是只剩一个 🏠。
        // 现在先按 liveRoots() 找出当前目录落在哪个根下，把那一段写成卷名，
        // 后面再逐级追加相对路径（卷根本身已经是最后一环时就不重复）。
        sb.append("<nav class='crumbs'>");
        sb.append("<a href='/'>").append(svgIcon("home", "svgi-crumb")).append("</a>");
        File crumbRoot = rootOf(dir);
        String rel = relPath(dir);
        if (crumbRoot != null) {
            String rootName = volumeLabel(crumbRoot);
            if (isDirRoot(dir, crumbRoot)) {
                // 当前就在卷根上：卷名不做链接（点了也只是刷新同一页）
                sb.append("<span class='sep'>›</span><b class='here'>")
                        .append(esc(rootName)).append("</b>");
            } else {
                sb.append("<span class='sep'>›</span><a href='/browse")
                        .append(encode(crumbRoot.getAbsolutePath())).append("'>")
                        .append(esc(rootName)).append("</a>");
            }
        }
        String acc = "";
        if (!rel.isEmpty()) {
            String[] parts = rel.split("/");
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i];
                if (p.isEmpty()) continue;
                acc += "/" + p;
                boolean last = (i == parts.length - 1);
                if (last) {
                    sb.append("<span class='sep'>›</span><b class='here'>")
                            .append(esc(p)).append("</b>");
                } else {
                    sb.append("<span class='sep'>›</span><a href='/browse")
                            .append(encode(rootPathOf(dir) + acc)).append("'>")
                            .append(esc(p)).append("</a>");
                }
            }
        }
        sb.append("</nav>\n");

        // 搜索框
        sb.append("<form class='search' method='get'><input type='text' name='q' placeholder='搜索当前目录…' value='")
                .append(q == null ? "" : esc(q)).append("'><button>搜索</button></form>\n");

        // ---- 上传面板 ----
        // 有写权限才显示。表单的 dir 固定成当前目录，用户在哪儿点就在哪儿传。
        boolean writeAllowed = canWrite(req, dir);
        if (writeAllowed) {
            // 只有一个按钮：没选文件时点它 = 打开文件选择器；选完文件后点它 = 开始上传。
            // 原来这里挂两个按钮（uplbtn「上传到此目录」+ 表单里的 uplgo「开始上传」），
            // 两个行为完全一样，用户看到就是「重复了」。
            sb.append("<div class='upl'>\n");
            sb.append("<form id='uplform' class='uplform' method='post' action='/upload'")
                    .append(" enctype='multipart/form-data'>");
            sb.append("<input type='hidden' name='dir' value=\"")
                    .append(esc(dir.getAbsolutePath()).replace("\"", "&quot;")).append("\">");
            sb.append("<input type='file' name='file' id='uplfile' multiple>");
            sb.append("<button type='submit' class='uplbtn' id='uplbtn'>⬆ 选择文件</button>");
            sb.append("</form>\n");
            sb.append("<span class='uplhint' id='uplhint'>可以多选，支持拖拽文件到页面</span>");
            sb.append("<div class='uplprog' id='uplprog'><i id='uplbar'></i></div>\n");
            sb.append("</div>\n");
        }

        sb.append("<div class='count'>").append(list.size()).append(" 项</div>\n");

        // 上一次删除失败的原因，由 /_del /_delmulti 通过 ?err= 带回来
        String err = req.param("err");
        if (err != null && !err.isEmpty()) {
            sb.append("<div class='errbar'>").append(esc(err)).append("</div>\n");
        }
        String okMsg = req.param("ok");
        if (okMsg != null && !okMsg.isEmpty()) {
            sb.append("<div class='okbar'>").append(esc(okMsg)).append("</div>\n");
        }

        // 列表。delAllowed 时整页内容包在 batch 表单里，每行带一个复选框。
        sb.append("<div class='list'>\n");
        boolean delAllowed = canDelete(req);
        boolean anyDeletable = false;
        if (delAllowed) {
            sb.append("<form id='batch' class='batch' method='post' action='/_delmulti'>");
            sb.append("<input type='hidden' name='back' value=\"")
                    .append(esc(backTo(dir)).replace("\"", "&quot;")).append("\">");
        }
        for (File f : list) {
            String name = f.getName();
            String ext = extOf(name);
            boolean isDir = f.isDirectory();
            String icon = typeIcon(isDir, ext);
            String full = f.getAbsolutePath();
            String enc = encode(full);
            String size = isDir ? "-" : human(f.length());

            sb.append("<div class='row");
            if (!isDir && (BROWSER_AUDIO.contains(ext) || BROWSER_VIDEO.contains(ext))) {
                sb.append(" playable");
            }
            sb.append("'>");

            // 复选框只发给「勾了也删得动」的行：目录本身可以删，共享根不行
            boolean canPick = delAllowed && Users.inScope(req.user, f) && !isRootItself(f);
            if (canPick) anyDeletable = true;
            sb.append("<input type='checkbox' class='ck' name='p' value=\"")
                    .append(esc(full).replace("\"", "&quot;")).append('"');
            if (!canPick) sb.append(" disabled");
            sb.append(" aria-label='选择 ").append(esc(name).replace("\"", "&quot;"))
                    .append("'>");

            if (isDir) {
                sb.append("<a class='nm' href='/browse").append(enc).append("'>");
            } else if (BROWSER_AUDIO.contains(ext)) {
                sb.append("<a class='nm' href='/play/music?f=").append(encode(full)).append("'>");
            } else if (BROWSER_VIDEO.contains(ext)) {
                sb.append("<a class='nm' href='/play/video?f=").append(encode(full)).append("'>");
            } else if (isImage(ext)) {
                // 图片走画廊页：同一目录的所有图片一起看，能左右滑、能放大
                sb.append("<a class='nm' href='/view?f=").append(encode(full)).append("'>");
            } else if (canPreview(ext)) {
                // PDF / Office / 文本 → 站内预览页，而不是逼着用户先下载
                sb.append("<a class='nm' href='/view?f=").append(encode(full)).append("'>");
            } else {
                sb.append("<a class='nm' href='").append(enc).append("' download>");
            }
            sb.append("<span class='ic'>").append(icon).append("</span>");
            sb.append("<span class='tx'>").append(esc(name)).append("</span>");
            sb.append("</a>");

            sb.append("<span class='sz'>").append(size).append("</span>");

            if (!isDir) {
                sb.append("<span class='acts'>");
                if (BROWSER_AUDIO.contains(ext)) {
                    sb.append("<a class='act play' href='/play/music?f=").append(encode(full))
                            .append("' title='播放'>").append(svgIcon("play", "svgi-act")).append("</a>");
                } else if (BROWSER_VIDEO.contains(ext)) {
                    sb.append("<a class='act play' href='/play/video?f=").append(encode(full))
                            .append("' title='播放'>").append(svgIcon("play", "svgi-act")).append("</a>");
                } else if (canPreview(ext)) {
                    sb.append("<a class='act eye' href='/view?f=").append(encode(full))
                            .append("' title='预览'>").append(svgIcon("eye", "svgi-act")).append("</a>");
                } else if (isImage(ext)) {
                    sb.append("<a class='act eye' href='/view?f=").append(encode(full))
                            .append("' title='预览'>").append(svgIcon("eye", "svgi-act")).append("</a>");
                }
                sb.append("<a class='act dl' href='").append(enc)
                        .append("' download title='下载'>").append(svgIcon("download", "svgi-act")).append("</a>");
                if (writeAllowed && Users.inScope(req.user, f) && !isRootItself(f)) {
                    sb.append(renameButton(full, backTo(dir)));
                    sb.append(moveButton(full, backTo(dir)));
                }
                if (delAllowed && Users.inScope(req.user, f)) {
                    sb.append(delButton(full, backTo(dir)));
                }
                sb.append("</span>");
            } else if (writeAllowed && Users.inScope(req.user, f) && !isRootItself(f)) {
                // 目录也能改名和搬走（搬之前服务端会检查不能搬进自己里）
                sb.append("<span class='acts'>")
                        .append(renameButton(full, backTo(dir)))
                        .append(moveButton(full, backTo(dir)))
                        .append(delAllowed ? delButton(full, backTo(dir)) : "")
                        .append("</span>");
            } else if (delAllowed && Users.inScope(req.user, f)) {
                // 目录也能删，但要求目录是空的，否则 confirm 会提示先清空
                sb.append("<span class='acts'>")
                        .append(delButton(full, backTo(dir)))
                        .append("</span>");
            }
            sb.append("</div>\n");
        }
        sb.append("</div>\n");

        // 批量操作条：固定在底部，没勾东西时是灰的
        if (delAllowed) {
            sb.append("</form>\n");
            if (anyDeletable) {
                sb.append("<div class='batchbar' id='batchbar'>");
                sb.append("<label class='ckall'><input type='checkbox' id='ckall'>")
                        .append("<span>全选</span></label>");
                sb.append("<span class='seln' id='seln'>已选 0 项</span>");
                // 下载所选：走 /zip，把勾中的路径用 p 逐个带过去。
                // 它是 GET 而不是表单提交，所以由脚本拼 URL 再打开，
                // 不放进 batch 表单里——表单只有一个 action。
                sb.append("<button type='button' id='btndl' class='ghost'")
                        .append(" disabled>下载所选</button>");
                sb.append("<button type='submit' form='batch' id='btndel' class='danger'")
                        .append(" disabled>删除所选</button>");
                sb.append("</div>\n");
            }
        }

        sb.append("<footer>").append(esc(dirPath)).append("</footer>\n");
        sb.append("</div>\n");
        // BATCH_JS 自带 <script> 包装，UPLOAD_JS 没有 —— 这里必须自己补上。
        // 漏了的话浏览器会把整段 JS 当成正文文本画出来，页面底部就是一坨
        // "(function(){ var form=document.getElementById('uplform'); ..." 的乱码。
        if (delAllowed && anyDeletable) sb.append(BATCH_JS);
        // 单删按钮（🗑）现在是纯 button，靠这段脚本发 POST。它必须跟着
        // 删除权限一起出现，否则按钮点了没反应。
        if (delAllowed) sb.append(DELONE_JS);
        // 重命名 / 移动按钮跟着写权限出现（它们改的是目录内容，不是删）
        if (writeAllowed) {
            // 面包屑要把「共享根」这一级单独画出来（内部存储 / 每个盘各一段），
            // 前端得知道根在哪，所以把当前可见的根路径塞进页面。
            StringBuilder rs = new StringBuilder();
            for (File r : liveRoots()) {
                if (rs.length() > 0) rs.append(",");
                rs.append("{\"n\":\"").append(jsonEsc(volumeLabel(r)))
                  .append("\",\"p\":\"").append(jsonEsc(r.getAbsolutePath())).append("\"}");
            }
            sb.append("<script>window.USBNAS_ROOTS=[")
              .append(rs).append("];</script>\n");
            sb.append(RNMOVE_JS);
        }
        if (writeAllowed) sb.append("<script>\n").append(UPLOAD_JS).append("</script>\n");
        htmlFoot(sb);
        return sb.toString();
    }

    private String renderMusic(Request req, String pathParam) {
        File f = resolve(pathParam);
        if (f == null || !f.isFile()) return errorPage("找不到音频文件: " + pathParam);

        String enc = encode(f.getAbsolutePath());
        String name = f.getName();
        String mime = mimeOf(name);
        String encJson = jsonEsc(f.getAbsolutePath());

        StringBuilder sb = new StringBuilder();
        htmlHead(sb, name, prefTheme);
        sb.append("<div class='wrap player-page'>\n");
        sb.append("<nav class='crumbs'><a href='/'>").append(svgIcon("home", "svgi-crumb")).append("</a>");
        File parent = f.getParentFile();
        if (parent != null) {
            sb.append("<span class='sep'>›</span><a href='/browse")
                    .append(encode(parent.getAbsolutePath())).append("'>")
                    .append(esc(parent.getName())).append("</a>");
        }
        sb.append("</nav>\n");

        sb.append("<div class='np'>\n");
        sb.append("<div class='cov'>").append(svgIcon("music","svgi-cov")).append("</div>\n");
        sb.append("<div class='meta'><div class='ttl'>").append(esc(name))
                .append("</div><div class='fmt'>").append(mime).append("</div></div>\n");
        sb.append("</div>\n");

        sb.append("<audio id='au' controls preload='metadata' src='").append(enc)
                .append("'></audio>\n");

        sb.append("<div class='lrcbar'><span id='lrcstat'>歌词加载中…</span>")
                .append("<span class='lrcbtns'>")
                .append("<button id='btnfind' class='mini'>").append(svgIcon("search","svgi-inline")).append(" 去网上找</button>")
                .append("<button id='btnview' class='mini'>查看歌词</button></span></div>\n");
        // 在线搜索结果挂载点：默认隐藏，点「去网上找」才展开
        sb.append("<div id='lrcfind' class='lrcfind'></div>\n");
        sb.append("<div class='lrc' id='lrc'></div>\n");

        sb.append("<div class='downloads'><a href='").append(enc)
                .append("' download>").append(svgIcon("download","svgi-inline")).append(" 下载原文件</a></div>\n");
        sb.append("</div>\n");

        // ---- JS ----
        sb.append("<script>\n");
        sb.append("const FILE=").append("\"").append(encJson).append("\";\n");
        sb.append(MUSIC_JS);
        sb.append("</script>\n");
        htmlFoot(sb);
        return sb.toString();
    }

    private String renderVideo(Request req, String pathParam) {
        File f = resolve(pathParam);
        if (f == null || !f.isFile()) return errorPage("找不到视频文件: " + pathParam);

        String enc = encode(f.getAbsolutePath());
        String name = f.getName();
        String mime = mimeOf(name);
        String ext = extOf(name);

        StringBuilder sb = new StringBuilder();
        htmlHead(sb, name, prefTheme);
        sb.append("<div class='wrap player-page wide'>\n");
        sb.append("<nav class='crumbs'><a href='/'>").append(svgIcon("home", "svgi-crumb")).append("</a>");
        File parent = f.getParentFile();
        if (parent != null) {
            sb.append("<span class='sep'>›</span><a href='/browse")
                    .append(encode(parent.getAbsolutePath())).append("'>")
                    .append(esc(parent.getName())).append("</a>");
        }
        sb.append("</nav>\n");

        sb.append("<div class='vwrap'><video id='vd' controls preload='metadata' src='")
                .append(enc).append("'></video></div>\n");
        sb.append("<div class='meta'><div class='ttl'>").append(esc(name))
                .append("</div><div class='fmt'>").append(mime).append("</div></div>\n");
        sb.append("<div class='downloads'><a href='").append(enc)
                .append("' download>").append(svgIcon("download","svgi-inline")).append(" 下载原文件</a></div>\n");

        // 刮削信息挂载点：脚本探测到 nfo/海报才往里填，什么也没有就保持空
        sb.append("<div id='scrape' class='scrape'></div>\n");

        sb.append("<script>\n");
        sb.append("const EXT=").append("\"").append(jsonEsc(ext)).append("\";\n");
        sb.append("const URL_=").append("\"").append(jsonEsc(f.getAbsolutePath())).append("\";\n");
        sb.append(VIDEO_JS);
        sb.append(SCRAPE_JS);
        sb.append("</script>\n");
        sb.append("</div>\n");
        htmlFoot(sb);
        return sb.toString();
    }

    /**
     * 文档预览页：PDF 直接喂给浏览器的内置 viewer，文本就地渲染，
     * Office 文档外包给在线预览服务。
     *
     * 三种路径的取舍：
     *  - PDF：Chrome/Edge/Firefox 都自带完整 PDF 阅读器（缩放、翻页、选词、
     *    目录），把字节直接流过去比自己写 canvas 渲染强太多，而且我们
     *    的 sendFile 本来就实现了 Range，大 PDF 能分段加载不会卡。
     *  - 文本 / 代码 / csv：读出来转义后铺在 pre 里，比对着一堆换行符硬调
     *    服务端语言判断省事得多（编码探测复用 readTextSmart）。
     *  - doc/docx/xls/xlsx/ppt/pptx 等：浏览器一个都不认，本地也没法转
     *    （设备无 libreoffice、无 soffice）。唯一现实路子是让外面的预览
     *    服务去拉 —— 微软 Office Online Viewer 或 Google Docs Viewer，
     *    两者都支持 iframe 嵌入且免费。前提是这份文件的公网地址可达；
     *    局域网 IP 拉不到，所以页面里如实写明这一点，别让用户对着
     *    一片空白猜。
     */
    private String renderDoc(Request req, String pathParam) {
        File f = resolve(pathParam);
        if (f == null || !f.isFile()) return errorPage("找不到文件: " + pathParam);

        // 图片单独走画廊：同目录所有图片一起列出来，能滑能放大
        if (isImage(extOf(f.getName()))) return renderGallery(req, f);

        String enc = encode(f.getAbsolutePath());
        String name = f.getName();
        String ext = extOf(name);
        boolean isPdf = BROWSER_PDF.contains(ext);
        boolean isOffice = OFFICE_DOC.contains(ext);
        boolean isText = MIME.containsKey(ext)
                && MIME.get(ext).startsWith("text/")
                && !"html".equals(ext) && !"htm".equals(ext);

        StringBuilder sb = new StringBuilder();
        htmlHead(sb, name, prefTheme);
        sb.append("<div class='wrap docpage'>\n");

        // 面包屑
        sb.append("<nav class='crumbs'><a href='/'>").append(svgIcon("home", "svgi-crumb")).append("</a>");
        File parent = f.getParentFile();
        if (parent != null) {
            sb.append("<span class='sep'>›</span><a href='/browse")
                    .append(encode(parent.getAbsolutePath())).append("'>")
                    .append(esc(parent.getName())).append("</a>");
        }
        sb.append("</nav>\n");

        sb.append("<div class='docbar'><span class='dotitle'>").append(esc(name))
                .append("</span><span class='docsize'>").append(human(f.length()))
                .append("</span>");
        sb.append("<span class='docacts'>");
        sb.append("<a href='").append(enc).append("' target='_blank'>新窗口</a>");
        sb.append("<a href='").append(enc).append("' download>下载</a>");
        sb.append("</span></div>\n");

        if (isPdf) {
            // PDF 内嵌三层降级。
            //
            // 别指望 <iframe src="x.pdf">：Chrome/Android 的 WebView 与手机浏览器
            // 都没有内置 PDF 插件，iframe 里只会渲染出一片纯黑（用户看到的就是这个）。
            // 所以先用 <object>：桌面 Chrome 走内置阅读器，Safari 走 PDFKit；
            // 再不认就落到 <embed>；两层都不行时，object 的兜底内容会显示出来。
            sb.append("<div class='docframe pdfbox'>")
                    .append("<object data='").append(enc).append("#view=FitH' type='application/pdf'>")
                    .append("<embed src='").append(enc).append("#view=FitH' type='application/pdf' />")
                    .append("<div class='pdfnil'><b>这个浏览器不内嵌显示 PDF</b>")
                    .append("<p>手机上可以用「USBNASLink」App 直接读，"
                            + "或者点下面的按钮下载后用系统阅读器打开。</p></div>")
                    .append("</object></div>\n");
            sb.append("<div class='pdfacts'>")
                    .append("<a href='").append(enc).append("' download>")
                    .append(svgIcon("download", "svgi-inline")).append(" 下载 PDF 用系统阅读器看</a>")
                    .append("</div>\n");
        } else if (isText) {
            String text;
            try {
                text = readTextSmart(f);
            } catch (Exception e) {
                text = "（读取失败：" + e.getMessage() + "）";
            }
            sb.append("<pre class='doctext'>").append(esc(text)).append("</pre>\n");
        } else if (isOffice) {
            // ---------- A + B 双层渲染 ----------
            // B 层：服务端直接解析 OOXML，先把正文吐出来，用户永远不白屏。
            // A 层：再尝试加载 docx-preview / SheetJS，成功就整块替换成高保真渲染。
            String abs = f.getAbsolutePath();
            boolean canB = OoxmlView.supports(ext) && OoxmlView.looksLikeZip(f);
            String bHtml = canB ? OoxmlView.render(f, ext) : null;

            if (bHtml != null) {
                // 走双层路线：容器里先放 B，A 成功后替换
                sb.append("<div class='docnote alt' id='oxhint'>"
                        + "正在加载高保真渲染器，未完成前显示的是服务端解析结果。</div>\n");
                sb.append("<div class='oxstage' id='oxstage'>").append(bHtml).append("</div>\n");
                sb.append("<script>window.OX_URL=")
                        .append(jsStr(enc)).append(";window.OX_EXT=")
                        .append(jsStr(ext)).append(";window.OX_NAME=")
                        .append(jsStr(name)).append(";</script>\n");
                // OFFICE_BOOST 同 UPLOAD_JS 一样是不带 <script> 的裸 IIFE，
                // 这里必须自己包。漏了浏览器就把整段 JS 当正文画在页面底部
                // （用户反馈过的那坨 "(function(){ var stage=..." 就是它）。
                sb.append("<script>\n").append(OFFICE_BOOST).append("</script>\n");
            } else {
                // B 层解析不出（老格式 .doc/.xls/.ppt，或文件损坏），只剩两条路：
                // 让用户下载，或者装个 L/could 转好的 PDF 版本
                String why = OoxmlView.supports(ext)
                        ? "这个 " + ext.toUpperCase() + " 文件解析失败（可能不是真正的 OOXML，或是加密文档）"
                        : "浏览器不能直接渲染 " + ext.toUpperCase() + " 这种老式二进制格式";
                String err = OoxmlView.lastErrorText();
                if (!err.isEmpty()) why = why + "：" + err;
                sb.append("<div class='docnote'><b>没法在网页里直接看</b><p>")
                        .append(esc(why))
                        .append("。可以下载后用 Office / WPS 打开，"
                                + "或者把它另存成 PDF 再放回来 —— PDF 是可以直接预览的。</p></div>\n");
                sb.append("<div class='docframe pdfbox'>")
                        .append("<object data='").append(enc).append("#view=FitH' type='application/pdf'>")
                        .append("<embed src='").append(enc).append("#view=FitH' type='application/pdf' />")
                        .append("<div class='pdfnil'><b>这个浏览器不内嵌显示 PDF</b>"
                                + "<p>点下面的链接下载，再用系统阅读器打开。</p></div>")
                        .append("</object></div>\n");
            }
        } else {
            sb.append("<div class='docnote'><b>这个格式没法预览</b>"
                    + "<p>浏览器不认识 .").append(esc(ext))
                    .append("，可以下载后用本地应用打开。</p></div>\n");
        }

        sb.append("<div class='downloads'><a href='").append(enc)
                .append("' download>").append(svgIcon("download","svgi-inline")).append(" 下载原文件</a></div>\n");
        sb.append("</div>\n");
        htmlFoot(sb);
        return sb.toString();
    }

    /** 从请求头里取出 Host，拼预览服务的公网地址用 */
    private static String hostFor(Request req) {
        String h = req.headers.get("host");
        if (h == null || h.isEmpty()) h = "127.0.0.1:8080";
        return h;
    }

    /**
     * Office 预览的 A 层：把服务端解析出来的「能看清」升级成「像原样」。
     *
     * <h3>加载策略</h3>
     * 三套库全部从本机 /docview/ 取（不碰 CDN —— 这是个局域网 NAS，
     * 用户很可能在没有外网的热点上用它）。任何一套加载失败就保留 B 层结果，
     * 一个字节都不动，用户感知到的只是"样式朴素一点"。
     *
     * <ul>
     *   <li>.docx → docx-preview，按页面渲染，分栏/页眉/编号都能还原</li>
     *   <li>.xlsx → SheetJS 读成表格，比 B 层的裸表格多出合列与数字格式</li>
     *   <li>.pptx → B 层的纯文字卡片已经很够用，A 层跳过（pptxjs 效果更差）</li>
     * </ul>
     *
     * <h3>为什么要加超时</h3>
     * 老 WebView 上 fetch 一个不存在的脚本会挂很久，页面一直停在"正在加载"。
     * 6 秒拿不到结果就认输，把提示条改成"已显示服务端解析结果"。
     */
    private static final String OFFICE_BOOST =
            "(function(){\n"
                    + "var stage=document.getElementById('oxstage');\n"
                    + "var hint=document.getElementById('oxhint');\n"
                    + "if(!stage)return;\n"
                    + "var ext=(window.OX_EXT||'').toLowerCase();\n"
                    + "var url=window.OX_URL||'';\n"
                    + "function done(msg,isErr){\n"
                    + "  if(hint){hint.textContent=msg;if(isErr)hint.className='docnote'}\n"
                    + "}\n"
                    + "function fail(why){done('高保真渲染没成功，已保留服务端解析结果（'+why+'）',true)}\n"
                    + "function loadScript(src){return new Promise(function(res,rej){\n"
                    + "  var s=document.createElement('script');\n"
                    + "  s.src=src;s.async=true;\n"
                    + "  s.onload=function(){res()};\n"
                    + "  s.onerror=function(){rej(new Error('脚本加载失败'))};\n"
                    + "  document.head.appendChild(s);\n"
                    + "  setTimeout(function(){rej(new Error('脚本加载超时'))},6000);\n"
                    + "})}\n"
                    + "function fetchBuf(u){return new Promise(function(res,rej){\n"
                    + "  var x=new XMLHttpRequest();\n"
                    + "  x.open('GET',u,true);x.responseType='arraybuffer';\n"
                    + "  x.onload=function(){\n"
                    + "    if(x.status>=200&&x.status<400)res(x.response);\n"
                    + "    else rej(new Error('取文件失败 HTTP '+x.status));\n"
                    + "  };\n"
                    + "  x.onerror=function(){rej(new Error('取文件失败'))};\n"
                    + "  x.send();\n"
                    + "})}\n"
                    + "function swap(node){\n"
                    + "  while(stage.firstChild)stage.removeChild(stage.firstChild);\n"
                    + "  stage.appendChild(node);\n"
                    + "  done('已用高保真渲染器显示。');\n"
                    + "}\n"
                    + "if(ext==='docx'){\n"
                    // docx-preview 是 UMD 包，浏览器分支会去要全局的 JSZip：
                    // 它是外部依赖，不先加载 jszip.js 的话 docx-preview 加载完
                    // 也只会报 "JSZip is not defined"，表面看像"渲染器没就绪"。
                    + "  loadScript('/docview/jszip.js').then(function(){\n"
                    + "    return loadScript('/docview/docx-preview.js');\n"
                    + "  }).then(function(){\n"
                    + "    if(!window.docx)throw new Error('渲染器没就绪');\n"
                    + "    return fetchBuf(url);\n"
                    + "  }).then(function(buf){\n"
                    + "    var box=document.createElement('div');\n"
                    + "    box.className='ox-docx-hi';\n"
                    + "    return window.docx.renderAsync(buf,box,null,{\n"
                    + "      className:'docx',inWrapper:true,ignoreWidth:false,\n"
                    + "      breakPages:true,experimental:true\n"
                    + "    }).then(function(){return box});\n"
                    + "  }).then(swap).catch(function(e){fail(e.message||'未知原因')});\n"
                    + "}else if(ext==='xlsx'){\n"
                    + "  loadScript('/docview/xlsx.full.min.js').then(function(){\n"
                    + "    if(!window.XLSX)throw new Error('渲染器没就绪');\n"
                    + "    return fetchBuf(url);\n"
                    + "  }).then(function(buf){\n"
                    + "    var wb=window.XLSX.read(new Uint8Array(buf),{type:'array'});\n"
                    + "    var box=document.createElement('div');\n"
                    + "    box.className='ox-xlsx-hi';\n"
                    + "    wb.SheetNames.forEach(function(nm,i){\n"
                    + "      if(i>=10)return;\n"
                    + "      var ws=wb.Sheets[nm];\n"
                    + "      var h=document.createElement('div');\n"
                    + "      h.className='ox-sheet-h';h.textContent='工作表 · '+nm;\n"
                    + "      var wrap=document.createElement('div');\n"
                    + "      wrap.className='ox-grid-wrap';\n"
                    + "      wrap.innerHTML=window.XLSX.utils.sheet_to_html(ws,{editable:false});\n"
                    + "      var t=wrap.querySelector('table');\n"
                    + "      if(t)t.className='ox-grid';\n"
                    + "      box.appendChild(h);box.appendChild(wrap);\n"
                    + "    });\n"
                    + "    return box;\n"
                    + "  }).then(swap).catch(function(e){fail(e.message||'未知原因')});\n"
                    + "}else{\n"
                    + "  done('服务端解析结果。点右上角「下载」可用本机 Office 打开原始排版。');\n"
                    + "}\n"
                    + "})();\n";

    /**
     * 图片画廊页。
     *
     * 不只是把原图丢给浏览器 —— 那样一次只看得见一张，看完得退回列表再点下一张。
     * 这里把同目录的所有图片一起列进来，一张主图 + 一条缩略图带：
     * 左右箭头 / ←→ 键 / 触屏左右滑都能翻页，点主图全屏放大，
     * 再点一下退出放大。缩放用 CSS transform，不引任何外部库。
     *
     * 懒加载：缩略图用 loading='lazy'，主图切换时才设 src，
     * 一个塞满 4K 原图的目录打开也不会一次性拉几百 MB。
     */
    private String renderGallery(Request req, File cur) {
        File dir = cur.getParentFile();
        List<File> imgs = new ArrayList<>();
        if (dir != null) {
            File[] all = dir.listFiles();
            if (all != null) {
                for (File t : all) {
                    if (t.isFile() && isImage(extOf(t.getName()))) imgs.add(t);
                }
            }
        }
        // 按名字排序，保证「下一张」是稳定的顺序，不是文件系统的返回顺序
        imgs.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        int idx = imgs.indexOf(cur);
        if (idx < 0) { imgs.add(cur); idx = imgs.size() - 1; }

        StringBuilder sb = new StringBuilder();
        htmlHead(sb, cur.getName(), prefTheme);
        sb.append("<div class='wrap gallery'>\n");

        sb.append("<nav class='crumbs'><a href='/'>").append(svgIcon("home", "svgi-crumb")).append("</a>");
        if (dir != null) {
            sb.append("<span class='sep'>›</span><a href='/browse")
                    .append(encode(dir.getAbsolutePath())).append("'>")
                    .append(esc(dir.getName())).append("</a>");
        }
        sb.append("</nav>\n");

        sb.append("<div class='docbar'><span class='dotitle' id='g-title'>")
                .append(esc(cur.getName())).append("</span>")
                .append("<span class='docsize' id='g-pos'>")
                .append(idx + 1).append(" / ").append(imgs.size()).append("</span>");
        sb.append("<span class='docacts'>");
        sb.append("<a id='g-raw' href='").append(encode(cur.getAbsolutePath()))
                .append("' target='_blank'>原图</a>");
        sb.append("<a id='g-dl' href='").append(encode(cur.getAbsolutePath()))
                .append("' download>下载</a>");
        sb.append("</span></div>\n");

        // 主图区
        sb.append("<div class='gstage' id='gstage'>");
        sb.append("<img id='gimg' src='").append(encode(cur.getAbsolutePath()))
                .append("' alt='").append(esc(cur.getName())).append("'>");
        sb.append("<button class='gnav prev' id='gprev' aria-label='上一张'>‹</button>");
        sb.append("<button class='gnav next' id='gnext' aria-label='下一张'>›</button>");
        sb.append("</div>\n");

        // 缩略图带
        sb.append("<div class='gstrip'>");
        for (int i = 0; i < imgs.size(); i++) {
            File t = imgs.get(i);
            sb.append("<a class='gthumb").append(i == idx ? " cur" : "")
                    .append("' href='/view?f=").append(encode(t.getAbsolutePath()))
                    .append("' data-i='").append(i).append("'>")
                    .append("<img loading='lazy' src='").append(encode(t.getAbsolutePath()))
                    .append("' alt='").append(esc(t.getName())).append("'></a>");
        }
        sb.append("</div>\n");

        sb.append("</div>\n");

        // ---- JS ----
        sb.append("<script>\n");
        sb.append("const G=").append(galleryJson(imgs)).append(";\n");
        sb.append("let gi=").append(idx).append(";\n");
        sb.append(GALLERY_JS);
        sb.append("</script>\n");
        htmlFoot(sb);
        return sb.toString();
    }

    /** 图片清单（名字 + 路径）编成 JSON，供画廊 JS 翻页用 */
    private static String galleryJson(List<File> imgs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < imgs.size(); i++) {
            File t = imgs.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"n\":\"").append(jsonEsc(t.getName()))
                    .append("\",\"p\":\"").append(jsonEsc(t.getAbsolutePath())).append("\"}");
        }
        return sb.append(']').toString();
    }

    /**
     * 画廊脚本。
     *
     * 翻页有三种触发：两侧箭头、键盘 ←→、触屏左右滑。
     * 放大是 toggle：点一下主图进放大态，再点退出 —— 不用 pinch，
     * 是因为 pinch 在浏览器里会被页面缩放抢走，手势冲突太难受。
     */
    private static final String GALLERY_JS =
            "const img=document.getElementById('gimg');\n"
                    + "const stage=document.getElementById('gstage');\n"
                    + "const pos=document.getElementById('g-pos');\n"
                    + "const ttl=document.getElementById('g-title');\n"
                    + "const raw=document.getElementById('g-raw');\n"
                    + "const dl=document.getElementById('g-dl');\n"
                    + "/* Array.from 是 ES6，老 WebView 没有；用 Array.prototype.slice.call 替代 */\n"
                    + "const thumbs=Array.prototype.slice.call(document.querySelectorAll('.gthumb'));\n"
                    + "let zoomed=false;\n"
                    + "function show(i){\n"
                    + "  if(!G.length)return;\n"
                    + "  gi=(i+G.length)%G.length;\n"
                    + "  const it=G[gi];\n"
                    + "  zoomed=false;img.classList.remove('zoom');\n"
                    + "  img.src='/'+it.p.replace(/^\\/+/, '');\n"
                    + "  img.alt=it.n;ttl.textContent=it.n;\n"
                    + "  pos.textContent=(gi+1)+' / '+G.length;\n"
                    + "  const u='/'+it.p.replace(/^\\/+/, '');\n"
                    + "  raw.href=u;dl.href=u;\n"
                                + "  for(var k=0;k<thumbs.length;k++){/* classList.toggle 的第二参在老 WebView 里不生效，显式 add/remove */}\n"
            + "    if(k===gi)thumbs[k].classList.add('cur');else thumbs[k].classList.remove('cur');}}\n"
                    + "  const cur=thumbs[gi];\n"
                    + "  if(cur&&cur.scrollIntoView){cur.scrollIntoView({inline:'center',block:'nearest'});}\n"
                    + "  try{history.replaceState(null,'','/view?f='+encodeURIComponent(it.p));}catch(e){}\n"
                    + "}\n"
                    + "document.getElementById('gprev').onclick=function(e){e.stopPropagation();show(gi-1);};\n"
                    + "document.getElementById('gnext').onclick=function(e){e.stopPropagation();show(gi+1);};\n"
                    + "img.onclick=function(e){\n"
                    // 以点的位置为缩放原点：点哪儿放大哪儿，不是死板地放大中心。
                    // 用 offsetX/offsetY 相对图片本身算百分比，正文被 scale 撑出去也不会错位。
                    + "  zoomed=!zoomed;\n"
                    + "  if(zoomed){\n"
                    + "    const r=img.getBoundingClientRect();\n"
                    + "    const px=((e.clientX-r.left)/r.width*100);\n"
                    + "    const py=((e.clientY-r.top)/r.height*100);\n"
                    + "    img.style.transformOrigin=Math.max(0,Math.min(100,px))+'% '\n"
                    + "      +Math.max(0,Math.min(100,py))+'%';\n"
                    + "  }\n"
                    + "  img.classList.toggle('zoom',zoomed);\n"
                    + "};\n"
                    + "document.addEventListener('keydown',function(e){\n"
                    + "  if(e.key==='ArrowLeft')show(gi-1);\n"
                    + "  else if(e.key==='ArrowRight')show(gi+1);\n"
                    + "  else if(e.key==='Escape'&&zoomed){zoomed=false;img.classList.remove('zoom');}\n"
                    + "});\n"
                    + "let tx=0,ty=0;\n"
                    + "stage.addEventListener('touchstart',function(e){\n"
                    + "  if(e.touches.length!==1)return;\n"
                    + "  tx=e.touches[0].clientX;ty=e.touches[0].clientY;\n"
                    + "},{passive:true});\n"
                    + "stage.addEventListener('touchend',function(e){\n"
                    + "  if(zoomed||e.touches.length)return;\n"
                    + "  const t=e.changedTouches[0];if(!t)return;\n"
                    + "  const dx=t.clientX-tx,dy=t.clientY-ty;\n"
                    + "  if(Math.abs(dx)>50&&Math.abs(dx)>Math.abs(dy)*1.6){show(gi+(dx<0?1:-1));}\n"
                    + "},{passive:true});\n";

    private String errorPage(String msg) {
        StringBuilder sb = new StringBuilder();
        htmlHead(sb, "错误", prefTheme);
        sb.append("<div class='wrap'><h2>出错了</h2><p>").append(esc(msg))
                .append("</p><p><a href='/'>返回首页</a></p></div>");
        htmlFoot(sb);
        return sb.toString();
    }

    // ==================== 日间 / 夜间主题 ====================
    //
    // 整站样式表里的颜色都是写死的十六进制字面量（70 个不同的值）。与其把 CSS 改造成
    // CSS 变量、把 200 多处引用全部重写一遍（那种改动量下很容易漏掉某个卡片），
    // 不如在运行时把样式规则里的颜色按表替换一遍 —— 规则本身的结构不变，只是换了值。
    //
    // 脚本必须同步跑在 <head> 里：等到 <body> 渲染出来再改，用户会先看到一帧深色再闪成
    // 浅色。所以 htmlHead 把它内联进去，不进 /docview 走网络。
    //
    // localStorage 里存的是「用户显式选择」（'light' / 'dark' / 'ink'）；没存过就用
    // 服务端当前主题（默认 dark）。**不跟随系统 prefers-color-scheme**。
// ==================== 日间 / 夜间主题 ====================
//
// 整站样式表里的颜色都是写死的十六进制字面量（70 个不同的值）。与其把 CSS 改造成
// CSS 变量、把 200 多处引用全部重写一遍（那种改动量下很容易漏掉某个卡片），
// 不如在运行时把样式规则里的颜色按表替换一遍 —— 规则本身的结构不变，只是换了值。
//
// 脚本必须同步跑在 <head> 里：等到 <body> 渲染出来再改，用户会先看到一帧深色再闪成
// 浅色。所以 htmlHead 把它内联进去，不进 /docview 走网络。
//
// localStorage 里存的是「用户显式选择」（'light' / 'dark' / 'ink'）；没存过就用
// 服务端当前主题（默认 dark）。**不跟随系统 prefers-color-scheme** —— 这站点是给
// 局域网里各种设备看的，跟着系统走会让手机上点好的「日间」被系统夜间模式悄悄改回去。

    private static final String THEME_JS =
            // 服务端当前主题，由 htmlHeadRaw 在调用前用 .replace("__SRV_THEME__", ...) 填进去。
            //
            // 占位符**不能**和变量名同形。上一条注释里我说的「让 THEME_JS 保持编译期常量」
            // 是对的，但第一版把占位符写成 SERVER_THEME，而变量名也叫 SERVER_THEME ——
            // String.replace 是全局替换，于是 `var SERVER_THEME='SERVER_THEME'` 被替换成
            // `var light='light'`，变量名一起没了，current() 里的 `return SERVER_THEME`
            // 直接 ReferenceError，整个主题脚本在第一次调用就抛异常。
            // 现在占位符带双下划线，绝不会撞上变量名。
            "var SERVER_THEME='__SRV_THEME__';\n"
            +
            // 深色主题原色 → 日间主题对应色。日间值不是简单反转：底色翻成浅灰阶、
            // 文字翻成深灰阶、强调色保持色相只调明度，否则蓝的翻成黄的、绿的翻成紫的。
            "var LIGHT={"
                    + "\"#0e1116\":\"#f2f4f7\","
                    + "\"#0d1117\":\"#f6f8fa\","
                    + "\"#0e1116\":\"#f2f4f7\","
                    + "\"#141922\":\"#f7f9fb\","
                    + "\"#141a21\":\"#f7f9fb\","
                    + "\"#16202c\":\"#eef3fa\","
                    + "\"#16212e\":\"#eaf1fb\","
                    + "\"#171b22\":\"#ffffff\","
                    + "\"#1a2028\":\"#f6f8fa\","
                    + "\"#1a2432\":\"#e8f0fc\","
                    + "\"#1b222c\":\"#f0f2f5\","
                    + "\"#1b2430\":\"#e8ecf1\","
                    + "\"#1d232c\":\"#e9edf3\","
                    + "\"#1d2530\":\"#e3ecfa\","
                    + "\"#1d2c3d\":\"#dfeaf8\","
                    + "\"#11161d\":\"#fafbfc\","
                    + "\"#2a1f12\":\"#fff6e0\","
                    + "\"#2a1a1e\":\"#fde8ea\","
                    + "\"#132a1c\":\"#e7f6ec\","
                    + "\"#1f4a2e\":\"#a8dcb8\","
                    + "\"#2a2222\":\"#f3eaea\","
                    + "\"#3a3323\":\"#f0e6cc\","
                    + "\"#3a1f24\":\"#fbe3e5\","
                    + "\"#3a1d22\":\"#ffd9dd\","
                    + "\"#3d8cf5\":\"#2f80ed\","
                    + "\"#222a34\":\"#eceff3\","
                    + "\"#232a34\":\"#e3e7ec\","
                    + "\"#233a52\":\"#c8ddf7\","
                    + "\"#24303d\":\"#e1e6ec\","
                    + "\"#243040\":\"#e1e6ec\","
                    + "\"#25344a\":\"#dbe3ec\","
                    + "\"#2a323d\":\"#dbe0e7\","
                    + "\"#2b4a33\":\"#b7dcc3\","
                    + "\"#2f3a48\":\"#d3dae3\","
                    + "\"#2f5a8f\":\"#b9d3f0\","
                    + "\"#4a262c\":\"#e8a0a0\","
                    + "\"#7a5057\":\"#b98a8f\","
                    + "\"#e6e9ef\":\"#1b1f26\","
                    + "\"#e6edf5\":\"#1b1f26\","
                    + "\"#e8a0a0\":\"#a4232b\","
                    + "\"#e8c07d\":\"#8a6516\","
                    + "\"#e06c75\":\"#c0392b\","
                    + "\"#d8dee6\":\"#242a33\","
                    + "\"#d6b45a\":\"#8a6516\","
                    + "\"#cfd6df\":\"#2a313a\","
                    + "\"#c9d1d9\":\"#2a313a\","
                    + "\"#c8d1da\":\"#2b313b\","
                    + "\"#a7b0bd\":\"#57606a\","
                    + "\"#9aa6b2\":\"#5a626c\","
                    + "\"#9aa4b2\":\"#57606a\","
                    + "\"#8b949e\":\"#5c6570\","
                    + "\"#7d8590\":\"#5c6570\","
                    + "\"#6b7480\":\"#6e7681\","
                    + "\"#4d5a68\":\"#8d949d\","
                    + "\"#4a525c\":\"#96a0aa\","
                    + "\"#66799a\":\"#7b8aa3\","
                    + "\"#a08080\":\"#8c7070\","
                    + "\"#2a3340\":\"#d8e2ec\","
                    + "\"#5aa9ff\":\"#0b64c8\","
                    + "\"#8ec5ff\":\"#1565c0\","
                    + "\"#7ee2a8\":\"#0f7a3d\","
                    + "\"#7ee787\":\"#1a7f37\","
                    + "\"#56ccf2\":\"#0d7ea8\","
                    + "\"#ff8a94\":\"#c0392b\","
                    + "\"#ff9aa2\":\"#c0392b\","
                    + "\"#8b5cf6\":\"#6b3fd4\","
                    + "\"#2f80ed\":\"#2f80ed\","
                    + "\"#c0392b\":\"#c0392b\","
                    + "\"#d64a3b\":\"#d64a3b\","
                    + "\"#3b68a8\":\"#2f80ed\","
                    + "\"#1a1a1a\":\"#1a1a1a\"};\n"
            // DARK 是 LIGHT 的反查表（把浅色压回深色）。
            // 构造方式：遍历 LIGHT，对每个「深→浅」对反向记一条「浅→深」；
            // 同一浅色有多个来源时保留第一个，避免两个深色都指向 #2f80ed 时互相覆盖。
            + "var DARK={\n"
                    + "\"#f2f4f7\":\"#0e1116\",\"#f6f8fa\":\"#0d1117\",\"#f7f9fb\":\"#141922\",\"#eef3fa\":\"#16202c\",\n"
                    + "\"#eaf1fb\":\"#16212e\",\"#ffffff\":\"#171b22\",\"#e8f0fc\":\"#1a2432\",\"#f0f2f5\":\"#1b222c\",\n"
                    + "\"#e8ecf1\":\"#1b2430\",\"#e9edf3\":\"#1d232c\",\"#e3ecfa\":\"#1d2530\",\"#dfeaf8\":\"#1d2c3d\",\n"
                    + "\"#fafbfc\":\"#11161d\",\"#fff6e0\":\"#2a1f12\",\"#fde8ea\":\"#2a1a1e\",\"#e7f6ec\":\"#132a1c\",\n"
                    + "\"#a8dcb8\":\"#1f4a2e\",\"#f3eaea\":\"#2a2222\",\"#f0e6cc\":\"#3a3323\",\"#fbe3e5\":\"#3a1f24\",\n"
                    + "\"#ffd9dd\":\"#3a1d22\",\"#2f80ed\":\"#3d8cf5\",\"#eceff3\":\"#222a34\",\"#e3e7ec\":\"#232a34\",\n"
                    + "\"#c8ddf7\":\"#233a52\",\"#e1e6ec\":\"#24303d\",\"#dbe3ec\":\"#25344a\",\"#dbe0e7\":\"#2a323d\",\n"
                    + "\"#b7dcc3\":\"#2b4a33\",\"#d3dae3\":\"#2f3a48\",\"#b9d3f0\":\"#2f5a8f\",\"#e8a0a0\":\"#4a262c\",\n"
                    + "\"#b98a8f\":\"#7a5057\",\"#1b1f26\":\"#e6e9ef\",\"#a4232b\":\"#e8a0a0\",\"#8a6516\":\"#e8c07d\",\n"
                    + "\"#c0392b\":\"#e06c75\",\"#242a33\":\"#d8dee6\",\"#2a313a\":\"#cfd6df\",\"#2b313b\":\"#c8d1da\",\n"
                    + "\"#57606a\":\"#a7b0bd\",\"#5a626c\":\"#9aa6b2\",\"#5c6570\":\"#8b949e\",\"#6e7681\":\"#6b7480\",\n"
                    + "\"#8d949d\":\"#4d5a68\",\"#96a0aa\":\"#4a525c\",\"#7b8aa3\":\"#66799a\",\"#8c7070\":\"#a08080\",\n"
                    + "\"#d8e2ec\":\"#2a3340\",\"#0b64c8\":\"#5aa9ff\",\"#1565c0\":\"#8ec5ff\",\"#0f7a3d\":\"#7ee2a8\",\n"
                    + "\"#1a7f37\":\"#7ee787\",\"#0d7ea8\":\"#56ccf2\",\"#6b3fd4\":\"#8b5cf6\",\"#d64a3b\":\"#d64a3b\",\n"
                    + "\"#1a1a1a\":\"#1a1a1a\"\n"
            + "};\n"
            // INK 是水墨屏（电子墨水屏）用的表。
            // 墨水屏只有灰阶、刷新慢、残影重，所以这里做了两件事：
            //   1) 把所有彩色一律换成纯灰 —— 彩色在多数墨水屏上会被抖动成脏点；
            //   2) 主动压低对比度。墨水屏上纯黑配纯白最容易留残影，正文和底
            //      之间留出 2~3 档灰的余量，看着也更像纸。
            // 键沿用 LIGHT 的那 122 个深色值，所以 INK 可以直接当 remap 的目标表用。
            + "var INK={\n"
                    + "\"#0e1116\":\"#e8e8e8\",\"#0d1117\":\"#e8e8e8\",\"#141922\":\"#e8e8e8\"," + "\n"
                    + "\"#141a21\":\"#d6d6d6\",\"#16202c\":\"#d6d6d6\",\"#16212e\":\"#d6d6d6\"," + "\n"
                    + "\"#171b22\":\"#d6d6d6\",\"#1a2028\":\"#d6d6d6\",\"#1a2432\":\"#d6d6d6\"," + "\n"
                    + "\"#1b222c\":\"#d6d6d6\",\"#1b2430\":\"#d6d6d6\",\"#1d232c\":\"#d6d6d6\"," + "\n"
                    + "\"#1d2530\":\"#d6d6d6\",\"#1d2c3d\":\"#c6c6c6\",\"#11161d\":\"#e8e8e8\"," + "\n"
                    + "\"#2a1f12\":\"#d6d6d6\",\"#2a1a1e\":\"#d6d6d6\",\"#132a1c\":\"#d6d6d6\"," + "\n"
                    + "\"#1f4a2e\":\"#969696\",\"#2a2222\":\"#d6d6d6\",\"#3a3323\":\"#c6c6c6\"," + "\n"
                    + "\"#3a1f24\":\"#d6d6d6\",\"#3a1d22\":\"#d6d6d6\",\"#3d8cf5\":\"#4e4e4e\"," + "\n"
                    + "\"#222a34\":\"#c6c6c6\",\"#232a34\":\"#c6c6c6\",\"#233a52\":\"#c6c6c6\"," + "\n"
                    + "\"#24303d\":\"#c6c6c6\",\"#243040\":\"#c6c6c6\",\"#25344a\":\"#c6c6c6\"," + "\n"
                    + "\"#2a323d\":\"#c6c6c6\",\"#2b4a33\":\"#969696\",\"#2f3a48\":\"#969696\"," + "\n"
                    + "\"#2f5a8f\":\"#6e6e6e\",\"#4a262c\":\"#c6c6c6\",\"#7a5057\":\"#6e6e6e\"," + "\n"
                    + "\"#e6e9ef\":\"#232323\",\"#e6edf5\":\"#181818\",\"#e8a0a0\":\"#373737\"," + "\n"
                    + "\"#e8c07d\":\"#373737\",\"#e06c75\":\"#4e4e4e\",\"#d8dee6\":\"#232323\"," + "\n"
                    + "\"#d6b45a\":\"#373737\",\"#cfd6df\":\"#232323\",\"#c9d1d9\":\"#232323\"," + "\n"
                    + "\"#c8d1da\":\"#232323\",\"#a7b0bd\":\"#373737\",\"#9aa6b2\":\"#373737\"," + "\n"
                    + "\"#9aa4b2\":\"#373737\",\"#8b949e\":\"#4e4e4e\",\"#7d8590\":\"#4e4e4e\"," + "\n"
                    + "\"#6b7480\":\"#4e4e4e\",\"#4d5a68\":\"#6e6e6e\",\"#4a525c\":\"#6e6e6e\"," + "\n"
                    + "\"#66799a\":\"#4e4e4e\",\"#a08080\":\"#4e4e4e\",\"#2a3340\":\"#c6c6c6\"," + "\n"
                    + "\"#5aa9ff\":\"#373737\",\"#8ec5ff\":\"#373737\",\"#7ee2a8\":\"#232323\"," + "\n"
                    + "\"#7ee787\":\"#232323\",\"#56ccf2\":\"#373737\",\"#ff8a94\":\"#373737\"," + "\n"
                    + "\"#ff9aa2\":\"#373737\",\"#8b5cf6\":\"#4e4e4e\",\"#2f80ed\":\"#4e4e4e\"," + "\n"
                    + "\"#c0392b\":\"#6e6e6e\",\"#d64a3b\":\"#4e4e4e\",\"#3b68a8\":\"#6e6e6e\"," + "\n"
                    + "\"#1a1a1a\":\"#d6d6d6\",\"#f2f4f7\":\"#181818\",\"#f6f8fa\":\"#181818\"," + "\n"
                    + "\"#f7f9fb\":\"#181818\",\"#eef3fa\":\"#181818\",\"#eaf1fb\":\"#181818\"," + "\n"
                    + "\"#ffffff\":\"#181818\",\"#e8f0fc\":\"#181818\",\"#f0f2f5\":\"#181818\"," + "\n"
                    + "\"#e8ecf1\":\"#181818\",\"#e9edf3\":\"#181818\",\"#e3ecfa\":\"#181818\"," + "\n"
                    + "\"#dfeaf8\":\"#232323\",\"#fafbfc\":\"#181818\",\"#fff6e0\":\"#181818\"," + "\n"
                    + "\"#fde8ea\":\"#181818\",\"#e7f6ec\":\"#181818\",\"#a8dcb8\":\"#232323\"," + "\n"
                    + "\"#f3eaea\":\"#181818\",\"#f0e6cc\":\"#232323\",\"#fbe3e5\":\"#232323\"," + "\n"
                    + "\"#ffd9dd\":\"#232323\",\"#eceff3\":\"#181818\",\"#e3e7ec\":\"#232323\"," + "\n"
                    + "\"#c8ddf7\":\"#232323\",\"#e1e6ec\":\"#232323\",\"#dbe3ec\":\"#232323\"," + "\n"
                    + "\"#dbe0e7\":\"#232323\",\"#b7dcc3\":\"#232323\",\"#d3dae3\":\"#232323\"," + "\n"
                    + "\"#b9d3f0\":\"#232323\",\"#b98a8f\":\"#4e4e4e\",\"#1b1f26\":\"#d6d6d6\"," + "\n"
                    + "\"#a4232b\":\"#969696\",\"#8a6516\":\"#4e4e4e\",\"#242a33\":\"#c6c6c6\"," + "\n"
                    + "\"#2a313a\":\"#c6c6c6\",\"#2b313b\":\"#c6c6c6\",\"#57606a\":\"#6e6e6e\"," + "\n"
                    + "\"#5a626c\":\"#6e6e6e\",\"#5c6570\":\"#6e6e6e\",\"#6e7681\":\"#4e4e4e\"," + "\n"
                    + "\"#8d949d\":\"#4e4e4e\",\"#96a0aa\":\"#373737\",\"#7b8aa3\":\"#4e4e4e\"," + "\n"
                    + "\"#8c7070\":\"#4e4e4e\",\"#d8e2ec\":\"#232323\",\"#0b64c8\":\"#6e6e6e\"," + "\n"
                    + "\"#1565c0\":\"#6e6e6e\",\"#0f7a3d\":\"#6e6e6e\",\"#1a7f37\":\"#4e4e4e\"," + "\n"
                    + "\"#0d7ea8\":\"#4e4e4e\",\"#6b3fd4\":\"#6e6e6e\","
            + "};\n"
            + "(function(){\n"
                        + "  // 老浏览器（Android 4.x WebView、IE9 之前）里 document.styleSheets 是可用的，\n"
            + "  // 但跨域样式表与 @import 的 cssRules 会抛 SecurityError，整段收集都包在\n"
            + "  // try 里。一条规则都收不到时 apply 会退化成「只切底色不换细色」，页面\n"
            + "  // 照常可用 —— 这就是老浏览器的保底策略：能看能点，只是配色只变一层。\n"
            + "  var RULES=[];\n"
            + "  function walk(list){for(var i=0;i<list.length;i++){var r=list[i];\n"
            + "    if(r.type===1&&r.selectorText&&r.style){RULES.push(r);}\n"
            + "    if(r.cssRules)walk(r.cssRules);}}\n"
            + "  try{for(var s=0;s<document.styleSheets.length;s++){\n"
            + "    try{walk(document.styleSheets[s].cssRules)}catch(e){}\n"
            + "  }}catch(e){}\n"
            // 这里必须先把 rgb() 归一成 #rrggbb 再查表。
            //
            // 原因：Chrome/WebView 把 CSSStyleRule.style.cssText 序列化时会把十六进制
            // 颜色改写成 `rgb(14, 17, 22)` 这种形式（实测 kxml 那个 WebView 就是这样，
            // 见 shots/jp6 的取证 —— `.card` 的 cssText 是 `background: rgb(23, 27, 34);
            // padding: 4px`）。只匹配 /#[0-9a-f]{6}/ 的话一条都命中不了，n 恒为 0，
            // 于是 generate 出的 <style id='theme-light'> 是空串，日间模式完全没用。
            // 这个 bug 在桌面浏览器上不会暴露（那边的 cssText 保留原写法）。
            + "  function toHex(css){\n"
            + "    return css.replace(/rgba?\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*(?:,\\s*[\\d.]+\\s*)?\\)/g,\n"
            + "      function(m,r,g,b){\n"
            + "        var h=function(v){var s=(+v).toString(16);return s.length<2?'0'+s:s;};\n"
            + "        return '#'+h(r)+h(g)+h(b);});}\n"
            + "  function lighten(css){\n"
            + "    if(!css)return css;\n"
            + "    if(css.indexOf('#')<0&&css.indexOf('rgb')<0)return css;\n"
            + "    return toHex(css).replace(/#[0-9a-fA-F]{6}/g,function(m){\n"
            + "      var v=LIGHT[m.toLowerCase()];return v?v:m;});}\n"
            // 通用版：按给定的映射表换色。lighten 是它的特例（表固定为 LIGHT）。
            + "  function remap(css,tbl){\n"
            + "    if(!css)return css;\n"
            + "    if(css.indexOf('#')<0&&css.indexOf('rgb')<0)return css;\n"
            + "    return toHex(css).replace(/#[0-9a-fA-F]{6}/g,function(m){\n"
            + "      var v=tbl[m.toLowerCase()];return v?v:m;});}\n"
            + "  function apply(mode){\n"
            + "    var i,n=0,attr=[],val=[];\n"
            // apply('dark') 必须真的把页面还原成深色。
            //
            // 第一版只做「light 时生成覆盖表 / dark 时清空覆盖表」，在浏览器里表现为：
            // 第一次点变白（对），第二次点文案变回「日间」但页面还是白的（错）。
            // 原因是清空 <style id='theme-light'> 之后，那条 <style> 元素本身还挂在
            // document 里，某些 WebView 不会立刻丢掉它的规则；而且更根本的是 ——
            // 「深色」不该依赖「什么都没有」，它应当是被显式写出来的一份样式。
            // 所以两个方向都生成覆盖表，只是查出的是不同的目标色：
            //   light → 用 LIGHT 表（深→浅）
            //   dark  → 用 DARK 表（浅→深，是 LIGHT 的反查），把白底压回 #0e1116。
            //   ink   → 用 INK 表（深→墨阶），底色抬到 #e8e8e8、文字压到 #4e4e4e，
            //           中间只走灰阶。注意方向仍是「深→浅」，和 LIGHT 同一朝向，
            //           所以 remap 拿它当目标表用就行，不需要额外分支。
            + "    var tbl = (mode==='light') ? LIGHT : (mode==='ink' ? INK : DARK);\n"
            // ★ 内联底色必须每次都写回去，不能只当兜底。
            //
            // 服务端为了消掉刷新白闪，已经在 <html> 和 <body> 的 style 属性里
            // 直写了 background:<pageBg>（见 htmlHeadRaw）。内联样式的优先级
            // 高于任何 <style> 规则，所以只改 body.className 是压不过它的 ——
            // 点「日间/夜间/水墨」时 class 变了、CSS 里的 body.light/.ink 也命中
            // 了，可背景那一格仍被内联值钉住，表现就是「背景不随日夜切换变」。
            // 这里在每次 apply 时把内联底色一并改成目标色，class 管卡片/文字，
            // 内联只管最外层那一层底，两边对齐。
            + "    var bg=(mode==='light')?'#f2f4f7':(mode==='ink'?'#e8e8e8':'#0e1116');\n"
            + "    var fg=(mode==='light')?'#1b1f26':(mode==='ink'?'#3a3a3a':'#e6e9ef');\n"
            + "    var sc=(mode==='dark')?'dark':'light';\n"
            + "    try{\n"
            + "      var de=document.documentElement;\n"
            + "      if(de&&de.style){de.style.backgroundColor=bg;de.style.colorScheme=sc;}\n"
            // body 在 <head> 里跑时是 null，所以 applyToBody 之外还要在
            // boot() 那次补一遍 —— 见下面的 boot。
            + "      var bo=document.body;\n"
            + "      if(bo&&bo.style){bo.style.backgroundColor=bg;}\n"
            + "    }catch(e){}\n"
            + "    // RULES 为空 = CSSOM 没读出来（老浏览器、跨域样式表、某些壳的沙箱）。\n"
            + "    // 这时连文字色也一起内联出来，让「日夜切换」至少能工作一层 ——\n"
            + "    // 卡片边线之类的细节保持原样，总比整站没反应好。\n"
            + "    try{if(!RULES.length){\n"
            + "      if(de&&de.style){de.style.color=fg;}\n"
            + "      if(bo&&bo.style){bo.style.color=fg;}\n"
            + "    }}catch(e){}\n"
            + "    for(i=0;i<RULES.length;i++){var st=RULES[i].style;\n"
            + "      var css=remap(st.cssText,tbl);\n"
            + "      if(css===st.cssText)continue;\n"
            + "      attr[n]=RULES[i].selectorText;val[n]=css;n++;}\n"
            // 老浏览器那条路会把「只有 html,body 换了色」的残缺表塞进
            // #theme-light，而那条表的优先级比服务端预置表还高（都是 <style>，
            // 它插在最后），于是卡片、按钮这些又被打回深色 —— 用户看到的就是
            // 「切回日间只变了背景」。所以这条路整个丢掉，直接交给预置表。
            //
            // 预置表一定画得全：它是服务端按同一套配色表直出的，不依赖
            // cssRules 能不能读到、也不依赖 rgb() 序列化格式。
            + "    var el=document.getElementById('theme-light');\n"
            + "    if(el&&el.parentNode)el.parentNode.removeChild(el);\n"
            // 服务端预置的两份表（日间 #theme-pre / 水墨 #theme-pre-ink）
            // 现在靠 body 的 class 决定生效，不再靠「谁在文档里靠后」。
            // 上一版把两份都塞进同一个 <style>，从水墨切回日间时只有 html/body
            // 变了、卡片仍是灰的 —— 就是这里没分家造成的。
            //
            // 注意 THEME_JS 是在 <head> 里同步跑的，那时 <body> 还没解析出来，
            // document.body 是 null。所以这里必须**同时**给 <html> 打标记：
            // data-theme 记的是「想要的模式」，body 的 class 才是实际生效的开关。
            // 页面加载完成后 applyToBody() 会把两者对齐 —— 缺了这一步，
            // 首次打开页面时配色要等到点一下按钮或刷新才生效。
            + "    applyToBody(mode);\n"
            + "    document.documentElement.setAttribute('data-theme',mode);\n"
            + "    var cs=document.querySelector('meta[name=theme-color]');\n"
            + "    if(cs)cs.setAttribute('content',mode==='light'?'#f2f4f7':(mode==='ink'?'#e8e8e8':'#0e1116'));\n"
            + "    var b=document.getElementById('btnTheme');\n"
            // 文案语义：按钮上写的是「点它能切到什么」。
            // 现在显示「日间」表示当前是深色、点一下变日间。
            // 注意 THEME_JS 跑在 <head> 里，那时 <body> 还不存在，
            // getElementById('btnTheme') 必然是 null —— 所以这里只是顺手更新，
            // 真正让按钮文案正确的是 DOMContentLoaded 里的那次 apply(current())。
            // 水墨屏是单独一颗按钮（btnInk），它只在墨屏态点亮，其余时候是暗的。
            + "    if(b){b.textContent=mode==='light'?'夜间':'日间';\n"
            + "      b.title=mode==='light'?'切换到夜间':'切换到日间';}\n"
            + "    var ib=document.getElementById('btnInk');\n"
            + "    if(ib){var on=(mode==='ink');\n"
            + "      ib.className=on?'themebtn ink on':'themebtn ink';\n"
            + "      ib.textContent='水墨';\n"
            + "      ib.title=on?'当前就是水墨屏':'切换到水墨屏';}\n"
            + "  }\n"
            + "  function saved(){try{return localStorage.getItem('usbnas-theme')}catch(e){return null}}\n"
            // 把模式写进 <body> 的 class —— 这是 CSS 唯一认的开关。
            //
            // 单独拎出来是因为 apply() 会在 <head> 里被调，那时 document.body
            // 还是 null。以前直接写 `(document.body||document.documentElement).className`，
            // 结果是首屏的 class 落在了 <html> 上，body 保留服务端写的旧值，
            // 于是「首次打开是错的，点一下或刷新才对」。
            //
            // body 不在时把模式记在 <html data-theme> 上，等 DOM 好了再补 ——
            // applyToBody() 就是那个补的动作。
            + "  function applyToBody(mode){\n"
            + "    var b=document.body;\n"
            + "    if(!b){document.documentElement.setAttribute('data-theme',mode);return false;}\n"
            + "    try{b.className=mode;}catch(e){try{b.setAttribute('class',mode);}catch(e2){}}\n"
            // 内联底色跟 class 一起落到 body 上。<head> 里那次 apply 时 body 还不存在，
            // 那时只有 <html> 的内联底色生效（首帧就不会白）；到了 boot() 再补 body。
            + "    try{\n"
            + "      var bg=(mode==='light')?'#f2f4f7':(mode==='ink'?'#e8e8e8':'#0e1116');\n"
            + "      if(b.style){b.style.backgroundColor=bg;}\n"
            + "    }catch(e){}\n"
            + "    return true;}\n"
            // 只认 localStorage 里存下的选择，**不读系统 prefers-color-scheme**。
            // 原因：这个站点是给局域网里各种设备看的，跟着系统深浅色走会让人
            // 在手机上点好的「日间」被系统夜间模式悄悄改回去，而且同一台手机
            // 的微信/Via 内核对 media query 的实现也不一致。默认就是 dark。
            + "  function current(){\n"
            + "    var s=saved();if(s==='light'||s==='dark'||s==='ink')return s;\n"
            + "    return SERVER_THEME;}\n"
            // URL 上的 ?theme=light / ?theme=dark / ?theme=ink 优先，并写进 localStorage。
            // 这是无点击的兜底入口：按钮万一被布局裁掉，用户改地址栏也能换肤；
            // 分享出去的链接也能直接带上想要的配色。
            + "  (function(){\n"
            + "    var m=/[?&]theme=(light|dark|ink)/.exec(location.search||'');\n"
            + "    if(m){try{localStorage.setItem('usbnas-theme',m[1])}catch(e){}}\n"
            + "  })();\n"
            + "  apply(current());\n"
            // 这里的 dropPre 只做一件事：把 remap 那份 #theme-light 清掉，
            // 让服务端预置表独占生效。服务端那份一定能画对，而 remap 在这台
            // ROM 上会因为 rgb() 序列化差异漏掉一部分规则 —— 留着就会「只变一半」。
            + "  function dropPre(){\n"
            + "    var hi=document.getElementById('theme-light');\n"
            + "    if(hi&&hi.parentNode){hi.parentNode.removeChild(hi);}\n"
            + "  }\n"
            + "  try{dropPre();}catch(e){}\n"
            // 按钮在 <body> 里，上面那次 apply 时它还不存在，文案没被更新。
            // 等 DOM 好了再刷一遍文案（不动颜色，只补按钮上的字）。
            + "  function syncBtn(){\n"
            + "    var b=document.getElementById('btnTheme');if(!b)return;\n"
            + "    var m=document.body.className||current();\n"
            + "    b.textContent=m==='light'?'夜间':'日间';\n"
            + "    b.title=m==='light'?'切换到夜间':'切换到日间';\n"
            + "    var ib=document.getElementById('btnInk');\n"
            + "    if(ib){var on=(m==='ink');\n"
            + "      ib.className=on?'themebtn ink on':'themebtn ink';\n"
            + "      ib.textContent='水墨';\n"
            + "      ib.title=on?'当前就是水墨屏':'切换到水墨屏';}}\n"
            // 登录页没有按钮，但要跟着首页的配色走：只读 localStorage，自己不切换。
            // 代价是它只在页面加载时同步一次 —— 可接受：登录页本就是过渡页。
            + "  window.USBNAS_THEME_FOLLOW=function(){\n"
            + "    apply(current());\n"
            + "  };\n"
            // DOM 就绪后补一次：把那两件在 <head> 里做不到的事做完 ——
            //   1. 把模式写到 <body> 的 class 上（那时 body 还不存在）
            //   2. 更新按钮文案
            // 这两件合成一次 apply + syncBtn，顺序不能反：syncBtn 读 body 的 class。
            + "  function boot(){\n"
            + "    apply(current());syncBtn();}\n"
            + "  if(document.readyState==='loading'){\n"
            + "    document.addEventListener('DOMContentLoaded',boot);\n"
            + "  }else{boot();}\n"
            + "  window.USBNAS_THEME={\n"
            + "    mode:current,\n"
            // 日夜按钮：只在 light / dark 之间翻。当前是墨屏时点了回夜间，
            // 不记忆成「上一次的日夜」—— 那会让「墨屏→点一下→又回墨屏」这种
            // 循环变得莫名其妙。
            + "    toggle:function(){\n"
            + "      var c=current(),m=(c==='light')?'dark':'light';\n"
            + "      try{localStorage.setItem('usbnas-theme',m)}catch(e){}\n"
            + "      apply(m);syncBtn();},\n"
            // 水墨按钮是纯开关（不循环）：在墨屏里点一下就是关掉回夜间，
            // 在其它模式下点就是打开墨屏。关掉之后按钮文案变「已关闭」，
            // 给一个明确的「这一下生效了」的回执 —— 墨屏底色变化很小，
            // 没有回执用户会以为按钮坏了，从而反复点。
            + "    ink:function(){\n"
            + "      var m=(current()==='ink')?'dark':'ink';\n"
            + "      try{localStorage.setItem('usbnas-theme',m)}catch(e){}\n"
            + "      apply(m);\n"
            + "      var ib=document.getElementById('btnInk');\n"
            + "      if(ib&&m!=='ink'){ib.textContent='已关闭';ib.title='水墨屏已关闭';}\n"
            + "      else if(ib){ib.textContent='水墨';ib.title='当前就是水墨屏';}},\n"
            + "    apply:apply};\n"
            + "})();\n";
    // ==================== HTML 骨架 / CSS ====================

    private void htmlHead(StringBuilder sb, String pageTitle, String theme) {
        // 子页面（目录 / 播放页）用「页面名 · 站名」，站名可在 App 里改
        htmlHeadRaw(sb, pageTitle + " · " + cfg.title, theme);
    }

    /** 完整指定 &lt;title&gt;，不再拼站名（首页用） */
    private void htmlHeadRaw(StringBuilder sb, String fullTitle, String prefTheme) {
        // 刷新时那一下白闪，是浏览器在「还没解析到任何 CSS」的空档里自己画的底。
        // WebView / Via 这类壳的白底来自三处，缺一处都会闪：
        //   1) <html> 标签上就得带 data-theme，不能等 THEME_JS 跑完再补
        //      —— 脚本在 <style> 之后执行，那一瞬间 html/body 都还没有底色；
        //   2) color-scheme 要提前声明，否则浏览器自绘的滚动条/画布是白的；
        //   3) theme-color 得跟着当前主题走，别写死深色。
        // 所以这里是「先定主题字符串，再拼 <html>」，而不是先拼 <html> 再让脚本改。
        String th = (prefTheme == null || prefTheme.isEmpty()) ? "dark" : prefTheme;
        String pageBg = "light".equals(th) ? "#f2f4f7"
                : ("ink".equals(th) ? "#e8e8e8" : "#0e1116");
        String scheme = "dark".equals(th) ? "dark" : "light";
        sb.append("<!doctype html><html lang='zh-CN' data-theme='").append(th)
          .append("' style='background:").append(pageBg)
          .append(";color-scheme:").append(scheme).append("'><head>");
        sb.append("<meta charset='utf-8'>");
        sb.append("<meta name='viewport' content='width=device-width,initial-scale=1'>");
        // 地址栏 / 状态栏配色跟着主题走，首帧就是对的，不等脚本
        sb.append("<meta name='theme-color' content='").append(pageBg).append("'>");
        sb.append("<meta name='color-scheme' content='").append(scheme).append("'>");
        sb.append("<style>html,body{background:").append(pageBg).append("}</style>");
        sb.append("<title>").append(esc(fullTitle)).append("</title>");
        sb.append("<style>").append(CSS).append("</style>");
        // 服务端直出日间/水墨屏的覆盖样式。两段分开写、各带各的选择器前缀，
        // 换成 class 分派之后就不再靠「谁在文档里靠后」决定胜负了。
        //
        // 两份都吐，靠 body 的 class 决定谁生效。
        //
        // 上一版只吐当前模式那一份，而且都用 `html,body` / `.card` 这种无前缀
        // 选择器 —— 从水墨切回日间时，两份表会同时存在、互相打架，结果是
        // 「只有底色变了，卡片还是灰的」。改成 `body.light .card` /
        // `body.ink .card` 之后，两份表共处一页也各管各的，切换只是换 class。
        sb.append("<style id='theme-pre'>").append(LIGHT_CSS).append("</style>");
        sb.append("<style id='theme-pre-ink'>").append(INK_CSS).append("</style>");
        // 主题脚本必须在 <style> 之后（要遍历样式表）、在 <body> 之前（否则会闪一帧深色）。
        // 内联而不是走 /docview：多一次网络往返就多一帧闪烁。
        //
        // __SRV_THEME__ 是 THEME_JS 里的占位符，填的是服务端认为的当前主题。
        // 脚本读 localStorage 找不到已存选择时就用它当默认值 —— 网页端**不跟系统
        // 深浅色走**，避免手机上点好的日间被系统夜间模式悄悄改回去。
        String sj = THEME_JS.replace("__SRV_THEME__", th);
        sb.append("<script>\n").append(sj).append("</script>\n");
        // 服务端就把 class 挂上，脚本没跑起来时页面也是对的。
        // 'light' / 'ink' 这两个 class 由上面两段 CSS 用 body.light / body.ink
        // 前缀认领，跟 <html data-theme> 无关 —— data-theme 只是给 CSS 里
        // 那几条 html[data-theme=...] 用的附加标记。
        // body 也带内联底色：万一 localStorage 里存的是另一个主题，
        // THEME_JS 会把 class 改成对的那个，内联底色由 color-scheme 兜住，
        // 至少不会是「先白一帧再切」。
        sb.append("</head><body class='").append(th)
          .append("' style='background:").append(pageBg).append("'>");
    }

    /** 右上角的主题开关。放在 header 和页脚两处，长页面滚到底也能切。 */
    private String themeButton(String extraClass) {
        // onclick 里的兜底分支不是装饰：某些 WebView 壳会在页面自己的脚本之前
        // 拦截点击、或者在 body 末尾重写 DOM，导致 USBNAS_THEME 拿不到。
        // 那时就走 URL 参数那条确定能用的路 —— 换页加载，主题由 localStorage 接住。
        return "<button type='button' class='themebtn" + extraClass + "' id='btnTheme'"
                + " onclick=\"if(window.USBNAS_THEME){USBNAS_THEME.toggle();return;}"
                + "var m=(document.body.className==='light')?'dark':'light';"
                + "location.replace(location.pathname+'?theme='+m)\""
                + " title='切换到日间'>日间</button>";
    }

    /**
     * 水墨屏开关。单独一颗，不并进上面的日夜按钮 ——
     * 日夜是「同一套版式的两种明暗」，墨屏是另一套灰阶取向的打底，
     * 混在一个按钮里循环会让「我现在到底在哪个模式」变得难猜。
     *
     * 已经在水墨屏时，再点它是回夜间（而不是什么都不做），否则用户被困在里面
     * 只能去点日夜按钮，路径不直观。点击后按钮自己会显示「已关闭」，
     * 让人知道这一下确实生效了 —— 墨屏底色变化本身很小，不给反馈像是没反应。
     */
    private String inkButton(String extraClass) {
        return "<button type='button' class='themebtn ink" + extraClass + "' id='btnInk'"
                + " onclick=\"if(window.USBNAS_THEME){USBNAS_THEME.ink();return;}"
                + "var m=(document.body.className==='ink')?'dark':'ink';"
                + "location.replace(location.pathname+'?theme='+m)\""
                + " title='切换到水墨屏'>水墨</button>";
    }

    private void htmlFoot(StringBuilder sb) {
        sb.append("</body></html>");
    }

    /**
     * App 版本号 —— 直接引用 {@code MainActivity.APP_VER}，不再各写一份。
     *
     * 这个常量有两个用途：一是在 {@code /_status} 里告诉客户端「服务器是什么版本」，
     * 二是给 WebDAV / 网页静态资源做缓存标记。原来这里是 `20261122` 这样的日期串，
     * 与 App 自己的版本各写一份，发版时必然对不上 —— 自动更新要靠这个号判断新旧，
     * 两处不一致会让「显示 1.0.0 却永远提示有新版」。所以改成单一真源。
     */
    private static final String APP_VER = MainActivity.APP_VER;

    /**
     * 服务端直出的日间覆盖表。
     *
     * 为什么要有一份 CSS，明明已经有 THEME_JS 的 remap 了？
     * 因为 remap 那条路要读 document.styleSheets 的 cssRules —— 老 WebView、
     * 某些套壳浏览器会把这条口子堵死（SecurityError 或干脆不支持），
     * 于是「切日间」在那些机器上完全没反应，用户只看到网址多了个 ?theme=light、
     * 页面纹丝不动。这份表不依赖任何脚本，服务端判定是日间就直接塞进 <head>，
     * 老浏览器也一定能变浅。现代浏览器上 THEME_JS 会把它 display:none 掉。
     *
     * 键值对必须和 LIGHT 映射表保持同一套颜色，否则两者切换会看到明显色差。
     */
    private static final String LIGHT_CSS =
            "body.light{background:#f2f4f7;color:#1b1f26;color-scheme:light}"
                    + "body.light .wrap{color:#1b1f26}"
                    // 卡片 / 面板 / 输入框这些有底色的块，统一提亮到接近纯白
                    + "body.light .root,body.light .crumbs,body.light .card,"
                    + "body.light .item,body.light .row,body.light li,body.light .tr,"
                    + "body.light .stat,body.light .panel,body.light .box,"
                    + "body.light .upl,body.light .login,body.light .docbar,"
                    + "body.light .gstrip,body.light .batchbar,body.light .search input,"
                    + "body.light header,body.light .footer,body.light footer,"
                    + "body.light table,body.light td,body.light th"
                    + "{background:#ffffff;border-color:#dbe0e7;color:#1b1f26}"
                    // PDF 兜底块与下载按钮：浅色主题下反色，墨色主题下压成灰阶
                    + "body.light .pdfnil{background:#ffffff;color:#5a6472}"
                    + "body.light .pdfnil b{color:#1b1f26}"
                    + "body.light .pdfacts a{background:#1b6fe0;color:#fff}"
                    + "body.light .pdfacts a:hover{background:#1559b8}"
                    // 高亮态 / 悬停态用浅灰蓝，别和纯白卡片混在一起
                    + "body.light .root:hover,body.light .row:hover,body.light li:hover,"
                    + "body.light .item:hover,body.light .tr:hover"
                    + "{background:#f0f2f5}"
                    // 次要文字：原来那批灰调太浅，浅底上几乎看不见，统一压深
                    + "body.light .sub,body.light .rp,body.light .stats,body.light .cnt,"
                    + "body.light .count,body.light .hint,body.light .uplhint,"
                    + "body.light .nmcur,body.light .nmpath,body.light .lf-s,"
                    + "body.light .lf-msg,body.light .docsize,body.light .gcount,"
                    + "body.light .muted,body.light small"
                    + "{color:#57606a}"
                    + "body.light .stats b,body.light .rn,body.light .nmrow,"
                    + "body.light .nmtx{color:#1b1f26}"
                    // 链接与强调色：深底上的亮蓝/亮绿在浅底上对比度不够，换成深一档
                    + "body.light a,body.light .crumbs a,body.light .lo,body.light .nmcr"
                    + "{color:#0b64c8}"
                    // 「退出/登录」小胶囊（.lo）底色是写死的 #1b222c，
                    // 上面那条只换了字色，胶囊底还是黑的 —— 切日间后看着像
                    // 一个锁死的深色块。这里连底带边一起换。
                    + "body.light .lo{background:#f0f2f5;color:#0b64c8;"
                    + "border:1px solid #d3dae3}"
                    + "body.light .lo:hover{background:#e3edfa;border-color:#a8c8ea}"
                    // 管理入口在日间是「描边胶囊」，底要和页面同色才不显重
                    + "body.light .lo.adm{background:#fafbfc;color:#57606a;"
                    + "border:1px solid #d3dae3}"
                    + "body.light .lo.adm:hover{background:#eef3fb;color:#0b64c8;"
                    + "border-color:#a8c8ea}"
                    + "body.light .okbar{background:#e7f6ec;color:#1a7f37;"
                    + "border-color:#b7dcc3}"
                    + "body.light .errbar,body.light .err{background:#fde8ea;"
                    + "color:#c0392b;border-color:#e8a0a0}"
                    + "body.light input,body.light textarea,body.light select"
                    + "{background:#fafbfc;color:#1b1f26;border-color:#d3dae3}"
                    + "body.light button,body.light .themebtn,body.light .rowact,"
                    + "body.light .delone{background:#f0f2f5;color:#1b1f26;"
                    + "border-color:#d3dae3}"
                    + "body.light button:hover,body.light .themebtn:hover,"
                    + "body.light .rowact:hover{background:#e8ecf1}"
                    + "body.light .themebtn{background:#f0f2f5;color:#57606a;"
                    + "border-color:#d3dae3}"
                    + "body.light .delf,body.light .delone{color:#c0392b}"
                    // ---- SVG 图标的日间配色 ----
                    // 深色那套是 #8b96a5 类型 / #8ec5ff 动作 / #5aa9ff 强调，
                    // 放到 #f2f4f7 底上会糊成一片浅灰看不见，日间必须加深。
                    + "body.light .svgi-type{color:#5b6673}"
                    + "body.light .nm:hover .svgi-type{color:#0b64c8}"
                    + "body.light .svgi-act{color:#0b64c8}"
                    + "body.light .svgi-crumb{color:#5b6673}"
                    + "body.light .crumbs a:hover .svgi-crumb{color:#0b64c8}"
                    + "body.light .svgi-root{color:#5b6673}"
                    + "body.light .svgi-cov{color:#0b64c8}"
                    + "body.light .acts a{background:#f0f2f5;color:#0b64c8}"
                    + "body.light .acts a:hover{background:#e3edfa}"
                    + "body.light .rowact{background:#f0f2f5;color:#0b64c8}"
                    + "body.light .rowact:hover{background:#e3edfa;color:#084a94}"
                    + "body.light .rowact.mv:hover{background:#e6f4ea;color:#1a7f37}"
                    + "body.light .rowact.rn:hover{background:#fdf3d8;color:#8a6100}"
                    // ---- 身份栏（用户名 + 等级徽章）----
                    // 基础表里 .un 是 #e6e9ef 白字、.ul 是 #2a323d 深描边加 #9aa4b2 字，
                    // 搬到 #f2f4f7 底上会变成一块深色贴纸，必须整族重配。
                    + "body.light .un{color:#1b1f26}"
                    + "body.light .ul{background:#f0f2f5;border-color:#d3dae3;color:#57606a}"
                    + "body.light .ul0{color:#57606a;border-color:#d3dae3}"
                    + "body.light .ul1{color:#0b64c8;background:#e8f0fb;border-color:#a8c8ea}"
                    + "body.light .ul2{color:#1a7f37;background:#e6f4ea;border-color:#a8dcb8}"
                    + "body.light hr,body.light td,body.light th{border-color:#e1e6ec}"
                    // ---- 批量选择条（.batchbar 这一族）----
                    // 顶部那段通用规则只清掉了 .batchbar 自己的底和字，
                    // 里面这些子元素还是写死的深色值： .ckall 是 #a7b0bd、
                    // .seln 是 #7d8590、.ghost 是 #16212e 底 + #8ec5ff 字、
                    // .danger:disabled 是 #2a1a1e 底 + #7a5057 字。
                    // 结果是切到日间后，底部那条「下载所选 / 删除所选」还是黑的。
                    + "body.light .batchbar{background:rgba(255,255,255,.97);"
                    + "border-top-color:#dbe0e7}"
                    + "body.light .batchbar .ckall{color:#57606a}"
                    + "body.light .batchbar .seln{color:#57606a}"
                    + "body.light .batchbar .ghost{background:#f0f5fc;color:#0b64c8;"
                    + "border-color:#a8c8ea}"
                    + "body.light .batchbar .ghost:hover:not(:disabled){background:#e3edfa}"
                    + "body.light .batchbar .ghost:disabled{background:#f6f8fa;"
                    + "border-color:#e1e6ec;color:#a8b0bb}"
                    + "body.light .batchbar .danger{background:#c0392b;color:#ffffff}"
                    + "body.light .batchbar .danger:hover:not(:disabled){background:#d64a3b}"
                    + "body.light .batchbar .danger:disabled{background:#f6e4e4;"
                    + "color:#c99a9a}"
                    // 勾选后的行高亮：深底上 #1a2432 挺好看，浅底上等于一块脏灰
                    + "body.light .row.picked{background:#eaf2fd;outline-color:#8bb8e8}"
                    // ---- 实时状态面板（.live 这一族）----
                    // 这套 class 是首页「手机状态」那块，原来完全没被覆盖：
                    // .live 是 #171b22 深底、.lc 是 #11161d、.lv 是 #e6e9ef，
                    // 所以切日间/水墨后只有这个框还黑着，看着像「没跟随主题」。
                    + "body.light .live{background:#ffffff;border-color:#dbe0e7}"
                    + "body.light .lt{color:#57606a}"
                    + "body.light .lc{background:#f6f8fa}"
                    + "body.light .lk{color:#6e7681}"
                    + "body.light .lv{color:#1b1f26}"
                    + "body.light .lu{color:#6e7681}"
                    + "body.light .ln{color:#6e7681}"
                    + "body.light .lbar{background:#e4e8ee}"
                    + "body.light .lp{color:#57606a}"
                    + "body.light .lclock b{color:#1b1f26}"
                    + "body.light .lclock i{color:#6e7681}"
                    + "body.light .lf{color:#6e7681}"
                    + "body.light ::-webkit-scrollbar-thumb{background:#d3dae3}";

    /**
     * 服务端直出的水墨屏覆盖表。
     *
     * 和 LIGHT_CSS 同一个用途、同一个位置、同样的理由（老浏览器读不到 cssRules），
     * 差别只在配色取向：
     *   1. 全部去色，只留灰阶。墨水屏上没有颜色可显示，留着彩色只会被抖成杂点。
     *   2. 对比度主动压低。日间表里那些「白底 + 近黑字」在墨水屏上不是不清爽，
     *      而是残留更重 —— 像素从近黑弹回近白的那一下就留下轮廓。这里正文和底色
     *      之间只留 2~3 档灰（#e8e8e8 底 / #4e4e4e 字），看久了眼睛也不累。
     *   3. 强调色一律降成中灰，不保留色相。
     *
     * 键值对必须和 INK 映射表保持同一套灰阶，否则脚本版和预置版切换会看到色差。
     */
    private static final String INK_CSS =
            "body.ink{background:#e8e8e8;color:#3a3a3a;color-scheme:light}"
                    + "body.ink .wrap{color:#3a3a3a}"
                    // 卡片比底再压一档，靠灰度差分层，不靠阴影
                    + "body.ink .root,body.ink .crumbs,body.ink .card,"
                    + "body.ink .item,body.ink .row,body.ink li,body.ink .tr,"
                    + "body.ink .stat,body.ink .panel,body.ink .box,"
                    + "body.ink .upl,body.ink .login,body.ink .docbar,"
                    + "body.ink .gstrip,body.ink .batchbar,body.ink .search input,"
                    + "body.ink header,body.ink .footer,body.ink footer,"
                    + "body.ink table,body.ink td,body.ink th"
                    + "{background:#d6d6d6;border-color:#c6c6c6;color:#3a3a3a}"
                    + "body.ink .pdfnil{background:#e0e0e0;color:#4e4e4e}"
                    + "body.ink .pdfnil b{color:#333333}"
                    + "body.ink .pdfacts a{background:#6b6b6b;color:#f2f2f2}"
                    + "body.ink .pdfacts a:hover{background:#5a5a5a}"
                    + "body.ink .root:hover,body.ink .row:hover,body.ink li:hover,"
                    + "body.ink .item:hover,body.ink .tr:hover"
                    + "{background:#dcdcdc}"
                    // 次要文字压到 #6e6e6e：再浅就和底同灰了
                    + "body.ink .sub,body.ink .rp,body.ink .stats,body.ink .cnt,"
                    + "body.ink .count,body.ink .hint,body.ink .uplhint,"
                    + "body.ink .nmcur,body.ink .nmpath,body.ink .lf-s,"
                    + "body.ink .lf-msg,body.ink .docsize,body.ink .gcount,"
                    + "body.ink .muted,body.ink small"
                    + "{color:#6e6e6e}"
                    + "body.ink .stats b,body.ink .rn,body.ink .nmrow,"
                    + "body.ink .nmtx{color:#232323}"
                    + "body.ink a,body.ink .crumbs a,body.ink .lo,body.ink .nmcr"
                    + "{color:#4e4e4e;text-decoration:underline}"
                    // 「退出/登录」胶囊：墨屏下同样要把写死的 #1b222c 底换掉，
                    // 只换字色的话那一小块永远是黑的，最刺眼。
                    + "body.ink .lo{background:#d6d6d6;color:#3a3a3a;"
                    + "border:1px solid #c6c6c6;text-decoration:none}"
                    + "body.ink .lo:hover{background:#cccccc;color:#232323}"
                    // 墨屏不留色相：管理入口只比「退出」浅一档
                    + "body.ink .lo.adm{background:#e4e4e4;color:#4a4a4a;"
                    + "border:1px solid #c6c6c6}"
                    + "body.ink .lo.adm:hover{background:#d8d8d8;color:#232323}"
                    // 提示条也不上色，只靠灰度深浅区分成功/失败
                    + "body.ink .okbar{background:#dcdcdc;color:#373737;"
                    + "border-color:#c6c6c6}"
                    + "body.ink .errbar,body.ink .err{background:#cfcfcf;"
                    + "color:#232323;border-color:#969696}"
                    + "body.ink input,body.ink textarea,body.ink select"
                    + "{background:#dedede;color:#3a3a3a;border-color:#c6c6c6}"
                    + "body.ink button,body.ink .themebtn,body.ink .rowact,"
                    + "body.ink .delone{background:#d0d0d0;color:#3a3a3a;"
                    + "border-color:#c6c6c6}"
                    + "body.ink button:hover,body.ink .themebtn:hover,"
                    + "body.ink .rowact:hover{background:#c6c6c6}"
                    + "body.ink .themebtn{background:#d0d0d0;color:#6e6e6e;"
                    + "border-color:#c6c6c6}"
                    + "body.ink .themebtn.ink.on{background:#3a3a3a;color:#f2f2f2;"
                    + "border-color:#3a3a3a;font-weight:600}"
                    + "body.ink .themebtn.ink.on:hover{background:#232323;color:#f2f2f2}"
                    + "body.ink .delf,body.ink .delone{color:#232323}"
                    // ---- SVG 图标的水墨配色 ----
                    // 全部换成纯灰：墨水屏只有 16 级灰，任何带色相的像素都要靠
                    // dither 抖出来，残影和噪点都难看。这里只留明度差，不留色。
                    + "body.ink .svgi-type{color:#4a4a4a}"
                    + "body.ink .nm:hover .svgi-type{color:#232323}"
                    + "body.ink .svgi-act{color:#3a3a3a}"
                    + "body.ink .svgi-crumb{color:#4a4a4a}"
                    + "body.ink .crumbs a:hover .svgi-crumb{color:#232323}"
                    + "body.ink .svgi-root{color:#4a4a4a}"
                    + "body.ink .svgi-cov{color:#3a3a3a}"
                    + "body.ink .acts a{background:#d6d6d6;color:#3a3a3a}"
                    + "body.ink .acts a:hover{background:#c6c6c6}"
                    + "body.ink .rowact{background:#d6d6d6;color:#3a3a3a}"
                    + "body.ink .rowact:hover{background:#b8b8b8;color:#1a1a1a}"
                    // ---- 身份栏（用户名 + 等级徽章）----
                    // 水墨屏不留色相：管理员/读写/只读三级只靠灰度深浅区分，
                    // 描边统一 #c6c6c6，字色从 #232323 到 #6e6e6e 拉开层级。
                    + "body.ink .un{color:#232323}"
                    + "body.ink .ul{background:#d6d6d6;border-color:#c6c6c6;color:#4a4a4a}"
                    + "body.ink .ul0{color:#6e6e6e;border-color:#c6c6c6}"
                    + "body.ink .ul1{color:#3a3a3a;border-color:#a8a8a8}"
                    + "body.ink .ul2{color:#232323;background:#c6c6c6;border-color:#a8a8a8}"
                    + "body.ink hr,body.ink td,body.ink th{border-color:#c6c6c6}"
                    // ---- 批量选择条（.batchbar 这一族）----
                    // 和日间表同样的理由：通用规则只清了条子自身，
                    // 子元素（.ckall / .seln / .ghost / .danger）全是深色值。
                    // 水墨屏这边全部换纯灰，删除按钮也不留红 —— 红色在墨水屏上
                    // 只会抖成一片黑点，看不出是「危险」。
                    + "body.ink .batchbar{background:rgba(232,232,232,.97);"
                    + "border-top-color:#c6c6c6}"
                    + "body.ink .batchbar .ckall{color:#5e5e5e}"
                    + "body.ink .batchbar .seln{color:#5e5e5e}"
                    + "body.ink .batchbar .ghost{background:#d6d6d6;color:#232323;"
                    + "border-color:#a8a8a8}"
                    + "body.ink .batchbar .ghost:hover:not(:disabled){background:#cccccc}"
                    + "body.ink .batchbar .ghost:disabled{background:#dedede;"
                    + "border-color:#c6c6c6;color:#9a9a9a}"
                    + "body.ink .batchbar .danger{background:#5e5e5e;color:#f2f2f2}"
                    + "body.ink .batchbar .danger:hover:not(:disabled){background:#4a4a4a}"
                    + "body.ink .batchbar .danger:disabled{background:#dedede;"
                    + "color:#9a9a9a}"
                    + "body.ink .row.picked{background:#d0d0d0;outline-color:#9a9a9a}"
                    // ---- 实时状态面板（.live 这一族）----
                    // 和日间表同样的理由：这套 class 原来整族没被覆盖，
                    // 水墨屏下「手机状态」那块仍是深底亮字，跟整体灰阶完全不搭。
                    + "body.ink .live{background:#dedede;border-color:#c6c6c6}"
                    + "body.ink .lt{color:#6e6e6e}"
                    + "body.ink .lc{background:#d6d6d6}"
                    + "body.ink .lk{color:#6e6e6e}"
                    + "body.ink .lv{color:#232323}"
                    + "body.ink .lu{color:#6e6e6e}"
                    + "body.ink .ln{color:#6e6e6e}"
                    + "body.ink .lbar{background:#c6c6c6}"
                    // 进度条渐变压成两级灰：墨水屏上彩色会被抖成杂点
                    + "body.ink .lbar i{background:linear-gradient(90deg,#8a8a8a,#5e5e5e)}"
                    + "body.ink .lp{color:#5e5e5e}"
                    + "body.ink .lclock b{color:#232323}"
                    + "body.ink .lclock i{color:#6e6e6e}"
                    + "body.ink .lf{color:#6e6e6e}"
                    + "body.ink ::-webkit-scrollbar-thumb{background:#c6c6c6}";

    private static final String CSS =
            "*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}"
                    // 默认深色（夜间）。日间/水墨屏分别由 body.light / body.ink 的
                    // 服务端预置表接管，和这里的基准声明互不干扰。
                    + "html{background:#0e1116;color-scheme:dark}"
                    + "html[data-theme=light]{background:#f2f4f7;color-scheme:light}"
                    // 水墨屏：底色是浅灰。这里显式声明 color-scheme:light，
                    // 否则滚动条、输入框光标这些浏览器自绘的东西还是深色。
                    + "html[data-theme=ink]{background:#e8e8e8;color-scheme:light}"
                    // body 自己也跟着改，否则默认 body 那段 #0e1116 会把 html 的
                    // 底色整个盖掉 —— 上一版「切回日间只变一半」就有这一份。
                    + "html[data-theme=light] body{background:#f2f4f7}"
                    + "html[data-theme=ink] body{background:#e8e8e8}"
                    + "body{margin:0;background:#0e1116;color:#e6e9ef;"
                    + "font-family:-apple-system,'Segoe UI',Roboto,'Helvetica Neue',"
                    + "'PingFang SC','Microsoft YaHei',sans-serif;}"
                    + ".wrap{max-width:1080px;margin:0 auto;padding:16px 16px 40px}"
                    + ".wrap.wide{max-width:1280px}"
                    + "header{display:flex;align-items:baseline;gap:12px;margin:8px 0 20px}"
                    + "h1{font-size:22px;margin:0;font-weight:700;letter-spacing:.5px}"
                    + ".sub{color:#7d8590;font-size:13px}"
                    + ".roots{display:grid;gap:10px;margin-bottom:18px}"
                    + ".root{display:flex;gap:12px;align-items:center;padding:14px;"
                    + "background:#171b22;border:1px solid #232a34;border-radius:12px;"
                    + "text-decoration:none;color:inherit;transition:.15s}"
                    + ".root:hover{background:#1d232c;border-color:#2f3a48}"
                    + ".ri{font-size:26px}"
                    + ".rn{font-weight:600;font-size:15px}"
                    + ".rp{color:#6b7480;font-size:11px;margin-top:2px;word-break:break-all}"
                    + ".stats{display:flex;gap:20px;color:#7d8590;font-size:13px;margin:20px 0}"
                    + ".stats b{color:#e6e9ef}"
                    + ".crumbs{display:flex;flex-wrap:wrap;align-items:center;gap:4px;"
                    + "font-size:14px;margin-bottom:12px;padding:10px 12px;"
                    + "background:#171b22;border-radius:10px}"
                    + ".crumbs a{color:#5aa9ff;text-decoration:none}"
                    + ".crumbs a:hover{text-decoration:underline}"
                    // 面包屑最后一环是「当前所在位置」，不是链接：粗一点、亮一点，
                    // 跟前面可点的层级区分开。卷根时它承载的是卷名（内部存储）。
                    + ".crumbs b.here{color:#e6e9ef;font-weight:600}"
                    + ".sep{color:#4a525c;margin:0 2px}"
                    + ".search{display:flex;gap:8px;margin-bottom:12px}"
                    + ".search input{flex:1;padding:10px 14px;border-radius:10px;"
                    + "border:1px solid #232a34;background:#171b22;color:#e6e9ef;font-size:14px;outline:none}"
                    + ".search input:focus{border-color:#2f80ed}"
                    + ".search button{padding:10px 18px;border-radius:10px;border:0;"
                    + "background:#2f80ed;color:#fff;font-size:14px;cursor:pointer}"
                    + ".count{color:#6b7480;font-size:12px;margin-bottom:8px}"
                    + ".list{display:flex;flex-direction:column;gap:2px}"
                    + ".row{display:flex;align-items:center;gap:10px;padding:11px 12px;"
                    + "border-radius:9px;transition:.12s}"
                    + ".row:hover{background:#171b22}"
                    + ".row.playable:hover{background:#16202c}"
                    + ".nm{display:flex;align-items:center;gap:11px;flex:1;min-width:0;"
                    + "text-decoration:none;color:inherit}"
                    + ".ic{font-size:19px;flex-shrink:0;display:flex;align-items:center}"
                    // ---- 内联 SVG 图标 ----
                    // 统一用 currentColor 上色：图标本身不带颜色，父元素的 color
                    // 是什么它就画成什么，所以三种主题各写一条规则就够，不需要
                    // 三份图。stroke/fill 都在这里定，SVG 里只写几何。
                    + ".svgi{width:18px;height:18px;flex-shrink:0;display:inline-block;"
                    + "vertical-align:-3px;fill:none;stroke:currentColor;stroke-width:1.8;"
                    + "stroke-linecap:round;stroke-linejoin:round}"
                    // 类型图标稍大一点，跟文件名 14px 的字视觉对齐
                    + ".svgi-type{width:19px;height:19px;color:#8b96a5}"
                    + ".nm:hover .svgi-type{color:#5aa9ff}"
                    // 行内动作按钮：底色块里的图标，用浅蓝
                    + ".svgi-act{width:16px;height:16px;color:#8ec5ff}"
                    // 面包屑首页
                    + ".svgi-crumb{width:15px;height:15px;color:#8b96a5}"
                    + ".crumbs a:hover .svgi-crumb{color:#5aa9ff}"
                    // 跟文字的图标（「去网上找」「下载原文件」）
                    + ".svgi-inline{width:15px;height:15px;vertical-align:-2px}"
                    // 存储卷根图标
                    + ".svgi-root{width:15px;height:15px;color:#8b96a5;margin-right:5px}"
                    // 音乐页封面
                    + ".svgi-cov{width:46px;height:46px;color:#5aa9ff;stroke-width:1.4}"
                    + ".tx{font-size:14px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}"
                    + ".sz{color:#6b7480;font-size:12px;flex-shrink:0;min-width:70px;text-align:right}"
                    + ".acts{display:flex;gap:6px;flex-shrink:0}"
                    + ".acts a{color:#5aa9ff;text-decoration:none;font-size:15px;"
                    + "padding:4px 7px;border-radius:6px;background:#1b222c;"
                    + "display:inline-flex;align-items:center;justify-content:center}"
                    + ".acts a:hover{background:#243040}"
                    + ".acts a.act .svgi-act{color:inherit}"
                    + ".delf{margin:0;display:inline}"
                    + ".rowact{font:inherit;font-size:15px;line-height:1;"
                    + "padding:4px 7px;border-radius:6px;border:0;cursor:pointer;"
                    + "background:#1b222c;color:#8ec5ff;flex-shrink:0;"
                    + "display:inline-flex;align-items:center;justify-content:center}"
                    + ".rowact .svgi-act{color:inherit}"
                    + ".delone .svgi-act{color:inherit}"
                    + ".delone{display:inline-flex;align-items:center;justify-content:center}"
                    + ".rowact:hover{background:#243040;color:#cfe4ff}"
                    + ".rowact.mv:hover{background:#20303a;color:#7ee787}"
                    + ".rowact.rn:hover{background:#33301f;color:#ffd479}"
                    // 重命名 / 移动 对话框
                    + ".nmmask{position:fixed;left:0;top:0;right:0;bottom:0;"
                    + "background:rgba(0,0,0,.62);z-index:9998;"
                    + "display:flex;align-items:center;justify-content:center;padding:16px}"
                    + ".nmbox{background:#171b22;border:1px solid #2a323d;border-radius:14px;"
                    + "width:100%;max-width:520px;max-height:86vh;display:flex;"
                    + "flex-direction:column;box-shadow:0 18px 50px rgba(0,0,0,.55)}"
                    + ".nmhd{padding:14px 18px;font-size:15px;font-weight:600;"
                    + "color:#e6e9ef;border-bottom:1px solid #222a34;"
                    + "overflow:hidden;text-overflow:ellipsis;white-space:nowrap}"
                    + ".nmbd{padding:16px 18px;overflow:auto;flex:1 1 auto}"
                    + ".nmlab{font-size:12px;color:#9aa4b2;margin-bottom:7px}"
                    + ".nmcur{font-size:11px;color:#6b7480;margin-top:8px;"
                    + "overflow:hidden;text-overflow:ellipsis;white-space:nowrap}"
                    + ".nminp{width:100%;box-sizing:border-box;font:inherit;font-size:14px;"
                    + "padding:10px 12px;border-radius:9px;border:1px solid #2a323d;"
                    + "background:#11161d;color:#e6e9ef;outline:none}"
                    + ".nminp:focus{border-color:#3d7fd0;background:#0f141b}"
                    + ".nmerr{color:#ff8a94;font-size:12px;padding:0 18px;min-height:0}"
                    + ".nmerr:not(:empty){padding-bottom:10px}"
                    + ".nmft{padding:12px 18px;display:flex;gap:10px;justify-content:flex-end;"
                    + "border-top:1px solid #222a34}"
                    + ".nmbtn{font:inherit;font-size:13px;padding:9px 18px;border-radius:9px;"
                    + "border:1px solid #2a323d;background:#1b222c;color:#9aa4b2;cursor:pointer}"
                    + ".nmbtn:hover{background:#222b36;color:#e6e9ef}"
                    + ".nmbtn.primary{background:#2f80ed;border-color:#2f80ed;color:#fff}"
                    + ".nmbtn.primary:hover{background:#3b8cf5}"
                    + ".nmbtn:disabled{opacity:.55;cursor:default}"
                    + ".nmpath{font-size:12px;color:#8ec5ff;background:#11161d;"
                    + "border:1px solid #222a34;border-radius:8px;padding:8px 11px;"
                    + "margin-bottom:10px;overflow:hidden;text-overflow:ellipsis;"
                    + "white-space:nowrap}"
                    + ".nmlist{border:1px solid #222a34;border-radius:9px;"
                    + "max-height:44vh;overflow:auto;background:#11161d}"
                    + ".nmrow{display:flex;align-items:center;gap:9px;width:100%;"
                    + "font:inherit;font-size:13px;text-align:left;padding:10px 12px;"
                    + "border:0;border-bottom:1px solid #1a212a;background:transparent;"
                    + "color:#c7cedb;cursor:pointer}"
                    + ".nmrow:last-child{border-bottom:0}"
                    + ".nmrow:hover{background:#1b2430}"
                    + ".nmrow.on{background:#1d2c3d;color:#8ec5ff}"
                    + ".nmic{flex-shrink:0;width:18px;text-align:center}"
                    + ".nmtx{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}"
                    + ".nmld,.nmempty{font-size:12px;color:#6b7480;padding:14px}"
                    + ".nmempty{text-align:center}"
                    + ".nmtgt{font-size:12px;color:#7ee787;padding:2px 0 0;"
                    + "overflow:hidden;text-overflow:ellipsis;white-space:nowrap}"
                    + ".nmpick{margin-top:10px;display:flex;justify-content:flex-end}"
                    // 面包屑：🏠 位置 › 内部存储 › Ringtones，只有末段不可点。
                    + ".nmpath{font-size:12px;color:#9aa4b2;padding:0 0 8px;"
                    + "display:flex;flex-wrap:wrap;align-items:center;gap:2px}"
                    + ".nmcr{color:#8ec5ff;text-decoration:none;cursor:pointer;"
                    + "padding:1px 3px;border-radius:4px}"
                    + ".nmcr:hover{background:#1b2430}"
                    + ".nmcr.last{color:#e6e9ef;cursor:default}"
                    + ".nmcr.last:hover{background:transparent}"
                    + ".nmsep{color:#5a6472;font-style:normal;padding:0 1px}"
                    // 行右侧的「›」钻入提示，和点目录名选中区分开。
                    + ".nmgo{margin-left:auto;color:#5a6472;font-size:15px;padding-left:8px}"
                    + ".nmrow.on .nmgo{color:#8ec5ff}"
                    + ".delf button{font:inherit;font-size:15px;line-height:1;"
                    + "padding:4px 7px;border-radius:6px;border:0;cursor:pointer;"
                    + "background:#1b222c;color:#e06c75}"
                    + ".delf button:hover{background:#3a1f24;color:#ff8a94}"
                    // 单删按钮已经改成 <button class='delone'>，样式跟着搬一份
                    + ".delone{font:inherit;font-size:15px;line-height:1;"
                    + "padding:4px 7px;border-radius:6px;border:0;cursor:pointer;"
                    + "background:#1b222c;color:#e06c75}"
                    + ".delone:hover{background:#3a1f24;color:#ff8a94}"
                    + ".errbar{margin:0 0 10px;padding:10px 14px;border-radius:9px;"
                    + "background:#2a1a1e;border:1px solid #4a262c;color:#ff8a94;font-size:13px}"
                    + ".okbar{margin:0 0 10px;padding:10px 14px;border-radius:9px;"
                    + "background:#132a1c;border:1px solid #1f4a2e;color:#7ee2a8;font-size:13px}"
                    // ---- 上传面板 ----
                    + ".upl{margin:0 0 12px;padding:12px 14px;background:#141a21;"
                    + "border:1px solid #24303d;border-radius:11px;display:flex;"
                    + "flex-wrap:wrap;align-items:center;gap:10px}"
                    + ".uplbtn{padding:9px 18px;border-radius:9px;border:0;cursor:pointer;"
                    + "background:#2f80ed;color:#fff;font-size:13.5px;font-weight:600}"
                    + ".uplbtn:hover:not(:disabled){background:#3d8cf5}"
                    + ".uplbtn:disabled{background:#25344a;color:#66799a;cursor:default}"
                    + ".uplhint{color:#7d8590;font-size:12.5px;flex:1;min-width:120px}"
                    // 原生 file input 藏起来，只留「⬆ 选择文件」这一个按钮当入口；
                    // display:none 在某些浏览器上 click() 不生效，用绝对定位挪出视口。
                    //
                    // 注意：**只能挪 input，不能挪整个 form**。整条 form 挪到
                    // left:-9999px 的话，里面的「开始上传」按钮也会跟着跑到屏幕外，
                    // 用户在页面上只会看到一块诡异的空白，还会把那段 JS 源码当正文显示出来。
                    + ".uplform{display:inline-flex;align-items:center;gap:10px;margin:0}"
                    + ".uplform input[type=file]{position:absolute;left:-9999px;"
                    + "width:1px;height:1px;overflow:hidden;opacity:0}"
                    + ".uplprog{display:none;width:100%;height:6px;border-radius:3px;"
                    + "background:#0d1117;overflow:hidden}"
                    + ".uplprog.on{display:block}"
                    + ".uplprog i{display:block;height:100%;width:0;border-radius:3px;"
                    + "background:linear-gradient(90deg,#2f80ed,#56ccf2);"
                    + "transition:width .15s linear}"
                    // 拖拽蒙层
                    + ".dropmask{position:fixed;inset:0;display:none;z-index:9999;"
                    + "background:rgba(13,17,23,.86);border:3px dashed #2f80ed;"
                    + "color:#8ec5ff;font-size:19px;font-weight:600;"
                    + "align-items:center;justify-content:center;pointer-events:none}"
                    + ".dropmask.on{display:flex}"
                    // 批量选择
                    + ".batch{margin:0}"
                    + ".ck{flex-shrink:0;width:17px;height:17px;margin:0 10px 0 0;"
                    + "accent-color:#2f80ed;cursor:pointer}"
                    + ".ck:disabled{opacity:.25;cursor:not-allowed}"
                    + ".row.picked{background:#1a2432;outline:1px solid #2f5a8f}"
                    + ".batchbar{position:fixed;left:0;right:0;bottom:0;display:flex;"
                    + "align-items:center;gap:12px;padding:10px 16px;"
                    + "background:rgba(17,21,27,.96);border-top:1px solid #232a34;"
                    + "backdrop-filter:blur(8px);z-index:20}"
                    + ".batchbar .ckall{display:flex;align-items:center;gap:7px;"
                    + "color:#a7b0bd;font-size:13px;cursor:pointer;user-select:none}"
                    + ".batchbar .ckall input{accent-color:#2f80ed;width:17px;height:17px;margin:0}"
                    + ".batchbar .seln{flex:1;color:#7d8590;font-size:13px}"
                    + ".batchbar .ghost{padding:9px 16px;border-radius:9px;"
                    + "border:1px solid #2f5a8f;background:#16212e;color:#8ec5ff;"
                    + "font-size:13px;cursor:pointer}"
                    + ".batchbar .ghost:hover:not(:disabled){background:#1d2c3d}"
                    + ".batchbar .ghost:disabled{border-color:#24303d;background:#141a21;"
                    + "color:#4d5a68;cursor:not-allowed}"
                    + ".batchbar .danger{padding:9px 18px;border-radius:9px;border:0;"
                    + "background:#c0392b;color:#fff;font-size:13px;cursor:pointer}"
                    + ".batchbar .danger:hover:not(:disabled){background:#d64a3b}"
                    + ".batchbar .danger:disabled{background:#2a1a1e;color:#7a5057;"
                    + "cursor:not-allowed}"
                    + "footer{margin-top:26px;color:#4a525c;font-size:11px;"
                    + "word-break:break-all;text-align:center}"
                    // 播放器
                    + ".player-page{max-width:520px}"
                    + ".player-page.wide{max-width:1100px}"
                    + ".np{display:flex;gap:16px;align-items:center;margin:18px 0}"
                    + ".cov{width:74px;height:74px;border-radius:14px;display:flex;"
                    + "align-items:center;justify-content:center;font-size:34px;"
                    + "background:linear-gradient(135deg,#2f80ed,#8b5cf6);flex-shrink:0}"
                    + ".meta{min-width:0}"
                    + ".ttl{font-size:16px;font-weight:600;word-break:break-all}"
                    + ".fmt{color:#6b7480;font-size:12px;margin-top:4px}"
                    + "audio{width:100%;margin:8px 0 14px;border-radius:10px}"
                    + ".vwrap{background:#000;border-radius:12px;overflow:hidden;margin:14px 0}"
                    + ".vwrap video{width:100%;max-height:78vh;display:block}"
                    + ".lrcbar{display:flex;justify-content:space-between;align-items:center;"
                    + "font-size:12px;color:#7d8590;margin-bottom:8px}"
                    + ".mini{padding:5px 10px;border-radius:7px;border:1px solid #2a323d;"
                    + "background:#171b22;color:#9aa4b2;font-size:12px;cursor:pointer}"
                    + ".lrc{height:340px;overflow-y:auto;padding:20px 0;text-align:center;"
                    + "line-height:2.05;font-size:15px;color:#6b7480;"
                    + "position:relative;overflow-anchor:none;"
                    + "scroll-behavior:auto;transition:none}"
                    + ".lrc div{padding:1px 10px;transition:color .18s,font-size .18s;cursor:pointer}"
                    + ".lrc div:hover{color:#9aa4b2}"
                    + ".lrc .cur{color:#5aa9ff;font-size:18px;font-weight:700;"
                    + "text-shadow:0 0 18px rgba(90,169,255,.45)}"
                    + ".lrc .near{color:#9aa4b2}"
                    + ".lrc .none{color:#4a525c;font-size:14px}"
                    // 在线搜歌词面板
                    + ".lrcbtns{display:flex;gap:8px}"
                    + ".lrcfind{display:none;margin:0 0 10px;padding:10px 12px;"
                    + "background:#141922;border:1px solid #232a34;border-radius:12px}"
                    + ".lrcfind.on{display:block}"
                    + ".lf-head{font-size:12px;color:#7d8590;margin-bottom:8px;"
                    + "display:flex;justify-content:space-between;align-items:center;gap:10px}"
                    + ".lf-head b{color:#c9d1d9;font-weight:600}"
                    + ".lf-list{max-height:260px;overflow-y:auto;display:flex;"
                    + "flex-direction:column;gap:6px}"
                    + ".lf-item{display:flex;align-items:center;gap:10px;padding:8px 10px;"
                    + "border-radius:9px;background:#171b22;border:1px solid #2a323d}"
                    + ".lf-item:hover{border-color:#3b68a8;background:#1b222c}"
                    + ".lf-main{flex:1;min-width:0}"
                    + ".lf-t{font-size:13px;color:#d8dee6;white-space:nowrap;"
                    + "overflow:hidden;text-overflow:ellipsis}"
                    + ".lf-s{font-size:11px;color:#6b7480;margin-top:2px;white-space:nowrap;"
                    + "overflow:hidden;text-overflow:ellipsis}"
                    + ".lf-tag{font-size:10px;padding:1px 6px;border-radius:5px;"
                    + "background:#1d2c3d;color:#8ec5ff;flex:none}"
                    + ".lf-tag.no{background:#2a2222;color:#a08080}"
                    + ".lf-save{flex:none;padding:5px 10px;border-radius:7px;"
                    + "border:1px solid #2f5a8f;background:#16212e;color:#8ec5ff;"
                    + "font-size:12px;cursor:pointer}"
                    + ".lf-save:hover:not(:disabled){background:#1d2c3d}"
                    + ".lf-save:disabled{border-color:#24303d;background:#141a21;"
                    + "color:#4d5a68;cursor:default}"
                    + ".lf-msg{font-size:12px;color:#8b949e;padding:6px 2px}"
                    + ".downloads{margin-top:16px;font-size:13px}"
                    + ".downloads a{color:#5aa9ff;text-decoration:none}"
                    + ".downloads a:hover{text-decoration:underline}"
                    // 文档预览页
                    + ".docpage{display:flex;flex-direction:column;height:100vh;"
                    + "padding:0 12px 12px;box-sizing:border-box}"
                    + ".docbar{display:flex;align-items:center;gap:10px;padding:8px 4px;"
                    + "font-size:13px;flex-wrap:wrap;border-bottom:1px solid #232a34}"
                    + ".dotitle{color:#d8dee6;font-weight:600;word-break:break-all}"
                    + ".docsize{color:#6b7480;font-size:12px;flex:none}"
                    + ".docacts{margin-left:auto;display:flex;gap:12px;flex:none}"
                    + ".docacts a{color:#5aa9ff;text-decoration:none;font-size:12px}"
                    + ".docacts a:hover{text-decoration:underline}"
                    + ".docframe{flex:1;min-height:0;margin-top:10px;border-radius:12px;"
                    + "overflow:hidden;background:#0d1117;border:1px solid #232a34}"
                    + ".docframe iframe{width:100%;height:100%;border:0;display:block}"
                    // PDF 走 object/embed，不是 iframe：手机内核没有 PDF 插件，
                    // iframe 只会给一片黑。object 不认时靠里面的 .pdfnil 兜底。
                    + ".pdfbox object{width:100%;height:100%;display:block}"
                    + ".pdfbox embed{width:100%;height:100%;display:block}"
                    + ".pdfnil{padding:24px 18px;color:#9aa6b2;font-size:13px;line-height:1.7;"
                    + "background:#0d1117}"
                    + ".pdfnil b{color:#d8dee6;display:block;margin-bottom:8px;font-size:14px}"
                    + ".pdfacts{margin-top:10px;display:flex;gap:8px;flex-wrap:wrap}"
                    + ".pdfacts a{display:inline-flex;align-items:center;gap:6px;padding:9px 14px;"
                    + "border-radius:9px;background:#2f80ed;color:#fff;font-size:13px;"
                    + "text-decoration:none}"
                    + ".pdfacts a:hover{background:#2463b8}"
                    + ".doctext{flex:1;min-height:0;overflow:auto;margin:10px 0 0;"
                    + "padding:14px 16px;background:#0d1117;border:1px solid #232a34;"
                    + "border-radius:12px;color:#c8d1da;font-size:13px;line-height:1.65;"
                    + "white-space:pre-wrap;word-break:break-word}"
                    + ".docnote{margin-top:10px;padding:11px 14px;border-radius:10px;"
                    + "background:#141a21;border:1px solid #24303d;color:#9aa6b2;"
                    + "font-size:12.5px;line-height:1.7}"
                    + ".docnote b{color:#d8dee6}"
                    + ".docnote p{margin:6px 0 0}"
                    + ".docnote.alt{margin-top:8px;color:#8b949e}"
                    + ".docnote a{color:#5aa9ff;text-decoration:none}"
                    // ---- Office 双层预览 ----
                    // oxstage 是可滚动的正文区：B 层解析结果和 A 层高保真渲染都塞在里面，
                    // 谁在里面由前端脚本决定，样式表不用关心。
                    + ".oxstage{flex:1;min-height:0;overflow:auto;margin:10px 0 0;"
                    + "padding:16px 18px;background:#0d1117;border:1px solid #232a34;"
                    + "border-radius:12px}"
                    // docx-preview 自带的页面是白底的 A4 纸，在深色页面上要给它留出对比
                    + ".ox-docx-hi{background:#1a2028;padding:10px;border-radius:8px}"
                    + ".ox-docx-hi .docx-wrapper{background:transparent!important;"
                    + "padding:0!important}"
                    + ".ox-docx-hi .docx{background:#fff;color:#1a1a1a;margin:0 auto 14px;"
                    + "box-shadow:0 2px 12px rgba(0,0,0,.45);border-radius:3px}"
                    + ".ox-docx-hi .docx img{max-width:100%}"
                    + ".ox-xlsx-hi{padding:2px}"
                    // SheetJS 生成的 table 没有 class，靠 .ox-grid 统一上样式
                    + ".ox-xlsx-hi table{border-collapse:collapse;font-size:13px;"
                    + "white-space:nowrap;min-width:100%}"
                    + ".ox-xlsx-hi td,.ox-xlsx-hi th{border:1px solid #222a34;"
                    + "padding:5px 9px;color:#cfd6df;background:transparent}"
                    + ".ox-xlsx-hi tr:first-child td{background:#1b2430;color:#e6edf5;"
                    + "font-weight:600}"
                    // B 层（服务端解析）的样式全在 OoxmlView.CSS 里，前缀 ox-，
                    // 和列表页那套 css 完全隔离，互不影响
                    + OoxmlView.CSS
                    // ---- 图片画廊 ----
                    + ".gstage{position:relative;margin-top:10px;background:#0d1117;"
                    + "border:1px solid #232a34;border-radius:12px;overflow:hidden;"
                    + "display:flex;align-items:center;justify-content:center;"
                    + "min-height:240px;max-height:72vh}"
                    // contain：整图看得见；放大态用 scale，原点跟随点击位置（JS 设 transform-origin）
                    + ".gstage img{max-width:100%;max-height:72vh;display:block;"
                    + "cursor:zoom-in;transition:transform .12s ease-out;user-select:none}"
                    // 1.9 倍够了：再大就只剩一小块色块，反而看不出是什么图
                    + ".gstage img.zoom{transform:scale(1.9);cursor:zoom-out}"
                    // 两侧箭头：半透明压在图上，不占布局
                    + ".gnav{position:absolute;top:50%;transform:translateY(-50%);"
                    + "width:46px;height:76px;border:0;border-radius:12px;"
                    + "background:rgba(13,17,23,.55);color:#d8dee6;font-size:30px;"
                    + "line-height:1;cursor:pointer;backdrop-filter:blur(3px)}"
                    + ".gnav:hover{background:rgba(30,40,52,.85)}"
                    + ".gnav.prev{left:8px}"
                    + ".gnav.next{right:8px}"
                    + ".gstrip{display:flex;gap:8px;overflow-x:auto;margin-top:10px;"
                    + "padding-bottom:6px;scrollbar-width:thin}"
                    + ".gthumb{flex:0 0 auto;width:88px;height:88px;border-radius:9px;"
                    + "overflow:hidden;border:2px solid transparent;background:#0d1117;"
                    + "line-height:0}"
                    + ".gthumb img{width:100%;height:100%;object-fit:cover;display:block;"
                    + "opacity:.72;transition:opacity .15s}"
                    + ".gthumb:hover img{opacity:1}"
                    + ".gthumb.cur{border-color:#5aa9ff}"
                    + ".gthumb.cur img{opacity:1}"
                    + "@media(max-width:520px){.gthumb{width:68px;height:68px}"
                    + ".gnav{width:38px;height:62px;font-size:24px}}"
                    // 刮削信息面板
                    + ".scrape{margin-top:18px}"
                    + ".sc-card{background:#171b22;border:1px solid #232a34;"
                    + "border-radius:14px;overflow:hidden}"
                    + ".sc-fan{position:relative;background:#0d1117;line-height:0}"
                    + ".sc-fan img{width:100%;max-height:220px;object-fit:cover;"
                    + "opacity:.72;display:block}"
                    + ".sc-body{display:flex;gap:16px;padding:14px 16px}"
                    + ".sc-poster{flex:0 0 108px}"
                    + ".sc-poster img{width:108px;border-radius:9px;display:block;"
                    + "box-shadow:0 4px 14px rgba(0,0,0,.5)}"
                    + ".sc-info{flex:1;min-width:0}"
                    + ".sc-ttl{font-size:19px;font-weight:600;color:#e6e9ef;"
                    + "line-height:1.35;margin-bottom:3px}"
                    + ".sc-otl{font-size:12.5px;color:#7d8590;margin-bottom:6px}"
                    + ".sc-meta{font-size:12.5px;color:#9aa4b2;margin-bottom:7px}"
                    + ".sc-tags{display:flex;flex-wrap:wrap;gap:6px;margin-bottom:8px}"
                    + ".sc-tag{font-size:11px;padding:2px 9px;border-radius:20px;"
                    + "background:#1d2530;color:#8ec5ff}"
                    + ".sc-line{font-size:12.5px;color:#9aa4b2;line-height:1.8;"
                    + "margin-bottom:3px;word-break:break-word}"
                    + ".sc-plot{font-size:13px;color:#a7b0bd;line-height:1.75;"
                    + "margin-top:8px;white-space:pre-wrap;word-break:break-word}"
                    + ".sc-plot.fold{display:-webkit-box;-webkit-line-clamp:4;"
                    + "-webkit-box-orient:vertical;overflow:hidden;cursor:pointer}"
                    + ".sc-strip{display:flex;gap:10px;padding:0 16px 14px;"
                    + "overflow-x:auto}"
                    + ".sc-thumb{height:46px;border-radius:7px;flex:0 0 auto;"
                    + "background:#0d1117}"
                    + "@media(max-width:520px){"
                    + ".sc-body{flex-direction:column;gap:12px}"
                    + ".sc-poster img{width:88px}"
                    + ".sc-ttl{font-size:17px}}"
                    // 身份栏
                    // flex-wrap: 手机上（窄视口 + 大字号）这一排会超出 header 宽度。
                    // 之前只有 margin-left:auto 的话，超出的部分直接被 body 的
                    // overflow 裁掉 —— 主题按钮实际落在屏幕外，用户点不到，
                    // 表现就是「点了没反应」。加 wrap 让它换到第二行，永远可点。
                    // 身份栏：始终与标题同一行、右对齐。
                    //
                    // 老浏览器的降级路径：display:flex 在 Android 4.4 以下不支持，
                    // flex-wrap 更是 5.0 才有 —— 所以这里不用 wrap，靠「允许收缩 +
                    // 缩到最小还放不下时才换行」的天然行为。给 inline-block 兜底：
                    // 先写 inline-block（谁都认），再用 @supports 提权成 flex，
                    // 这样老 WebView 得到的是右对齐的 inline 块，一样能点。
                    + ".who{display:inline-block;text-align:right;margin-left:auto;max-width:100%}"
                    + ".who>*{display:inline-block;vertical-align:middle;margin-left:6px}"
                    + "@supports(display:flex){"
                    + ".who{display:flex;align-items:center;justify-content:flex-end;"
                    + "gap:6px;flex-wrap:wrap;max-width:100%}"
                    + ".who>*{margin-left:0}}"
                    + "header{flex-wrap:wrap}"
                    + ".un{font-size:13px;color:#e6e9ef}"
                    + ".ul{font-size:11px;padding:2px 8px;border-radius:20px;"
                    + "border:1px solid #2a323d;color:#9aa4b2}"
                    + ".ul2{color:#7ee787;border-color:#2b4a33}"
                    + ".ul1{color:#5aa9ff;border-color:#233a52}"
                    + ".ul0{color:#9aa4b2}"
                    + ".sc{font-size:11px;padding:2px 8px;border-radius:20px;"
                    + "border:1px solid #3a3323;color:#d6b45a;cursor:default}"
                    + ".lo{font-size:12px;color:#5aa9ff;text-decoration:none;"
                    + "padding:2px 8px;border-radius:6px;background:#1b222c}"
                    // 管理入口：比「退出」更轻一档（描边而不是实底），
                    // 免得它比「退出」还抢眼 —— 那是个低频动作
                    + ".lo.adm{background:transparent;border:1px solid #2a323d;"
                    + "color:#9aa4b2}"
                    + ".lo.adm:hover{color:#e6e9ef;border-color:#2f3a48}"
                    // 主题开关：和「退出」同一枚小胶囊，紧挨着放，不打乱身份栏的节奏
                    + ".themebtn{font:inherit;font-size:12px;cursor:pointer;"
                    + "padding:3px 10px;border-radius:6px;border:1px solid #2a323d;"
                    + "background:#1b222c;color:#9aa4b2;line-height:1.4;"
                    + "transition:background .15s,color .15s,border-color .15s}"
                    + ".themebtn:hover{background:#1d232c;color:#e6e9ef;border-color:#2f3a48}"
                    // 主题按钮挤在身份栏最右端，尺寸要收一收，否则手机上整排超宽。
                    // min-width 去掉、左右 padding 压到 9px：最坏情况（目录徽章 +
                    // 只读 + 登录 + 日间）实测整排 1080px 视口下刚好放得下。
                    + ".themebtn.sm{padding:4px 9px;font-size:11px;line-height:16px}"
                    // 水墨屏按钮：平时是暗的（和日夜按钮同款），只有当前真的在墨屏态时
                    // 才点亮成反白胶囊 —— 那是「墨水屏现在生效」的唯一视觉提示。
                    + ".themebtn.ink{color:#8b949e}"
                    + ".themebtn.ink:hover{color:#e6e9ef}"
                    + ".themebtn.ink.on{background:#e8e8e8;color:#1b1f26;"
                    + "border-color:#e8e8e8;font-weight:600}"
                    + ".themebtn.ink.on:hover{background:#f2f4f7;color:#1b1f26}"
                    // 登录页没有切换按钮（配色跟随首页），.themeline / .themebtn.wide
                    // 两条样式已无用，别再留着误导后来人。
                    // 登录页
                    + ".login{background:#171b22;border:1px solid #232a34;border-radius:16px;"
                    + "padding:26px 24px}"
                    + ".login h2{margin:0 0 4px;font-size:20px;color:#e6e9ef}"
                    + ".login .sub{font-size:12px;color:#6b7480;margin-bottom:18px}"
                    + ".login label{display:block;font-size:11px;color:#6b7480;"
                    + "margin:14px 0 6px;letter-spacing:.5px}"
                    + ".login input{width:100%;box-sizing:border-box;background:#11161d;"
                    + "border:1px solid #2a3340;border-radius:10px;color:#e6e9ef;"
                    + "padding:11px 12px;font-size:15px;outline:none}"
                    + ".login input:focus{border-color:#2f80ed}"
                    + ".login button{width:100%;margin-top:20px;border:0;border-radius:10px;"
                    + "background:linear-gradient(90deg,#2f80ed,#8b5cf6);color:#fff;"
                    + "font-size:15px;font-weight:600;padding:12px;cursor:pointer}"
                    + ".login .err{margin-top:16px;padding:9px 12px;border-radius:8px;"
                    + "background:#3a1d22;color:#ff9aa2;font-size:13px}"
                    + ".login .anon{margin-top:16px;text-align:center;font-size:12px}"
                    + ".login .anon a{color:#5aa9ff;text-decoration:none}"
                    // 管理页
                    + ".adm-top{display:flex;align-items:baseline;gap:12px;margin:18px 0 12px}"
                    + ".adm-top h2{margin:0;font-size:19px;color:#e6e9ef}"
                    + ".adm-back{font-size:12px;color:#5aa9ff;text-decoration:none}"
                    + ".adm-note{margin-bottom:14px;padding:10px 13px;border-radius:10px;"
                    + "background:#12301f;border:1px solid #1f5236;color:#8fe0ac;font-size:13px}"
                    + ".adm-grid{display:grid;gap:16px}"
                    + ".adm-sect{background:#171b22;border:1px solid #232a34;border-radius:14px;"
                    + "padding:16px 16px 14px}"
                    + ".adm-h{font-size:14px;font-weight:700;color:#e6e9ef;margin-bottom:4px}"
                    + ".adm-hint{font-size:12px;color:#6b7480;line-height:1.6;margin-bottom:12px}"
                    + ".adm-cands{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:12px;"
                    + "max-height:280px;overflow:auto}"
                    + ".adm-cand{display:flex;align-items:center;gap:7px;background:#11161d;"
                    + "border:1px solid #2a3340;border-radius:9px;padding:7px 10px;cursor:pointer;"
                    + "font-size:12px;color:#c3cad4;user-select:none}"
                    + ".adm-cand.on{border-color:#2f80ed;background:#12253d;color:#cfe2ff}"
                    + ".adm-cand input{margin:0;accent-color:#2f80ed}"
                    + ".adm-cn{font-weight:600}"
                    + ".adm-cp{color:#5d6773;font-size:11px;font-family:ui-monospace,monospace}"
                    + ".adm-cand.on .adm-cp{color:#7fb0e8}"
                    + ".adm-more{font-size:12px;color:#6b7480}"
                    // ---- 目录树 ----
                    // 候选目录以前是 flex-wrap 的标签块，40 个块平铺开，
                    // DCIM/Camera 和 Music 看着同级，谁是谁的子目录只能读路径。
                    // 现在按路径前缀折成树：缩进表示层级，▸/▾ 展开收起。
                    + ".adm-tree{border:1px solid #232a34;border-radius:10px;background:#11161d;"
                    + "padding:6px 4px;max-height:340px;overflow:auto;margin-bottom:10px}"
                    + ".adm-tbar{display:flex;align-items:center;gap:14px;padding:4px 10px 8px;"
                    + "border-bottom:1px solid #1d242e;margin-bottom:4px}"
                    + ".adm-tcnt{font-size:11px;color:#6b7480;flex:1}"
                    + ".adm-tgl{font-size:11px;color:#5aa9ff;cursor:pointer}"
                    + ".adm-tgl:hover{color:#8ec5ff}"
                    + ".adm-tn{display:flex;align-items:center;gap:6px;padding:4px 8px;"
                    + "border-radius:7px;font-size:12px;color:#c3cad4}"
                    + ".adm-tn:hover{background:#182029}"
                    + ".adm-tn.on{background:#12253d;color:#cfe2ff}"
                    + ".adm-tn input{margin:0;accent-color:#2f80ed;flex:none}"
                    + ".adm-tw{width:13px;flex:none;color:#6b7480;cursor:pointer;text-align:center}"
                    + ".adm-tw.leaf{cursor:default}"
                    + ".adm-tl{font-weight:600;cursor:pointer;white-space:nowrap}"
                    + ".adm-tw.leaf+.adm-tl{cursor:default}"
                    + ".adm-tp{font-size:10px;color:#5d6773;font-family:ui-monospace,monospace;"
                    + "margin-left:4px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}"
                    + ".adm-tc{font-size:10px;color:#ffc96b;border:1px solid #4a3f22;"
                    + "border-radius:6px;padding:0 5px;margin-left:4px}"
                    + ".adm-tk.hide{display:none}"
                    + ".adm-ta{width:100%;box-sizing:border-box;background:#11161d;"
                    + "border:1px solid #2a3340;border-radius:10px;color:#e6e9ef;"
                    + "padding:10px 12px;font-size:13px;line-height:1.7;outline:none;resize:vertical;"
                    + "font-family:ui-monospace,monospace}"
                    + ".adm-ta:focus{border-color:#2f80ed}"
                    + ".adm-form{margin:0}"
                    + ".adm-inp{width:100%;box-sizing:border-box;background:#11161d;"
                    + "border:1px solid #2a3340;border-radius:10px;color:#e6e9ef;"
                    + "padding:11px 12px;font-size:15px;outline:none}"
                    + ".adm-inp:focus{border-color:#2f80ed}"
                    + ".adm-act{display:flex;align-items:center;gap:12px;margin-top:12px}"
                    + ".adm-btn{border:0;border-radius:10px;background:linear-gradient(90deg,"
                    + "#2f80ed,#8b5cf6);color:#fff;font-size:14px;font-weight:600;"
                    + "padding:10px 20px;cursor:pointer}"
                    + ".adm-cnt{font-size:12px;color:#6b7480}"
                    + ".adm-foot{margin:18px 0 40px;font-size:12px;color:#5d6773;text-align:center}"
                    + "body.light .adm-top h2{color:#1b1f26}"
                    + "body.light .adm-sect{background:#fff;border-color:#dbe0e7}"
                    + "body.light .adm-h{color:#1b1f26}"
                    + "body.light .adm-hint{color:#57606a}"
                    + "body.light .adm-cand{background:#f6f8fa;border-color:#d3dae3;color:#31363d}"
                    + "body.light .adm-cand.on{background:#e8f1ff;border-color:#2f80ed;color:#123a6b}"
                    + "body.light .adm-cp{color:#8a939d}"
                    + "body.light .adm-cand.on .adm-cp{color:#3a6ea8}"
                    + "body.light .adm-tree{background:#fafbfc;border-color:#dbe0e7}"
                    + "body.light .adm-tbar{border-color:#e6eaef}"
                    + "body.light .adm-tn{color:#31363d}"
                    + "body.light .adm-tn:hover{background:#eef2f7}"
                    + "body.light .adm-tn.on{background:#e8f1ff;color:#123a6b}"
                    + "body.light .adm-tp{color:#8a939d}"
                    + "body.light .adm-tgl{color:#0b64c8}"
                    + "body.light .adm-tc{color:#8a6100;border-color:#e2cf9c}"
                    + "body.light .adm-ta,body.light .adm-inp{background:#fafbfc;"
                    + "border-color:#d3dae3;color:#1b1f26}"
                    + "body.light .adm-note{background:#e8f7ee;border-color:#a9dcbd;color:#1c6b3e}"
                    + "body.light .adm-cnt,body.light .adm-foot,body.light .adm-more{color:#6b7480}"
                    + "body.ink .adm-top h2{color:#232323}"
                    + "body.ink .adm-sect{background:#f2f2f2;border-color:#c6c6c6}"
                    + "body.ink .adm-h{color:#232323}"
                    + "body.ink .adm-hint{color:#6e6e6e}"
                    + "body.ink .adm-cand{background:#dedede;border-color:#c6c6c6;color:#3a3a3a}"
                    + "body.ink .adm-cand.on{background:#c4c4c4;border-color:#5e5e5e;color:#232323}"
                    + "body.ink .adm-cp{color:#7a7a7a}"
                    + "body.ink .adm-tree{background:#e8e8e8;border-color:#c6c6c6}"
                    + "body.ink .adm-tbar{border-color:#d2d2d2}"
                    + "body.ink .adm-tn{color:#3a3a3a}"
                    + "body.ink .adm-tn:hover{background:#dcdcdc}"
                    + "body.ink .adm-tn.on{background:#c4c4c4;color:#232323}"
                    + "body.ink .adm-tp{color:#7a7a7a}"
                    + "body.ink .adm-tgl{color:#3f3f3f}"
                    + "body.ink .adm-tc{color:#5e5e5e;border-color:#b8b8b8}"
                    + "body.ink .adm-ta,body.ink .adm-inp{background:#e8e8e8;"
                    + "border-color:#c6c6c6;color:#2b2b2b}"
                    + "body.ink .adm-btn{background:#5e5e5e;color:#f2f2f2}"
                    + "body.ink .adm-note{background:#cfcfcf;border-color:#a8a8a8;color:#232323}"
                    + "body.ink .adm-cnt,body.ink .adm-foot,body.ink .adm-more{color:#6e6e6e}"
                    // 上面这一族是登录页专用的，深色值全部写死在 .login 作用域里。
                    // 通用那条 body.light .login 只清了容器底，卡片里面还是黑的：
                    // 标题是 #e6e9ef（浅字）、说明/标签是 #6b7480、错误条是 #3a1d22 底。
                    // 所以补一组日间覆盖，把卡片里每一层都点一遍。
                    + "body.light .login h2{color:#1b1f26}"
                    + "body.light .login .sub,body.light .login label{color:#57606a}"
                    + "body.light .login input{background:#fafbfc;color:#1b1f26;"
                    + "border-color:#d3dae3}"
                    + "body.light .login input:focus{border-color:#2f80ed}"
                    + "body.light .login button{background:linear-gradient(90deg,"
                    + "#2f80ed,#8b5cf6);color:#fff;border:0}"
                    + "body.light .login .err{background:#fde8ea;color:#c0392b;"
                    + "border:1px solid #e8a0a0}"
                    + "body.light .login .anon{color:#57606a}"
                    + "body.light .login .anon a{color:#0b64c8}"
                    // 水墨屏同构：整卡改成灰阶，错误条只靠深浅区分，不留红/蓝。
                    + "body.ink .login h2{color:#232323}"
                    + "body.ink .login .sub,body.ink .login label{color:#6e6e6e}"
                    + "body.ink .login input{background:#dedede;color:#3a3a3a;"
                    + "border-color:#c6c6c6}"
                    + "body.ink .login button{background:#5e5e5e;color:#f2f2f2;"
                    + "border:0}"
                    + "body.ink .login .err{background:#cfcfcf;color:#232323;"
                    + "border:1px solid #969696}"
                    + "body.ink .login .anon{color:#6e6e6e}"
                    + "body.ink .login .anon a{color:#4e4e4e;"
                    + "text-decoration:underline}"
                    // 实时状态面板
                    + ".live{background:#171b22;border:1px solid #232a34;border-radius:14px;"
                    + "padding:14px 16px;margin-bottom:20px}"
                    + ".lt{font-size:12px;color:#9aa4b2;font-weight:700;margin-bottom:10px}"
                    + ".lg{display:grid;grid-template-columns:repeat(auto-fit,minmax(96px,1fr));gap:10px}"
                    + ".lc{background:#11161d;border-radius:10px;padding:9px 10px}"
                    + ".lk{font-size:10px;color:#6b7480;letter-spacing:.5px}"
                    + ".lv{font-size:16px;font-weight:700;color:#e6e9ef;margin-top:2px;"
                    + "font-variant-numeric:tabular-nums}"
                    + ".lu{font-size:10px;color:#6b7480;margin-top:1px;"
                    + "overflow:hidden;text-overflow:ellipsis;white-space:nowrap}"
                    + ".lb{margin-top:12px;display:flex;flex-direction:column;gap:7px}"
                    + ".lrow{display:flex;align-items:center;gap:10px}"
                    + ".ln{font-size:10px;color:#6b7480;width:32px;flex-shrink:0;"
                    + "overflow:hidden;text-overflow:ellipsis;white-space:nowrap}"
                    + ".lbar{flex:1;height:5px;background:#11161d;border-radius:3px;overflow:hidden}"
                    + ".lbar i{display:block;height:100%;width:0;border-radius:3px;"
                    + "background:linear-gradient(90deg,#2f80ed,#8b5cf6);transition:width .4s}"
                    + ".lp{font-size:10px;color:#7d8590;flex-shrink:0;"
                    + "font-variant-numeric:tabular-nums;white-space:nowrap}"
                    + ".lclock{display:flex;align-items:baseline;gap:10px;margin:-2px 0 12px}"
                    + ".lclock b{font-size:24px;font-weight:600;letter-spacing:.5px;"
                    + "font-variant-numeric:tabular-nums;color:#e6e9ef}"
                    + ".lclock i{font-style:normal;font-size:11px;color:#6b7480}"
                    + ".lf{display:flex;flex-wrap:wrap;gap:14px;margin-top:11px;"
                    + "font-size:10px;color:#6b7480;font-variant-numeric:tabular-nums}";;

    // ==================== 播放器 JS ====================

    /** 音乐播放器 + LRC 解析 + 三行居中滚动 */
    private static final String MUSIC_JS =
            "const au=document.getElementById('au');\n"
                    + "const box=document.getElementById('lrc');\n"
                    + "const stat=document.getElementById('lrcstat');\n"
                    + "let lines=[],curIdx=-1,offsets=[];\n"
                    + "function parseLRC(t){\n"
                    + "  const out=[];const re=/\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]/g;\n"
                    + "  t.split(/\\r?\\n/).forEach(function(raw){\n"
                    + "    let m,stamps=[],last=0,plain=false;\n"
                    + "    while((m=re.exec(raw))!==null){stamps.push(parseInt(m[1])*60+parseInt(m[2])"
                    + "+(m[3]?parseInt(m[3].padEnd(3,'0').slice(0,3))/1000:0));last=re.lastIndex;}\n"
                    + "    re.lastIndex=0;\n"
                    + "    const txt=raw.slice(last).trim();\n"
                    + "    if(stamps.length===0){\n"
                    + "      const mm=raw.match(/^\\[(ti|ar|al|by|offset):(.*)\\]$/i);\n"
                    + "      if(mm){if(mm[1].toLowerCase()==='offset'){offsets.push(parseInt(mm[2])||0);}return;}\n"
                    + "      return;\n"
                    + "    }\n"
                    + "    stamps.forEach(function(s){out.push({t:s,txt:txt});});\n"
                    + "  });\n"
                    + "  out.sort(function(a,b){return a.t-b.t;});\n"
                    + "  return out;\n"
                    + "}\n"
                    + "function render(){\n"
                    + "  box.innerHTML='';\n"
                    + "  if(lines.length===0){box.innerHTML='<div class=none>无歌词</div>';return;}\n"
                    + "  lines.forEach(function(l,i){\n"
                    + "    const d=document.createElement('div');d.textContent=l.txt||'♪';d.dataset.i=i;box.appendChild(d);\n"
                    + "  });\n"
                    + "}\n"
                    + "const off=function(){return offsets.length?offsets[0]/1000:0;};\n"
                    + "let userScrollAt=0,programScroll=false;\n"
                    + "/* 目标：把第 idx 行滚到容器垂直中央。\n"
                    + "   用 getBoundingClientRect 算相对位移，避免 offsetTop 依赖定位祖先\n"
                    + "   —— .lrc 没有 position:relative，offsetTop 会跑到 body 上，越往后偏越远。 */\n"
                    + "function centerOn(idx,instant){\n"
                    + "  const kids=box.children;\n"
                    + "  if(idx<0||!kids[idx])return;\n"
                    + "  const br=box.getBoundingClientRect(),lr=kids[idx].getBoundingClientRect();\n"
                    + "  const delta=(lr.top-br.top)-(box.clientHeight-lr.height)/2;\n"
                    + "  const top=Math.max(0,Math.min(box.scrollHeight-box.clientHeight,box.scrollTop+delta));\n"
                    + "  programScroll=true;\n"
                    + "  /* CSS 里 scroll-behavior:smooth 会让每次赋值都从头起一次动画，\n"
                    + "     rAF 每帧改 scrollTop 就永远滚不到位，这里必须显式 instant。 */\n"
                    + "  if(instant||!box.scrollTo){box.scrollTop=top;}\n"
                    + "  else{box.scrollTo({top:top,behavior:'auto'});}\n"
                    + "  setTimeout(function(){programScroll=false;},60);\n"
                    + "}\n"
                    + "function paint(idx){\n"
                    + "  const kids=box.children;\n"
                    + "  for(let i=0;i<kids.length;i++){\n"
                    + "    kids[i].className=(i===idx?'cur':(Math.abs(i-idx)<=1?'near':''));\n"
                    + "  }\n"
                    + "}\n"
                    + "function tick(){\n"
                    + "  if(lines.length){\n"
                    + "    const t=au.currentTime+off();\n"
                    + "    let idx=-1;\n"
                    + "    for(let i=0;i<lines.length;i++){if(lines[i].t<=t)idx=i;else break;}\n"
                    + "    if(idx!==curIdx){\n"
                    + "      curIdx=idx;\n"
                    + "      paint(idx);\n"
                    + "      /* 用户刚手动滚过就先别抢滚动条 */\n"
                    + "      if(Date.now()-userScrollAt>2500)centerOn(idx,false);\n"
                    + "    }\n"
                    + "  }\n"
                    + "  requestAnimationFrame(tick);\n"
                    + "}\n"
                    + "/* 用户主动滚动时记时间，2.5 秒内不自动居中 */\n"
                    + "box.addEventListener('wheel',function(){userScrollAt=Date.now();},{passive:true});\n"
                    + "box.addEventListener('touchmove',function(){userScrollAt=Date.now();},{passive:true});\n"
                    + "box.addEventListener('scroll',function(){if(!programScroll)userScrollAt=Date.now();},{passive:true});\n"
                    + "/* 点歌词行跳转对应时间 */\n"
                    + "box.addEventListener('click',function(ev){\n"
                    + "  if(!lines.length)return;\n"
                    + "  let el=ev.target;\n"
                    + "  while(el&&el!==box&&!(el.dataset&&el.dataset.i!==undefined))el=el.parentNode;\n"
                    + "  if(!el||el===box)return;\n"
                    + "  const i=parseInt(el.dataset.i,10);\n"
                    + "  if(isNaN(i)||!lines[i])return;\n"
                    + "  au.currentTime=Math.max(0,lines[i].t-off());\n"
                    + "  curIdx=i;paint(i);centerOn(i,true);userScrollAt=0;\n"
                    + "});\n"
                    + "/* 拖动进度 / 开始播放后立刻对齐，别等下一行触发 */\n"
                    + "['seeked','play','loadedmetadata'].forEach(function(e){\n"
                    + "  au.addEventListener(e,function(){setTimeout(function(){\n"
                    + "    if(lines.length)centerOn(curIdx,true);},60);});\n"
                    + "});\n"
                    + "fetch('/lyrics?f='+encodeURIComponent(FILE))\n"
                    + "  .then(function(r){return r.json();})\n"
                    + "  .then(function(j){\n"
                    + "    if(!j.ok){stat.textContent='未找到歌词文件（需同名 .lrc）';"
                    + "box.innerHTML='<div class=none>把同名 .lrc 放在音频旁边即可</div>';return;}\n"
                    + "    lines=parseLRC(j.lrc);\n"
                    + "    stat.textContent=j.file+' · '+lines.length+' 行';\n"
                    + "    render();tick();\n"
                    + "    if(lines.length&&lines[0].t>0)au.currentTime=Math.max(0,lines[0].t-0.2);\n"
                    + "  })\n"
                    + "  .catch(function(e){stat.textContent='歌词加载失败';});\n"
                    + "document.getElementById('btnview').onclick=function(){\n"
                    + "  const url='/lyrics?f='+encodeURIComponent(FILE);\n"
                    + "  fetch(url).then(function(r){return r.json();}).then(function(j){\n"
                    + "    if(!j.ok){alert('没有歌词文件');return;}\n"
                    + "    const w=window.open('','_blank');\n"
                    + "    w.document.write('<pre style=\"white-space:pre-wrap;font-family:monospace;"
                    + "padding:20px;line-height:1.6\">'+\n"
                    + "      j.lrc.replace(/&/g,'&amp;').replace(/</g,'&lt;')+'</pre>');\n"
                    + "  });\n"
                    + "};\n"
                    + "if(au.readyState<1){au.load();}\n"
                    + "\n"
                    + "/* ==================== 在线搜歌词 ====================\n"
                    + "   只在用户点按钮时查一次，绝不自动批量搜 —— 上千首的目录挨个查\n"
                    + "   会把手机烤热、宽带打满。候选只显示元信息，选中后才让服务器\n"
                    + "   按 tok 去源头重取正文落盘，正文不经前端。 */\n"
                    + "const findBox=document.getElementById('lrcfind');\n"
                    + "const btnFind=document.getElementById('btnfind');\n"
                    + "let finding=false,saving=false;\n"
                    + "function lfMsg(t,color){\n"
                    + "  findBox.innerHTML='';\n"
                    + "  const d=document.createElement('div');d.className='lf-msg';\n"
                    + "  d.style.color=color||'#8b949e';d.textContent=t;findBox.appendChild(d);\n"
                    + "}\n"
                    + "function durText(ms){\n"
                    + "  if(!ms||ms<0)return '';\n"
                    + "  const s=Math.round(ms/1000);\n"
                    + "  return Math.floor(s/60)+':'+String(s%60).padStart(2,'0');\n"
                    + "}\n"
                    + "function srcName(s){\n"
                    + "  return s==='lrclib'?'LRCLIB':(s==='netease'?'网易云':(s==='qq'?'QQ音乐':s));\n"
                    + "}\n"
                    + "function renderCands(j){\n"
                    + "  findBox.innerHTML='';\n"
                    + "  const head=document.createElement('div');head.className='lf-head';\n"
                    + "  const q=document.createElement('span');\n"
                    + "  const b=document.createElement('b');b.textContent=j.query||'';\n"
                    + "  q.appendChild(document.createTextNode('搜索：'));q.appendChild(b);\n"
                    + "  if(j.artist)q.appendChild(document.createTextNode(' — '+j.artist));\n"
                    + "  const cnt=document.createElement('span');\n"
                    + "  cnt.textContent=(j.items?j.items.length:0)+' 个候选';\n"
                    + "  head.appendChild(q);head.appendChild(cnt);findBox.appendChild(head);\n"
                    + "  if(!j.items||!j.items.length){\n"
                    + "    const e=document.createElement('div');e.className='lf-msg';\n"
                    + "    e.textContent='没搜到。文件名拆得不准可以在下面用「换个关键词」再试。';\n"
                    + "    findBox.appendChild(e);\n"
                    + "  } else {\n"
                    + "    const list=document.createElement('div');list.className='lf-list';\n"
                    + "    j.items.forEach(function(it){\n"
                    + "      const row=document.createElement('div');row.className='lf-item';\n"
                    + "      const tag=document.createElement('span');\n"
                    + "      tag.className='lf-tag'+(it.synced?'':' no');\n"
                    + "      tag.textContent=srcName(it.src);\n"
                    + "      const main=document.createElement('div');main.className='lf-main';\n"
                    + "      const t=document.createElement('div');t.className='lf-t';\n"
                    + "      t.textContent=it.title||'(无标题)';\n"
                    + "      const s=document.createElement('div');s.className='lf-s';\n"
                    + "      const bits=[];\n"
                    + "      if(it.artist)bits.push(it.artist);\n"
                    + "      if(it.album)bits.push(it.album);\n"
                    + "      const dt=durText(it.dur);\n"
                    + "      if(dt)bits.push(dt);\n"
                    + "      bits.push(it.synced?'逐行时间轴':'纯文本');\n"
                    + "      s.textContent=bits.join(' · ');\n"
                    + "      main.appendChild(t);main.appendChild(s);\n"
                    + "      const btn=document.createElement('button');btn.className='lf-save';\n"
                    + "      btn.textContent='用这个';btn.disabled=!it.synced;\n"
                    + "      if(!it.synced)btn.title='没有时间轴，存下来也不会滚动';\n"
                    + "      btn.onclick=function(){saveCand(it,btn);};\n"
                    + "      row.appendChild(tag);row.appendChild(main);row.appendChild(btn);\n"
                    + "      list.appendChild(row);\n"
                    + "    });\n"
                    + "    findBox.appendChild(list);\n"
                    + "  }\n"
                    + "  const bar=document.createElement('div');bar.className='lf-head';\n"
                    + "  bar.style.marginTop='8px';bar.style.marginBottom='0';\n"
                    + "  const again=document.createElement('button');again.className='mini';\n"
                    + "  again.textContent='换个关键词';\n"
                    + "  again.onclick=function(){askKeyword(j.query);};\n"
                    + "  const close=document.createElement('button');close.className='mini';\n"
                    + "  close.textContent='收起';close.onclick=function(){findBox.classList.remove('on');};\n"
                    + "  const wrap=document.createElement('div');wrap.className='lrcbtns';\n"
                    + "  wrap.appendChild(again);wrap.appendChild(close);bar.appendChild(wrap);\n"
                    + "  findBox.appendChild(bar);\n"
                    + "}\n"
                    + "function askKeyword(cur){\n"
                    + "  const kw=window.prompt('换个关键词（歌名或「歌手 歌名」）：',cur||'');\n"
                    + "  if(kw===null)return;\n"
                    + "  const t=kw.trim();\n"
                    + "  if(!t)return;\n"
                    + "  doSearch(t);\n"
                    + "}\n"
                    + "function doSearch(kw){\n"
                    + "  if(finding)return;\n"
                    + "  finding=true;findBox.classList.add('on');\n"
                    + "  lfMsg('正在搜索…');\n"
                    + "  let url='/lyrics/search?f='+encodeURIComponent(FILE);\n"
                    + "  if(kw)url+='&q='+encodeURIComponent(kw);\n"
                    + "  fetch(url)\n"
                    + "    .then(function(r){return r.json();})\n"
                    + "    .then(function(j){\n"
                    + "      finding=false;\n"
                    + "      if(!j.ok){lfMsg('搜索失败：'+(j.error||'未知错误'),'#e8a0a0');return;}\n"
                    + "      renderCands(j);\n"
                    + "    })\n"
                    + "    .catch(function(e){finding=false;\n"
                    + "      lfMsg('网络不通或查询超时，稍后再试。','#e8a0a0');});\n"
                    + "}\n"
                    + "function saveCand(it,btn,over){\n"
                    + "  if(saving)return;\n"
                    + "  saving=true;btn.disabled=true;btn.textContent='保存中…';\n"
                    + "  const body='f='+encodeURIComponent(FILE)+'&tok='+encodeURIComponent(it.tok)\n"
                    + "    +'&overwrite='+(over?1:0);\n"
                    + "  fetch('/lyrics/save',{method:'POST',\n"
                    + "    headers:{'Content-Type':'application/x-www-form-urlencoded'},\n"
                    + "    body:body})\n"
                    + "    .then(function(r){return r.json();})\n"
                    + "    .then(function(j){\n"
                    + "      saving=false;\n"
                    + "      if(!j.ok){\n"
                    + "        btn.disabled=false;btn.textContent='用这个';\n"
                    + "        if(j.alreadyExists){\n"
                    + "          /* 服务端不擅自覆盖，先问一句；确认了再带 overwrite=1 重发一次 */\n"
                    + "          if(window.confirm('同目录已有 '+j.file+'。\\n用这份新的替换掉它吗？')){\n"
                    + "            saveCand(it,btn,true);\n"
                    + "          }else{\n"
                    + "            lfMsg('保留了原来的 '+j.file+'。','#e8c07d');\n"
                    + "          }\n"
                    + "        }\n"
                    + "        else lfMsg('保存失败：'+(j.error||'未知错误'),'#e8a0a0');\n"
                    + "        return;\n"
                    + "      }\n"
                    + "      lfMsg((j.replaced?'已覆盖 ':'已保存 ')+j.file+'（'+j.bytes+' 字节），正在重新加载歌词…','#7ee2a8');\n"
                    + "      setTimeout(reloadLyrics,600);\n"
                    + "    })\n"
                    + "    .catch(function(e){saving=false;btn.disabled=false;\n"
                    + "      btn.textContent='用这个';lfMsg('保存超时，稍后再试。','#e8a0a0');});\n"
                    + "}\n"
                    + "function reloadLyrics(){\n"
                    + "  fetch('/lyrics?f='+encodeURIComponent(FILE))\n"
                    + "    .then(function(r){return r.json();})\n"
                    + "    .then(function(j){\n"
                    + "      if(!j.ok)return;\n"
                    + "      lines=parseLRC(j.lrc);curIdx=-1;\n"
                    + "      stat.textContent=j.file+' · '+lines.length+' 行';\n"
                    + "      render();\n"
                    + "      if(lines.length)centerOn(0,true);\n"
                    + "    })\n"
                    + "    .catch(function(){});\n"
                    + "}\n"
                    + "if(btnFind)btnFind.onclick=function(){\n"
                    + "  if(findBox.classList.contains('on')){findBox.classList.remove('on');return;}\n"
                    + "  doSearch('');\n"
                    + "};\n";

    /**
     * 视频播放器脚本。
     *
     * 核心是「先问浏览器认不认，再决定放还是劝退」：
     * mp4/mov/webm 基本都认；mkv 的容器 Chrome 不认识，但里面若是 H.264/AAC，
     * 换 mime 探一次 canPlayType 往往能过，就直接交给原生 video 播（比走
     * 转码省事、还能拖进度）。探不过才落回「下载 / 外部播放器」的提示。
     *
     * 失败提示里带上 URL，方便直接丢给 VLC/MX Player —— 它们对 mkv 支持最全。
     */
    private static final String VIDEO_JS =
            "const vd=document.getElementById('vd');\n"
                    + "const okExt=['mp4','m4v','webm','mov','mkv'];\n"
                    + "const NE='video/x-matroska';\n"
                    + "/* 浏览器对容器的支持只能靠 canPlayType 探，别猜 */\n"
                    + "function canProbe(ext){\n"
                    + "  if(!ext)return false;\n"
                    + "  try{return !!vd.canPlayType('video/'+ext.toLowerCase());}catch(e){return false;}\n"
                    + "}\n"
                    + "function hint(title,detail){\n"
                    + "  var b=document.createElement('div');\n"
                    + "  b.className='downloads';\n"
                    + "  b.style.cssText='padding:14px;background:#2a1f12;border-radius:10px;"
                    + "color:#e8c07d;font-size:13px;line-height:1.7';\n"
                    + "  b.innerHTML=title+'<br>'+detail+'<br>直连地址：'\n"
                    + "    +'<code style=\"word-break:break-all\">'\n"
                    + "    +'http://'+location.host+encodeURI(URL_)+'</code>';\n"
                    + "  vd.parentNode.insertBefore(b,vd.nextSibling);\n"
                    + "}\n"
                    + "vd.addEventListener('error',function(){\n"
                    + "  if(okExt.indexOf((EXT||'').toLowerCase())<0){\n"
                    + "    hint('浏览器无法直接解码 .'+EXT+' 容器。',"
                    + "'可以：① 点上面下载后用本地播放器打开；② 用 VLC / MX Player 直接打开下面地址。');\n"
                    + "    return;\n"
                    + "  }\n"
                    + "  if((EXT||'').toLowerCase()==='mkv'){\n"
                    + "    hint('这个 .mkv 打不开。',"
                    + "'常见原因是内部编码是 H.265/HEVC 或 DTS 音轨，浏览器没有对应解码器。'\n"
                    + "      +'① 下载后用本地播放器；② 用 VLC / MX Player 直接播放下面地址；'\n"
                    + "      +'③ 想在线看请转成 H.264+AAC 的 mp4。');\n"
                    + "    return;\n"
                    + "  }\n"
                    + "  hint('播放失败。',"
                    + "'① 下载后用本地播放器打开；② 用 VLC / MX Player 直接打开下面地址。');\n"
                    + "});\n"
                    + "/* 浏览器自己都说不认这个容器，就别让它对着黑框等 */\n"
                    + "if(!canProbe(EXT)&&(EXT||'').toLowerCase()==='mkv'){\n"
                    + "  hint('这台浏览器不接受 .mkv 容器，但里面的编码可能仍是 H.264。',\n"
                    + "      '先直接播放试试；卡住就下载或用 VLC / MX Player 打开下面地址。');\n"
                    + "}\n";

    // ==================== 刮削信息渲染 JS ====================

    /**
     * 播放页底部的刮削信息面板。
     *
     * 拉 /scrape?f=... 拿 nfo 与图片清单，有内容才渲染，一条都没有就整块留空
     * （连边框都不画，免得空白框看着像加载失败）。
     *
     * 全部走 textContent / setAttribute 构造节点，不拼 innerHTML ——
     * nfo 是外部文件，剧情简介里出现尖括号或引号是常事。
     */
    private static final String SCRAPE_JS =
            "(function(){\n"
                    + "  var host=document.getElementById('scrape');\n"
                    + "  if(!host)return;\n"
                    + "  function el(tag,cls,txt){\n"
                    + "    var e=document.createElement(tag);\n"
                    + "    if(cls)e.className=cls;\n"
                    + "    if(txt!=null)e.textContent=txt;\n"
                    + "    return e;\n"
                    + "  }\n"
                    + "  function img(url,alt,cls){\n"
                    + "    var i=document.createElement('img');\n"
                    + "    i.className=cls||'sc-img';\n"
                    + "    i.loading='lazy';\n"
                    + "    i.alt=alt||'';\n"
                    + "    i.src=url;\n"
                    + "    /* 图挂了就整张拿掉，别留一个破图标 */\n"
                    + "    i.onerror=function(){if(i.parentNode)i.parentNode.removeChild(i);};\n"
                    + "    return i;\n"
                    + "  }\n"
                    + "  fetch('/scrape?f='+encodeURIComponent(URL_))\n"
                    + "    .then(function(r){return r.json();})\n"
                    + "    .then(function(d){\n"
                    + "      if(!d||!d.ok)return;\n"
                    + "      var n=d.nfo||null;\n"
                    + "      var imgs=d.images||[];\n"
                    + "      if(!n&&!imgs.length)return;\n"
                    + "\n"
                    + "      var pick=function(kind){\n"
                    + "        for(var i=0;i<imgs.length;i++){if(imgs[i].kind===kind)return imgs[i];}\n"
                    + "        return null;\n"
                    + "      };\n"
                    + "      var poster=pick('poster')||pick('folder')||pick('cover')||pick('post');\n"
                    + "      var fan=pick('fanart')||pick('backdrop')||pick('background');\n"
                    + "\n"
                    + "      var card=el('div','sc-card');\n"
                    + "      if(fan){\n"
                    + "        var fw=el('div','sc-fan');\n"
                    + "        fw.appendChild(img(fan.url,'背景图'));\n"
                    + "        card.appendChild(fw);\n"
                    + "      }\n"
                    + "      var body=el('div','sc-body');\n"
                    + "      if(poster){\n"
                    + "        var pw=el('div','sc-poster');\n"
                    + "        pw.appendChild(img(poster.url,'海报'));\n"
                    + "        body.appendChild(pw);\n"
                    + "      }\n"
                    + "      var info=el('div','sc-info');\n"
                    + "      var ttl=n&&(n.title||n.showTitle);\n"
                    + "      if(ttl)info.appendChild(el('div','sc-ttl',ttl));\n"
                    + "      if(n&&n.originalTitle&&n.originalTitle!==ttl){\n"
                    + "        info.appendChild(el('div','sc-otl',n.originalTitle));\n"
                    + "      }\n"
                    + "      /* 元信息一条：年份 · 时长 · 评分 · 分级 */\n"
                    + "      var bits=[];\n"
                    + "      if(n){\n"
                    + "        if(n.showTitle&&n.season){\n"
                    + "          bits.push('S'+n.season+(n.episode?'E'+n.episode:''));\n"
                    + "        }\n"
                    + "        if(n.year)bits.push(n.year);\n"
                    + "        if(n.runtime){var m=parseInt(n.runtime,10);"
                    + "if(m>0)bits.push(Math.floor(m/60)+' 分钟');}\n"
                    + "        if(n.rating){var rt=parseFloat(n.rating);"
                    + "if(rt>0)bits.push('★ '+rt.toFixed(1));}\n"
                    + "        if(n.mpaa)bits.push(n.mpaa);\n"
                    + "      }\n"
                    + "      if(bits.length)info.appendChild(el('div','sc-meta',bits.join(' · ')));\n"
                    + "      /* 类型 / 地区 */\n"
                    + "      var tags=[];\n"
                    + "      if(n&&n.genres)tags=tags.concat(n.genres);\n"
                    + "      if(n&&n.countries)tags=tags.concat(n.countries);\n"
                    + "      if(tags.length){\n"
                    + "        var tw=el('div','sc-tags');\n"
                    + "        for(var i=0;i<tags.length&&i<8;i++)tw.appendChild(el('span','sc-tag',tags[i]));\n"
                    + "        info.appendChild(tw);\n"
                    + "      }\n"
                    + "      if(n&&n.director)info.appendChild(el('div','sc-line','导演：'+n.director));\n"
                    + "      if(n&&n.actors&&n.actors.length){\n"
                    + "        var names=[];\n"
                    + "        for(var i=0;i<n.actors.length&&i<6;i++){\n"
                    + "          names.push(n.actors[i].role?n.actors[i].name+'（'+n.actors[i].role+'）'\n"
                    + "                                         :n.actors[i].name);\n"
                    + "        }\n"
                    + "        info.appendChild(el('div','sc-line','主演：'+names.join('、')));\n"
                    + "      }\n"
                    + "      var plot=n&&(n.plot||n.outline);\n"
                    + "      if(plot){\n"
                    + "        var p=el('div','sc-plot',plot);\n"
                    + "        info.appendChild(p);\n"
                    + "        /* 简介超过 4 行就折叠，点一下展开，避免把页面撑太长 */\n"
                    + "        setTimeout(function(){\n"
                    + "          if(p.scrollHeight>p.clientHeight+8){\n"
                    + "            p.classList.add('fold');\n"
                    + "            p.addEventListener('click',function(){\n"
                    + "              p.classList.toggle('fold');\n"
                    + "            });\n"
                    + "          }\n"
                    + "        },60);\n"
                    + "      }\n"
                    + "      body.appendChild(info);\n"
                    + "      card.appendChild(body);\n"
                    + "\n"
                    + "      /* 其它图片（logo / banner / thumb）缩略图条 */\n"
                    + "      var extra=[];\n"
                    + "      for(var i=0;i<imgs.length;i++){\n"
                    + "        var k=imgs[i].kind;\n"
                    + "        if(k==='poster'||k==='folder'||k==='cover'||k==='post')continue;\n"
                    + "        if(k==='fanart'||k==='backdrop'||k==='background')continue;\n"
                    + "        extra.push(imgs[i]);\n"
                    + "      }\n"
                    + "      if(extra.length){\n"
                    + "        var strip=el('div','sc-strip');\n"
                    + "        for(var i=0;i<extra.length;i++){\n"
                    + "          var th=img(extra[i].url,extra[i].kind,'sc-thumb');\n"
                    + "          th.title=extra[i].file;\n"
                    + "          strip.appendChild(th);\n"
                    + "        }\n"
                    + "        card.appendChild(strip);\n"
                    + "      }\n"
                    + "      host.appendChild(card);\n"
                    + "    })\n"
                    + "    .catch(function(){});\n"
                    + "})();\n";

    // ==================== 列表页批量选择 JS ====================

    /**
     * 列表页的复选框脚本。
     *
     * 只在有删除权限时注入。行为：
     *   - 勾选动到全选状态：全勾 = 全选打钩，一个不勾 = 取消，部分勾 = 半选
     *   - 底部「已选 N 项」跟着变，N=0 时删除按钮置灰
     *   - 提交前二次确认，把勾选数量说清楚
     * 不用 innerHTML 拼字符串，全部走 textContent，文件名里的尖括号不会变成标签。
     */
    private static final String BATCH_JS =
            "<script>\n"
                    + "(function(){\n"
                    + "  var form=document.getElementById('batch');\n"
                    + "  var bar=document.getElementById('batchbar');\n"
                    + "  if(!form||!bar)return;\n"
                    + "  var all=document.getElementById('ckall');\n"
                    + "  var seln=document.getElementById('seln');\n"
                    + "  var btn=document.getElementById('btndel');\n"
                    + "  var bdl=document.getElementById('btndl');\n"
                    + "  var boxes=[].slice.call(document.querySelectorAll('.ck:not(:disabled)'));\n"
                    + "  function picked(){var n=0;for(var i=0;i<boxes.length;i++){if(boxes[i].checked)n++;}\n"
                    + "    return n;}\n"
                    + "  function chosen(){var a=[];for(var i=0;i<boxes.length;i++){\n"
                    + "    if(boxes[i].checked)a.push(boxes[i].value);}return a;}\n"
                    + "  function sync(){\n"
                    + "    var n=picked();\n"
                    + "    seln.textContent='已选 '+n+' 项';\n"
                    + "    btn.disabled=(n===0);\n"
                    + "    if(bdl)bdl.disabled=(n===0);\n"
                    + "    all.checked=(n>0&&n===boxes.length);\n"
                    + "    all.indeterminate=(n>0&&n<boxes.length);\n"
                    + "    for(var i=0;i<boxes.length;i++){\n"
                    + "      var row=boxes[i].closest('.row');\n"
                    + "      if(row){if(boxes[i].checked){row.classList.add('picked');}\n"
                    + "        else{row.classList.remove('picked');}}\n"
                    + "    }\n"
                    + "  }\n"
                    + "  for(var i=0;i<boxes.length;i++){boxes[i].addEventListener('change',sync);}\n"
                    + "  if(all){all.addEventListener('change',function(){\n"
                    + "    for(var i=0;i<boxes.length;i++){boxes[i].checked=all.checked;}\n"
                    + "    sync();});}\n"
                    + "  form.addEventListener('submit',function(e){\n"
                    + "    var n=picked();\n"
                    + "    if(n===0){e.preventDefault();return;}\n"
                    + "    if(!confirm('删除选中的 '+n+' 项？\\n此操作不可撤销，目录会连同里面的内容一起删掉。')){\n"
                    + "      e.preventDefault();\n"
                    + "    }\n"
                    + "  });\n"
                    + "  /* 下载所选：/zip 本来就吃重复的 p，直接拼好 URL 交给浏览器下载。\n"
                    + "     不用 fetch —— 那样整个 zip 会先进内存再落盘，大目录会撑爆页签。 */\n"
                    + "  if(bdl){bdl.addEventListener('click',function(){\n"
                    + "    var ps=chosen();\n"
                    + "    if(!ps.length)return;\n"
                    + "    if(ps.length>300&&!confirm('已选 '+ps.length+' 项，打包和解压都会比较慢，继续？'))return;\n"
                    + "    var q=[];\n"
                    + "    for(var i=0;i<ps.length;i++){q.push('p='+encodeURIComponent(ps[i]));}\n"
                    + "    location.href='/zip?'+q.join('&');\n"
                    + "  });}\n"
                    + "  sync();\n"
                    + "})();\n"
                    + "</script>\n";

    // ==================== 状态面板 JS ====================

    /** 轮询 /_status，刷新首页卡片与进度条 */
    private static final String LIVE_JS =
            "function fb(n){n=Number(n)||0;var u=['B','K','M','G','T'],i=0;"
                    + "while(n>=1024&&i<4){n/=1024;i++;}return (i?n.toFixed(1):n)+u[i]}\n"
                    + "function setv(k,v,u){var e=document.getElementById('v-'+k);if(e)e.textContent=v;"
                    + "var f=document.getElementById('u-'+k);if(f)f.textContent=u||''}\n"
                    + "function setb(k,p){var e=document.getElementById('b-'+k);"
                    + "if(e)e.style.width=Math.max(0,Math.min(100,p))+'%'}\n"
                    + "/* 每个盘一行进度条：按 /_status 的 disks 动态建，盘数会随插拔变化 */\n"
                    + "function renderDisks(ds){\n"
                    + "  var box=document.getElementById('diskBars');\n"
                    + "  if(!box)return;\n"
                    + "  ds=ds||[];\n"
                    + "  var sig='';\n"
                    + "  for(var i=0;i<ds.length;i++){sig+=ds[i].name+'|'}\n"
                    + "  if(box.getAttribute('data-sig')!==sig){\n"
                    + "    var h='';\n"
                    + "    for(var i=0;i<ds.length;i++){\n"
                    + "      h+='<div class=\"lrow\"><span class=\"ln\">'+fx(ds[i].name)+'</span>'\n"
                    + "        +'<div class=\"lbar\"><i id=\"b-d'+i+'\" style=\"width:0\"></i></div>'\n"
                    + "        +'<span class=\"lp\" id=\"p-d'+i+'\"></span></div>';\n"
                    + "    }\n"
                    + "    box.innerHTML=h;\n"
                    + "    box.setAttribute('data-sig',sig);\n"
                    + "  }\n"
                    + "  for(var i=0;i<ds.length;i++){\n"
                    + "    var dk=ds[i];\n"
                    + "    var used=Math.max(0,(dk.total||0)-(dk.free||0));\n"
                    + "    setb('d'+i,dk.total?(used*100/dk.total):0);\n"
                    + "    var pe=document.getElementById('p-d'+i);\n"
                    + "    if(pe)pe.textContent=fb(used)+' / '+fb(dk.total);\n"
                    + "  }\n"
                    + "}\n"
                    + "function fx(s){return String(s==null?'':s).replace(/[&<>]/g,"
                    + "function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;'}[c]})}\n"
                    + "/* fetch 是 ES6 的，老 WebView（Android 4.x）没有。改用 XMLHttpRequest，\n"
                    + "   responseType 也不设，直接 JSON.parse —— 老引擎上一样能跑。 */\n"
                    + "function tick(){\n"
                    + "  var x=new XMLHttpRequest();\n"
                    + "  x.open('GET','/_status',true);\n"
                    + "  x.onreadystatechange=function(){\n"
                    + "    if(x.readyState!==4)return;if(x.status!==200)return;\n"
                    + "    try{render(JSON.parse(x.responseText))}catch(e){}\n"
                    + "  };\n"
                    + "  try{x.send(null)}catch(e){}\n"
                    + "}\n"
                    + "function render(d){\n"
                    + "  setv('cpu',(d.cpu||0).toFixed(0)+'%',(d.cpuCores||0)+' 核 · '"
                    + "+(d.cpuFreq||0).toFixed(2)+'/'+(d.cpuFreqMax||0).toFixed(2)+'GHz');\n"
                    + "  setv('gpu',(d.gpu>=0?d.gpu.toFixed(0)+'%':'—'),"
                    + "(d.gpuFreq||0).toFixed(2)+'/'+(d.gpuFreqMax||0).toFixed(2)+'GHz');\n"
                    + "  setv('mem',fb((d.memTotal||0)-(d.memAvail||0)),'/'+fb(d.memTotal||0)+' 已用');\n"
                    + "  setv('disk',(d.disks?d.disks.length:0)+' 个','已挂载');\n"
                    + "  renderDisks(d.disks);\n"
                    + "  setv('net','↑'+fb(d.srvBps||0)+'/s','↓'+fb(d.srvRxBps||0)+'/s');\n"
                    + "  setv('temp',(d.cpuTemp>0?d.cpuTemp.toFixed(0)+'℃':'—'),"
                    + "'GPU '+(d.gpuTemp>0?d.gpuTemp.toFixed(0)+'℃':'—'));\n"
                    + "  var up=document.getElementById('l-up');"
                    // 用 serviceUptimeText（服务自身跑了多久），不是 uptimeText（开机多久）
                    + "if(up)up.textContent='已运行 '+(d.serviceUptimeText||'—');\n"
                    // 顶部大字同样是服务运行时长，跟 App 首页那块卡片同源
                    + "  var u2=document.getElementById('l-up2');"
                    + "if(u2)u2.textContent=(d.serviceUptimeText||'—');\n"
                    + "  var io=document.getElementById('l-io');"
                    + "if(io)io.textContent='↑ '+fb(d.srvBps||0)+'/s · ↓ '+fb(d.srvRxBps||0)+'/s';\n"
                    + "  var rq=document.getElementById('l-req');"
                    + "if(rq)rq.textContent='请求 '+(d.srvReq||0)+' · 已发送 '+fb(d.srvBytes||0);\n"
                    + "}\n"
                    // 页面切到后台 / 标签不可见时停掉轮询：
                    // 每次 /_status 都会触一次目录扫描，机械盘就一直被唤醒。
                    // 回到前台立刻补一次，界面不会显示过期数据。
                    + "var tickTimer=null;\n"
                    + "function startTick(){if(tickTimer===null){tick();tickTimer=setInterval(tick,5000)}}\n"
                    + "function stopTick(){if(tickTimer!==null){clearInterval(tickTimer);tickTimer=null}}\n"
                    + "document.addEventListener('visibilitychange',function(){"
                    + "if(document.hidden)stopTick();else startTick()});\n"
                    + "window.addEventListener('pagehide',stopTick);\n"
                    + "if(!document.hidden)startTick();\n";

    /**
     * 上传脚本。
     *
     * 用 XMLHttpRequest 而不是 fetch：**只有 XHR 能拿到上传进度事件**
     * （fetch 的 request body 进度至今没有稳定实现）。上传大文件时
     * 没有进度条，用户不知道是卡住了还是在传。
     *
     * 拖拽：整页监听 dragover/drop，拖进来的文件塞进同一个 input.files
     * 再提交，行为和多选完全一致，不另开一套逻辑。
     */
    private static final String UPLOAD_JS =
            "(function(){\n"
                    + "var form=document.getElementById('uplform');\n"
                    + "if(!form)return;\n"
                    + "var inp=document.getElementById('uplfile');\n"
                    + "var btn=document.getElementById('uplbtn');\n"
                    + "var hint=document.getElementById('uplhint');\n"
                    + "var prog=document.getElementById('uplprog');\n"
                    + "var bar=document.getElementById('uplbar');\n"
                    + "var busy=false;\n"
                    + "function fb(n){\n"
                    + "  n=Number(n)||0;\n"
                    + "  if(n<1024)return n+' B';\n"
                    + "  var u=['KB','MB','GB','TB'],i=-1;\n"
                    + "  do{n/=1024;i++}while(n>=1024&&i<u.length-1);\n"
                    + "  return n.toFixed(n<10?1:0)+' '+u[i];\n"
                    + "}\n"
                    // 只有一个按钮，它自己就是状态机：
                    //   idle（没选文件）→ 点它打开文件选择器，文案「⬆ 选择文件」
                    //   picked（选好了）→ 点它直接提交，文案「开始上传」
                    // 用 submit() 而不是让表单默认行为发起，是为了走 XHR 拿进度条。
                    + "if(btn)btn.onclick=function(e){\n"
                    + "  if(busy){e.preventDefault();return;}\n"
                    + "  if(!inp.files.length){e.preventDefault();inp.click();}\n"
                    + "  else{e.preventDefault();submit();}\n"
                    + "};\n"
                    + "inp.onchange=function(){\n"
                    + "  var n=inp.files.length;\n"
                    + "  if(n){\n"
                    + "    var sz=0;for(var i=0;i<n;i++)sz+=(inp.files[i].size||0);\n"
                    + "    if(hint)hint.textContent='已选 '+n+' 个文件（'+fb(sz)+'），点按钮开始上传';\n"
                    + "    if(btn)btn.textContent='⬆ 开始上传';\n"
                    + "  }else{\n"
                    + "    if(hint)hint.textContent='可以多选，支持拖拽文件到页面';\n"
                    + "    if(btn)btn.textContent='⬆ 选择文件';\n"
                    + "  }\n"
                    + "};\n"
                    // ---- 拖拽 ----
                    + "var drop=document.createElement('div');\n"
                    + "drop.className='dropmask';drop.textContent='松手即上传到当前目录';\n"
                    + "document.body.appendChild(drop);\n"
                    + "var depth=0;\n"
                    + "window.addEventListener('dragenter',function(e){\n"
                    + "  if(busy)return;\n"
                    + "  e.preventDefault();depth++;drop.classList.add('on');\n"
                    + "});\n"
                    + "window.addEventListener('dragover',function(e){e.preventDefault()});\n"
                    + "window.addEventListener('dragleave',function(e){\n"
                    + "  depth--;if(depth<=0){depth=0;drop.classList.remove('on')}\n"
                    + "});\n"
                    + "window.addEventListener('drop',function(e){\n"
                    + "  e.preventDefault();depth=0;drop.classList.remove('on');\n"
                    + "  if(busy||!e.dataTransfer||!e.dataTransfer.files.length)return;\n"
                    + "  inp.files=e.dataTransfer.files;\n"
                    + "  inp.onchange();\n"
                    + "  submit();\n"
                    + "});\n"
                    // ---- 提交 ----
                    + "function submit(){\n"
                    + "  if(busy)return;\n"
                    + "  if(!inp.files.length){if(hint)hint.textContent='还没选文件';return}\n"
                    // btn 可能因为页面结构变化而不存在，做空值保护，
                    // 否则一个小改动就能让整个上传脚本静默失效
                    + "  busy=true;\n"
                    + "  if(btn)btn.disabled=true;\n"
                    + "  if(prog)prog.classList.add('on');\n"
                    + "  var fd=new FormData(form);\n"
                    + "  var xhr=new XMLHttpRequest();\n"
                    + "  xhr.open('POST','/upload',true);\n"
                    + "  xhr.upload.onprogress=function(ev){\n"
                    + "    if(!ev.lengthComputable)return;\n"
                    + "    var p=ev.loaded*100/ev.total;\n"
                    + "    if(bar)bar.style.width=p+'%';\n"
                    + "    if(hint)hint.textContent='上传中 '+p.toFixed(0)+'%  '+fb(ev.loaded)+' / '+fb(ev.total);\n"
                    + "  };\n"
                    + "  xhr.onload=function(){\n"
                    + "    busy=false;\n"
                    + "    if(btn)btn.disabled=false;\n"
                    + "    if(bar)bar.style.width='100%';\n"
                    + "    if(xhr.status>=200&&xhr.status<400){\n"
                    + "      if(hint)hint.textContent='完成，正在刷新…';\n"
                    + "      setTimeout(function(){location.reload()},350);\n"
                    + "    }else{\n"
                    + "      if(hint)hint.textContent='上传失败（HTTP '+xhr.status+'）';\n"
                    + "      if(prog)prog.classList.remove('on');\n"
                    + "      if(bar)bar.style.width='0';\n"
                    + "    }\n"
                    + "  };\n"
                    + "  xhr.onerror=function(){\n"
                    + "    busy=false;\n"
                    + "    if(btn)btn.disabled=false;\n"
                    + "    if(hint)hint.textContent='网络中断，上传失败';\n"
                    + "    if(prog)prog.classList.remove('on');\n"
                    + "    if(bar)bar.style.width='0';\n"
                    + "  };\n"
                    + "  xhr.send(fd);\n"
                    + "}\n"
                    + "form.onsubmit=function(e){e.preventDefault();submit()};\n"
                    + "})();\n";

    // ==================== 工具 ====================

    private String relPath(File dir) {
        for (File r : liveRoots()) {
            String rp = r.getAbsolutePath();
            String dp = dir.getAbsolutePath();
            if (dp.equals(rp)) return "";
            if (dp.startsWith(rp + File.separator)) {
                return dp.substring(rp.length() + 1).replace(File.separatorChar, '/');
            }
        }
        return dir.getName();
    }

    private String rootPathOf(File dir) {
        for (File r : liveRoots()) {
            String rp = r.getAbsolutePath();
            if (dir.getAbsolutePath().equals(rp) || dir.getAbsolutePath().startsWith(rp + File.separator)) {
                return rp;
            }
        }
        return dir.getAbsolutePath();
    }

    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(i + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static boolean isImage(String ext) {
        // svg 例外：它是 image/*，但内容是可执行的 XML，塞进 <img> 之外的地方
        // 就是同源 XSS。画廊只收位图，svg 继续走「直接下载」。
        if ("svg".equals(ext)) return false;
        return MIME.containsKey(ext) && MIME.get(ext).startsWith("image/");
    }

    /**
     * 能不能给个站内预览页。
     *
     * 文本类只放行明确的纯文本格式 —— html/htm 故意排除：我们把这些文件
     * 当静态资源以 text/html 直接吐出，若再套进预览页就是同源任意 HTML
     * 执行，等于给上传权的人一个 XSS 跳板。
     */
    private static boolean canPreview(String ext) {
        if (BROWSER_PDF.contains(ext)) return true;
        if (OFFICE_DOC.contains(ext)) return true;
        if ("html".equals(ext) || "htm".equals(ext) || "svg".equals(ext)) return false;
        return PLAIN_TEXT.contains(ext);
    }

    private static String emojiFor(String ext) {
        for (String[] p : ICON) if (p[0].equals(ext)) return p[1];
        return "text";
    }

    /**
     * 内联 SVG 图标表。
     *
     * 为什么不用 emoji 也不引外部图标字体：
     * - emoji 由系统字体渲染，Android / Windows / iOS 三种字形完全不同，
     *   用户看到的「下载」「预览」按钮在别人机器上可能是另一套图案；
     * - emoji 是彩色字形，CSS 的 color 对它无效，日夜/水墨切换时那一片
     *   彩色永远不跟着变，只能靠再叠一层 filter 硬压，效果很脏。
     *
     * SVG 的 fill 用 currentColor，父元素的 color 是什么它就什么颜色，
     * 所以三种主题只要各写一条 .ic-xxx{color:...} 就够了，不需要三份图。
     * 全部 24x24 视box、stroke 统一 1.8，视觉重量一致。
     */
    private static final java.util.LinkedHashMap<String, String> SVG = new java.util.LinkedHashMap<>();
    static {
        // ---- 文件类型 ----
        SVG.put("folder",
                "<path d='M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z'/>");
        SVG.put("video",
                "<rect x='2.5' y='5' width='19' height='14' rx='2.5'/>"
                        + "<path d='M10 9.2v5.6l5-2.8z' fill='currentColor' stroke='none'/>");
        SVG.put("music",
                "<path d='M9 18V6l10-2v12'/><circle cx='7' cy='18' r='2.4'/>"
                        + "<circle cx='17' cy='16' r='2.4'/>");
        SVG.put("lyric",
                "<rect x='3.5' y='3.5' width='17' height='17' rx='2.5'/>"
                        + "<path d='M7 8.5h10M7 12h10M7 15.5h6'/>");
        SVG.put("image",
                "<rect x='3' y='4.5' width='18' height='15' rx='2.5'/>"
                        + "<circle cx='8.5' cy='10' r='1.6'/>"
                        + "<path d='M3.6 17.2l5-4.6 4 3.6 3.2-2.8 4.6 4'/>");
        SVG.put("zip",
                "<path d='M4.5 6.5a2 2 0 0 1 2-2h11a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2h-11a2 2 0 0 1-2-2z'/>"
                        + "<path d='M10 4.5v4M12 8.5v4M10 12.5v3.5M12 16v4'/>");
        SVG.put("android",
                "<rect x='6.5' y='3.5' width='11' height='17' rx='2.5'/>"
                        + "<path d='M10 7h4'/><circle cx='12' cy='17' r='.9' fill='currentColor' stroke='none'/>");
        SVG.put("pdf",
                "<path d='M6 3.5h7.5L19 9v11.5H6z'/><path d='M13.5 3.5V9H19'/>"
                        + "<path d='M8.8 15.6c1.6-.6 3.4-1.7 4.6-3 1-1.1 1.6-2.3 1.1-2.9"
                        + "-.5-.6-1.5 0-1.2 1.4.3 1.5 1.7 3 3.1 3.6'/>");
        SVG.put("book",
                "<path d='M4 5.5A2.5 2.5 0 0 1 6.5 3H11v17H6.5A2.5 2.5 0 0 1 4 17.5z'/>"
                        + "<path d='M20 5.5A2.5 2.5 0 0 0 17.5 3H13v17h4.5A2.5 2.5 0 0 0 20 17.5z'/>");
        SVG.put("text",
                "<path d='M6 3.5h7.5L19 9v11.5H6z'/><path d='M13.5 3.5V9H19'/>"
                        + "<path d='M9 12.5h7M9 16h5'/>");
        SVG.put("word",
                "<path d='M6 3.5h7.5L19 9v11.5H6z'/><path d='M13.5 3.5V9H19'/>"
                        + "<path d='M9 12h1.6l1 4.4 1.2-3.2h.9l1.2 3.2 1-4.4H17'/>");
        SVG.put("sheet",
                "<path d='M6 3.5h7.5L19 9v11.5H6z'/><path d='M13.5 3.5V9H19'/>"
                        + "<path d='M8.8 11.5h6.4v6.4H8.8z M8.8 14.7h6.4 M11.9 11.5v6.4'/>");
        SVG.put("slide",
                "<path d='M6 3.5h7.5L19 9v11.5H6z'/><path d='M13.5 3.5V9H19'/>"
                        + "<rect x='8.8' y='11.6' width='6.4' height='4.6' rx='.7'/>"
                        + "<path d='M12 16.2v1.8'/>");

        // ---- 行内动作 ----
        SVG.put("play",
                "<path d='M8 5.4v13.2l11-6.6z' fill='currentColor' stroke='none'/>");
        SVG.put("eye",
                "<path d='M2.5 12S6 5.8 12 5.8 21.5 12 21.5 12 18 18.2 12 18.2 2.5 12 2.5 12z'/>"
                        + "<circle cx='12' cy='12' r='3'/>");
        SVG.put("download",
                "<path d='M12 3.5v11'/><path d='M7.4 10.2L12 15l4.6-4.8'/>"
                        + "<path d='M4.5 17.5v1.5a1.5 1.5 0 0 0 1.5 1.5h12a1.5 1.5 0 0 0 1.5-1.5v-1.5'/>");
        SVG.put("edit",
                "<path d='M16.2 3.8l4 4L9 19H5v-4z'/><path d='M13.6 6.4l4 4'/>");
        SVG.put("move",
                "<path d='M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z'/>"
                        + "<path d='M9.6 13.6h4.8M12.6 11.4l2.2 2.2-2.2 2.2'/>");
        SVG.put("trash",
                "<path d='M4.5 6.5h15'/><path d='M9.5 6.5V4.4h5v2.1'/>"
                        + "<path d='M6.4 6.5l.9 12.2a2 2 0 0 0 2 1.8h5.4a2 2 0 0 0 2-1.8l.9-12.2'/>"
                        + "<path d='M10.2 10v6.6M13.8 10v6.6'/>");
        SVG.put("search",
                "<circle cx='10.5' cy='10.5' r='6.2'/><path d='M15.2 15.2l4.6 4.6'/>");
        SVG.put("home",
                "<path d='M3.5 10.5L12 3.6l8.5 6.9'/><path d='M6 9.4V19a1.4 1.4 0 0 0 1.4 1.4h9.2A1.4 1.4 0 0 0 18 19V9.4'/>"
                        + "<path d='M10 20.4v-5.2h4v5.2'/>");
        SVG.put("phone",
                "<rect x='6.5' y='2.8' width='11' height='18.4' rx='2.5'/>"
                        + "<path d='M10.4 5.4h3.2'/><circle cx='12' cy='18' r='.9' fill='currentColor' stroke='none'/>");
        SVG.put("drive",
                "<rect x='3' y='6' width='18' height='12' rx='2.4'/>"
                        + "<circle cx='7.4' cy='12' r='1.1' fill='currentColor' stroke='none'/>"
                        + "<path d='M10.5 12h7'/>");
        SVG.put("arrowUp",
                "<path d='M12 20V5'/><path d='M5.6 11.4L12 5l6.4 6.4'/>");
    }

    /** 取一个图标的 SVG 标记；不认识的键回落到 text。 */
    static String svgIcon(String key, String cls) {
        String body = SVG.get(key);
        if (body == null) body = SVG.get("text");
        return "<svg class='svgi " + cls + "' viewBox='0 0 24 24' aria-hidden='true'>"
                + body + "</svg>";
    }

    /** 表格行首的文件类型图标。 */
    static String typeIcon(boolean isDir, String ext) {
        return svgIcon(isDir ? "folder" : emojiFor(ext), "svgi-type");
    }

    static String mimeOf(String name) {
        String ext = extOf(name);
        String m = MIME.get(ext);
        return m != null ? m : "application/octet-stream";
    }

    static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024L * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / 1048576.0);
        return String.format(Locale.US, "%.2f GB", b / 1073741824.0);
    }

    static String encode(String s) {
        try {
            // 保留 / ，编码其余
            return URLEncoder.encode(s, "UTF-8")
                    .replace("%2F", "/")
                    .replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String xmlEsc(String s) { return esc(s); }

    /**
     * 把字符串包成 JS 字符串字面量。
     *
     * 和 jsonEsc 的区别：这里还要防 {@code </script>} 提前闭合脚本标签 ——
     * 文件名里带这个子串是完全合法的，但直接写进内联 script 会把页面切断。
     * 顺便把 U+2028 / U+2029 转义掉，它们在新版 ES 里算行终止符。
     */
    static String jsStr(String s) {
        if (s == null) s = "";
        String j = jsonEsc(s);
        j = j.replace("</", "<\\/");
        j = j.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
        return "\"" + j + "\"";
    }

    static String jsonEsc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    static String httpDate() { return httpDate(System.currentTimeMillis()); }

    static String httpDate(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f.format(new Date(ms));
    }

    private void sendHtml(OutputStream out, int code, String html) throws IOException {
        sendHtml(out, code, html, null);
    }

    /**
     * @param extraHeaders 额外响应头（每项不含 CRLF）。用来补 Set-Cookie 之类
     *                     原先只有 303 分支才写得出来的头。
     */
    private void sendHtml(OutputStream out, int code, String html, String extraHeaders)
            throws IOException {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        String reason = code == 200 ? "OK" : (code == 401 ? "Unauthorized" : "Error");
        // 页面本身必须禁缓存。
        //
        // 浏览器（尤其 Via 这种 WebView 壳）会把 "no-cache" 理解成「可以存，用前校验」，
        // 而我们要的是彻底不存 —— 页面 HTML 里带着主题脚本、上传脚本、渲染库引用，
        // 发一次新版就得立刻生效。之前就是 no-cache 让旧页面活了下来：
        // 服务端已经换了主题逻辑，手机里点按钮还是没反应，白查半天。
        // no-store 才是「不许写进任何缓存」；Pragma/Expires 是给老 HTTP/1.0 代理的。
        out.write(("HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Server: " + cfg.title + "\r\n"
                + "Date: " + httpDate() + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n"
                + "Pragma: no-cache\r\n"
                + "Expires: 0\r\n"
                + (extraHeaders == null ? "" : extraHeaders)
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private void sendHtml(OutputStream out, String html) throws IOException {
        sendHtml(out, 200, html);
    }

    /**
     * 把 APK assets 里的文件直接吐给浏览器。
     *
     * 这些脚本体积不小（xlsx.full.min.js 约 900KB），所以：
     * 1. 允许浏览器缓存一天 —— 只要不改 APK，每次打开预览页都走 304；
     * 2. 只在宿主侧读一次就整块 write，不做任何字符串转换（二进制安全）。
     *
     * assets 里没有这个文件时回 404，前端脚本的 onerror 会接住并退回 B 层。
     */
    private void sendAsset(OutputStream out, String name, String mime) throws IOException {
        // 挡掉 ../ 之类的路径穿越。assets 本身不支持子目录遍历，但入口参数是外部输入
        if (name == null || name.isEmpty() || name.contains("..") || name.contains("/")
                || name.contains("\\")) {
            sendText(out, 404, "Not Found", "text/plain");
            return;
        }
        android.content.Context ctx = cfg.appContext;
        if (ctx == null) {
            sendText(out, 503, "assets unavailable", "text/plain");
            return;
        }
        byte[] data;
        try (java.io.InputStream ins = ctx.getAssets().open("docview/" + name)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = ins.read(buf)) > 0) bos.write(buf, 0, n);
            data = bos.toByteArray();
        } catch (Exception e) {
            sendText(out, 404, "Not Found: " + name, "text/plain");
            return;
        }
        out.write(("HTTP/1.1 200 OK\r\n"
                + "Server: " + cfg.title + "\r\n"
                + "Date: " + httpDate() + "\r\n"
                + "Content-Type: " + mime + "; charset=utf-8\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Cache-Control: public, max-age=86400\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    private void sendText(OutputStream out, int code, String body, String mime) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + code + " Error\r\n"
                + "Server: " + cfg.title + "\r\n"
                + "Content-Type: " + mime + "; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    /** 表单登录页 */
    private String renderLogin(String presetUser, String error) {
        return renderLogin(presetUser, error, "/");
    }

    // ==================== 网页端管理页 ====================

    /**
     * GET /_admin  —— 管理页
     * POST /_admin —— 提交表单（action=music / video / title）
     *
     * 为什么不在 App 里做：目录这种东西一台设备配一次就够，但配它的人
     * 往往正坐在电脑前整理文件。网页端已经能浏览全盘目录了，顺手把
     * 「指目录」这件事放在同一处，比让人拿起手机去点一堆多选框自然。
     *
     * 权限：必须登录且 level >= LEVEL_ADMIN。没有账号（单机模式）时
     * 一律拒绝 —— 这种模式本来就是匿名全开，再给个管理页等于把
     * 「谁都能改配置」摆到明面上。
     */
    private void handleAdmin(Request req, InputStream in, OutputStream out) throws IOException {
        boolean logged = req.user != null;
        boolean isAdmin = logged && req.user.level >= Users.LEVEL_ADMIN;

        if ("POST".equalsIgnoreCase(req.method)) {
            if (!isAdmin) {
                sendHtml(out, 403, adminPage(req, "需要管理员账号才能修改设置"));
                return;
            }
            Map<String, String> form = readForm(in, req.contentLength());
            String action = form.containsKey("action") ? form.get("action") : "";
            String msg;
            if ("music".equals(action)) {
                List<String> picked = splitDirs(form.get("dirs"));
                cfg.setMusicDirs(picked);
                AppPrefs.setMusicDirs(appCtx(), picked);
                msg = "音乐目录已保存：" + picked.size() + " 个";
                log("管理页：音乐目录设为 " + picked.size() + " 个");
            } else if ("video".equals(action)) {
                List<String> picked = splitDirs(form.get("dirs"));
                cfg.setVideoDirs(picked);
                AppPrefs.setVideoDirs(appCtx(), picked);
                msg = "影视目录已保存：" + picked.size() + " 个";
                log("管理页：影视目录设为 " + picked.size() + " 个");
            } else if ("book".equals(action)) {
                List<String> picked = splitDirs(form.get("dirs"));
                cfg.setBookDirs(picked);
                AppPrefs.setBookDirs(appCtx(), picked);
                msg = "图书目录已保存：" + picked.size() + " 个";
                log("管理页：图书目录设为 " + picked.size() + " 个");
            } else if ("photo".equals(action)) {
                List<String> picked = splitDirs(form.get("dirs"));
                cfg.setPhotoDirs(picked);
                AppPrefs.setPhotoDirs(appCtx(), picked);
                msg = "相册目录已保存：" + picked.size() + " 个";
                log("管理页：相册目录设为 " + picked.size() + " 个");
            } else if ("title".equals(action)) {
                String t = form.containsKey("title") ? form.get("title") : "";
                cfg.setTitle(t);
                AppPrefs.saveTitle(appCtx(), t);
                msg = "站点名称已保存";
            } else if ("update".equals(action)) {
                // 表单自己带目标状态（on=1 打开 / on=0 关闭），不靠服务端猜当前值：
                // 两个标签页同时开着时，猜出来的结果会和用户点的那个相反。
                String on = form.containsKey("on") ? form.get("on") : "";
                boolean want = "1".equals(on);
                AppPrefs.setAutoUpdate(appCtx(), want);
                msg = want ? "自动更新已打开" : "自动更新已关闭";
                log("管理页：自动更新 " + (want ? "开" : "关"));
            } else if ("unskip".equals(action)) {
                AppPrefs.setSkipVersion(appCtx(), "");
                msg = "已清除跳过标记";
            } else {
                msg = "未知操作";
            }
            // 303 回自己：表单重提会重复保存，刷新要看到的是结果页不是「确认重提」
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 303 See Other\r\n");
            sb.append("Location: /_admin?ok=").append(encode(msg)).append("\r\n");
            sb.append("Content-Length: 0\r\nConnection: close\r\n\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            return;
        }

        String ok = req.param("ok");
        String err = null;
        if (!isAdmin) {
            err = logged ? "当前账号是「" + Users.levelName(req.user.level) + "」，改设置需要管理员"
                    : "请先用管理员账号登录";
        }
        sendHtml(out, 200, adminPage(req, err != null ? err : ok));
    }

    private android.content.Context appCtx() {
        return cfg.appContext;
    }

    /**
     * GET  /_admin/api            —— 读当前配置 + 候选目录 + 权限等级
     * POST /_admin/api            —— 保存（action=title/music/video，dirs 用换行分隔）
     *
     * 这是给两个原生客户端用的：网页端已经能做同样的事，但客户端里点几下
     * 比开浏览器顺手得多，而且客户端本来就知道自己连的是哪台 NAS。
     *
     * 与 /_admin 共用同一套权限判定，绝不因为「是客户端」就放宽。
     */
    private void handleAdminApi(Request req, InputStream in, OutputStream out) throws IOException {
        boolean logged = req.user != null;
        boolean isAdmin = logged && req.user.level >= Users.LEVEL_ADMIN;

        if ("POST".equalsIgnoreCase(req.method)) {
            if (!isAdmin) {
                sendJson(out, logged
                        ? "{\"ok\":false,\"error\":\"当前账号不能改设置，需要管理员\"}"
                        : "{\"ok\":false,\"error\":\"请先登录管理员账号\"}");
                return;
            }
            Map<String, String> form = readForm(in, req.contentLength());
            String action = form.containsKey("action") ? form.get("action") : "";
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            if ("music".equals(action)) {
                List<String> picked = splitDirs(form.get("dirs"));
                cfg.setMusicDirs(picked);
                AppPrefs.setMusicDirs(appCtx(), picked);
                log("客户端管理：音乐目录设为 " + picked.size() + " 个");
                sb.append("\"ok\":true,\"msg\":\"音乐目录已保存：").append(picked.size()).append(" 个\"");
            } else if ("video".equals(action)) {
                List<String> picked = splitDirs(form.get("dirs"));
                cfg.setVideoDirs(picked);
                AppPrefs.setVideoDirs(appCtx(), picked);
                log("客户端管理：影视目录设为 " + picked.size() + " 个");
                sb.append("\"ok\":true,\"msg\":\"影视目录已保存：").append(picked.size()).append(" 个\"");
            } else if ("book".equals(action)) {
                List<String> picked = splitDirs(form.get("dirs"));
                cfg.setBookDirs(picked);
                AppPrefs.setBookDirs(appCtx(), picked);
                log("客户端管理：图书目录设为 " + picked.size() + " 个");
                sb.append("\"ok\":true,\"msg\":\"图书目录已保存：").append(picked.size()).append(" 个\"");
            } else if ("photo".equals(action)) {
                List<String> picked = splitDirs(form.get("dirs"));
                cfg.setPhotoDirs(picked);
                AppPrefs.setPhotoDirs(appCtx(), picked);
                log("客户端管理：相册目录设为 " + picked.size() + " 个");
                sb.append("\"ok\":true,\"msg\":\"相册目录已保存：").append(picked.size()).append(" 个\"");
            } else if ("title".equals(action)) {
                String t = form.containsKey("title") ? form.get("title") : "";
                cfg.setTitle(t);
                AppPrefs.saveTitle(appCtx(), t);
                log("客户端管理：站点名称设为 " + cfg.title);
                sb.append("\"ok\":true,\"msg\":\"站点名称已保存\"");
            } else if ("update".equals(action)) {
                // 表单自带目标状态，理由同 /_admin 那一段
                boolean want = "1".equals(form.containsKey("on") ? form.get("on") : "");
                AppPrefs.setAutoUpdate(appCtx(), want);
                log("客户端管理：自动更新 " + (want ? "开" : "关"));
                sb.append("\"ok\":true,\"msg\":\"").append(want ? "自动更新已打开" : "自动更新已关闭").append('"');
            } else if ("unskip".equals(action)) {
                AppPrefs.setSkipVersion(appCtx(), "");
                sb.append("\"ok\":true,\"msg\":\"已清除跳过标记\"");
            } else {
                sb.append("\"ok\":false,\"error\":\"未知操作\"");
            }
            sb.append('}');
            sendJson(out, sb.toString());
            return;
        }

        // GET：非管理员也给一份最小信息，客户端好据此显示「需要管理员」而不是报错
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true");
        sb.append(",\"admin\":").append(isAdmin);
        sb.append(",\"logged\":").append(logged);
        sb.append(",\"who\":\"").append(jsonEsc(req.who())).append('"');
        sb.append(",\"level\":").append(logged ? req.user.level : -1);
        sb.append(",\"levelName\":\"").append(jsonEsc(logged ? Users.levelName(req.user.level) : "匿名")).append('"');
        if (isAdmin) {
            sb.append(",\"title\":\"").append(jsonEsc(cfg.title)).append('"');
            sb.append(",\"musicDirs\":").append(jsonArr(cfg.musicDirs));
            sb.append(",\"videoDirs\":").append(jsonArr(cfg.videoDirs));
            sb.append(",\"bookDirs\":").append(jsonArr(cfg.bookDirs));
            sb.append(",\"photoDirs\":").append(jsonArr(cfg.photoDirs));
            sb.append(",\"dirs\":").append(dirChoicesJson(candidateDirs()));
        }
        // 版本与自动更新状态不放在 isAdmin 分支里：
        // 普通只读账号也该知道自己连的是什么版本的服务端。
        sb.append(",\"version\":\"").append(jsonEsc(APP_VER)).append('"');
        sb.append(",\"autoUpdate\":").append(AppPrefs.autoUpdate(appCtx()));
        sb.append(",\"skipVersion\":\"").append(jsonEsc(AppPrefs.skipVersion(appCtx()))).append('"');
        sb.append(",\"lastUpdateCheck\":").append(AppPrefs.lastUpdateCheck(appCtx()));
        sb.append('}');
        sendJson(out, sb.toString());
    }

    private static String jsonArr(List<String> arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(jsonEsc(arr.get(i))).append('"');
        }
        return sb.append(']').toString();
    }

    private static String dirChoicesJson(List<DirChoice> cands) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cands.size(); i++) {
            DirChoice d = cands.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"p\":\"").append(jsonEsc(d.path)).append("\",\"n\":\"")
              .append(jsonEsc(d.label)).append("\"}");
        }
        return sb.append(']').toString();
    }

    /** 表单里 dirs 用换行分隔（textarea 一行一个目录） */
    private static List<String> splitDirs(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (String line : raw.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            while (t.length() > 1 && t.endsWith("/")) t = t.substring(0, t.length() - 1);
            if (seen.add(t)) out.add(t);
        }
        return out;
    }

    /** 一块可点的目录：给管理页的「候选目录」列表用 */
    private static final class DirChoice {
        final String path;
        final String label;
        DirChoice(String path, String label) {
            this.path = path;
            this.label = label;
        }
    }

    /**
     * 树上的一个节点。
     *
     * 候选列表本身是「各存储根下两层」的扁平数组，但管理页上要的是树：
     *   /storage/emulated/0
     *   /storage/emulated/0/DCIM
     *   /storage/emulated/0/DCIM/Camera
     * 平铺成 40 个标签块时，Camera 和 Music 看着是同级，谁属于谁全靠读路径。
     * 这里按路径前缀重组成树，节点自己带 label 与深度，渲染层只管画。
     */
    private static final class DirNode {
        final String path;
        final String name;
        final int depth;
        boolean checked;
        /** 已配置但候选里没有这个目录：标出来，免得用户以为配置丢了 */
        boolean configuredOnly;
        final List<DirNode> kids = new ArrayList<>();
        DirNode(String path, String name, int depth) {
            this.path = path;
            this.name = name;
            this.depth = depth;
        }
    }

    /**
     * 列出「可能想选」的目录：各存储根下的两层子目录。
     *
     * 只下探两层是刻意的 —— 全盘递归枚举在机械盘上要几十秒，管理页打开
     * 就转圈没人受得了。真正深层的位置让管理员在下面的文本框里手写。
     */
    private List<DirChoice> candidateDirs() {
        List<DirChoice> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (File root : liveRoots()) {
            String rn = SysInfo.isInternal(root) ? "内部存储" : root.getName();
            out.add(new DirChoice(root.getAbsolutePath(), rn));
            seen.add(root.getAbsolutePath());
            File[] kids = root.listFiles();
            if (kids == null) continue;
            Arrays.sort(kids, new java.util.Comparator<File>() {
                public int compare(File a, File b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File k : kids) {
                if (!k.isDirectory() || k.getName().startsWith(".")) continue;
                String p1 = k.getAbsolutePath();
                if (seen.add(p1)) out.add(new DirChoice(p1, rn + " / " + k.getName()));
                File[] sub = k.listFiles();
                if (sub == null) continue;
                Arrays.sort(sub, new java.util.Comparator<File>() {
                    public int compare(File a, File b) {
                        return a.getName().compareToIgnoreCase(b.getName());
                    }
                });
                for (File s : sub) {
                    if (!s.isDirectory() || s.getName().startsWith(".")) continue;
                    String p2 = s.getAbsolutePath();
                    if (seen.add(p2)) out.add(new DirChoice(p2, rn + " / " + k.getName() + " / " + s.getName()));
                }
            }
        }
        return out;
    }

    /**
     * 把扁平候选列表折成树。
     *
     * 根键规则要与客户端 DirTree.rootKeyOf() 一致：/storage/emulated/0 与
     * /mnt/media_rw/XXXX 都整体当一个根，否则内部存储会被拆成
     * storage → emulated → 0 三层，点开三次才看到一个目录。
     */
    private List<DirNode> buildDirTree(List<DirChoice> cands, List<String> current) {
        List<DirNode> roots = new ArrayList<>();
        java.util.Map<String, DirNode> index = new java.util.HashMap<>();

        for (DirChoice c : cands) {
            String path = c.path;
            if (path == null || path.isEmpty()) continue;
            String rk = dirRootKey(path);
            DirNode root = index.get(rk);
            if (root == null) {
                String rn = dirRootLabel(rk, c.label);
                root = new DirNode(rk, rn, 0);
                index.put(rk, root);
                roots.add(root);
            }
            if (path.equals(rk)) continue;
            // 逐段建链：/storage/emulated/0/DCIM/Camera → 先保 DCIM 再挂 Camera
            String acc = rk;
            DirNode parent = root;
            String[] seg = path.substring(Math.min(path.length(), rk.length()))
                    .split("/");
            for (String s : seg) {
                if (s.isEmpty()) continue;
                acc = acc + "/" + s;
                DirNode n = index.get(acc);
                if (n == null) {
                    n = new DirNode(acc, s, parent.depth + 1);
                    index.put(acc, n);
                    parent.kids.add(n);
                }
                parent = n;
            }
        }

        // 配置里有、候选里没有的路径：补成一支并标出来。之前这种情况在网页上
        // 完全看不到（文本框里有，勾选列表里没有），管理员会以为没保存成功。
        if (current != null) {
            for (String raw : current) {
                if (raw == null) continue;
                String p = raw.trim();
                while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
                if (p.isEmpty()) continue;
                if (index.containsKey(p)) {
                    index.get(p).checked = true;
                    continue;
                }
                String rk = dirRootKey(p);
                DirNode root = index.get(rk);
                if (root == null) {
                    root = new DirNode(rk, dirRootLabel(rk, ""), 0);
                    index.put(rk, root);
                    roots.add(root);
                }
                DirNode n = new DirNode(p, dirLastSeg(p), root.depth + 1);
                n.configuredOnly = true;
                n.checked = true;
                root.kids.add(n);
                index.put(p, n);
            }
        }

        // 勾了父级就不必再显示子级的勾 —— 子级全部跟着亮，页面会像一片蓝
        for (DirNode r : roots) pruneChecked(r);
        for (DirNode r : roots) sortTree(r);
        return roots;
    }

    /** 父级已勾选时，子级的 checked 归零（值仍由文本框决定） */
    private void pruneChecked(DirNode n) {
        for (DirNode k : n.kids) {
            if (n.checked) k.checked = false;
            pruneChecked(k);
        }
    }

    private void sortTree(DirNode n) {
        java.util.Collections.sort(n.kids, new java.util.Comparator<DirNode>() {
            public int compare(DirNode a, DirNode b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        for (DirNode k : n.kids) sortTree(k);
    }

    /** /storage/emulated/0 与 /mnt/media_rw/XXXX 整体当根 */
    private static String dirRootKey(String path) {
        String p = path;
        if (p.startsWith("/storage/emulated/") && p.length() > "/storage/emulated/0".length()) {
            int i = p.indexOf('/', "/storage/emulated/".length());
            return i < 0 ? p : p.substring(0, i);
        }
        if (p.startsWith("/mnt/")) {
            int a = p.indexOf('/', 5);
            if (a < 0) return p;
            int b = p.indexOf('/', a + 1);
            return b < 0 ? p : p.substring(0, b);
        }
        int i = p.indexOf('/', 1);
        return i < 0 ? p : p.substring(0, i);
    }

    private static String dirRootLabel(String key, String serverLabel) {
        if (key.startsWith("/storage/emulated/")) return "内部存储";
        if (key.startsWith("/mnt/media_rw/")) {
            int i = key.lastIndexOf('/');
            return "外部盘 " + key.substring(i + 1);
        }
        if (serverLabel != null && !serverLabel.isEmpty()) return serverLabel;
        int i = key.lastIndexOf('/');
        return i < 0 ? key : key.substring(i + 1);
    }

    private static String dirLastSeg(String p) {
        int i = p.lastIndexOf('/');
        return i < 0 ? p : p.substring(i + 1);
    }

    /** 渲染一棵树。默认只展开有勾选的那几支，其余收起。 */
    private void appendDirNodes(StringBuilder sb, List<DirNode> nodes, java.util.Set<String> open) {
        for (DirNode n : nodes) {
            boolean hasKids = !n.kids.isEmpty();
            boolean unfolded = open.contains(n.path);
            sb.append("<div class='adm-tn").append(n.checked ? " on" : "")
              .append(n.configuredOnly ? " cfg" : "")
              .append("' style='padding-left:").append(8 + n.depth * 16).append("px'>")
              .append("<span class='adm-tw").append(hasKids ? "" : " leaf").append("'")
              .append(hasKids ? " data-toggle='" + esc(n.path) + "'" : "")
              .append(">").append(hasKids ? (unfolded ? "▾" : "▸") : " ").append("</span>")
              .append("<input type='checkbox' data-dir='").append(esc(n.path)).append("'")
              .append(n.checked ? " checked" : "").append(">")
              .append("<span class='adm-tl' data-toggle='").append(hasKids ? esc(n.path) : "")
              .append("'>").append(esc(n.name)).append("</span>");
            if (n.depth == 0) {
                sb.append("<span class='adm-tp'>").append(esc(n.path)).append("</span>");
            } else if (n.configuredOnly) {
                sb.append("<span class='adm-tc'>已配置</span>");
            }
            sb.append("</div>\n");
            if (hasKids) {
                sb.append("<div class='adm-tk").append(unfolded ? "" : " hide")
                  .append("' data-kids='").append(esc(n.path)).append("'>\n");
                appendDirNodes(sb, n.kids, open);
                sb.append("</div>\n");
            }
        }
    }

    /** 有勾选的节点及其祖先都要展开，否则用户一进来看不到自己配了什么 */
    private void collectOpen(List<DirNode> nodes, List<String> current, java.util.Set<String> open) {
        for (DirNode n : nodes) {
            boolean hit = false;
            for (String c : current) {
                if (c == null) continue;
                String p = c.trim();
                if (p.equals(n.path) || p.startsWith(n.path + "/")) { hit = true; break; }
            }
            if (hit && !n.path.isEmpty()) open.add(n.path);
            collectOpen(n.kids, current, open);
        }
    }

    /** 把已配目录渲染成「目录树 + 手写文本框」两块 */
    private void appendDirPicker(StringBuilder sb, String name, String title, String hint,
                                 List<String> current, List<DirChoice> cands, int limit) {
        sb.append("<div class='adm-sect'>\n");
        sb.append("<div class='adm-h'>").append(esc(title)).append("</div>\n");
        sb.append("<div class='adm-hint'>").append(hint).append("</div>\n");

        List<DirNode> tree = buildDirTree(cands, current);
        java.util.Set<String> open = new java.util.HashSet<>();
        collectOpen(tree, current, open);

        // 一块都没选时整棵树是「全收起」，用户会以为没得选 —— 这种情况直接
        // 把根节点全部展开（只展开根这一层，子目录仍然要自己点）。
        boolean anyChecked = !current.isEmpty();
        sb.append("<div class='adm-tree' data-all='").append(anyChecked ? "0" : "1").append("'>\n");
        sb.append("<div class='adm-tbar'>")
          .append("<span class='adm-tcnt'>已选 ").append(current.size()).append(" 个</span>")
          .append("<span class='adm-tgl' data-act='all'>展开全部</span>")
          .append("<span class='adm-tgl' data-act='none'>收起全部</span>")
          .append("<span class='adm-tgl' data-act='sel'>只看已选</span>")
          .append("</div>\n");
        if (tree.isEmpty()) {
            sb.append("<div class='adm-more'>服务端没列出候选目录，直接在下面的框里手写路径也行。</div>\n");
        } else {
            appendDirNodes(sb, tree, open);
        }
        sb.append("</div>\n");

        // 手写文本框：一行一个；树上的勾只负责同步进这里，
        // 真正的存储格式以文本为准 —— 脚本没跑起来时文本框照样能提交。
        sb.append("<textarea class='adm-ta' name='").append(name).append("' rows='4' ")
          .append("placeholder='/storage/emulated/0/Music&#10;/mnt/media_rw/XXXX/电影'>")
          .append(esc(joinLines(current))).append("</textarea>\n");
        sb.append("</div>\n");
    }

    private static String joinLines(List<String> arr) {
        StringBuilder sb = new StringBuilder();
        for (String s : arr) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * 管理页 HTML。
     *
     * 三套主题都靠 CSS 里的 body.light / body.ink 前缀覆盖，这里只写深色基准值 ——
     * 与全站其余页面同一套路，新增类名必须同时在 LIGHT_CSS 和 INK_CSS 里补一份。
     */
    private String adminPage(Request req, String notice) {
        boolean isAdmin = req.user != null && req.user.level >= Users.LEVEL_ADMIN;
        List<DirChoice> cands = isAdmin ? candidateDirs() : new ArrayList<DirChoice>();

        StringBuilder sb = new StringBuilder();
        htmlHead(sb, "管理", prefTheme);
        sb.append("<div class='wrap'>\n");
        sb.append("<header><a class='brand' href='/'><span class='dot'></span>")
          .append(esc(cfg.title)).append("</a><div class='hact'>")
          .append(themeButton("")).append(inkButton(""))
          .append("</div></header>\n");

        sb.append("<div class='adm-top'><h2>网页端管理</h2>")
          .append("<a class='adm-back' href='/'>返回文件列表</a></div>\n");

        if (notice != null && !notice.isEmpty()) {
            sb.append("<div class='adm-note'>").append(esc(notice)).append("</div>\n");
        }
        if (!isAdmin) {
            sb.append("<div class='login' style='max-width:420px'>")
              .append("<div class='sub'>这个页面要用管理员账号登录后才能打开。</div>")
              .append("<a href='/_login?back=/_admin' style='color:#5aa9ff'>去登录</a>")
              .append("</div>\n");
            sb.append("</div>\n");
            htmlFoot(sb);
            return sb.toString();
        }

        sb.append("<div class='adm-grid'>\n");

        // ---- 站点名称 ----
        sb.append("<div class='adm-sect'>\n");
        sb.append("<div class='adm-h'>站点名称</div>\n");
        sb.append("<div class='adm-hint'>浏览器标题栏、客户端扫描结果里显示的名字。</div>\n");
        sb.append("<form method='post' action='/_admin' class='adm-form'>")
          .append("<input type='hidden' name='action' value='title'>")
          .append("<input class='adm-inp' name='title' maxlength='24' value='")
          .append(esc(cfg.title)).append("'>")
          .append("<button class='adm-btn' type='submit'>保存名称</button>")
          .append("</form>\n");
        sb.append("</div>\n");

        // ---- 音乐目录 ----
        sb.append("<form method='post' action='/_admin' class='adm-form'>")
          .append("<input type='hidden' name='action' value='music'>\n");
        appendDirPicker(sb, "dirs", "音乐目录",
                "客户端「音乐」页只显示这里的歌。可以指多个目录，也能指单个专辑目录。",
                cfg.musicDirs, cands, 160);
        sb.append("<div class='adm-act'><button class='adm-btn' type='submit'>保存音乐目录</button>")
          .append("<span class='adm-cnt'>当前 ").append(cfg.musicDirs.size()).append(" 个</span>")
          .append("</div></form>\n");

        // ---- 影视目录 ----
        sb.append("<form method='post' action='/_admin' class='adm-form'>")
          .append("<input type='hidden' name='action' value='video'>\n");
        appendDirPicker(sb, "dirs", "影视目录",
                "电影板块会按这里分块显示（盘0 一块、盘1 一块）。刮削信息读目录里的 .nfo。",
                cfg.videoDirs, cands, 160);
        sb.append("<div class='adm-act'><button class='adm-btn' type='submit'>保存影视目录</button>")
          .append("<span class='adm-cnt'>当前 ").append(cfg.videoDirs.size()).append(" 个</span>")
          .append("</div></form>\n");

        // ---- 图书目录 ----
        sb.append("<form method='post' action='/_admin' class='adm-form'>")
          .append("<input type='hidden' name='action' value='book'>\n");
        appendDirPicker(sb, "dirs", "图书目录",
                "客户端「图书」页只显示这里的书。认 epub/pdf/mobi/azw3/fb2/djvu/cbz/cbr/txt/md；"
                        + "不配就没有书库（不会去扫全盘找书）。",
                cfg.bookDirs, cands, 160);
        sb.append("<div class='adm-act'><button class='adm-btn' type='submit'>保存图书目录</button>")
          .append("<span class='adm-cnt'>当前 ").append(cfg.bookDirs.size()).append(" 个</span>")
          .append("</div></form>\n");

        // ---- 相册目录 ----
        sb.append("<form method='post' action='/_admin' class='adm-form'>")
          .append("<input type='hidden' name='action' value='photo'>\n");
        appendDirPicker(sb, "dirs", "相册目录",
                "客户端「相册」页只显示这里的图。认 jpg/jpeg/png/webp/bmp/gif/heic；"
                        + "不配就没有相册（不会去扫全盘找图，手机会被图标和缓存淹没）。",
                cfg.photoDirs, cands, 160);
        sb.append("<div class='adm-act'><button class='adm-btn' type='submit'>保存相册目录</button>")
          .append("<span class='adm-cnt'>当前 ").append(cfg.photoDirs.size()).append(" 个</span>")
          .append("</div></form>\n");

        sb.append("</div>\n");   // adm-grid

        // ---- 自动更新 ----
        // 单独一块、不塞进上面列目录的网格：它是「这台机器自己要不要联网」，
        // 与「把哪个盘共享出去」不是一类事。默认关闭的状态在按钮文字里就能读到。
        boolean upOn = AppPrefs.autoUpdate(appCtx());
        long upLast = AppPrefs.lastUpdateCheck(appCtx());
        String upSkip = AppPrefs.skipVersion(appCtx());
        sb.append("<div class='adm-sect' style='margin-top:14px'>\n");
        sb.append("<div class='adm-h'>自动更新</div>\n");
        sb.append("<div class='adm-hint'>当前版本 ").append(esc(APP_VER))
          .append("。打开后 App 回到首页时最多每 12 小时查一次 GitHub Release；")
          .append("关着就一个请求都不发。</div>\n");
        sb.append("<form method='post' action='/_admin' class='adm-form'>")
          .append("<input type='hidden' name='action' value='update'>")
          .append("<input type='hidden' name='on' value='").append(upOn ? "0" : "1").append("'>")
          .append("<button class='adm-btn' type='submit'>")
          .append(upOn ? "关闭自动更新" : "打开自动更新")
          .append("</button>")
          .append("<span class='adm-cnt'>").append(upOn ? "已开启" : "已关闭").append("</span>")
          .append("</form>\n");
        if (upLast > 0L) {
            sb.append("<div class='adm-hint'>上次检查：")
              .append(esc(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT)
                      .format(new java.util.Date(upLast))))
              .append("</div>\n");
        }
        if (upSkip != null && !upSkip.isEmpty()) {
            sb.append("<form method='post' action='/_admin' class='adm-form'>")
              .append("<input type='hidden' name='action' value='unskip'>")
              .append("<button class='adm-btn' type='submit'>清除跳过（")
              .append(esc(upSkip)).append("）</button>")
              .append("</form>\n");
        }
        sb.append("</div>\n");

        sb.append("<div class='adm-foot'>曲库、影视库与书库在修改后自动重扫，不用重启服务。</div>\n");
        sb.append("</div>\n");

        // 树上的勾 → 文本框同步。裸 IIFE 要自己包 <script>（这台设备上有壳浏览器
        // 不认 <script src> 之外的注入，包起来最稳）。
        // 文本框始终是唯一数据源：树上勾的写进去，手改的读出来重标勾。
        sb.append("<script>\n(function(){\n")
          .append("function lines(t){var a=t.value.split('\\n'),o=[];for(var i=0;i<a.length;i++){")
          .append("var v=a[i].replace(/\\s+$/,'').replace(/^\\s+/,'');if(v)o.push(v);}return o;}\n")
          .append("function treeOf(el){return el.closest('.adm-tree');}\n")
          .append("function taOf(el){var tr=treeOf(el);return tr?tr.parentNode.querySelector('.adm-ta'):null;}\n")
          .append("function syncTree(tr){\n")
          .append("var ta=taOf(tr);if(!ta)return;var cur=lines(ta);\n")
          .append("var bs=tr.querySelectorAll('input[data-dir]');var set={};\n")
          .append("for(var i=0;i<bs.length;i++){if(bs[i].checked)set[bs[i].getAttribute('data-dir')]=1;\n")
          .append("var row=bs[i].parentNode;if(row&&row.classList){")
          .append("if(bs[i].checked)row.classList.add('on');else row.classList.remove('on');}}\n")
          .append("var out=[];\n")
          .append("for(var k=0;k<cur.length;k++){if(bs.length===0||set[cur[k]]){out.push(cur[k]);delete set[cur[k]];}}\n")
          .append("for(var d in set)out.push(d);\n")
          .append("ta.value=out.join('\\n');\n")
          .append("var cnt=tr.querySelector('.adm-tcnt');if(cnt)cnt.textContent='已选 '+out.length+' 个';\n")
          .append("}\n")
          .append("function markFromText(tr){\n")
          .append("var ta=taOf(tr);if(!ta)return;var cur=lines(ta);\n")
          .append("var bs=tr.querySelectorAll('input[data-dir]');\n")
          .append("for(var i=0;i<bs.length;i++){var p=bs[i].getAttribute('data-dir');\n")
          .append("var on=false;for(var k=0;k<cur.length;k++){if(cur[k]===p){on=true;break;}}\n")
          .append("bs[i].checked=on;\n")
          .append("var row=bs[i].parentNode;if(row&&row.classList){")
          .append("if(on)row.classList.add('on');else row.classList.remove('on');}}\n")
          .append("}\n")
          .append("document.addEventListener('change',function(e){\n")
          .append("var t=e.target;if(!t||t.type!=='checkbox')return;\n")
          .append("var tr=treeOf(t);if(!tr)return;syncTree(tr);\n")
          .append("});\n")
          .append("document.addEventListener('input',function(e){\n")
          .append("var t=e.target;if(!t||!t.classList||!t.classList.contains('adm-ta'))return;\n")
          .append("var tr=t.parentNode.querySelector('.adm-tree');if(tr)markFromText(tr);\n")
          .append("});\n")
          .append("document.addEventListener('click',function(e){\n")
          .append("var t=e.target;if(!t||!t.getAttribute)return;\n")
          .append("var tg=t.getAttribute('data-toggle');\n")
          .append("if(tg!==null&&tg!==undefined&&tg!==''){\n")
          .append("var tr=treeOf(t);if(!tr)return;\n")
          .append("var kid=tr.querySelector('.adm-tk[data-kids=\"'+tg.replace(/\"/g,'')+'\"]');\n")
          .append("if(!kid)return;kid.classList.toggle('hide');\n")
          .append("var w=t.classList.contains('adm-tw')?t:t.parentNode.querySelector('.adm-tw');\n")
          .append("if(w)w.textContent=kid.classList.contains('hide')?'\\u25B8':'\\u25BE';\n")
          .append("return;}\n")
          .append("if(!t.classList||t.getAttribute('data-act')===null)return;\n")
          .append("var act=t.getAttribute('data-act');var tr=treeOf(t);if(!tr)return;\n")
          .append("var kids=tr.querySelectorAll('.adm-tk');\n")
          .append("var ws=tr.querySelectorAll('.adm-tw');\n")
          .append("if(act==='all'){\n")
          .append("for(var i=0;i<kids.length;i++)kids[i].classList.remove('hide');\n")
          .append("for(var j=0;j<ws.length;j++){if(!ws[j].classList.contains('leaf'))ws[j].textContent='\\u25BE';}\n")
          .append("}else if(act==='none'){\n")
          .append("for(var i2=0;i2<kids.length;i2++)kids[i2].classList.add('hide');\n")
          .append("for(var j2=0;j2<ws.length;j2++){if(!ws[j2].classList.contains('leaf'))ws[j2].textContent='\\u25B8';}\n")
          .append("}else if(act==='sel'){\n")
          .append("var rows=tr.querySelectorAll('.adm-tn');\n")
          .append("for(var i3=0;i3<rows.length;i3++){")
          .append("rows[i3].style.display=rows[i3].classList.contains('on')?'':'none';}\n")
          .append("var on=t.getAttribute('data-done');\n")
          .append("t.textContent=on?'只看已选':'显示全部';t.setAttribute('data-done',on?'':'1');\n")
          .append("if(on){for(var i4=0;i4<rows.length;i4++)rows[i4].style.display='';}\n")
          .append("}\n")
          .append("});\n")
          .append("})();\n</script>\n");

        htmlFoot(sb);
        return sb.toString();
    }

    /**
     * @param back 登录成功后跳回的地址。带上一整个路径，是因为「匿名关闭」
     *             时未登录用户访问的往往是某个深层目录，登完应当回到那里，
     *             而不是统统丢回首页。
     */
    private String renderLogin(String presetUser, String error, String back) {
        StringBuilder sb = new StringBuilder();
        htmlHead(sb, "登录", prefTheme);
        sb.append("<div class='wrap' style='max-width:420px;padding-top:8vh'>\n");
        sb.append("<div class='login'>\n");
        sb.append("<h2>").append(esc(cfg.title)).append("</h2>\n");
        sb.append("<div class='sub'>局域网媒体库 · 请登录</div>\n");
        if (error != null) {
            sb.append("<div class='err'>").append(esc(error)).append("</div>\n");
        }
        sb.append("<form method='post' action='/_login'>\n");
        // back 走隐藏域，不再塞进 action 的 query string：
        // 路径里带 & 时<form action='/login?back=/a&b'> 会被浏览器截断。
        sb.append("<input type='hidden' name='back' value='").append(esc(back)).append("'>\n");
        sb.append("<label>用户名</label>\n");
        sb.append("<input name='u' value='").append(esc(presetUser))
          .append("' autocomplete='username' autocapitalize='off' autofocus>\n");
        sb.append("<label>密码</label>\n");
        sb.append("<input name='p' type='password' autocomplete='current-password'>\n");
        sb.append("<button type='submit'>登录</button>\n");
        sb.append("</form>\n");
        if (cfg.anonymousRead) {
            sb.append("<div class='anon'><a href='/'>先随便看看（只读）</a></div>\n");
        }
        // 登录页不放切换按钮：主题完全跟随首页。
        // 两边共用 THEME_JS + 同一个 localStorage['usbnas-theme']，
        // 所以这里只要什么都不做，打开时就会自动套用首页已选的日间/夜间。
        sb.append("</div>\n</div>\n");
        htmlFoot(sb);
        return sb.toString();
    }

    private void sendJson(OutputStream out, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\n"
                + "Server: " + cfg.title + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private void sendBytes(OutputStream out, String mime, byte[] data) throws IOException {
        sendBytes(out, mime, data, 200, "OK");
    }

    private void sendBytes(OutputStream out, String mime, byte[] data, int code, String reason)
            throws IOException {
        out.write(("HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Server: " + cfg.title + "\r\n"
                + "Content-Type: " + mime + "\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    /** /_status 实时状态 JSON：手机状态 + 服务器吞吐 */
    private String statusJson(Request req) {
        String srvBps = "0", srvRx = "0";
        if (cfg.sys != null) {
            String json = cfg.sys.toJson(liveRoots(), reqCount.get(), bytesOut.get(),
                    bytesOutBps(), inBytesBps());
            // 冒烟：「已运行」要的是服务自身跑了多久，不是手机开机多久。
            // SysInfo 不知道服务何时启动（它比服务早、也可能被别处复用），
            // 所以那两个字段在这里按 cfg.startedAt 现场算出来再塞进去。
            long st = cfg.startedAt;
            String upText = SysInfo.serviceUptimeText(st);
            String tail = "\"startedAt\":" + st
                    + ",\"serviceUptimeText\":\"" + jsonEsc(upText) + "\",";
            // 覆盖 SysInfo.toJson 里那对占位字段，保持 JSON 只有一份 key
            json = json.replace("\"startedAt\":0,", tail);
            json = json.replace("\"serviceUptimeText\":\"—\",", "");
            // 版本号一并吐出来，客户端才能在列表里显示「20261122」而不是空。
            // APP_VER 之前只出现在注释里，是个从没被引用过的死常量。
            json = json.replace("\"uptime\":", "\"version\":\"" + jsonEsc(APP_VER) + "\",\"uptime\":");
            return json;
        }
        return "{\"version\":\"" + jsonEsc(APP_VER) + "\","
                + "\"running\":" + running.get()
                + ",\"requests\":" + reqCount.get()
                + ",\"bytesOut\":" + bytesOut.get()
                + ",\"srvBps\":" + srvBps + ",\"srvRxBps\":" + srvRx + "}";
    }

    /** 每秒发送字节数（用滑动采样估算） */
    private long bytesOutBps() {
        long now = SystemClock.elapsedRealtime();
        long cur = bytesOut.get();
        synchronized (bpsLock) {
            if (bpsLastAt == 0) {
                bpsLastAt = now;
                bpsLastVal = cur;
                return 0;
            }
            long dt = now - bpsLastAt;
            if (dt < 800) return bpsOut;
            long dv = cur - bpsLastVal;
            bpsOut = dt > 0 ? dv * 1000 / dt : 0;
            bpsLastAt = now;
            bpsLastVal = cur;
            return bpsOut;
        }
    }

    private long inBytesBps() {
        long now = SystemClock.elapsedRealtime();
        long cur = bytesIn.get();
        synchronized (bpsLock) {
            if (inLastAt == 0) {
                inLastAt = now;
                inLastVal = cur;
                return 0;
            }
            long dt = now - inLastAt;
            if (dt < 800) return bpsIn;
            long dv = cur - inLastVal;
            bpsIn = dt > 0 ? dv * 1000 / dt : 0;
            inLastAt = now;
            inLastVal = cur;
            return bpsIn;
        }
    }

    private void sendError(OutputStream out, int code, String msg) throws IOException {
        String body = "<!doctype html><meta charset=utf-8>"
                + "<body style='font-family:sans-serif;background:#0e1116;color:#e6e9ef;"
                + "text-align:center;padding:70px'>"
                + "<h2>" + code + "</h2><p>" + esc(msg) + "</p>"
                + "<p><a style='color:#5aa9ff' href='/'>返回首页</a></p></body>";
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private static String reason(int c) {
        switch (c) {
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 416: return "Range Not Satisfiable";
            case 500: return "Internal Server Error";
            default:  return "OK";
        }
    }

    // ==================== 请求解析 ====================

    static class Request {
        String method = "GET";
        String path = "/";
        String version = "HTTP/1.1";
        final Map<String, String> headers = new HashMap<>();
        final Map<String, List<String>> params = new HashMap<>();
        /** 对端地址，形如 127.0.0.1；/_login/quick 用它判是不是本机来的 */
        String remoteAddr = "";
        /** 已登录用户；null 表示匿名 */
        Users.User user;

        /** 当前访问者的显示名 */
        String who() {
            return user == null ? "匿名" : user.name;
        }

        String param(String k) {
            List<String> l = params.get(k);
            return (l == null || l.isEmpty()) ? null : l.get(0);
        }

        long contentLength() {
            String v = headers.get("content-length");
            if (v == null) return -1;
            try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return -1; }
        }
    }

    private Request readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream(1024);
        int prev = -1, cur;
        int limit = 32 * 1024;

        while ((cur = in.read()) != -1) {
            head.write(cur);
            if (head.size() > limit) return null;
            int n = head.size();
            if (n >= 4) {
                byte[] a = head.toByteArray();
                if (a[n - 4] == '\r' && a[n - 3] == '\n' && a[n - 2] == '\r' && a[n - 1] == '\n') break;
            }
        }
        if (head.size() == 0) return null;

        String raw = head.toString("UTF-8");
        String[] lines = raw.split("\r\n");
        if (lines.length == 0) return null;

        String[] first = lines[0].split(" ");
        if (first.length < 2) return null;

        Request r = new Request();
        r.method = first[0].toUpperCase(Locale.ROOT);
        String target = first[1];
        r.version = first.length > 2 ? first[2] : "HTTP/1.1";

        for (int i = 1; i < lines.length; i++) {
            String ln = lines[i];
            if (ln.isEmpty()) continue;
            int c = ln.indexOf(':');
            if (c <= 0) continue;
            r.headers.put(ln.substring(0, c).trim().toLowerCase(Locale.ROOT),
                    ln.substring(c + 1).trim());
        }

        // 绝对 URL 形态（WebDAV 客户端有时这么发）
        if (target.startsWith("http://") || target.startsWith("https://")) {
            int s = target.indexOf('/', target.indexOf("//") + 2);
            target = s > 0 ? target.substring(s) : "/";
        }

        String qs = null;
        int qi = target.indexOf('?');
        if (qi >= 0) {
            qs = target.substring(qi + 1);
            target = target.substring(0, qi);
        }
        r.path = target.isEmpty() ? "/" : target;

        if (qs != null && !qs.isEmpty()) {
            for (String kv : qs.split("&")) {
                if (kv.isEmpty()) continue;
                int e = kv.indexOf('=');
                String k, v;
                if (e < 0) { k = kv; v = ""; }
                else { k = kv.substring(0, e); v = kv.substring(e + 1); }
                k = formDecode(k);
                v = formDecode(v);
                r.params.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
            }
        }
        // /browse/xxx 是路径形态的目录浏览
        if (r.path.startsWith("/browse")) {
            String p = r.path.substring(7);
            if (p.isEmpty()) p = "/";
            r.path = p;
        }
        return r;
    }

    // ==================== ZIP 流式写入 ====================

    /** chunked transfer 包装 */
    static class ChunkedOut extends OutputStream {
        private final OutputStream out;
        ChunkedOut(OutputStream o) { this.out = o; }
        @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            if (len <= 0) return;
            out.write((Integer.toHexString(len) + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(b, off, len);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        void finish() throws IOException {
            out.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    /**
     * 流式 ZIP 写入器，只用 STORED（不压缩）。
     * 媒体文件本来就没法再压，省 CPU 且边打边发。
     */
    static class ZipStreamWriter {
        private final OutputStream out;
        private final ByteArrayOutputStream central = new ByteArrayOutputStream();
        private long offset = 0;
        private int count = 0;

        ZipStreamWriter(OutputStream out) { this.out = out; }

        void addFile(String entryName, File f) throws IOException {
            byte[] nameBytes = entryName.replace('\\', '/').getBytes(StandardCharsets.UTF_8);
            long size = f.length();
            long crc = crc32(f);

            // 本地文件头
            ByteArrayOutputStream lh = new ByteArrayOutputStream();
            le32(lh, 0x04034b50L);
            le16(lh, 10);            // version needed
            le16(lh, 0x0800);        // flag: UTF-8
            le16(lh, 0);             // method: store
            le16(lh, 0); le16(lh, 0);// time, date
            le32(lh, crc);
            le32(lh, size);
            le32(lh, size);
            le16(lh, nameBytes.length);
            le16(lh, 0);
            lh.write(nameBytes);

            long localOffset = offset;
            byte[] lhb = lh.toByteArray();
            out.write(lhb);
            offset += lhb.length;

            // 数据
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] buf = new byte[CHUNK];
                int r;
                while ((r = fis.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    offset += r;
                }
            }

            // 中央目录项
            le32(central, 0x02014b50L);
            le16(central, 20);       // version made by
            le16(central, 10);       // version needed
            le16(central, 0x0800);
            le16(central, 0);
            le16(central, 0); le16(central, 0);
            le32(central, crc);
            le32(central, size);
            le32(central, size);
            le16(central, nameBytes.length);
            le16(central, 0); le16(central, 0);
            le16(central, 0); le16(central, 0);
            le32(central, 0);
            le32(central, localOffset);
            central.write(nameBytes);

            count++;
        }

        void finish() throws IOException {
            long cdStart = offset;
            byte[] cd = central.toByteArray();
            out.write(cd);
            offset += cd.length;

            ByteArrayOutputStream end = new ByteArrayOutputStream();
            le32(end, 0x06054b50L);
            le16(end, 0); le16(end, 0);
            le16(end, count); le16(end, count);
            le32(end, cd.length);
            le32(end, cdStart);
            le16(end, 0);
            byte[] eb = end.toByteArray();
            out.write(eb);
            offset += eb.length;
        }

        private long crc32(File f) throws IOException {
            java.util.zip.CRC32 c = new java.util.zip.CRC32();
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] buf = new byte[CHUNK];
                int r;
                while ((r = fis.read(buf)) > 0) c.update(buf, 0, r);
            }
            return c.getValue();
        }

        private static void le16(ByteArrayOutputStream o, int v) {
            o.write(v & 0xFF);
            o.write((v >>> 8) & 0xFF);
        }
        private static void le32(ByteArrayOutputStream o, long v) {
            o.write((int) (v & 0xFF));
            o.write((int) ((v >>> 8) & 0xFF));
            o.write((int) ((v >>> 16) & 0xFF));
            o.write((int) ((v >>> 24) & 0xFF));
        }
    }

    /** 直接写文件的 OutputStream（避免 FileOutputStream 命名冲突） */
    static class FileOutputStreamSafe extends OutputStream {
        private final java.io.FileOutputStream fos;
        FileOutputStreamSafe(File f) throws IOException {
            this.fos = new java.io.FileOutputStream(f);
        }
        @Override public void write(int b) throws IOException { fos.write(b); }
        @Override public void write(byte[] b, int off, int len) throws IOException { fos.write(b, off, len); }
        @Override public void flush() throws IOException { fos.flush(); }
        @Override public void close() throws IOException { fos.close(); }
    }
}
