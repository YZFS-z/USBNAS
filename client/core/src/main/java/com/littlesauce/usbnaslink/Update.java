package com.littlesauce.usbnaslink;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 自动更新：从 GitHub Releases 拉最新的 release 清单，比较版本，挑出当前平台能用的安装包。
 *
 * <h3>为什么是这个形状</h3>
 * <ul>
 *   <li><b>不用 GitHub SDK</b>：只发一个 GET，自己解析 JSON。桌面端要能离线编译，
 *       安卓端不想为一个请求多引一个库；core 里已经有 {@link Music.Json} 这个极简
 *       解析器，直接复用。</li>
 *   <li><b>版本比较走 tag</b>：tag 统一发成 {@code vX.Y.Z}。比较时把前缀 v/V 去掉、
 *       按 '.' 切成数字逐段比，缺段补 0。{@code 1.10.0 > 1.9.9} 才对。</li>
 *   <li><b>按文件名挑资产</b>：release 里挂好几个平台的包，靠文件名后缀区分
 *       （{@code -win64.zip} / {@code -arm64-v8a.apk} / …）。挑不到就不提供更新，
 *       绝不让用户下到一个装不上的文件。</li>
 *   <li><b>只读、不改状态</b>：{@link #check} 只返回结果对象，开关判定、
 *       「跳过的版本」这类用户偏好由调用方管，因为桌面端存 Preferences、
 *       安卓端存 SharedPreferences，服务端存 AppPrefs，三套不同。</li>
 * </ul>
 *
 * <h3>线程</h3>
 * 所有方法都会阻塞网络，只能在后台线程调。UI 回调由调用方切回主线程。
 */
public final class Update {

    /** GitHub 仓库坐标：发布新版本时改这两个常量（或子类传参） */
    public static final String OWNER = "YZFS-z";
    public static final String REPO = "USBNAS";

    private static final String API =
            "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest";

    /** release 说明的最大长度，避免把几万字的 CHANGELOG 塞进一个小框 */
    public static final int NOTES_MAX = 4000;

    /** 单次请求的超时（毫秒）。GitHub 在国内偶尔慢，给足 8 秒 */
    public static final int TIMEOUT_MS = 8000;

    private Update() {
    }

    /** 检查结果。ok=false 时 error 里是给人看的一句话。 */
    public static final class Info {
        public boolean ok;
        public String error = "";
        /** release 的 tag，如 v1.2.0 */
        public String tag = "";
        /** 去掉 v 前缀后的版本号 */
        public String version = "";
        public String name = "";
        /** release 正文（Markdown，已截断到 NOTES_MAX） */
        public String notes = "";
        public String pageUrl = "";
        public String publishedAt = "";
        /** 挑中的资产；没有匹配平台的资产时为 null */
        public Asset asset;

        public boolean hasAsset() {
            return asset != null && asset.url != null && !asset.url.isEmpty();
        }
    }

    /** 一个可下载的资产 */
    public static final class Asset {
        public String name = "";
        public String url = "";
        public long size;
        /** 资产自带的摘要，形如 "sha256:xxxx"；GitHub 目前只给 sha256，空串表示没给 */
        public String digest = "";

        public String humanSize() {
            if (size <= 0) return "";
            if (size < 1024) return size + " B";
            if (size < 1024 * 1024) return (size / 1024) + " KB";
            return String.format(java.util.Locale.ROOT, "%.1f MB", size / 1048576.0);
        }
    }

    /**
     * 拉最新 release 并与 {@code currentVersion} 比较。
     *
     * @param suffix 资产文件名后缀，用来挑平台包。例如桌面端传 {@code "-win64.zip"}，
     *               安卓传 {@code "-arm64-v8a.apk"}。传 null 表示任意资产都收。
     * @return 永远不返回 null；网络失败时 ok=false
     */
    public static Info check(String currentVersion, String suffix) {
        Info info = new Info();
        try {
            String body = get(API);
            Object root = Music.Json.parse(body);
            if (!(root instanceof Map)) {
                info.error = "GitHub 返回的不是 JSON（可能被网络劫持或超出限流）";
                return info;
            }
            Map<?, ?> m = (Map<?, ?>) root;

            info.tag = str(m.get("tag_name"));
            info.version = trimTag(info.tag);
            info.name = str(m.get("name"));
            info.pageUrl = str(m.get("html_url"));
            info.publishedAt = str(m.get("published_at"));
            String notes = str(m.get("body"));
            if (notes.length() > NOTES_MAX) notes = notes.substring(0, NOTES_MAX) + "\n…";
            info.notes = notes;

            if (info.version.isEmpty()) {
                info.error = "这个 release 没有 tag，无法比较版本";
                return info;
            }

            Object arr = m.get("assets");
            List<Asset> assets = new ArrayList<>();
            if (arr instanceof List) {
                for (Object o : (List<?>) arr) {
                    if (!(o instanceof Map)) continue;
                    Map<?, ?> a = (Map<?, ?>) o;
                    Asset as = new Asset();
                    as.name = str(a.get("name"));
                    as.url = str(a.get("browser_download_url"));
                    Object sz = a.get("size");
                    if (sz instanceof Number) as.size = ((Number) sz).longValue();
                    as.digest = str(a.get("digest"));
                    assets.add(as);
                }
            }
            info.asset = pick(assets, suffix);
            info.ok = true;
        } catch (Throwable t) {
            info.error = describe(t);
        }
        return info;
    }

    /** 这次检查的结果算不算「有新版本」 */
    public static boolean newer(Info info, String currentVersion) {
        if (info == null || !info.ok) return false;
        return compare(info.version, currentVersion) > 0;
    }

    /**
     * 版本比较：{@code 1.10.0 > 1.9.9}。
     *
     * 规则：去掉前缀 v/V；按 '.' 切段；每段只取前导数字（"3-beta" → 3）；
     * 段数不同时短的一方补 0。整段都不是数字时按 0 处理，不会抛异常。
     */
    public static int compare(String a, String b) {
        String[] sa = trimTag(a).split("\\.");
        String[] sb = trimTag(b).split("\\.");
        int n = Math.max(sa.length, sb.length);
        for (int i = 0; i < n; i++) {
            long x = num(sa, i);
            long y = num(sb, i);
            if (x != y) return x < y ? -1 : 1;
        }
        return 0;
    }

    private static long num(String[] parts, int i) {
        if (i >= parts.length) return 0L;
        String s = parts[i].trim();
        int end = 0;
        while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
        if (end == 0) return 0L;
        try {
            return Long.parseLong(s.substring(0, end));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 去掉 tag 的 v/V 前缀与首尾空白 */
    public static String trimTag(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (!t.isEmpty() && (t.charAt(0) == 'v' || t.charAt(0) == 'V')) t = t.substring(1);
        return t;
    }

    /**
     * 按后缀挑资产。
     *
     * 先找完全后缀匹配，再退一步找「包含」匹配（防止有人给包名加了个前缀），
     * suffix 为 null/空时直接取第一个。挑不到返回 null。
     */
    private static Asset pick(List<Asset> assets, String suffix) {
        if (assets.isEmpty()) return null;
        if (suffix == null || suffix.isEmpty()) return assets.get(0);
        String s = suffix.toLowerCase(java.util.Locale.ROOT);
        for (Asset a : assets) {
            if (a.name != null && a.name.toLowerCase(java.util.Locale.ROOT).endsWith(s)) return a;
        }
        for (Asset a : assets) {
            if (a.name != null && a.name.toLowerCase(java.util.Locale.ROOT).contains(s)) return a;
        }
        return null;
    }

    /**
     * 带进度下载到 {@code sink}。
     *
     * 之所以不返回 byte[]：桌面端要落成 50MB 的 zip，安卓端要落成 60MB 的 apk，
     * 全塞内存会 OOM。调用方给个 OutputStream，这里边读边写。
     *
     * @param onStep 每读一块回调一次（已下载字节, 总字节），总字节未知时传 -1
     * @return true 表示完整下载完成
     */
    public static boolean download(String url, java.io.OutputStream sink, Step onStep) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(30000);
            // GitHub 的 assets 会 302 到 objects.githubusercontent.com，
            // HttpURLConnection 默认会自己跟，但跨协议（https→https）才跟，
            // 这里显式打开，免得哪天改成 http 就静默失败。
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "USBNASLink/1.0 (+update)");
            c.setRequestProperty("Accept", "application/octet-stream");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code);
            long total = c.getContentLengthLong();
            InputStream in = c.getInputStream();
            byte[] buf = new byte[64 * 1024];
            long got = 0;
            int n;
            int stepAcc = 0;
            while ((n = in.read(buf)) > 0) {
                sink.write(buf, 0, n);
                got += n;
                stepAcc += n;
                // 每 256KB 回调一次，太密会把 UI 线程淹掉
                if (stepAcc >= 256 * 1024) {
                    stepAcc = 0;
                    if (onStep != null) onStep.onStep(got, total);
                }
            }
            sink.flush();
            if (onStep != null) onStep.onStep(got, total);
            // 大小对不上就当失败：可以拦住被截断的下载（移动网络下并不少见）
            return total <= 0 || got == total;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 下载进度回调 */
    public interface Step {
        void onStep(long done, long total);
    }

    /**
     * 下载到文件。Android 端用这个 —— 那边没法顺手拿到 {@link java.io.OutputStream}，
     * 直接把目标文件交过来更省事，而且失败时能顺手把半截文件删掉。
     *
     * <p>先写 {@code xxx.part} 再改名：万一中途被杀，下次不会有个大小看着对、
     * 内容却是半截的 apk 被拿去装。
     *
     * @return true 表示下载完整
     */
    public static boolean downloadUrl(String url, java.io.File out, Step onStep) {
        java.io.File part = new java.io.File(out.getAbsolutePath() + ".part");
        java.io.FileOutputStream os = null;
        try {
            java.io.File dir = out.getParentFile();
            if (dir != null && !dir.isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            os = new java.io.FileOutputStream(part);
            boolean ok = download(url, os, onStep);
            os.close();
            os = null;
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                return false;
            }
            //noinspection ResultOfMethodCallIgnored
            if (out.exists()) out.delete();
            return part.renameTo(out);
        } catch (Throwable t) {
            return false;
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------ 内部

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("User-Agent", "USBNASLink/1.0 (+update)");
            // 不带 token：公开仓库的 releases/latest 匿名可读，未认证限流是每小时 60 次，
            // 一天查一次远远用不到。带上 token 反而要用户填 PAT，不值。
            c.setRequestProperty("Accept", "application/vnd.github+json");
            int code = c.getResponseCode();
            if (code == 404) throw new java.io.IOException("还没有发布过 release");
            if (code == 403) throw new java.io.IOException("GitHub 限流了，稍后再试");
            if (code < 200 || code >= 300) throw new java.io.IOException("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream(16384);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 2 * 1024 * 1024) break;
            }
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static String str(Object o) {
        if (o == null) return "";
        String s = String.valueOf(o);
        return "null".equals(s) ? "" : s;
    }

    /** 把异常翻成一句人话，别把 UnknownHostException 这种原文甩到对话框里 */
    public static String describeForUser(Throwable t) {
        return describe(t);
    }

    private static String describe(Throwable t) {
        String m = t.getMessage() == null ? "" : t.getMessage();
        String cls = t.getClass().getSimpleName();
        if (cls.contains("UnknownHost")) return "连不上 GitHub（检查网络或 DNS）";
        if (cls.contains("SocketTimeout")) return "连 GitHub 超时，稍后再试";
        if (cls.contains("SSL") || cls.contains("Cert")) return "HTTPS 握手失败（系统时间或证书有问题）";
        if (m.startsWith("HTTP ")) return "GitHub 返回 " + m.substring(5);
        return m.isEmpty() ? cls : m;
    }
}
