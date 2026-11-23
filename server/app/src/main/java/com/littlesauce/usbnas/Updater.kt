package com.littlesauce.usbnas

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 服务端 App 的自动更新：查 GitHub Releases，告诉用户有没有新版本，并带他去下载。
 *
 * <h3>为什么不像客户端那样自己下载安装</h3>
 * 服务端 App 装在一台常年当 NAS 用的手机上，它的定位是「插上电、别管它」。
 * 自动下载 8MB 的 APK 再弹系统安装器，会在这台机器的屏幕上插一杠子，
 * 而这台机器的屏幕此刻可能正盖在抽屉里、或者正在当相框显示照片。
 * 所以这里的策略是：
 *   <ul>
 *   <li>只查、只提示 —— 一个 GET，几十 KB 的 JSON；</li>
 *   <li>用户点了「去下载」才把 release 页面交给浏览器，下载与安装都由系统管；</li>
 *   <li>默认 <b>关闭</b>，开关在首页「自动更新」按钮和网页 /_admin 里都能改。</li>
 *   </ul>
 *
 * <h3>限流</h3>
 * GitHub 匿名访问是每小时 60 次。这里两道闸：
 * {@link #MIN_GAP_MS} 保证不会频繁查，{@link #lastCheck} 跨重启保留，
 * 再加上「只在前台可见时才查一次」，一天最多也就几次。
 */
object Updater {

    /** 仓库坐标。发布新版本改这里（或让它在每次发版时保持一致即可） */
    const val OWNER = "YZFS-z"
    const val REPO = "USBNAS"

    private const val API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** 两次自动检查之间至少隔 12 小时：这台机器可能一直开着，不能每天狂查 */
    const val MIN_GAP_MS = 12L * 60 * 60 * 1000

    /** 挑哪个资产：服务端 App 只发 arm64 包（现役机器全是 64 位 ARM） */
    const val ASSET_SUFFIX = "-arm64-v8a.apk"

    /** 单次查询超时 */
    private const val TIMEOUT_MS = 8000

    /** release 正文最多留这么长，避免把几万字塞进对话框 */
    private const val NOTES_MAX = 1200

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
        val assetSize: Long = 0L
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
                assetSize = assetSize
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
        sb.append("\n\n下载后由系统安装器覆盖安装，设置与账号都保留。")

        // 优先给直链（点一下就开始下），没有匹配资产才退回 release 页面
        val target = if (info.assetUrl.isNotEmpty()) info.assetUrl else info.pageUrl

        AlertDialog.Builder(activity)
            .setTitle("发现新版本 " + info.version)
            .setMessage(sb.toString())
            .setPositiveButton("去下载") { _, _ ->
                if (target.isNotEmpty()) openUrl(activity, target)
                else Toast.makeText(activity, "这个版本没有可下载的安装包", Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("跳过此版本") { _, _ ->
                AppPrefs.setSkipVersion(activity, info.version)
                Toast.makeText(activity, "以后不再提示 " + info.version, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("稍后", null)
            .show()
    }

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
}
