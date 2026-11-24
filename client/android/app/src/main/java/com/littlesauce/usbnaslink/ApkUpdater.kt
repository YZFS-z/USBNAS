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
    private const val ASSET_PREFIX = "usbnaslink-"
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
            val info = Update.check(versionName(activity), ASSET_PREFIX, ASSET_SUFFIX)
            prefs.lastUpdateCheck = System.currentTimeMillis()
            activity.runOnUiThread {
                if (!info.ok) {
                    if (manual) toast(activity, activity.getString(R.string.upd_check_fail, info.error))
                    return@runOnUiThread
                }
                val skip = prefs.skipVersion
                if (!Update.newer(info, versionName(activity))) {
                    if (manual) toast(activity, activity.getString(R.string.upd_latest, versionName(activity)))
                    return@runOnUiThread
                }
                if (skip.isNotEmpty() && Update.compare(info.version, skip) == 0) {
                    if (manual) toast(activity, activity.getString(R.string.upd_skipped_ver, skip))
                    return@runOnUiThread
                }
                showFound(activity, info)
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 发现新版本：一个自绘的深色底部卡片。
     *
     * 不用 `AlertDialog.setMessage` 的原因：它在深色主题下是白底黑字的一整块，
     * 而且更新说明是 Markdown 原文（## ### - 都在里面），塞进一个 TextView
     * 会连成一大坨看不清。这里手动分层：版本对比 / 体积 / 说明（可滚动）/ 三个按钮，
     * 颜色全部走 [Palette]，和「管理设置」页签、播放页是同一套。
     */
    private fun showFound(activity: Activity, info: Update.Info) {
        val prefs = Prefs(activity)
        val pad = dp(activity, 18)
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(activity, 16), pad, dp(activity, 8))
            background = Palette.round(activity, Palette.CARD, 16f, Palette.LINE, 1f)
        }

        // 标题行：左侧「发现新版本」，右侧版本对比
        wrap.addView(TextView(activity).apply {
            text = activity.getString(R.string.upd_found, info.version)
            setTextColor(Palette.FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        wrap.addView(TextView(activity).apply {
            val size = if (info.asset != null && info.asset.size > 0)
                " · " + info.asset.humanSize() else ""
            text = activity.getString(R.string.upd_current, versionName(activity), info.version, size)
            setTextColor(Palette.ACCENT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(activity, 6), 0, 0)
        })

        // 说明：Markdown 原文照抄，去掉它自己的 # 标记 —— 手机屏幕宽度不够做真正的渲染，
        // 但至少要让人能按行读，而不是一整段挤成一片。
        if (info.notes.isNotEmpty()) {
            val notes = info.notes
                .replace("\r\n", "\n")
                .lines()
                .joinToString("\n") { l ->
                    val s = l.trimStart()
                    when {
                        s.startsWith("### ") -> "· " + s.substring(4)
                        s.startsWith("## ") -> s.substring(3)
                        s.startsWith("# ") -> s.substring(2)
                        s.startsWith("- ") -> "· " + s.substring(2)
                        else -> l
                    }
                }
                .replace(Regex("\n{3,}"), "\n\n")
            val sv = ScrollView(activity).apply {
                isFillViewport = false
                addView(TextView(activity).apply {
                    text = notes
                    setTextColor(Palette.DIM)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setLineSpacing(dp(activity, 3).toFloat(), 1f)
                })
            }
            wrap.addView(sv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 260)).apply {
                topMargin = dp(activity, 12)
            })
        }

        wrap.addView(TextView(activity).apply {
            text = activity.getString(R.string.upd_install_note)
            setTextColor(Palette.FAINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(activity, 12), 0, dp(activity, 14))
        })

        // 按钮行：稍后（描边）/ 跳过此版本（描边）/ 下载并安装（实心）
        val ops = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun op(label: String, primary: Boolean, act: () -> Unit): Button =
            Button(activity).apply {
                text = label
                isAllCaps = false
                Palette.stripMaterial(this)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(if (primary) 0xFFFFFFFF.toInt() else Palette.FG2)
                background = if (primary) Palette.round(activity, Palette.ACCENT, 9f)
                else Palette.round(activity, Palette.BTN, 9f, Palette.LINE, 1f)
                layoutParams = LinearLayout.LayoutParams(0, dp(activity, 44), 1f).apply {
                    rightMargin = dp(activity, 8)
                }
                setOnClickListener { act() }
            }

        val dlg = AlertDialog.Builder(activity).setView(wrap).create()
        val bLater = op(activity.getString(R.string.upd_later), false) { dlg.dismiss() }
        val bSkip = op(activity.getString(R.string.upd_skip), false) {
            prefs.skipVersion = info.version
            toast(activity, activity.getString(R.string.upd_no_more, info.version))
            dlg.dismiss()
        }
        val bGo = op(activity.getString(R.string.upd_go), true) {
            dlg.dismiss()
            download(activity, info)
        }
        (bGo.layoutParams as LinearLayout.LayoutParams).rightMargin = 0
        ops.addView(bLater)
        ops.addView(bSkip)
        ops.addView(bGo)
        wrap.addView(ops)

        dlg.window?.setBackgroundDrawable(
            Palette.round(activity, Palette.CARD, 16f, Palette.LINE, 1f))
        dlg.show()
        // 弹窗宽度不要顶满，留点边距才像卡片
        dlg.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    // ---------------------------------------------------------------- 下载

    /** 下载到 cache/update/，进度走一个不可取消的进度对话框 */
    private fun download(activity: Activity, info: Update.Info) {
        val asset = info.asset
        if (asset == null) {
            openUrl(activity, if (info.pageUrl.isNotEmpty()) info.pageUrl else PAGE)
            return
        }

        // 进度条自己画一条，不用系统 ProgressBar —— 系统那套在深色弹窗里是
        // 白底里的一条细线，和「管理设置」卡片完全不是一个世界的。
        val track = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Palette.round(activity, Palette.FIELD, 4f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 6))
        }
        val fill = View(activity).apply {
            background = Palette.round(activity, Palette.ACCENT, 4f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        track.addView(fill)

        val pct = TextView(activity).apply {
            text = "0%"
            setTextColor(Palette.ACCENT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val label = TextView(activity).apply {
            text = activity.getString(R.string.upd_connecting)
            setTextColor(Palette.DIM2)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.END
        }
        val line = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(pct)
            addView(label)
        }

        val pad = dp(activity, 18)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(activity, 16), pad, dp(activity, 16))
            background = Palette.round(activity, Palette.CARD, 16f, Palette.LINE, 1f)
            addView(TextView(activity).apply {
                text = activity.getString(R.string.upd_downloading, info.version)
                setTextColor(Palette.FG)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, dp(activity, 14))
            })
            addView(track)
            addView(line, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(activity, 8)
            })
        }
        val dlg = AlertDialog.Builder(activity)
            .setView(box)
            .setCancelable(false)
            .create()
        dlg.window?.setBackgroundDrawable(
            Palette.round(activity, Palette.CARD, 16f, Palette.LINE, 1f))
        dlg.setOnShowListener { }
        dlg.show()
        dlg.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT)

        val dir = File(activity.cacheDir, "update").apply { mkdirs() }
        val out = File(dir, "usbnaslink-${info.version}.apk")
        // 下载前先把上一版留下的包清掉：它们已经装过（或跳过），留着只是白占十几 MB。
        // 放在这里而不是 install() 之后，是因为 install 之后 Activity 可能已经切走，
        // 用户装完直接杀进程的话那一份永远没人回收。
        CacheMgr.trimUpdate(activity)

        Thread {
            var err: String? = null
            try {
                val ok = Update.downloadUrl(asset.url, out) { done, total ->
                    activity.runOnUiThread {
                        if (total > 0) {
                            val p = (done * 100 / total).toInt().coerceIn(0, 100)
                            pct.text = p.toString() + "%"
                            label.text = fmt(done) + " / " + fmt(total)
                            // 用权重模拟进度条填充：宽度 = 总宽 * p%
                            val lp = fill.layoutParams as LinearLayout.LayoutParams
                            lp.width = 0
                            lp.weight = p / 100f
                            fill.layoutParams = lp
                            // weight=0 时宽度会是 0，给一个最小的可见块
                            if (p <= 0) lp.width = dp(activity, 2)
                        } else {
                            label.text = fmt(done)
                        }
                    }
                }
                if (!ok) throw java.io.IOException(activity.getString(R.string.upd_incomplete))
            } catch (t: Throwable) {
                err = Update.describeForUser(t)
            }
            activity.runOnUiThread {
                dlg.dismiss()
                if (err != null) {
                    toast(activity, activity.getString(R.string.upd_download_fail, err))
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
            toast(activity, activity.getString(R.string.upd_no_installer, t.javaClass.simpleName, apk.absolutePath))
        }
    }

    // ---------------------------------------------------------------- 设置界面

    /**
     * 「设置」里的自动更新一块：开关 + 当前版本 + 立即检查。
     *
     * 直接返回 View 而不是弹对话框，是为了让它跟别的设置项并排躺在同一页里 ——
     * 用户找「怎么关掉联网」时不需要先猜它在哪个二级页面。
     */
    /**
     * 「管理设置」页签的全部内容：自动更新 + 版本信息 + 连接信息。
     *
     * 它不再是对话框，而是和「影视 / 音乐 / 图书」并列的第四个页签 ——
     * 对话框只有一个「关闭」，改完开关想回来看结果得重开一次；
     * 页签切走再切回，滚动位置和开关状态都还在（视图只建一次）。
     *
     * 所有颜色走 [Palette]，与播放页、图书页同一套，不再有裸 Button 的灰块。
     */
    fun settingsView(activity: Activity): View {
        val prefs = Prefs(activity)
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 16), dp(activity, 24))
            setBackgroundColor(Palette.BG)
        }

        // ---- 卡片：更新 ----
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14))
            background = Palette.round(activity, Palette.CARD, 12f, Palette.LINE, 1f)
        }
        card.addView(TextView(activity).apply {
            text = activity.getString(R.string.upd_title)
            setTextColor(Palette.FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 10), 0, 0)
        }
        row.addView(TextView(activity).apply {
            text = activity.getString(R.string.upd_check_online)
            setTextColor(Palette.FG2)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        // CheckBox 的方块默认是主题色，这里强制刷成主蓝，避免和旁边文字两个调
        row.addView(CheckBox(activity).apply {
            isChecked = prefs.autoUpdate
            text = ""
            buttonTintList = Palette.tint(Palette.ACCENT)
            setOnCheckedChangeListener { _, checked -> prefs.autoUpdate = checked }
        })
        card.addView(row)

        card.addView(TextView(activity).apply {
            text = activity.getString(R.string.upd_check_note, versionName(activity))
            setTextColor(Palette.DIM2)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(activity, 8), 0, dp(activity, 12))
        })

        val ops = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        ops.addView(smallButton(activity, activity.getString(R.string.upd_check_now), true).apply {
            setOnClickListener { checkOnStart(activity, true) }
        })
        val skip = prefs.skipVersion
        if (skip.isNotEmpty()) {
            ops.addView(smallButton(activity, activity.getString(R.string.upd_unskip, skip), false).apply {
                setOnClickListener {
                    prefs.skipVersion = ""
                    text = activity.getString(R.string.upd_skip_cleared)
                    isEnabled = false
                }
            })
        }
        card.addView(ops)
        wrap.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(activity, 12)
        })

        // ---- 卡片：存储 ----
        // 放在「关于」之前：缓存是用户真会去点的，版本号只是看。
        wrap.addView(cacheCard(activity), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(activity, 12)
        })

        // ---- 卡片：关于 ----
        val about = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14))
            background = Palette.round(activity, Palette.CARD, 12f, Palette.LINE, 1f)
        }
        about.addView(TextView(activity).apply {
            text = activity.getString(R.string.upd_about)
            setTextColor(Palette.FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val lines = listOf(
            activity.getString(R.string.upd_k_version) to versionName(activity),
            activity.getString(R.string.upd_k_last_check) to (if (prefs.lastUpdateCheck > 0)
                android.text.format.DateFormat.format("MM-dd HH:mm", prefs.lastUpdateCheck).toString()
            else activity.getString(R.string.upd_never_checked)),
            activity.getString(R.string.upd_k_releases) to PAGE
        )
        for ((k, v) in lines) {
            val r = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(activity, 8), 0, 0)
            }
            r.addView(TextView(activity).apply {
                text = k
                setTextColor(Palette.DIM2)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams = LinearLayout.LayoutParams(dp(activity, 76), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            r.addView(TextView(activity).apply {
                text = v
                setTextColor(Palette.FG2)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (k == activity.getString(R.string.upd_k_releases)) {
                    setTextColor(Palette.ACCENT_HI)
                    setOnClickListener { openUrl(activity, PAGE) }
                }
            })
            about.addView(r)
        }
        about.addView(smallButton(activity, activity.getString(R.string.upd_open_releases), false).apply {
            setOnClickListener { openUrl(activity, PAGE) }
            val lp = layoutParams as LinearLayout.LayoutParams
            lp.topMargin = dp(activity, 12)
        })
        wrap.addView(about)

        return wrap
    }

    /**
     * 卡片：存储占用。
     *
     * <p>这个 App 的缓存大头不是系统那个「清除缓存」按钮能说清的：PDF 是整本拉下来
     * 缓存的（一本几 MB 到几十 MB，读完不会自己走），更新包每个十几 MB。
     * 所以这里把两块分开列出来，给一个「立即清理」。
     *
     * <p>清理线程放在后台：目录里可能几十个文件要 stat，走 UI 线程会卡一下。
     * 清完回到主线程刷新数字并把按钮文字改成结果。
     */
    private fun cacheCard(activity: Activity): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14))
            background = Palette.round(activity, Palette.CARD, 12f, Palette.LINE, 1f)
        }
        card.addView(TextView(activity).apply {
            text = activity.getString(R.string.set_storage)
            setTextColor(Palette.FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        val detail = TextView(activity).apply {
            setTextColor(Palette.DIM2)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dp(activity, 8), 0, 0)
            text = activity.getString(R.string.upd_counting)
        }
        card.addView(detail)
        card.addView(TextView(activity).apply {
            text = activity.getString(R.string.upd_storage_note)
            setTextColor(Palette.FAINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(activity, 6), 0, 0)
        })

        val btn = smallButton(activity, activity.getString(R.string.upd_clean_now), false)
        val lp = btn.layoutParams as LinearLayout.LayoutParams
        lp.topMargin = dp(activity, 12)
        card.addView(btn)

        fun refresh() {
            Thread {
                val u = CacheMgr.usage(activity)
                activity.runOnUiThread {
                    detail.text = if (u.totalBytes <= 0) activity.getString(R.string.upd_nothing_to_clean)
                    else activity.getString(R.string.upd_usage_total, u.text(activity), u.human(u.totalBytes))
                }
            }.start()
        }
        btn.setOnClickListener {
            btn.isEnabled = false
            btn.text = activity.getString(R.string.upd_cleaning)
            Thread {
                val freed = CacheMgr.clearAll(activity)
                val u = CacheMgr.usage(activity)
                activity.runOnUiThread {
                    btn.text = if (freed > 0) activity.getString(R.string.upd_freed, u.human(freed)) else activity.getString(R.string.upd_already_clean)
                    detail.text = if (u.totalBytes <= 0) activity.getString(R.string.upd_nothing_to_clean)
                    else activity.getString(R.string.upd_usage_total, u.text(activity), u.human(u.totalBytes))
                    toast(activity, if (freed > 0) activity.getString(R.string.upd_freed, u.human(freed)) else activity.getString(R.string.upd_nothing_to_clean))
                }
            }.start()
        }
        refresh()
        return card
    }

    /**
     * 卡片里的按钮。[primary] 为真用主色实心，否则用深灰描边 ——
     * 一屏里最多一个实心按钮，视线才有落点。
     */
    private fun smallButton(activity: Activity, label: String, primary: Boolean): Button =
        Button(activity).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(if (primary) 0xFFFFFFFF.toInt() else Palette.FG2)
            backgroundTintList = null
            stateListAnimator = null
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            background = if (primary) {
                Palette.round(activity, Palette.ACCENT, 8f)
            } else {
                Palette.round(activity, Palette.BTN, 8f, Palette.LINE, 1f)
            }
            val lp = LinearLayout.LayoutParams(dp(activity, 108), dp(activity, 36))
            lp.rightMargin = dp(activity, 8)
            layoutParams = lp
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
            toast(ctx, ctx.getString(R.string.upd_open_link_fail, url))
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
