package com.littlesauce.usbnaslink;

/**
 * 相册里的一张图。
 *
 * <p>字段与服务端 {@code /_photos} 的 JSON 一一对应，没有额外计算：
 * 服务端已经读过文件头拿到宽高，客户端再解一次纯属浪费。
 */
public final class Photo {

    /** 服务端绝对路径，取原图 / 缩略图都以它为准 */
    public final String path;
    /** 文件名（含扩展名），列表里没别的可用时显示它 */
    public final String name;
    /** 所在目录绝对路径，用来分组 */
    public final String dir;
    public final long size;
    public final long mtime;
    /** 宽高；服务端读不出（坏文件 / 不认识的格式）就是 0 */
    public final int width;
    public final int height;

    public Photo(String path, String name, String dir, long size, long mtime, int width, int height) {
        this.path = path == null ? "" : path;
        this.name = name == null ? "" : name;
        this.dir = dir == null ? "" : dir;
        this.size = size;
        this.mtime = mtime;
        this.width = width;
        this.height = height;
    }

    /** 宽高都有效时才有意义；用来给格子算占位比例，避免瀑布流跳一下 */
    public boolean hasSize() {
        return width > 0 && height > 0;
    }

    /** 宽高比（宽 ÷ 高）。没有尺寸时按 1 处理，格子退化成正方形 */
    public float ratio() {
        if (!hasSize()) return 1f;
        return (float) width / (float) height;
    }

    /** 列表里显示的名字：优先文件名去掉扩展名 */
    public String title() {
        int dot = name.lastIndexOf('.');
        if (dot > 0) return name.substring(0, dot);
        return name.isEmpty() ? path : name;
    }

    @Override
    public String toString() {
        return title();
    }
}
