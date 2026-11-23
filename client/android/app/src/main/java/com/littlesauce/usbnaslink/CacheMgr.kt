package com.littlesauce.usbnaslink

import android.content.Context
import android.util.Log
import java.io.File

/**
 * App 自己产生的磁盘占用清理。
 *
 * <p>为什么要专门写一个类：这个 App 的缓存不是系统「应用信息 → 清除缓存」里那一个数字
 * 能覆盖的，实际大头有三块，各由不同代码路径写入、都从不回收：
 *
 * <ul>
 *   <li>`cache/pdf/` —— 每次打开一本 PDF 都把整本拉下来（实测 467 页那本 10MB，
 *       几次之后这个目录 205MB），文件名是路径哈希，同一本书会复用，但**换一本书就是一份新文件**；</li>
 *   <li>`cache/update/` —— 自动更新下载的 APK（每个 18MB 左右），装完就没人删；
 *       跨版本反复检查会一直累加；</li>
 *   <li>`cache/pdf/` 下的 `.part` —— 下载中途被杀掉留下的半截文件，永远没人碰。</li>
 * </ul>
 *
 * <p>策略：**按总量 + 按时间双重限额，不做「打开就清」**，因为用户可能来回翻同一本书。
 * 每次打开新书时调用 [trimPdf]，超过阈值才淘汰最旧的文件，留最近的一两本。
 * 这样既不打断正在读的书，也不会让目录无限长。
 *
 * <p>刻意不碰 WebView 缓存：那是网页页签的滚动位置与登录态的一部分，
 * 删了会让「网页」页签每次重新加载，体验倒退。
 */
object CacheMgr {

    private const val TAG = "USBNASLink"

    /** PDF 缓存总量上限：两本大书 + 余量。超过就按修改时间从旧到新删到线以下。 */
    private const val PDF_MAX_BYTES = 120L * 1024 * 1024

    /** 最多保留几本。总量没超但本数太多时也淘汰（小文件一堆同样占名额） */
    private const val PDF_MAX_FILES = 6

    /** 更新包只留当前这一份最新的（旧的已装完，没用了） */
    private const val UPDATE_KEEP = 1

    /** 统计结果，给设置页显示 */
    data class Usage(
        val pdfBytes: Long = 0,
        val pdfFiles: Int = 0,
        val updateBytes: Long = 0,
        val updateFiles: Int = 0
    ) {
        val totalBytes: Long get() = pdfBytes + updateBytes
        fun human(b: Long): String {
            if (b < 1024) return "$b B"
            val kb = b / 1024.0
            if (kb < 1024) return String.format("%.0f KB", kb)
            val mb = kb / 1024.0
            if (mb < 1024) return String.format("%.1f MB", mb)
            return String.format("%.2f GB", mb / 1024.0)
        }
        fun text(): String = "PDF ${human(pdfBytes)}（$pdfFiles 个） · 安装包 ${human(updateBytes)}（$updateFiles 个）"
    }

    fun pdfDir(ctx: Context): File = File(ctx.cacheDir, "pdf")

    fun updateDir(ctx: Context): File = File(ctx.cacheDir, "update")

    /** 统计用，给「管理设置」页显示一行 */
    fun usage(ctx: Context): Usage {
        val ps = files(pdfDir(ctx), ".pdf")
        val us = files(updateDir(ctx), ".apk")
        return Usage(
            pdfBytes = ps.sumOf { it.length() },
            pdfFiles = ps.size,
            updateBytes = us.sumOf { it.length() },
            updateFiles = us.size
        )
    }

    /**
     * 清掉 pdf 目录里超过限额的部分。
     *
     * @param keep 正在读的这份不能删（它的 File 还被 PDFium 打开着），传 null 表示没有
     * @return 删掉的字节数
     */
    @JvmStatic
    fun trimPdf(ctx: Context, keep: File?): Long {
        val dir = pdfDir(ctx)
        if (!dir.isDirectory) return 0
        // 半截文件先清：.part 要么是上次被杀留下的，要么是本次正在写的。
        // 正在写的那份会被 download() 自己 rename，所以这里只删「不是 keep 的 .part」，
        // 且要求已经放置超过 1 小时，避免删掉另一线程正常下载中的临时文件。
        var freed = 0L
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".part") && f != keep &&
                now - f.lastModified() > 3600_000L
            ) {
                val n = f.length()
                if (f.delete()) freed += n
            }
        }
        val pdfs = files(dir, ".pdf").sortedBy { it.lastModified() }  // 最旧在前
        var total = pdfs.sumOf { it.length() }
        var count = pdfs.size
        for (f in pdfs) {
            if (total <= PDF_MAX_BYTES && count <= PDF_MAX_FILES) break
            if (keep != null && f.absolutePath == keep.absolutePath) continue
            val n = f.length()
            if (f.delete()) {
                total -= n
                count -= 1
                freed += n
            }
        }
        if (freed > 0) Log.i(TAG, "trimPdf freed=$freed now=$total files=$count")
        return freed
    }

    /** 清掉除最新一个之外的安装包（新版本下载完装好之后调用） */
    @JvmStatic
    fun trimUpdate(ctx: Context): Long {
        val dir = updateDir(ctx)
        if (!dir.isDirectory) return 0
        var freed = 0L
        // .part 同样是半截，超过 1 小时就清
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".part") && now - f.lastModified() > 3600_000L) {
                val n = f.length()
                if (f.delete()) freed += n
            }
        }
        val apks = files(dir, ".apk").sortedByDescending { it.lastModified() }
        for (i in UPDATE_KEEP until apks.size) {
            val n = apks[i].length()
            if (apks[i].delete()) freed += n
        }
        if (freed > 0) Log.i(TAG, "trimUpdate freed=$freed")
        return freed
    }

    /** 全清（设置页的「清空缓存」按钮）。不动 WebView。 */
    fun clearAll(ctx: Context): Long {
        var freed = 0L
        for (d in listOf(pdfDir(ctx), updateDir(ctx))) {
            d.listFiles()?.forEach { f ->
                val n = if (f.isFile) f.length() else 0L
                if (f.deleteRecursively()) freed += n
            }
        }
        Log.i(TAG, "clearAll freed=$freed")
        return freed
    }

    private fun files(dir: File, suffix: String): List<File> =
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(suffix) } ?: emptyList()
}
