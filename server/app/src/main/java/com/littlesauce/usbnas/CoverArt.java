package com.littlesauce.usbnas;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 从音频文件里把内嵌封面挖出来。
 *
 * <h3>为什么需要这个</h3>
 * 以前曲库的封面只认「同名图片」和「cover/folder/front 这几个通用名」。
 * 但绝大多数 MP3、FLAC 根本不带旁边的 jpg —— 专辑图是直接钉在文件里的：
 * MP3 是 ID3v2 的 APIC 帧，FLAC 是 PICTURE 元数据块，M4A 是 moov/udta/covr。
 * 实测库里「Beyond - 海阔天空.mp3」这类的文件头里就明明白白有
 * {@code APIC / image/jpeg / JFIF}，而目录里一张同名图都没有，
 * 于是整个曲库 1000 多首全部退回自绘贴纸 —— 这就是「好多音乐有封面却没显示」。
 *
 * <h3>为什么是整个文件里瞎找</h3>
 * 正经做法是完整实现 ID3v2.3/2.4 帧解析、FLAC 块链、MP4 box 树。但这里的目标只是
 * 「给一张能贴到唱片中心的方图」，不是做标签编辑器，所以采取：
 * <ol>
 *   <li>先按格式走一小段正规流程（ID3v2 只看第一个 APIC；FLAC 顺着块链走，
 *       最多 40 块，碰到 PICTURE 就取）；</li>
 *   <li>正规流程没找到，再退化成「全文找 JPEG/PNG 的魔数」。MP3 的 APIC 帧、
 *       ID3v2.2 的 PIC 帧、某些容器把图藏在私有块里，全都能被这一步捞出来。</li>
 * </ol>
 * 魔数扫描只对前 {@link #SCAN_MAX} 字节做（默认 12MB）：封面 99% 都在文件头，
 * 而 U 盘上动辄 40MB 的无损文件全读一遍太慢。
 *
 * <h3>为什么自带缓存</h3>
 * 抽一张封面要把文件头读进来，客户端每次进播放页都会请求一次。曲库上千首时
 * 反复读 U 盘会明显拖慢。所以按「绝对路径 + 大小 + mtime」做键缓存：
 * 换文件（大小或时间变了）自然失效，不用手动清。
 */
final class CoverArt {

    private CoverArt() {
    }

    /** 只认这几个格式：客户端 BitmapFactory 直接能解，不用额外依赖 */
    private static final String[] MIME = {"image/jpeg", "image/png"};

    /** 单次最多往后读多少字节找封面（够覆盖 ID3 头 + 前几 MB 的音频数据） */
    private static final int SCAN_MAX = 12 * 1024 * 1024;

    /** 抽出来的图最大收多少字节：再大就不像正经封面了，客户端也不好解 */
    private static final int IMG_MAX = 4 * 1024 * 1024;

    /** 缓存上限：一条记录里带几 MB 会撑爆堆，所以只留最近若干张 */
    private static final int CACHE_MAX = 24;

    /** 结果类型：mime 为 null 表示「这个文件确实没有封面」 */
    static final class Art {
        final String mime;
        final byte[] data;

        Art(String mime, byte[] data) {
            this.mime = mime;
            this.data = data;
        }
    }

    /** 缓存上限在 {@link ArtCache} 里，这里不再重复定义 */
    private static final ArtCache CACHE = new ArtCache();

    /**
     * 取一首歌的内嵌封面。命中缓存直接返回；文件换了（大小/时间变）自动重抽。
     *
     * @return 永远不返回 null，用 {@code art.mime == null} 表示没有
     */
    static Art get(File f) {
        if (f == null || !f.isFile() || f.length() < 128) return new Art(null, null);
        String key = f.getAbsolutePath();
        long size = f.length();
        long mtime = f.lastModified();
        ArtCache.Rec hit = CACHE.get(key, size, mtime);
        if (hit != null) return new Art(hit.mime, hit.data);
        Art art = extract(f, size);
        CACHE.put(key, new ArtCache.Rec(art.mime, art.data, size, mtime));
        return art;
    }

    /** 抽一次就完事，不碰缓存 —— 只有 [get] 会调它 */
    private static Art extract(File f, long size) {
        byte[] buf = null;
        try {
            buf = readHead(f, size);
            if (buf == null) return new Art(null, null);

            // 1) ID3v2 头：认第一个 APIC（MP3 最常见的情况）
            Art a = fromId3(buf);
            if (a != null) return a;
            // 2) FLAC 的 PICTURE 块（无损专辑几乎都有）
            a = fromFlac(buf);
            if (a != null) return a;
            // 3) 兜底：全文扫 JPEG/PNG 魔数
            return byMagic(buf);
        } catch (Throwable t) {
            // 一个坏文件不该让整张封面接口 500
            return new Art(null, null);
        } finally {
            buf = null;
        }
    }

    /** 按需读文件头：最多读 SCAN_MAX，小文件就整读 */
    private static byte[] readHead(File f, long size) throws IOException {
        int want = (int) Math.min(size, SCAN_MAX);
        if (want <= 0) return null;
        byte[] b = new byte[want];
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            int off = 0;
            while (off < want) {
                int n = raf.read(b, off, want - off);
                if (n <= 0) break;
                off += n;
            }
            if (off < want) {
                byte[] t = new byte[off];
                System.arraycopy(b, 0, t, 0, off);
                return t;
            }
            return b;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // ---------------------------------------------------------------- ID3v2

    /**
     * ID3v2 的 APIC 帧。帧结构：
     * <pre>
     * 帧头 10 字节（v2.3/2.4：4 字节 id + 4 字节 size + 2 字节 flags）
     * 正文：1 字节编码 + mime\0 + 1 字节图片类型 + 描述\0 + 图片数据
     * </pre>
     * 只取第一个 APIC —— 一首歌挂三张图时，第一张就是主封面。
     */
    private static Art fromId3(byte[] b) {
        if (b.length < 20) return null;
        if (b[0] != 'I' || b[1] != 'D' || b[2] != '3') return null;
        int ver = b[3] & 0xff;
        if (ver != 3 && ver != 4) return null;
        // 标签总长：syncsafe 整数（每字节只用低 7 位）
        int tagLen = syncsafe(b, 6);
        if (tagLen <= 0) return null;
        int end = Math.min(b.length, 10 + tagLen);

        int p = 10;
        // 2.4 的扩展头：4 字节 syncsafe 长度，跳过
        if ((b[5] & 0x40) != 0 && p + 4 <= end) p += syncsafe(b, p);
        while (p + 10 <= end) {
            String id = new String(b, p, 4, StandardCharsets.ISO_8859_1);
            if (id.charAt(0) == '\0') break; // 帧区结束
            int fsz = ver == 4 ? syncsafe(b, p + 4) : be32(b, p + 4);
            int body = p + 10;
            if (fsz <= 0 || body + fsz > b.length) break;
            if ("APIC".equals(id)) {
                Art a = parseApic(b, body, fsz);
                if (a != null) return a;
            }
            p = body + fsz;
        }
        return null;
    }

    /** 解一帧 APIC 的正文 */
    private static Art parseApic(byte[] b, int off, int len) {
        int end = off + len;
        if (off >= end) return null;
        int p = off + 1;                       // 跳过文本编码字节
        int mimeStart = p;
        while (p < end && b[p] != 0) p++;       // mime 以 \0 结尾
        if (p >= end) return null;
        String mime = new String(b, mimeStart, p - mimeStart, StandardCharsets.ISO_8859_1)
                .toLowerCase(java.util.Locale.ROOT);
        p++;                                   // 跳过 \0
        if (p >= end) return null;
        p++;                                   // 图片类型（0=other, 3=front cover）
        // 描述串：按编码字节定结尾宽度
        p = skipDesc(b, p, end, b[off] & 0xff);
        if (p < 0 || p >= end) return null;
        return slice(b, p, end, mime);
    }

    /** 跳过 ID3 文本串（描述）：UTF-16 是双字节 \0\0，其余是单字节 \0 */
    private static int skipDesc(byte[] b, int p, int end, int enc) {
        if (enc == 1 || enc == 2) {
            while (p + 1 < end) {
                if (b[p] == 0 && b[p + 1] == 0) return p + 2;
                p += 2;
            }
            return -1;
        }
        while (p < end && b[p] != 0) p++;
        return p < end ? p + 1 : -1;
    }

    // ------------------------------------------------------------------ FLAC

    /**
     * FLAC 的 PICTURE 块（type=6）。块结构：
     * <pre>
     * 4 字节图片类型 + 4 字节 mime 长度 + mime + 4 字节描述长度 + 描述
     * + 4 字节宽 + 4 字节高 + 4 字节色深 + 4 字节色数 + 4 字节数据长度 + 数据
     * </pre>
     * 块链从 "fLaC" 之后开始，每块头 4 字节（1 位「最后一块」+ 7 位类型 + 24 位长度）。
     */
    private static Art fromFlac(byte[] b) {
        if (b.length < 8) return null;
        if (b[0] != 'f' || b[1] != 'L' || b[2] != 'a' || b[3] != 'C') return null;
        int p = 4;
        for (int guard = 0; guard < 64 && p + 4 <= b.length; guard++) {
            int head = b[p] & 0xff;
            boolean last = (head & 0x80) != 0;
            int type = head & 0x7f;
            int len = ((b[p + 1] & 0xff) << 16) | ((b[p + 2] & 0xff) << 8) | (b[p + 3] & 0xff);
            p += 4;
            if (len < 0 || p + len > b.length) return null;
            if (type == 6) {
                Art a = parseFlacPic(b, p, len);
                if (a != null) return a;
            }
            if (last) return null;
            p += len;
        }
        return null;
    }

    private static Art parseFlacPic(byte[] b, int off, int len) {
        int end = off + len;
        int p = off;
        if (p + 8 > end) return null;
        p += 4;                                   // 图片类型
        int mimeLen = be32(b, p);
        p += 4;
        if (mimeLen <= 0 || p + mimeLen > end) return null;
        String mime = new String(b, p, mimeLen, StandardCharsets.ISO_8859_1)
                .toLowerCase(java.util.Locale.ROOT);
        p += mimeLen;
        if (p + 4 > end) return null;
        int descLen = be32(b, p);
        p += 4;
        if (descLen < 0 || p + descLen > end) return null;
        p += descLen;
        if (p + 20 > end) return null;
        p += 16;                                  // 宽高深色数
        int dataLen = be32(b, p);
        p += 4;
        if (dataLen <= 0 || p + dataLen > end) return null;
        return slice(b, p, p + dataLen, mime);
    }

    // -------------------------------------------------------------- 魔数兜底

    /**
     * 全文找一张 JPEG 或 PNG。
     *
     * 先找 PNG（8 字节魔数 + IEND 结尾，边界最干净），找不到再找 JPEG
     * （FFD8FF 开头，往后扫到 FFD9）。返回第一个能自洽收尾的。
     */
    private static Art byMagic(byte[] b) {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};
        int p = indexOf(b, png, 0);
        if (p >= 0) {
            Art a = pngAt(b, p);
            if (a != null) return a;
        }
        int j = 0;
        while (true) {
            int s = indexOfJpeg(b, j);
            if (s < 0) break;
            Art a = jpegAt(b, s);
            if (a != null) return a;
            j = s + 3;
        }
        return null;
    }

    private static Art pngAt(byte[] b, int start) {
        // IEND 块是固定 12 字节：4 长度 + "IEND" + 4 CRC
        byte[] iend = {'I', 'E', 'N', 'D'};
        int q = indexOf(b, iend, start + 8);
        if (q < 0) return null;
        int end = q + 8;
        if (end > b.length) return null;
        return slice(b, start, end, "image/png");
    }

    private static Art jpegAt(byte[] b, int start) {
        if (start + 4 > b.length) return null;
        for (int p = start + 2; p + 1 < b.length; p++) {
            if ((b[p] & 0xff) == 0xff && (b[p + 1] & 0xff) == 0xd9) {
                int end = p + 2;
                if (end - start < 512) return null;   // 太碎的多半是缩略图残片，不要
                return slice(b, start, end, "image/jpeg");
            }
        }
        return null;
    }

    private static int indexOfJpeg(byte[] b, int from) {
        for (int p = Math.max(0, from); p + 3 <= b.length; p++) {
            if ((b[p] & 0xff) == 0xff && (b[p + 1] & 0xff) == 0xd8 && (b[p + 2] & 0xff) == 0xff) {
                return p;
            }
        }
        return -1;
    }

    private static int indexOf(byte[] h, byte[] n, int from) {
        if (n.length == 0) return -1;
        outer:
        for (int p = Math.max(0, from); p + n.length <= h.length; p++) {
            for (int i = 0; i < n.length; i++) {
                if (h[p + i] != n[i]) continue outer;
            }
            return p;
        }
        return -1;
    }

    // ------------------------------------------------------------------ 小工具

    /** 切一段出来并校验 mime；不认识的一律当没有 */
    private static Art slice(byte[] b, int from, int to, String mime) {
        if (from < 0 || to > b.length || to - from < 512) return null;
        if (to - from > IMG_MAX) return null;
        String m = mime;
        if (m == null) return null;
        boolean ok = false;
        for (String s : MIME) {
            if (s.equals(m)) {
                ok = true;
                break;
            }
        }
        if (!ok) {
            // mime 写着 jpg/jpe 或干脆是空串的，按魔数纠正
            if (b[from] == (byte) 0xff && b[from + 1] == (byte) 0xd8) m = "image/jpeg";
            else if (b[from] == (byte) 0x89 && b[from + 1] == 'P') m = "image/png";
            else return null;
        }
        byte[] d = new byte[to - from];
        System.arraycopy(b, from, d, 0, d.length);
        return new Art(m, d);
    }

    private static int be32(byte[] b, int p) {
        if (p + 4 > b.length) return -1;
        return ((b[p] & 0xff) << 24) | ((b[p + 1] & 0xff) << 16)
                | ((b[p + 2] & 0xff) << 8) | (b[p + 3] & 0xff);
    }

    /** ID3 的 syncsafe 整数：4 字节各取低 7 位 */
    private static int syncsafe(byte[] b, int p) {
        if (p + 4 > b.length) return -1;
        return ((b[p] & 0x7f) << 21) | ((b[p + 1] & 0x7f) << 14)
                | ((b[p + 2] & 0x7f) << 7) | (b[p + 3] & 0x7f);
    }

    /** 给别的类用的空表，避免每次返回 null 时新建 */
    static final HashMap<String, String> NONE = new HashMap<>();
}
