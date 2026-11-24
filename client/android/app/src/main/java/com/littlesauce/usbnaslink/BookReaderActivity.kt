package com.littlesauce.usbnaslink

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * 内嵌阅读页：书直接在 App 里排版，不跳浏览器。
 *
 * 正文来自服务端 /_books/text —— docx / doc / epub / txt 统一抽成纯文本，
 * 所以这里不需要任何文档解析依赖，只要一个可滚动的 TextView。
 *
 * PDF 是例外：服务端不抽 PDF 文本层（要完整解析器），这里给两个选择 ——
 * 用系统浏览器打开 /view 页，或者用设备上装的 PDF 阅读器直开原文件。
 * 不在 App 里渲染 PDF 是为了不引 PDFium 那套 .so（会给 APK 加 8MB 以上）。
 */
class BookReaderActivity : Activity() {

    companion object {
        const val EXTRA_URL = "base_url"
        const val EXTRA_SID = "sid"
        const val EXTRA_PATH = "book_path"
        const val EXTRA_NAME = "book_name"

        fun intent(ctx: Context, baseUrl: String, sid: String?, path: String, name: String): Intent =
            Intent(ctx, BookReaderActivity::class.java).apply {
                putExtra(EXTRA_URL, baseUrl)
                putExtra(EXTRA_SID, sid)
                putExtra(EXTRA_PATH, path)
                putExtra(EXTRA_NAME, name)
            }
    }

    private lateinit var titleText: TextView
    private lateinit var metaText: TextView
    private lateinit var body: TextView
    private lateinit var scroll: ScrollView
    private lateinit var spinner: ProgressBar
    private lateinit var bottom: LinearLayout
    private lateinit var fontSeek: SeekBar
    private lateinit var rootLayout: LinearLayout
    private var pdfBar: LinearLayout? = null

    private var baseUrl = ""
    private var sid: String? = null
    private var path = ""
    private var name = ""
    private var fontSize = 17f

    private val ui = Handler(Looper.getMainLooper())
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "usbnas-read").apply { isDaemon = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题必须在建界面之前定下来：Palette 的取值是"当时"算的，
        // 界面建完再改模式，已画好的控件不会自己重算颜色。
        ThemeCtl.prime(this)
        baseUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        sid = intent.getStringExtra(EXTRA_SID)
        path = intent.getStringExtra(EXTRA_PATH) ?: ""
        name = intent.getStringExtra(EXTRA_NAME) ?: ""
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Immersive.prepare(this)
        buildUi()
        load()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.enter(this)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1116"))
        }
        rootLayout = root

        // 顶栏
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#171B22"))
            setPadding(dp(8), dp(10), dp(12), dp(10))
        }
        val back = TextView(this).apply {
            text = "‹"
            setTextColor(Color.parseColor("#5AA9FF"))
            textSize = 26f
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { finish() }
        }
        head.addView(back)

        val headText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleText = TextView(this).apply {
            text = if (name.isEmpty()) "阅读" else name
            setTextColor(Color.parseColor("#E6E9EF"))
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        metaText = TextView(this).apply {
            text = "正在读取…"
            setTextColor(Color.parseColor("#7D8590"))
            textSize = 10f
        }
        headText.addView(titleText)
        headText.addView(metaText)
        head.addView(headText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val other = TextView(this).apply {
            text = "网页"
            setTextColor(Color.parseColor("#5AA9FF"))
            textSize = 13f
            setPadding(dp(10), dp(6), dp(4), dp(6))
            setOnClickListener { openInBrowser() }
        }
        head.addView(other)
        root.addView(head, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        spinner = ProgressBar(this).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#2F80ED"))
        }
        root.addView(spinner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        body = TextView(this).apply {
            setTextColor(Color.parseColor("#D8DEE6"))
            textSize = fontSize
            setLineSpacing(dp(7).toFloat(), 1f)
            setPadding(dp(18), dp(16), dp(18), dp(40))
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0E1116"))
            addView(body, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 底部字号条：手机上翻设置调字号太麻烦，直接给个滑块
        bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#171B22"))
            setPadding(dp(14), dp(4), dp(14), dp(8))
            visibility = View.GONE
        }
        bottom.addView(TextView(this).apply {
            text = "A"
            setTextColor(Color.parseColor("#7D8590"))
            textSize = 11f
        })
        fontSeek = SeekBar(this).apply {
            max = 20
            progress = 6
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    fontSize = 13f + p
                    body.textSize = fontSize
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        bottom.addView(fontSeek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(TextView(this).apply {
            text = "A"
            setTextColor(Color.parseColor("#7D8590"))
            textSize = 18f
        })
        root.addView(bottom, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        scroll.setOnClickListener { bottom.visibility =
            if (bottom.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        scroll.setOnLongClickListener {
            bottom.visibility =
                if (bottom.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            true
        }

        setContentView(root)
        Immersive.enter(this)
    }

    private fun load() {
        spinner.visibility = View.VISIBLE
        val base = baseUrl
        val ck = sid
        val p = path
        io.execute {
            val bt = BookText.fetch(base, ck, p, 25000)
            ui.post { show(bt) }
        }
    }

    private fun show(bt: BookText) {
        spinner.visibility = View.GONE
        if (!bt.ok) {
            if (bt.pdf) {
                // PDF 不再给「去浏览器」两个按钮：PDFium 已经打进包里了，
                // 直接开内嵌阅读页，翻页/缩放/跳页全在 App 里。
                metaText.text = "PDF：正在打开内嵌阅读器"
                spinner.visibility = View.GONE
                openPdf()
                return
            } else {
                metaText.text = "打不开"
                body.setTextColor(Color.parseColor("#FF8A94"))
                body.text = bt.error ?: "读取失败"
            }
            return
        }
        body.setTextColor(Color.parseColor("#D8DEE6"))
        metaText.text = bt.summary()
        body.text = bt.text
        scroll.scrollTo(0, 0)
        bottom.visibility = View.VISIBLE
    }

    private fun showPdfButtons() {
        // ScrollView 只能有一个直接子 View（body）。早先这里往 body.parent
        // 又塞了一个按钮行，直接崩 IllegalStateException: ScrollView can host
        // only one direct child。所以按钮不放进 ScrollView，而是挂到根布局上，
        // 盖在滚动区顶部（根是 LinearLayout，wrap_content 高度会被挤走，
        // 用 gravity 靠下的固定区域更稳）。
        if (pdfBar != null) return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#171B22"))
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        row.addView(Button(this).apply {
            text = "在 App 里打开（PDFium）"
            isAllCaps = false
            setTextColor(Color.WHITE)
            // 主色实心：这是这一屏唯一推荐的主动作
            backgroundTintList = null
            stateListAnimator = null
            background = Palette.round(this@BookReaderActivity, Palette.ACCENT, 9f)
            setOnClickListener { openPdf() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(8)
        })
        row.addView(Button(this).apply {
            text = "用设备上的 PDF 阅读器打开"
            isAllCaps = false
            setTextColor(Palette.FG)
            backgroundTintList = null
            stateListAnimator = null
            background = Palette.round(this@BookReaderActivity, Palette.BTN, 9f, Palette.LINE, 1f)
            setOnClickListener { openWithOtherApp() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pdfBar = row
        // 插到滚动区之前：root 的子序是 head / spinner / scroll / bottom，
        // 索引 2 正好是 scroll 前面，这样按钮在正文上方而不是被正文挤出去。
        (rootLayout as? LinearLayout)?.let { rl ->
            val idx = if (rl.childCount >= 3) 2 else rl.childCount
            rl.addView(row, idx, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /**
     * PDF 就是 PDF，别在 App 里假装能排版：交给 PDFium 那一页。
     *
     * 这里不 finish() 自己 —— 用户从 PDF 页按返回要回到阅读页，
     * 让他一眼看到「这本书是 PDF、已用内嵌阅读器打开」，再点一次也能进去。
     */
    private fun openPdf() {
        startActivity(PdfActivity.intent(this, baseUrl, sid, path, name))
    }

    private fun openInBrowser() {
        // 书是 PDF 时要直接落到 /view 阅读页（服务端那边用 iframe 把 PDF
        // 塞给浏览器内核），否则只会打开站点首页，还得自己再点一层目录。
        val url = if (path.isEmpty()) baseUrl else BookLib.readUrl(baseUrl, path)
        startActivity(BrowseActivity.intent(this, url, name))
    }

    private fun openWithOtherApp() {
        val fileUrl = BookLib.fileUrl(baseUrl, path)
        val mime = when (path.substringAfterLast('.', "").lowercase()) {
            "pdf" -> "application/pdf"
            "epub", "ibooks" -> "application/epub+zip"
            else -> "*/*"
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.parse(fileUrl), mime)
            })
        } catch (e: Throwable) {
            Toast.makeText(this, "设备上没有能打开这个格式的应用", Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }
}
