package com.littlesauce.usbnaslink

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * 分享管理页（APK）。
 *
 * <p>把服务端的 /_share/new|drop|list 接到手机端：输入服务端绝对路径生成短链，
 * 列出已有分享，撤销，或用系统分享面板（ACTION_SEND）把链接发出去。
 * 与桌面端 ShareDialog、网页端共用同一套短链 —— 三端同步。
 */
class ShareActivity : android.app.Activity() {

    companion object {
        const val EXTRA_URL = "base_url"
        const val EXTRA_SID = "sid"

        fun intent(ctx: Context, baseUrl: String, sid: String?): Intent =
            Intent(ctx, ShareActivity::class.java)
                .putExtra(EXTRA_URL, baseUrl)
                .putExtra(EXTRA_SID, sid)
    }

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "usbnas-share").apply { isDaemon = true }
    }
    private val ui by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    private lateinit var base: String
    private var sid: String? = null
    private lateinit var stateText: TextView
    private lateinit var pathBox: EditText
    private lateinit var listBody: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeCtl.prime(this)
        L.prime(this)
        base = intent.getStringExtra(EXTRA_URL) ?: ""
        sid = intent.getStringExtra(EXTRA_SID)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1116"))
        }

        // 顶栏
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(8), dp(8), dp(8))
        }
        bar.addView(TextView(this).apply {
            text = "‹"
            textSize = 26f
            setTextColor(Color.parseColor("#9AA4B2"))
            setPadding(dp(10), 0, dp(14), 0)
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = "分享管理"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#E6E9EF"))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(this).apply {
            text = "刷新"
            textSize = 13f
            setTextColor(Color.parseColor("#5AA9FF"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { load() }
        })
        root.addView(bar)

        stateText = TextView(this).apply {
            text = "正在加载…"
            textSize = 12f
            setTextColor(Color.parseColor("#9AA4B2"))
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        root.addView(stateText)

        // 新建：路径 + 生成按钮
        pathBox = EditText(this).apply {
            hint = "服务端路径，如 /mnt/media_rw/xxx/电影/山村老尸.mp4"
            textSize = 13f
            setTextColor(Color.parseColor("#E6E9EF"))
            setHintTextColor(Color.parseColor("#6B7480"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = Palette.round(this@ShareActivity, Color.parseColor("#171B22"), 8f)
        }
        root.addView(pathBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(16); rightMargin = dp(16); topMargin = dp(4)
        })
        root.addView(Button(this).apply {
            text = "生成分享链接"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            background = Palette.round(this@ShareActivity, Color.parseColor("#2F80ED"), 9f)
            setOnClickListener { create() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(16); rightMargin = dp(16); topMargin = dp(8)
        })

        // 分享列表（可滚动）
        listBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(this).apply {
            addView(listBody, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) })

        setContentView(root)
        Immersive.tintBars(this, Color.parseColor("#0E1116"))
        Immersive.padBySystemBars(root)
        load()
    }

    private fun create() {
        val p = pathBox.text.toString().trim()
        if (p.isEmpty()) { toast("先填路径"); return }
        state("正在创建…")
        io.execute {
            val r = Share.create(base, sid, p, 20000)
            ui.post {
                if (r.ok) {
                    toast("已生成：" + r.code)
                    pathBox.text.clear()
                    load()
                } else {
                    toast("创建失败：" + r.error)
                    state("创建失败：" + r.error)
                }
            }
        }
    }

    private fun load() {
        state("正在加载分享列表…")
        io.execute {
            val r = Share.list(base, sid, 20000)
            ui.post {
                listBody.removeAllViews()
                if (r.ok) {
                    state("共 " + r.items.size + " 条分享（服务重启会失效）")
                    for (it in r.items) listBody.addView(row(it))
                    if (r.items.isEmpty()) {
                        listBody.addView(TextView(this).apply {
                            text = "还没有分享。上面填个路径生成一条。"
                            textSize = 12f
                            setTextColor(Color.parseColor("#6B7480"))
                            setPadding(dp(16), dp(20), dp(16), dp(20))
                        })
                    }
                } else {
                    state("列分享失败：" + r.error)
                }
            }
        }
    }

    private fun row(item: Share.Item): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = Palette.round(this@ShareActivity, Color.parseColor("#171B22"), 9f)
        }
        box.addView(TextView(this).apply {
            text = (if (item.dir) "[目录] " else "") + (if (item.name.isNotEmpty()) item.name else item.path)
            textSize = 13f
            setTextColor(Color.parseColor("#E6E9EF"))
        })
        box.addView(TextView(this).apply {
            text = item.code
            textSize = 11f
            setTextColor(Color.parseColor("#6B7480"))
            setPadding(0, dp(2), 0, dp(6))
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "分享链接"
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.WHITE)
            background = Palette.round(this@ShareActivity, Color.parseColor("#2F80ED"), 8f)
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, item.url)
                }
                startActivity(Intent.createChooser(send, "分享链接"))
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(4)
        })
        actions.addView(Button(this).apply {
            text = "撤销"
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.parseColor("#FF8A94"))
            background = Palette.round(this@ShareActivity, Color.parseColor("#171B22"), 8f)
            setOnClickListener {
                state("正在撤销…")
                io.execute {
                    val d = Share.drop(base, sid, item.code, 20000)
                    ui.post {
                        toast(if (d.ok && d.removed) "已撤销" else "撤销失败：" + d.error)
                        load()
                    }
                }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(4)
        })
        box.addView(actions)
        return box
    }

    private fun state(s: String) { stateText.text = s }

    private fun toast(s: String) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }
}
