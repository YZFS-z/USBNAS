package com.littlesauce.usbnas

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.util.Log
import java.io.File

/**
 * 找出所有值得共享的根目录。
 *
 * Android 上 U 盘挂载点通常是 /storage/XXXX-XXXX，
 * 内置存储在 /storage/emulated/0，
 * 只读原始挂载在 /mnt/media_rw/XXXX-XXXX。
 */
object StorageUtils {

    private const val TAG = "StorageUtils"

    fun listRoots(ctx: Context): List<File> {
        val out = LinkedHashMap<String, File>()

        // 1. StorageManager 卷列表（最靠谱）
        try {
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            for (vol in sm.storageVolumes) {
                if (vol.isPrimary) {
                    vol.directory?.let { addIfReadable(out, it) }
                    continue
                }
                // 可移除卷：U 盘 / SD 卡
                var dir: File? = null
                try {
                    dir = vol.directory
                } catch (e: Exception) {
                    // ignore
                }
                if (dir != null && dir.exists()) {
                    addIfReadable(out, dir)
                } else {
                    val uuid = vol.uuid
                    if (!uuid.isNullOrEmpty()) addIfReadable(out, File("/storage/$uuid"))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "storageVolumes 失败", e)
        }

        // 2. 扫描 /storage 下所有目录（兜底：UUID 形态的 U 盘）
        try {
            File("/storage").listFiles()?.forEach { f ->
                val n = f.name
                // emulated / self 跳过（self 是符号链接）
                if (n == "emulated" || n == "self") return@forEach
                if (f.isDirectory && f.canRead()) addIfReadable(out, f)
            }
        } catch (e: Exception) {
            Log.w(TAG, "scan /storage 失败", e)
        }

        // 3. 内置存储
        try {
            val ext = Environment.getExternalStorageDirectory()
            if (ext != null && ext.exists()) addIfReadable(out, ext)
        } catch (e: Exception) {
            Log.w(TAG, "external 失败", e)
        }

        // 4. /mnt/media_rw（读得到就共享，注意可能只读）
        try {
            File("/mnt/media_rw").listFiles()?.forEach { f ->
                if (f.isDirectory && f.canRead()) addIfReadable(out, f)
            }
        } catch (e: Exception) {
            // 无权限，正常
        }

        return out.values.toList()
    }

    /** U 盘是否插着 */
    fun hasRemovable(ctx: Context): Boolean = try {
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        sm.storageVolumes.any { !it.isPrimary }
    } catch (e: Exception) {
        false
    }

    /** 该路径是否可写（U 盘若是只读挂载则不能写） */
    fun isWritable(f: File): Boolean = f.exists() && f.canWrite()

    private fun addIfReadable(map: LinkedHashMap<String, File>, dir: File) {
        val key = dir.absolutePath
        if (map.containsKey(key)) return
        if (!dir.exists() || !dir.isDirectory || !dir.canRead()) return
        map[key] = dir
    }
}
