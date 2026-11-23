package com.littlesauce.usbnas;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内嵌封面的 LRU 缓存。
 *
 * 单独成类而不是在主类里写匿名 {@code LinkedHashMap} 子类：那个写法里
 * {@code removeEldestEntry} 的参数类型和 {@code CoverArt.Entry} 会互相遮蔽，
 * 无论怎么写都有一边的 {@code art} 字段解析不到（实测 javac 直接报
 * 「找不到符号: 变量 art」）。拆出来以后类型就干净了。
 *
 * 淘汰规则两条同时生效：
 * <ol>
 *   <li>条数超过 {@link #MAX_COUNT}；</li>
 *   <li>累计字节超过 {@link #MAX_BYTES}（单张封面最大 4MB，只按条数收会到近百 MB）。</li>
 * </ol>
 */
final class ArtCache {

    /** 最多留多少张封面 */
    static final int MAX_COUNT = 24;
    /** 累计最多占多少字节 */
    static final long MAX_BYTES = 16L * 1024 * 1024;

    /** 一条缓存记录：内容 + 抽取时看到的文件大小与时间，文件一改就失效 */
    static final class Rec {
        final String mime;      // null 表示「这个文件确认没有内嵌封面」
        final byte[] data;
        final long size;
        final long mtime;

        Rec(String mime, byte[] data, long size, long mtime) {
            this.mime = mime;
            this.data = data;
            this.size = size;
            this.mtime = mtime;
        }
    }

    private final LinkedHashMap<String, Rec> map =
            new LinkedHashMap<String, Rec>(32, 0.75f, true);

    /** 读：命中且文件未变才返回，否则 null */
    synchronized Rec get(String key, long size, long mtime) {
        Rec r = map.get(key);
        if (r == null) return null;
        // 文件被换掉（大小或时间变了）就当没命中，顺手清掉旧记录
        if (r.size != size || r.mtime != mtime) {
            map.remove(key);
            return null;
        }
        return r;
    }

    /** 写：先放进去，再按条数与字节两个上限从最旧的开始淘汰 */
    synchronized void put(String key, Rec r) {
        map.put(key, r);
        while (map.size() > MAX_COUNT && !map.isEmpty()) {
            dropOldest();
        }
        while (bytes() > MAX_BYTES && map.size() > 1) {
            dropOldest();
        }
    }

    /** 当前累计字节数，只在淘汰时算一次，不是热路径 */
    private long bytes() {
        long total = 0;
        Iterator<Rec> it = map.values().iterator();
        while (it.hasNext()) {
            Rec r = it.next();
            if (r != null && r.data != null) total += r.data.length;
        }
        return total;
    }

    /** accessOrder = true 时，迭代器给出的第一条就是最久没用的 */
    private void dropOldest() {
        Iterator<Map.Entry<String, Rec>> it = map.entrySet().iterator();
        if (it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    /** 测试与排障用 */
    synchronized int size() {
        return map.size();
    }
}
