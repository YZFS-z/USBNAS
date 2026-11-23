package com.littlesauce.usbnaslink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 曲库与歌词的拉取。
 *
 * 为什么不用 Android 的 WebView 或网页端那套 /play/music：那个页面一次只认一首歌，
 * 想听上一首得先返回上级目录再点。播放器要的是「整个曲库一次给全」，
 * 所以服务端加了 /_music 端点，这里负责把它的 JSON 解析成 Track 列表。
 */
public final class Music {

    /** 一次拉取的曲库上限保护：真超过这个数，说明服务端配错目录了 */
    public static final int MAX_TRACKS = 20000;

    private Music() {
    }

    /** 一次曲库查询的结果 */
    public static final class Lib {
        public final List<Track> tracks = new ArrayList<>();
        /** 服务端预设的音乐根目录（绝对路径） */
        public final List<String> dirs = new ArrayList<>();
        public boolean ok;
        public String error = "";
        public long scannedAt;

        public boolean isEmpty() {
            return tracks.isEmpty();
        }
    }

    /** GET /_music */
    public static Lib fetch(String baseUrl, String cookie, int timeoutMs) {
        Lib lib = new Lib();
        HttpURLConnection c = null;
        try {
            c = open(baseUrl + "/_music", cookie, timeoutMs);
            int code = c.getResponseCode();
            if (code != 200) {
                lib.error = "HTTP " + code;
                return lib;
            }
            String body = readAll(c.getInputStream(), 32 * 1024 * 1024);
            parseLib(body, lib);
        } catch (Throwable t) {
            lib.error = t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (c != null) c.disconnect();
        }
        return lib;
    }

    /** GET /lyrics?f=<绝对路径>；返回 LRC 正文，没有则 null */
    public static String fetchLyrics(String baseUrl, String cookie, String filePath, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            String q = pct(filePath);
            c = open(baseUrl + "/lyrics?f=" + q, cookie, timeoutMs);
            if (c.getResponseCode() != 200) return null;
            String body = readAll(c.getInputStream(), 4 * 1024 * 1024);
            Map<String, Object> m = Json.parseObject(body);
            if (m == null) return null;
            Object ok = m.get("ok");
            if (!(ok instanceof Boolean) || !((Boolean) ok)) return null;
            Object lrc = m.get("lrc");
            return lrc instanceof String ? (String) lrc : null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 音频的直链，播放器直接用（服务端支持 Range，可以拖进度） */
    public static String audioUrl(String baseUrl, String filePath) {
        try {
            // encodePath 已经带前导 '/'，这里不能再补一个：曾经写
            // baseUrl + "/" + encodePath(p) 拼出 http://host:8080//mnt/...，
            // 服务端大概率能兜住（它归一化 // ），但任何中间代理/CDN 都会
            // 把 // 当成协议相对路径，排查过一次就够烦了。
            String enc = encodePath(filePath);
            if (enc.startsWith("/")) return baseUrl + enc;
            return baseUrl + "/" + enc;
        } catch (Throwable t) {
            return baseUrl;
        }
    }

    /** 把绝对路径按段编码，保留 '/' —— 服务端的 resolve() 就是这么解回来的 */
    private static String encodePath(String p) {
        if (p == null || p.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(p.length() + 16);
        boolean abs = p.charAt(0) == '/';
        for (String seg : p.split("/")) {
            if (seg.isEmpty()) continue;
            sb.append('/').append(pct(seg));
        }
        String out = sb.toString();
        // 绝对路径必须保留前导斜杠：曾经这里统一 substring(1)，把
        // /storage/emulated/0/Books/a.docx 变成 storage/emulated/0/Books/a.docx，
        // 服务端 resolve() 靠 canonical 前缀匹配才勉强兜住，语义已经错了。
        if (abs && !out.startsWith("/")) out = "/" + out;
        return out;
    }

    /** 单段 UTF-8 百分号编码；URLEncoder 的受检异常在这里吃掉，返回原串 */
    private static String pct(String seg) {
        try {
            return URLEncoder.encode(seg, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return seg;
        }
    }

    // 包内可见的几个：VideoLib 复用同一套连接与编码，避免两处各写一遍
    static String pctPublic(String s) {
        return pct(s);
    }

    /**
     * 按路径段编码：保留 '/'，其余交给 URLEncoder。
     *
     * 凡是「把服务端绝对路径塞进 URL 查询参数」的地方都要用这个，
     * 不能用 {@link #pctPublic}——后者会把 '/' 变 %2F，服务端 resolve()
     * 就认不出这是路径了。
     */
    static String encodePathPublic(String p) {
        return encodePath(p);
    }

    static HttpURLConnection openPublic(String url, String cookie, int timeoutMs) throws IOException {
        return open(url, cookie, timeoutMs);
    }

    static String readAllPublic(InputStream in, int limit) throws IOException {
        return readAll(in, limit);
    }

    private static HttpURLConnection open(String url, String cookie, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(1200);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("User-Agent", "USBNASLink/1.0");
        c.setRequestProperty("Accept", "application/json, */*");
        if (cookie != null && !cookie.isEmpty()) {
            c.setRequestProperty("Cookie", Session.COOKIE + "=" + cookie);
        }
        return c;
    }

    private static String readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > limit) break;
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    // ==================== 极简 JSON 解析 ====================
    //
    // 只解析自己服务端吐出来的这一个形状（对象/数组/字符串/数字/布尔/null），
    // 所以不引任何第三方库：客户端要能在没有网络的机器上编译。

    private static void parseLib(String json, Lib lib) {
        Object root = Json.parse(json);
        if (!(root instanceof Map)) {
            lib.error = "bad json";
            return;
        }
        Map<?, ?> m = (Map<?, ?>) root;
        Object ok = m.get("ok");
        lib.ok = (ok instanceof Boolean) && ((Boolean) ok);
        Object t = m.get("total");
        if (t instanceof Number) { /* 只是提示，实际以 tracks 长度为准 */ }
        Object dirs = m.get("dirs");
        if (dirs instanceof List) {
            for (Object o : (List<?>) dirs) {
                if (o instanceof Map) {
                    Object p = ((Map<?, ?>) o).get("p");
                    if (p instanceof String) lib.dirs.add((String) p);
                }
            }
        }
        Object arr = m.get("tracks");
        if (arr instanceof List) {
            for (Object o : (List<?>) arr) {
                if (lib.tracks.size() >= MAX_TRACKS) break;
                if (!(o instanceof Map)) continue;
                Map<?, ?> tm = (Map<?, ?>) o;
                String p = str(tm.get("p"));
                if (p == null || p.isEmpty()) continue;
                Track tr = new Track(p, str(tm.get("n")), str(tm.get("t")), str(tm.get("d")),
                        num(tm.get("s")), num(tm.get("m")));
                Object l = tm.get("l");
                if (l instanceof String && !((String) l).isEmpty()) tr.lrcName = (String) l;
                // 老服务端不吐这三个键，缺失时保持空串（= 不知道），
                // 由 Track.artist()/album() 退到文件名与目录名推断。
                String ar = str(tm.get("ar"));
                String al = str(tm.get("al"));
                String ti = str(tm.get("ti"));
                if (ar != null) tr.tagArtist = ar;
                if (al != null) tr.tagAlbum = al;
                if (ti != null) tr.tagTitle = ti;
                // c：封面绝对路径。老服务端没有这个键 → 保持 null，播放页画贴纸
                String cv = str(tm.get("c"));
                if (cv != null && !cv.isEmpty()) tr.cover = cv;
                lib.tracks.add(tr);
            }
        }
        Object sa = m.get("scannedAt");
        if (sa instanceof Number) lib.scannedAt = ((Number) sa).longValue();
    }

    private static String str(Object o) {
        return o instanceof String ? (String) o : null;
    }

    private static long num(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    /** 只够用的 JSON 解析器；失败返回 null */
    static final class Json {

        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String s) {
            if (s == null) return null;
            Json j = new Json(s);
            try {
                Object v = j.value();
                j.ws();
                return v;
            } catch (Throwable t) {
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> parseObject(String s) {
            Object o = parse(s);
            return o instanceof Map ? (Map<String, Object>) o : null;
        }

        private void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        private Object value() {
            ws();
            if (i >= s.length()) return null;
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return object();
                case '[':
                    return array();
                case '"':
                    return string();
                case 't':
                    // 只有真是布尔字面量才吞 4 个字；否则（服务端裸串 "testdoc"）交给 bare()。
                    // 原来无条件 i += 4，把 "test" 当 true 吞掉，于是 name=true、title=true。
                    if (s.startsWith("true", i)) {
                        i += 4;
                        return Boolean.TRUE;
                    }
                    return bare();
                case 'f':
                    if (s.startsWith("false", i)) {
                        i += 5;
                        return Boolean.FALSE;
                    }
                    return bare();
                case 'n':
                    if (s.startsWith("null", i)) {
                        i += 4;
                        return null;
                    }
                    return bare();
                default:
                    return bare();
            }
        }

        /**
         * 非标准但真实存在的情况：服务端 jsonEsc() 只转义引号/反斜杠/控制字符，
         * 遇到 ':' 和 '<' 这类字符时吐出来的值是**不带引号**的裸串
         * （实测 /_books/text 返回 {"title":testdoc,"text":\n\n第一章…}）。
         * 原来的实现把它当数字解析，于是 title=true、text=null。
         * 这里把「到下一个 ', ' 或 '}' 为止」当成字符串收下，数字仍走 number()。
         */
        private Object bare() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ',' || c == '}' || c == ']') break;
                if (c == '\\' && i + 1 < s.length()) {
                    i += 2;
                    continue;
                }
                i++;
            }
            String t = s.substring(start, i).trim();
            if (t.isEmpty()) {
                return null;
            }
            if (isNumber(t)) {
                try {
                    if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) {
                        return Long.parseLong(t);
                    }
                    return Double.parseDouble(t);
                } catch (Throwable ignored) {
                }
            }
            if ("true".equals(t)) return Boolean.TRUE;
            if ("false".equals(t)) return Boolean.FALSE;
            if ("null".equals(t)) return null;
            // 裸串里的 \n \t \" 依旧要还原：服务端 jsonEsc 把换行写成两个字符
            // 「\ + n」，不收的话界面上会看到字面量 \n。
            return unescape(t);
        }

        private static String unescape(String t) {
            if (t.indexOf('\\') < 0) return t;
            StringBuilder sb = new StringBuilder(t.length());
            for (int k = 0; k < t.length(); k++) {
                char c = t.charAt(k);
                if (c != '\\' || k + 1 >= t.length()) {
                    sb.append(c);
                    continue;
                }
                char e = t.charAt(++k);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (k + 4 < t.length()) {
                            try {
                                sb.append((char) Integer.parseInt(t.substring(k + 1, k + 5), 16));
                                k += 4;
                            } catch (Throwable ignored) {
                            }
                        }
                        break;
                    default: sb.append(e);
                }
            }
            return sb.toString();
        }

        private static boolean isNumber(String t) {
            if (t.isEmpty()) return false;
            for (int k = 0; k < t.length(); k++) {
                char c = t.charAt(k);
                boolean ok = (c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.'
                        || c == 'e' || c == 'E';
                if (!ok) return false;
            }
            return true;
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            while (true) {
                ws();
                if (i >= s.length()) return m;
                if (s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                if (s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                if (s.charAt(i) != '"') {
                    // 键必须是字符串。走到这里说明格式坏了；别 i++ 静默吞字符
                    // 继续找下一个引号 —— 那会把后面的字符串值当成键，
                    // 症状是 name=true、text=null 这种「值全串位」。
                    i++;
                    continue;
                }
                String k = string();
                ws();
                if (i < s.length() && s.charAt(i) == ':') i++;
                m.put(k, value());
            }
        }

        private List<Object> array() {
            List<Object> a = new ArrayList<>();
            i++; // [
            while (true) {
                ws();
                if (i >= s.length()) return a;
                if (s.charAt(i) == ']') {
                    i++;
                    return a;
                }
                if (s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                a.add(value());
            }
        }

        private String string() {
            StringBuilder sb = new StringBuilder();
            i++; // "
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) break;
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 4 <= s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            } catch (Throwable ignored) {
                            }
                            i += 4;
                        }
                        break;
                    default: sb.append(e);
                }
            }
            return sb.toString();
        }

        private Object number() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.'
                        || c == 'e' || c == 'E') {
                    i++;
                } else {
                    break;
                }
            }
            if (start == i) {
                i++;
                return null;
            }
            String t = s.substring(start, i);
            try {
                if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) {
                    return Long.parseLong(t);
                }
                return Double.parseDouble(t);
            } catch (Throwable e) {
                return null;
            }
        }
    }

    // ==================== 播放列表引擎 ====================

    /** 播放模式：顺序、列表循环、单曲循环、随机 */
    public enum Mode {
        SEQUENTIAL("顺序"),
        LOOP_ALL("列表循环"),
        LOOP_ONE("单曲循环"),
        SHUFFLE("随机");

        public final String label;

        Mode(String label) {
            this.label = label;
        }

        public Mode next() {
            Mode[] all = values();
            return all[(ordinal() + 1) % all.length];
        }
    }

    /**
     * 播放列表 + 当前游标 + 模式。
     *
     * 所有「上一首/下一首」的判定都收在这里，桌面端和安卓端共用同一套语义，
     * 免得两端按不同的直觉跳曲（比如到底末尾了要不要停）。
     */
    public static final class Playlist {
        public final List<Track> items = new ArrayList<>();
        public int index = -1;
        public Mode mode = Mode.LOOP_ALL;
        private final java.util.Random rnd = new java.util.Random();
        /** 随机模式下已经走过的下标，保证一轮之内不重复 */
        private final java.util.LinkedHashSet<Integer> played = new java.util.LinkedHashSet<>();

        public void setTracks(List<Track> list) {
            items.clear();
            if (list != null) items.addAll(list);
            played.clear();
            if (index >= items.size()) index = items.isEmpty() ? -1 : items.size() - 1;
        }

        public boolean isEmpty() {
            return items.isEmpty();
        }

        public Track current() {
            return (index >= 0 && index < items.size()) ? items.get(index) : null;
        }

        public Track select(int i) {
            if (i < 0 || i >= items.size()) return null;
            index = i;
            played.add(i);
            return items.get(i);
        }

        /** 找一首歌在列表里的位置，找不到 -1 */
        public int indexOf(Track t) {
            if (t == null) return -1;
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).path.equals(t.path)) return i;
            }
            return -1;
        }

        /**
         * 下一首。
         *
         * 单曲循环在「用户主动点下一首」时仍然要往下走 —— 否则按钮就像坏了。
         * 这个区别由 {@link #next()} 与 {@link #onEnded()} 分开表达。
         */
        public Track next() {
            if (items.isEmpty()) return null;
            if (mode == Mode.SHUFFLE) return shuffleNext();
            int i = index + 1;
            if (i >= items.size()) {
                if (mode == Mode.SEQUENTIAL && index == items.size() - 1) {
                    // 顺序模式到底了就停在最后一首
                    return null;
                }
                i = 0;
            }
            index = i;
            played.add(i);
            return items.get(i);
        }

        /** 上一首：顺序模式在 0 号位停住，其余模式从末尾绕回 */
        public Track prev() {
            if (items.isEmpty()) return null;
            if (mode == Mode.SHUFFLE) return shuffleNext();
            int i = index - 1;
            if (i < 0) {
                if (mode == Mode.SEQUENTIAL && index == 0) return null;
                i = items.size() - 1;
            }
            index = i;
            played.add(i);
            return items.get(i);
        }

        /** 一首放完：单曲循环返回自己，其余同 next()（列表循环会绕回） */
        public Track onEnded() {
            Track cur = current();
            if (mode == Mode.LOOP_ONE && cur != null) return cur;
            Track t = next();
            // 顺序模式到底返回 null，表示该停了
            return t;
        }

        private Track shuffleNext() {
            if (items.size() == 1) return items.get(0);
            if (played.size() >= items.size()) played.clear();
            int guard = 0;
            while (guard++ < items.size() * 4) {
                int i = rnd.nextInt(items.size());
                if (played.contains(i) && played.size() < items.size()) continue;
                if (i == index && items.size() > 1) continue;
                index = i;
                played.add(i);
                return items.get(i);
            }
            index = (index + 1) % items.size();
            return items.get(index);
        }

        /** 过滤后的子列表下标 → 原列表下标；给「搜索后点播」用 */
        public static int mapIndex(List<Track> all, Track t) {
            if (all == null || t == null) return -1;
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).path.equals(t.path)) return i;
            }
            return -1;
        }

        /**
         * 「现在点下一首真的有歌吗」。
         *
         * 只给播放页做切歌动画前的判断用：动画放完却什么都没换，比不放动画更迷惑。
         * 语义必须和 {@link #next()} 完全一致 —— 顺序模式到底就是 false，
         * 别的模式都会绕回，所以永远是 true；随机模式下只要不止一首就还能走。
         */
        public boolean hasNext() {
            if (items.isEmpty()) return false;
            if (mode == Mode.SHUFFLE) return true;
            if (mode == Mode.SEQUENTIAL && index >= items.size() - 1) return false;
            return true;
        }

        /** 同上，对应 {@link #prev()} */
        public boolean hasPrev() {
            if (items.isEmpty()) return false;
            if (mode == Mode.SHUFFLE) return true;
            if (mode == Mode.SEQUENTIAL && index <= 0) return false;
            return true;
        }
    }

    /** 拼一个给人看的时长文本 */
    public static String humanSize(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.0fK", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1fM", bytes / (1024.0 * 1024.0));
    }

    // ==================== 曲库分类（歌手 / 专辑） ====================
    //
    // 曲库动辄上千首，平铺成一条长列表根本翻不动。分类逻辑必须两端一致，
    // 所以收在 core 里，桌面和安卓都调这里，免得「电脑上看到 8 个歌手、
    // 手机上有 12 个」这种没法解释的差异。

    /** 未知歌手/未知专辑的显示名。空串、纯数字、纯符号都归到这一类。 */
    public static final String UNKNOWN_ARTIST = "未知歌手";
    public static final String UNKNOWN_ALBUM = "未知专辑";

    /** 一个分类桶：歌手或专辑 */
    public static final class Bucket {
        public final String name;
        public final List<Track> tracks = new ArrayList<>();

        Bucket(String name) {
            this.name = name;
        }

        public int size() {
            return tracks.size();
        }

        /** 总时长不可知（服务端不解码音频），只能给总大小 */
        public String summary() {
            long bytes = 0;
            for (Track t : tracks) bytes += t.size;
            return tracks.size() + " 首   " + humanSize(bytes);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public enum GroupBy {
        /** 按歌手：lrc 的 [ar:]，没有就拆「歌手 - 歌名」，都不行进「未知歌手」 */
        ARTIST("歌手"),
        /** 按专辑：lrc 的 [al:]，没有就用所在目录名；目录只有一层时退回猜歌名 */
        ALBUM("专辑");

        public final String label;

        GroupBy(String label) {
            this.label = label;
        }

        public GroupBy next() {
            return this == ARTIST ? ALBUM : ARTIST;
        }
    }

    /** 一个桶里装多少首才算「大桶」——大桶排在前面，小桶沉底 */
    private static final int BIG_BUCKET = 3;

    /**
     * 把一批歌按歌手或专辑分组。
     *
     * 排序规则（两端共用，别各自发挥）：
     *   1. 「未知」永远在最后 —— 用户找的是人，不是垃圾桶；
     *   2. 其余按曲目数从多到少（听得多的歌手先出现），
     *      只有 1 首的散装歌手全部沉到后面，不跟主力歌手抢版面；
     *   3. 数量相同按名字排序（用不区分大小写的比较，避免 A/a 乱序）。
     */
    public static List<Bucket> group(List<Track> tracks, GroupBy by) {
        java.util.LinkedHashMap<String, Bucket> map = new java.util.LinkedHashMap<>();
        if (tracks != null) {
            for (Track t : tracks) {
                if (t == null) continue;
                String k = by == GroupBy.ARTIST ? t.artist() : t.guessAlbum();
                k = cleanName(k, by == GroupBy.ARTIST ? UNKNOWN_ARTIST : UNKNOWN_ALBUM);
                Bucket b = map.get(k);
                if (b == null) {
                    b = new Bucket(k);
                    map.put(k, b);
                }
                b.tracks.add(t);
            }
        }
        List<Bucket> out = new ArrayList<>(map.values());
        final String unknown = by == GroupBy.ARTIST ? UNKNOWN_ARTIST : UNKNOWN_ALBUM;
        out.sort((a, b) -> {
            boolean ua = a.name.equals(unknown);
            boolean ub = b.name.equals(unknown);
            if (ua != ub) return ua ? 1 : -1;
            boolean ba = a.size() >= BIG_BUCKET;
            boolean bb = b.size() >= BIG_BUCKET;
            if (ba != bb) return ba ? -1 : 1;
            if (a.size() != b.size()) return b.size() - a.size();
            return a.name.compareToIgnoreCase(b.name);
        });
        return out;
    }

    /**
     * 清掉没法当分类名用的值：空串、纯数字、纯标点。
     * 「01」「2009」「---」当歌手名会让人以为曲库坏了。
     */
    public static String cleanName(String raw, String fallback) {
        if (raw == null) return fallback;
        String s = raw.trim();
        if (s.isEmpty()) return fallback;
        boolean hasLetter = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
                break;
            }
        }
        if (!hasLetter) return fallback;
        if (s.length() > 60) s = s.substring(0, 60);
        return s;
    }

    /**
     * 按关键词过滤曲库：先按标题/文件名/歌手/专辑匹配，再按 {@link GroupBy} 分组。
     * 搜索框和分类页签共用这一条路径，保证「搜出来的」和「分类里的」是同一批歌。
     */
    public static List<Track> filter(List<Track> tracks, String query) {
        List<Track> out = new ArrayList<>();
        if (tracks == null) return out;
        for (Track t : tracks) {
            if (t != null && t.matches(query)) out.add(t);
        }
        return out;
    }
}
