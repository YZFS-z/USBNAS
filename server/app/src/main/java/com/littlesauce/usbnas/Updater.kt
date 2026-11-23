package com.littlesauce.usbnas

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 服务端 App 的自动更新：查 GitHub Releases，告诉用户有没有新版本，并带他去下载。
 *
 * <h3>下载策略：只查不自动下，用户点了才下</h3>
 * 服务端 App 装在一台常年当 NAS 用的手机上，它的定位是「插上电、别管它」。
 * 自动下载 8MB 的 APK 再弹系统安装器，会在这台机器的屏幕上插一杠子，
 * 而这台机器的屏幕此刻可能正盖在抽屉里、或者正在当相框显示照片。
 * 所以这里的策略是：
 *   <ul>
 *   <li>自动检查只查、只提示 —— 一个 GET，几十 KB 的 JSON；</li>
 *   <li>用户点了「去下载」才真的下 —— <b>在 App 内下完并直接交给系统安装器</b>；</li>
 *   <li>默认 <b>关闭</b>，开关在首页「自动更新」按钮和网页 /_admin 里都能改。</li>
 *   </ul>
 *
 * <h3>为什么不能把下载交给浏览器（1.0.1 及以前的做法，是个 bug）</h3>
 * 之前「去下载」是 openUrl(release 直链)，指望浏览器下完之后装。实际情况是：
 * 浏览器只会把文件塞进它自己的下载目录（通常是 Download/），不会弹安装器；
 * 用户点完之后屏幕上什么也没发生，也不知道文件去哪儿了。
 * 更糟的是，服务端 App 发的包名是 com.littlesauce.usbnas，
 * 而浏览器里下载的那个链接是 usbnas-*.apk，
 * 用户下次在客户端里点「检查更新」，下的是 usbnaslink-*.apk，两边文件名前缀只差一个词，
 * 极易混淆。所以现在改成自己下、自己校验、自己交给安装器，并且把落盘路径写进日志。
 *
 * <h3>限流</h3>
 * GitHub 匿名访问是每小时 60 次。这里两道闸：
 * [MIN_GAP_MS] 保证不会频繁查，lastCheck 跨重启保留，
 * 再加上「只在前台可见时才查一次」，一天最多也就几次。
 */
object Updater {

    /** 仓库坐标。发布新版本改这里（或让它在每次发版时保持一致即可） */
    const val OWNER = "YZFS-z"
    const val REPO = "USBNAS"

    private const val API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** 兜底用：release 里没有匹配资产、或者下载失败时，把用户送到这一页自己挑 */
    private const val PAGE = "https://github.com/$OWNER/$REPO/releases"

    /**
     * 下载资产的第二条腿：`https://api.github.com/repos/O/R/releases/assets/<id>`。
     *
     * 加这条的原因是真机实测出来的：这台机器上 `api.github.com` 通（200/600ms），
     * 而 `github.com`（`browser_download_url` 的域名）连 TCP 之后就不回应，
     * 15 秒超时。只押 `browser_download_url` 的话，用户能看到「有新版本 1.0.1」，
     * 点下载却永远失败。API 这条腿要求 `Accept: application/octet-stream`，
     * 否则返回的是那段 JSON 元数据而不是文件。
     */
    private const val ASSET_API = "https://api.github.com/repos/$OWNER/$REPO/releases/assets/"

    /** 两次自动检查之间至少隔 12 小时：这台机器可能一直开着，不能每天狂查 */
    const val MIN_GAP_MS = 12L * 60 * 60 * 1000

    /** 挑哪个资产：服务端 App 只发 arm64 包（现役机器全是 64 位 ARM） */
    const val ASSET_SUFFIX = "-arm64-v8a.apk"

    /** 单次查询超时 */
    private const val TIMEOUT_MS = 8000

    /** release 正文最多留这么长，避免把几万字塞进对话框 */
    private const val NOTES_MAX = 1200

    private const val TAG = "USBNASUpdater"

    /** FileProvider 的 authority，必须和 AndroidManifest 里写的一致 */
    private const val AUTHORITY = "com.littlesauce.usbnas.fileprovider"

    /** 一次检查的结果 */
    data class Info(
        val ok: Boolean,
        val error: String = "",
        val tag: String = "",
        val version: String = "",
        val name: String = "",
        val notes: String = "",
        val pageUrl: String = "",
        val assetUrl: String = "",
        val assetName: String = "",
        val assetSize: Long = 0L,
        /** 资产的数字 id：`browser_download_url` 走不通时用它去 api.github.com 取 */
        val assetId: Long = 0L
    )

    /**
     * 检查更新。**必须**在后台线程调。
     *
     * @param currentVersion 本机版本，形如 "1.0.0"
     * @param suffix         资产名后缀，null 表示随便挑第一个
     */
    fun check(currentVersion: String, suffix: String? = ASSET_SUFFIX): Info {
        return try {
            val body = get(API)
            val o = JSONObject(body)
            val tag = o.optString("tag_name", "")
            val version = trimTag(tag)
            if (version.isEmpty()) return Info(false, "这个 release 没有 tag，无法比较版本")
            val notes = o.optString("body", "").let {
                if (it.length > NOTES_MAX) it.substring(0, NOTES_MAX) + "\n…" else it
            }

            var assetUrl = ""
            var assetName = ""
            var assetSize = 0L
            var assetId = 0L
            val arr: JSONArray = o.optJSONArray("assets") ?: JSONArray()
            // 先看精确后缀，再退一步看包含；都没命中就不给下载按钮 ——
            // 宁可让用户手动去 release 页，也不给他一个装不上的包。
            for (pass in 0..1) {
                if (assetUrl.isNotEmpty()) break
                for (i in 0 until arr.length()) {
                    val a = arr.optJSONObject(i) ?: continue
                    val n = a.optString("name", "").lowercase()
                    if (n.isEmpty()) continue
                    val hit = if (suffix == null) true
                    else if (pass == 0) n.endsWith(suffix.lowercase())
                    else n.contains(suffix.lowercase())
                    if (hit) {
                        assetUrl = a.optString("browser_download_url", "")
                        assetName = a.optString("name", "")
                        assetSize = a.optLong("size", 0L)
                        // 资产在 API 里的数字 id。下载时 github.com 走不通还可以用
                        // /releases/assets/<id> 走 api.github.com 这条腿（见 fetchAsset）
                        assetId = a.optLong("id", 0L)
                        break
                    }
                }
            }

            Info(
                ok = true,
                tag = tag,
                version = version,
                name = o.optString("name", ""),
                notes = notes,
                pageUrl = o.optString("html_url", ""),
                assetUrl = assetUrl,
                assetName = assetName,
                assetSize = assetSize,
                assetId = assetId
            )
        } catch (t: Throwable) {
            Info(false, describe(t))
        }
    }

    /** GitHub 的 tag 通常是 v1.2.3，去掉前缀后才能整段按数字比 */
    fun trimTag(s: String?): String {
        var t = s?.trim() ?: return ""
        if (t.isNotEmpty() && (t[0] == 'v' || t[0] == 'V')) t = t.substring(1)
        return t
    }

    /**
     * 版本比较：{@code 1.10.0 > 1.9.9}。
     * 每段只取前导数字，缺段补 0，非数字段当 0 —— 任何时候都不抛异常。
     */
    fun compare(a: String?, b: String?): Int {
        val sa = trimTag(a).split('.')
        val sb = trimTag(b).split('.')
        val n = maxOf(sa.size, sb.size)
        for (i in 0 until n) {
            val x = num(sa, i)
            val y = num(sb, i)
            if (x != y) return if (x < y) -1 else 1
        }
        return 0
    }

    private fun num(parts: List<String>, i: Int): Long {
        if (i >= parts.size) return 0L
        val s = parts[i].trim()
        var end = 0
        while (end < s.length && s[end].isDigit()) end++
        if (end == 0) return 0L
        return try {
            s.substring(0, end).toLong()
        } catch (e: NumberFormatException) {
            0L
        }
    }

    /** 有新版本且不是用户跳过的那个 */
    fun newer(info: Info, currentVersion: String, skip: String): Boolean {
        if (!info.ok) return false
        if (info.version.isEmpty()) return false
        if (skip.isNotEmpty() && compare(info.version, skip) == 0) return false
        return compare(info.version, currentVersion) > 0
    }

    /**
     * 该不该现在自动查一次。
     *
     * @param force 用户手动点的「检查更新」传 true，绕过开关与间隔
     */
    fun due(ctx: Context, force: Boolean): Boolean {
        if (force) return true
        if (!AppPrefs.autoUpdate(ctx)) return false
        val last = AppPrefs.lastUpdateCheck(ctx)
        if (last <= 0L) return true
        return System.currentTimeMillis() - last >= MIN_GAP_MS
    }

    /**
     * 自动检查：切到后台线程，查到新版本才弹对话框；没有新版本静默。
     *
     * 用户在首页停留时每次 onResume 调一次即可，内部有两道闸会把它变成
     * 「最多 12 小时一次」。{@code manual=true} 时无论有没有都会给一句反馈，
     * 因为用户点了按钮，什么都不发生会以为程序死了。
     */
    fun autoCheck(activity: Activity, currentVersion: String, manual: Boolean) {
        if (!due(activity, manual)) return
        Thread {
            val info = check(currentVersion)
            AppPrefs.setLastUpdateCheck(activity, System.currentTimeMillis())
            val skip = AppPrefs.skipVersion(activity)
            activity.runOnUiThread {
                if (!info.ok) {
                    if (manual) {
                        Toast.makeText(activity, "检查失败：" + info.error, Toast.LENGTH_LONG).show()
                    }
                    return@runOnUiThread
                }
                if (!newer(info, currentVersion, skip)) {
                    if (manual) {
                        Toast.makeText(
                            activity,
                            "已经是最新版本（" + currentVersion + "）",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    return@runOnUiThread
                }
                showDialog(activity, info, currentVersion)
            }
        }.apply { isDaemon = true }.start()
    }

    /** 把检查结果摆成对话框：版本号 + 更新说明 + 三个出口 */
    fun showDialog(activity: Activity, info: Info, currentVersion: String) {
        val sb = StringBuilder()
        sb.append("当前 ").append(currentVersion).append(" → 最新 ").append(info.version)
        if (info.assetSize > 0) {
            sb.append("\n安装包 ").append(fmtSize(info.assetSize))
        }
        if (info.notes.isNotEmpty()) {
            sb.append("\n\n").append(info.notes)
        }
        sb.append("\n\n")
        if (info.assetUrl.isNotEmpty()) {
            sb.append("点「下载并安装」后在本机下载，完成后自动交给系统安装器；")
            sb.append("覆盖安装，设置与账号都保留。")
        } else {
            sb.append("这个版本没有可下载的安装包，会打开发布页。")
        }

        AlertDialog.Builder(activity)
            .setTitle("发现新版本 " + info.version)
            .setMessage(sb.toString())
            .setPositiveButton(if (info.assetUrl.isNotEmpty()) "下载并安装" else "打开发布页") { _, _ ->
                if (info.assetUrl.isNotEmpty()) download(activity, info)
                else if (info.pageUrl.isNotEmpty()) openUrl(activity, info.pageUrl)
                else Toast.makeText(activity, "这个版本没有可下载的安装包", Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("跳过此版本") { _, _ ->
                AppPrefs.setSkipVersion(activity, info.version)
                Toast.makeText(activity, "以后不再提示 " + info.version, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    /**
     * 在本机下载 APK，完成后直接交给系统安装器。
     *
     * 落盘在 `cacheDir/update/usbnas-<version>.apk`，走 FileProvider 的
     * `cache-path update/`（见 res/xml/update_paths.xml）。
     * 下载完先比大小：GitHub 的直链会 302 到 objects.githubusercontent.com，
     * 中途断流时 downloadUrl 返回 false，这里就删掉半截文件重来，不留垃圾。
     */
    private fun download(activity: Activity, info: Info) {
        val assetUrl = info.assetUrl
        if (assetUrl.isEmpty()) {
            openUrl(activity, if (info.pageUrl.isNotEmpty()) info.pageUrl else PAGE)
            return
        }

        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
        }
        val label = TextView(activity).apply {
            text = "连接中…"
            setTextColor(0xFF9AA4B2.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(activity, 12), 0, 0)
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 22), dp(activity, 18), dp(activity, 22), dp(activity, 6))
            addView(bar)
            addView(label)
        }
        val dlg = AlertDialog.Builder(activity)
            .setTitle("正在下载 " + info.version)
            .setView(box)
            .setCancelable(false)
            .create()
        dlg.show()

        val dir = File(activity.cacheDir, "update").apply { mkdirs() }
        // 文件名带版本号：装完不清 cache 也不会被下一次下载覆盖到一半
        val out = File(dir, "usbnas-" + info.version + ".apk")

        Thread {
            var err: String? = null
            try {
                // 两条腿依次试。国内网络里 api.github.com 常常通、github.com 主站不通，
                // 反过来也有；只押一个域名会让「检查得到更新、却永远下不下来」。
                val sources = ArrayList<Pair<String, Boolean>>()
                if (info.assetId > 0) sources.add(ASSET_API + info.assetId to true)
                if (assetUrl.isNotEmpty()) sources.add(assetUrl to false)
                var ok = false
                var lastErr = ""
                for ((src, needAccept) in sources) {
                    try {
                        if (nameOf(src, needAccept) == null) continue // 这个源试不通，换下一个
                        ok = downloadUrl(src, out, needAccept) { done, total ->
                            activity.runOnUiThread {
                                if (total > 0) {
                                    bar.progress = (done * 1000 / total).toInt()
                                    label.text = (done * 100 / total).toString() + "%  " +
                                            fmtSize(done) + " / " + fmtSize(total)
                                } else {
                                    label.text = fmtSize(done)
                                }
                            }
                        }
                        if (ok) break
                    } catch (t: Throwable) {
                        lastErr = describe(t)
                        out.delete()
                    }
                }
                if (!ok) throw java.io.IOException(if (lastErr.isEmpty()) "下载不完整" else lastErr)
                // GitHub 给的 size 是发布时的大小；对不上说明拿到的是错误页
                if (info.assetSize > 0 && out.length() != info.assetSize) {
                    throw java.io.IOException(
                        "大小对不上（" + out.length() + " / " + info.assetSize + "）"
                    )
                }
            } catch (t: Throwable) {
                err = describe(t)
                out.delete()
            }
            activity.runOnUiThread {
                dlg.dismiss()
                if (err != null) {
                    toast(activity, "下载失败：" + err)
                    // 下载失败才退回浏览器，让用户至少还有一条路
                    openUrl(activity, if (info.pageUrl.isNotEmpty()) info.pageUrl else PAGE)
                    return@runOnUiThread
                }
                install(activity, out, info.version)
            }
        }.apply { isDaemon = true }.start()
    }

    /** 交给系统安装器。file:// 在 Android 7+ 会抛 FileUriExposedException，必须走 content:// */
    private fun install(activity: Activity, apk: File, version: String) {
        // 先查一次「安装未知应用」有没有放行。
        //
        // 这个开关不查也能拉起安装器，但没开时安装器会原地卡在「无法安装」那一屏，
        // 用户看不到任何一句「为什么」。在这里先问一次，可以给出直达设置的按钮。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            Log.w(TAG, "install: canRequestPackageInstalls=false, ask user")
            toast(activity, "还差一步：请允许「USBNAS」安装应用")
            try {
                val i = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.packageName)
                )
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(i)
            } catch (t: Throwable) {
                Log.e(TAG, "open unknown-sources failed: " + t)
            }
            return
        }
        try {
            val uri: Uri = FileProvider.getUriForFile(activity, AUTHORITY, apk)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(i)
            Log.i(TAG, "install " + version + " -> " + apk.absolutePath)
            toast(activity, "安装包已下载：" + apk.absolutePath)
        } catch (t: Throwable) {
            // 定制 ROM 可能没装安装器组件
            Log.e(TAG, "install failed: " + t)
            toast(
                activity,
                "打不开安装器：" + t.javaClass.simpleName +
                        "，安装包在 " + apk.absolutePath +
                        "（若提示被拦截，去「安装未知应用」里放行一次）"
            )
        }
    }

    private fun toast(activity: Activity, msg: String) {
        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
        // MIUI 的 Toast 不进 logcat 文本，也不进 uiautomator 树，调试时只能靠自打一行
        Log.i(TAG, "toast: " + msg)
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    /** 用系统浏览器/下载器打开一个 URL */
    fun openUrl(ctx: Context, url: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (t: Throwable) {
            Toast.makeText(ctx, "打不开链接：" + url, Toast.LENGTH_LONG).show()
        }
    }

    fun fmtSize(n: Long): String = when {
        n <= 0 -> "—"
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> (n / 1024).toString() + " KB"
        else -> String.format(java.util.Locale.ROOT, "%.1f MB", n / 1048576.0)
    }

    // ------------------------------------------------------------------ 内部

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            // 匿名访问公开仓库就够了；带 token 要用户去建 PAT，不值
            c.setRequestProperty("User-Agent", "USBNAS/1.0 (+update)")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            when (c.responseCode) {
                404 -> throw java.io.IOException("还没有发布过 release")
                403 -> throw java.io.IOException("GitHub 限流了，稍后再试")
            }
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP " + c.responseCode)
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** 把异常翻成一句人话，别把 UnknownHostException 原文甩给用户 */
    private fun describe(t: Throwable): String {
        val cls = t.javaClass.simpleName
        val m = t.message ?: ""
        return when {
            cls.contains("UnknownHost") -> "连不上 GitHub（检查网络或 DNS）"
            cls.contains("SocketTimeout") -> "连 GitHub 超时，稍后再试"
            cls.contains("SSL") || cls.contains("Cert") -> "HTTPS 握手失败（系统时间或证书有问题）"
            m.startsWith("HTTP ") -> "GitHub 返回 " + m.substring(5)
            else -> if (m.isEmpty()) cls else m
        }
    }

    // ------------------------------------------------------------------ 下载

    /**
     * 一个下载源能不能用：发一次极短的探测请求，200/206/302 才算通。
     *
     * 不直接进 downloadUrl 是因为它的超时是 20 秒，而这里要的是「快速判死」——
     * github.com 在国内经常是连得上 TCP 但 TLS 之后没响应，硬等 20 秒用户会以为卡死。
     */
    private fun nameOf(url: String, needAccept: Boolean): String? {
        var c: HttpURLConnection? = null
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            c = conn
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "USBNAS/1.0 (+update)")
            if (needAccept) conn.setRequestProperty("Accept", "application/octet-stream")
            conn.setRequestProperty("Range", "bytes=0-0")
            val code = conn.responseCode
            if (code in 200..399) url else null
        } catch (t: Throwable) {
            null
        } finally {
            c?.disconnect()
        }
    }

    /** 把一个 URL 下到文件里，边下边回调进度。 */
    private fun downloadUrl(url: String, out: File, needAccept: Boolean, onStep: (Long, Long) -> Unit): Boolean {
        val part = File(out.absolutePath + ".part")
        part.parentFile?.mkdirs()
        if (part.exists()) part.delete()
        var c: HttpURLConnection? = null
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            c = conn
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = 20000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "USBNAS/1.0 (+update)")
            // GitHub 的资产直链会 302 到 objects.githubusercontent.com。
            // /releases/assets/<id> 这条腿必须声明 octet-stream，否则拿到的是 JSON 元数据。
            if (needAccept) conn.setRequestProperty("Accept", "application/octet-stream")
            val code = conn.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP " + code)
            val total = conn.contentLengthLong
            var done = 0L
            var lastMark = 0L
            conn.inputStream.use { ins ->
                part.outputStream().use { fos ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                        done += n
                        if (done - lastMark >= 256 * 1024) {
                            lastMark = done
                            onStep(done, total)
                        }
                    }
                    fos.flush()
                }
            }
            onStep(done, total)
            // total<=0 说明服务端没给 Content-Length，只能看下到多少算多少
            if (total > 0 && done != total) throw java.io.IOException("只下了 " + done + " / " + total)
            if (out.exists()) out.delete()
            if (!part.renameTo(out)) {
                part.copyTo(out, overwrite = true)
                part.delete()
            }
            return true
        } catch (t: Throwable) {
            part.delete()
            throw t
        } finally {
            c?.disconnect()
        }
    }
}
