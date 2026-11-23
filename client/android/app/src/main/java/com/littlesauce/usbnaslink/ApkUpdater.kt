package com.littlesauce.usbnaslink

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 客户端 APK 的自动更新。
 *
 * <h3>三条硬规则</h3>
 * <ol>
 *   <li><b>可以关</b>：开关在 {@code Prefs.autoUpdate} 里，默认<b>开</b> ——
 *       手机是随身设备，跟桌面端一样默认替用户留意新版本，但一次关掉就永远不查。</li>
 *   <li><b>只查一次</b>：每次冷启动最多查一次，且距上次检查不少于
 *       {@link #MIN_GAP_MS}；前台来回切不会反复请求。</li>
 *   <li><b>装不装由人</b>：下载完不自动装，弹系统安装器让用户亲手点。
 *       Android 上静默安装需要 root 或设备管理器权限，普通 App 没有，
 *       硬要绕只会变成要用户去开「未知来源」再被拦一次，反而更糟。</li>
 * </ol>
 *
 * <h3>为什么用 FileProvider</h3>
 * Android 7 起 {@code file://} 的 URI 交给别的 App 会抛
 * {@code FileUriExposedException}。所以下载到 {@code cache/update/} 之后必须经
 * {@code content://} 交出去，authorities 与 AndroidManifest 里声明的保持一致。
 */
object ApkUpdater {

    private const val TAG = "USBNASLink"

    /** 资产名后缀：这个包只发 arm64-v8a（现役手机全是 64 位 ARM） */
    private const val ASSET_SUFFIX = "-arm64-v8a.apk"

    /** 两次自动检查至少隔 6 小时（手机比 NAS 更常开开关关，但没必要每次启动都查） */
    private const val MIN_GAP_MS = 6L * 60 * 60 * 1000

    private const val TIMEOUT_MS = 8000

    /** 与 Update.OWNER/REPO 一致，这里单独写是为了失败时能直接拼页面地址 */
    private const val PAGE = "https://github.com/" + Update.OWNER + "/" + Update.REPO + "/releases"

    private const val AUTHORITY = "com.littlesauce.usbnaslink.fileprovider"

    // ---------------------------------------------------------------- 检查

    /**
     * 冷启动时调一次。用户没开就立刻返回，一个请求都不发。
     *
     * @param manual 用户主动点的，无论结果都给一句反馈
     */
    fun checkOnStart(activity: Activity, manual: Boolean) {
        val prefs = Prefs(activity)
        if (!manual) {
            if (!prefs.autoUpdate) return
            val last = prefs.lastUpdateCheck
            if (last > 0L && System.currentTimeMillis() - last < MIN_GAP_MS) return
        }
        Thread {
            val info = Update.check(versionName(activity), ASSET_SUFFIX)
            prefs.lastUpdateCheck = System.currentTimeMillis()
            activity.runOnUiThread {
                if (!info.ok) {
                    if (manual) toast(activity, "检查失败：" + info.error)
                    return@runOnUiThread
                }
                val skip = prefs.skipVersion
                if (!Update.newer(info, versionName(activity))) {
                    if (manual) toast(activity, "已经是最新版本（" + versionName(activity) + "）")
                    return@runOnUiThread
                }
                if (skip.isNotEmpty() && Update.compare(info.version, skip) == 0) {
                    if (manual) toast(activity, "这个版本（" + skip + "）已被你跳过")
                    return@runOnUiThread
                }
                showFound(activity, info)
            }
        }.apply { isDaemon = true }.start()
    }

    /** 发现新版本：说明 + 下载 / 跳过 / 稍后 */
    private fun showFound(activity: Activity, info: Update.Info) {
        val sb = StringBuilder()
        sb.append("当前 ").append(versionName(activity)).append(" → 最新 ").append(info.version)
        if (info.asset != null && info.asset.size > 0) {
            sb.append("\n安装包 ").append(info.asset.humanSize())
        }
        if (info.notes.isNotEmpty()) sb.append("\n\n").append(info.notes)
        sb.append("\n\n下载完会打开系统安装器，覆盖安装不影响已保存的服务器列表。")

        AlertDialog.Builder(activity)
            .setTitle("发现新版本 " + info.version)
            .setMessage(sb.toString())
            .setPositiveButton("下载并安装") { _, _ -> download(activity, info) }
            .setNeutralButton("跳过此版本") { _, _ ->
                Prefs(activity).skipVersion = info.version
                toast(activity, "以后不再提示 " + info.version)
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    // ---------------------------------------------------------------- 下载

    /** 下载到 cache/update/，进度走一个不可取消的进度对话框 */
    private fun download(activity: Activity, info: Update.Info) {
        val asset = info.asset
        if (asset == null) {
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
        val out = File(dir, "usbnaslink-${info.version}.apk")

        Thread {
            var err: String? = null
            try {
                val ok = Update.downloadUrl(asset.url, out) { done, total ->
                    activity.runOnUiThread {
                        if (total > 0) {
                            bar.progress = (done * 1000 / total).toInt()
                            label.text = (done * 100 / total).toString() + "%  " +
                                    fmt(done) + " / " + fmt(total)
                        } else {
                            label.text = fmt(done)
                        }
                    }
                }
                if (!ok) throw java.io.IOException("下载不完整")
            } catch (t: Throwable) {
                err = Update.describeForUser(t)
            }
            activity.runOnUiThread {
                dlg.dismiss()
                if (err != null) {
                    toast(activity, "下载失败：" + err)
                    openUrl(activity, if (info.pageUrl.isNotEmpty()) info.pageUrl else PAGE)
                    return@runOnUiThread
                }
                install(activity, out, info.version)
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 交给系统安装器。
     *
     * 这里必须用 FileProvider：直接给 file:// 会在 Android 7+ 抛
     * FileUriExposedException；而 content:// 需要在 Manifest 里声明 provider，
     * 见 {@code res/xml/file_paths.xml}。
     */
    private fun install(activity: Activity, apk: File, version: String) {
        try {
            val uri: Uri = FileProvider.getUriForFile(activity, AUTHORITY, apk)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(i)
            Log.i(TAG, "install " + version + " -> " + apk.absolutePath)
        } catch (t: Throwable) {
            // 有些壳（比如没装安装器组件的定制 ROM）会在这里抛 ActivityNotFoundException
            toast(activity, "打不开安装器：" + t.javaClass.simpleName + "，安装包在 " + apk.absolutePath)
        }
    }

    // ---------------------------------------------------------------- 设置界面

    /**
     * 「设置」里的自动更新一块：开关 + 当前版本 + 立即检查。
     *
     * 直接返回 View 而不是弹对话框，是为了让它跟别的设置项并排躺在同一页里 ——
     * 用户找「怎么关掉联网」时不需要先猜它在哪个二级页面。
     */
    fun settingsView(activity: Activity): View {
        val prefs = Prefs(activity)
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(activity, 4), 0, 0)
        }

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(activity).apply {
            text = "自动检查新版本"
            setTextColor(0xFFE6E9EF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val box = CheckBox(activity).apply {
            isChecked = prefs.autoUpdate
            
            text = ""
            setOnCheckedChangeListener { _, checked -> prefs.autoUpdate = checked }
        }
        row.addView(title)
        row.addView(box)
        wrap.addView(row)

        wrap.addView(TextView(activity).apply {
            text = "当前 " + versionName(activity) +
                    "。打开后每次启动最多查一次 GitHub Release（间隔不少于 6 小时）；" +
                    "关掉就一个请求都不发。"
            setTextColor(0xFF7D8590.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(activity, 6), 0, dp(activity, 8))
        })

        val ops = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        ops.addView(smallButton(activity, "立即检查").apply {
            setOnClickListener { checkOnStart(activity, true) }
        })
        val skip = prefs.skipVersion
        if (skip.isNotEmpty()) {
            ops.addView(smallButton(activity, "不再跳过 " + skip).apply {
                setOnClickListener {
                    prefs.skipVersion = ("")
                    text = "已清除跳过标记"
                    isEnabled = false
                }
            })
        }
        wrap.addView(ops)
        return wrap
    }

    private fun smallButton(activity: Activity, label: String): Button =
        Button(activity).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFFE6E9EF.toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 8).toFloat()
                setColor(0xFF1B222C.toInt())
                setStroke(dp(activity, 1), 0xFF232A34.toInt())
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 36)
            )
            lp.rightMargin = dp(activity, 8)
            layoutParams = lp
            minWidth = dp(activity, 96)
        }

    // ---------------------------------------------------------------- 工具

    /** 版本号：读 PackageManager，避免 gradle 里的 versionName 和代码各写一份 */
    fun versionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.0.0"
    } catch (t: Throwable) {
        "0.0.0"
    }

    fun openUrl(ctx: Context, url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            toast(ctx, "打不开链接：" + url)
        }
    }

    private fun toast(ctx: Context, s: String) {
        Toast.makeText(ctx, s, Toast.LENGTH_LONG).show()
        Log.i(TAG, "toast: $s")
    }

    private fun fmt(n: Long): String = when {
        n <= 0 -> "—"
        n < 1024 -> "$n B"
        n < 1048576 -> (n / 1024).toString() + " KB"
        else -> String.format(java.util.Locale.ROOT, "%.1f MB", n / 1048576.0)
    }

    private fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    /** 保留：某些 ROM 的「未知来源」开关要从这里跳 */
    @Suppress("unused")
    private fun settingsIntent(ctx: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:" + ctx.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } catch (t: Throwable) {
            // 打不开就算了，安装器自己会提示去哪开
        }
    }

    /** 让外部能顺手清掉旧包，避免 cache 里堆几个历史版本 */
    fun cleanOld(ctx: Context, keep: String) {
        val dir = File(ctx.cacheDir, "update")
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (!f.name.contains(keep)) f.delete()
        }
    }

    @Suppress("unused")
    private fun scrollWrap(v: View): View = ScrollView(v.context).apply { addView(v) }

    @Suppress("unused")
    private fun parse(s: String): JSONObject = JSONObject(s)

    @Suppress("unused")
    private fun conn(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }

    @Suppress("unused")
    private fun tint(c: Int): Int = Color.argb(255, Color.red(c), Color.green(c), Color.blue(c))
}
