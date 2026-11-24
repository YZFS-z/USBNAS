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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.github.barteksc.pdfviewer.PDFView
import com.github.barteksc.pdfviewer.listener.OnErrorListener
import com.github.barteksc.pdfviewer.listener.OnLoadCompleteListener
import com.github.barteksc.pdfviewer.listener.OnPageChangeListener
import com.github.barteksc.pdfviewer.util.FitPolicy

/**
 * 内嵌 PDF 阅读页（PDFium）。
 *
 * <p>为什么要自己带原生库：Android 从 API 21 起有 `android.graphics.pdf.PdfRenderer`，
 * 但它只能渲染，不能选中文字、不支持加密文档，页面缩放/滚动全得自己写；
 * 而且它读的是 `ParcelFileDescriptor` 或内存里的 `byte[]`，要从 NAS 拉一个 800MB 的
 * 扫描版 PDF 到内存里直接 OOM。所以这里走 PDFium（Barteksc 的 android-pdf-viewer），
 * 它自己按块解析、自己管内存，还会把页面渲到 Bitmap 上做滑动缓存。
 *
 * <p>原生库的位置：`app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}`，
 * 只放了这两个 ABI —— 手机不是 x86 的，多带一套就是白涨 12MB。
 *
 * <p>为什么从网络读而不是先下载到本地：这本书可能有几百 MB，
 * 先落盘再打开，用户要盯着进度条等到天荒地老；PDFium 支持按需 seek，
 * 翻到哪页读哪段，首屏只要几百 KB。
 */
class PdfActivity : Activity() {

    companion object {
        const val EXTRA_URL = "base_url"
        const val EXTRA_SID = "sid"
        const val EXTRA_PATH = "pdf_path"
        const val EXTRA_NAME = "pdf_name"
        const val EXTRA_PAGE = "pdf_page"

        fun intent(ctx: Context, baseUrl: String, sid: String?, path: String, name: String): Intent =
            Intent(ctx, PdfActivity::class.java).apply {
                putExtra(EXTRA_URL, baseUrl)
                putExtra(EXTRA_SID, sid)
                putExtra(EXTRA_PATH, path)
                putExtra(EXTRA_NAME, name)
            }
    }

    private lateinit var pdfView: PDFView
    private lateinit var titleText: TextView
    private lateinit var pageText: TextView
    private lateinit var metaText: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var rootLayout: LinearLayout
    private lateinit var seekBar: SeekBar

    private var baseUrl = ""
    private var sid: String? = null
    private var path = ""
    private var name = ""
    private var lastPage = 0
    private var pageCount = 0

    private val ui = Handler(Looper.getMainLooper())
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "usbnas-pdf").apply { isDaemon = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题必须在建界面之前定下来：Palette 的取值是"当时"算的，
        // 界面建完再改模式，已画好的控件不会自己重算颜色。
        ThemeCtl.prime(this)
        // 语言同理：字符串资源在建界面时就定死了，晚一步就已经拿旧语言 inflate 了。
        L.prime(this)
        baseUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        sid = intent.getStringExtra(EXTRA_SID)
        path = intent.getStringExtra(EXTRA_PATH) ?: ""
        name = intent.getStringExtra(EXTRA_NAME) ?: ""
        // 看 PDF 时别让屏幕自己灭 —— 一页能盯好几分钟
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Immersive.prepare(this)
        buildUi()
        open()
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

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#171B22"))
            setPadding(dp(8), dp(10), dp(12), dp(10))
        }
        head.addView(TextView(this).apply {
            text = "‹"
            setTextColor(Color.parseColor("#5AA9FF"))
            textSize = 26f
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { finish() }
        })
        val headText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleText = TextView(this).apply {
            text = if (name.isEmpty()) "PDF" else name
            setTextColor(Color.parseColor("#E6E9EF"))
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        metaText = TextView(this).apply {
            text = getString(R.string.pdf_opening)
            setTextColor(Color.parseColor("#7D8590"))
            textSize = 10f
        }
        headText.addView(titleText)
        headText.addView(metaText)
        head.addView(headText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pageText = TextView(this).apply {
            text = "-/-"
            setTextColor(Color.parseColor("#5AA9FF"))
            textSize = 13f
            setPadding(dp(10), 0, 0, 0)
        }
        head.addView(pageText)
        root.addView(head, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        spinner = ProgressBar(this).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#2F80ED"))
        }
        root.addView(spinner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        // PDFView 只有 (Context, AttributeSet) 这一个公开构造器 —— 它本来是给
        // XML 里写的控件，代码里 new 就得把第二个参数显式传 null
        pdfView = PDFView(this, null)
        pdfView.setBackgroundColor(Color.parseColor("#0E1116"))
        root.addView(pdfView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 页码滑块：扫描版 PDF 动辄几百页，靠滑动翻到第 300 页不现实
        val jump = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#171B22"))
            setPadding(dp(14), dp(4), dp(14), dp(8))
        }
        jump.addView(TextView(this).apply {
            text = "1"
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#7D8590"))
            textSize = 11f
        }, LinearLayout.LayoutParams(dp(28), dp(40)))
        val seek = SeekBar(this).apply { max = 100 }
        seekBar = seek
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser || pageCount <= 1) return
                pageText.text = "${p + 1}/$pageCount"
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val p = sb?.progress ?: return
                if (pageCount <= 0) return
                pdfView.jumpTo(p, true)
            }
        })
        jump.addView(seek, LinearLayout.LayoutParams(0, dp(40), 1f))
        val lastBtn = TextView(this).apply {
            text = getString(R.string.pdf_last_page)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#5AA9FF"))
            textSize = 11f
            isClickable = true
            setOnClickListener {
                if (pageCount > 1) pdfView.jumpTo(pageCount - 1, true)
            }
        }
        jump.addView(lastBtn, LinearLayout.LayoutParams(dp(52), dp(40)))
        // 高度必须写死：这一行全是 SeekBar 和 TextView，WRAP_CONTENT 在某些 ROM
        // 上量出来是 0，底栏看不见也点不动（uiautomator 拿到 bounds=[0,0][0,0]）。
        jump.minimumHeight = dp(48)
        root.addView(jump, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        setContentView(root)
        Immersive.enter(this)
    }

    private fun open() {
        if (baseUrl.isEmpty() || path.isEmpty()) {
            fail(getString(R.string.pdf_empty_url))
            return
        }
        spinner.visibility = View.VISIBLE
        metaText.text = getString(R.string.pdf_fetching)
        // PDFium 要能随机 seek（读 xref、跳页），必须拿到一个可重复读的来源。
        // 不能直接 fromUri(http://…)：那走的是 ContentResolver，只认 content:// 和
        // file://，会抛「No content provider: http://…」。所以自己拉一遍存到
        // cacheDir，再让 PDFView 读本地文件 —— 服务端本来就要整本发一次，
        // 落到缓存里反而让「翻到第 300 页」不用再发请求。
        io.execute {
            var tmp: java.io.File? = null
            try {
                tmp = download()
                val f = tmp
                ui.post { render(f) }
            } catch (e: Throwable) {
                val msg = e.message ?: e.javaClass.simpleName
                ui.post {
                    spinner.visibility = View.GONE
                    fail(getString(R.string.pdf_fetch_fail, msg))
                }
            }
        }
    }

    /** 把 PDF 拉到 cacheDir，返回本地文件。磁盘不够时抛异常由调用方提示。 */
    private fun download(): java.io.File {
        val url = java.net.URL(pdfUrl())
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "USBNASLink/1.0")
        val ck = sid
        if (!ck.isNullOrEmpty()) {
            conn.setRequestProperty("Cookie", Session.COOKIE + "=" + ck)
        }
        val code = conn.responseCode
        if (code != 200 && code != 206) {
            conn.disconnect()
            throw java.io.IOException("HTTP $code")
        }
        val total = conn.contentLengthLong
        val dir = java.io.File(cacheDir, "pdf")
        if (!dir.isDirectory && !dir.mkdirs()) throw java.io.IOException(getString(R.string.pdf_no_cache_dir))
        // 文件名用路径的哈希，同一本书重复打开能直接复用
        val out = java.io.File(dir, Integer.toHexString(path.hashCode()) + ".pdf")
        val tmp = java.io.File(dir, out.name + ".part")
        var done = 0L
        tmp.outputStream().use { os ->
            conn.inputStream.use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    os.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct % 2 == 0) ui.post { metaText.text = getString(R.string.pdf_fetching_pct, pct) }
                    }
                }
            }
        }
        conn.disconnect()
        if (tmp.length() < 1024) {
            tmp.delete()
            throw java.io.IOException(getString(R.string.pdf_too_small))
        }
        if (out.exists()) out.delete()
        if (!tmp.renameTo(out)) throw java.io.IOException(getString(R.string.pdf_cache_write_fail))
        // 刚写完就裁一次：keep 传自己，别人（包括这本旧的一份）可以淘汰。
        // 放在下载线程里做，别占 UI 线程 —— 目录里可能有几十个文件要 stat。
        CacheMgr.trimPdf(this, out)
        return out
    }

    private fun render(file: java.io.File) {
        try {
            metaText.text = getString(R.string.pdf_parsing)
            // 不调 pages(...)：这个重载要 IntArray，而空数组会被它当成
            // 「一页都不要」而不是「全部页面」。契约就是「不写 pages 即全部」。
            pdfView.fromFile(file)
                .enableSwipe(true)
                .enableDoubletap(true)
                .swipeHorizontal(false)
                .pageFitPolicy(FitPolicy.WIDTH)
                .pageSnap(false)
                .autoSpacing(false)
                .fitEachPage(true)
                .spacing(dp(6))
                .nightMode(false)
                .enableAnnotationRendering(true)
                .onLoad(object : OnLoadCompleteListener {
                    override fun loadComplete(nbPages: Int) {
                        ui.post {
                            pageCount = nbPages
                            spinner.visibility = View.GONE
                            metaText.text = getString(R.string.pdf_pages, nbPages)
                            pageText.text = "${lastPage + 1}/$nbPages"
                            // 滑块范围对齐页数：之前固定 max=100，而这本书是 467 页，
                            // 滑到头也只能到第 101 页，看起来像「翻页坏了」。
                            if (nbPages > 1) seekBar.max = nbPages - 1
                            seekBar.progress = lastPage
                        }
                    }
                })
                .onPageChange(object : OnPageChangeListener {
                    override fun onPageChanged(page: Int, pageCount: Int) {
                        lastPage = page
                        ui.post { pageText.text = "${page + 1}/$pageCount" }
                    }
                })
                .onError(object : OnErrorListener {
                    override fun onError(t: Throwable?) {
                        ui.post {
                            spinner.visibility = View.GONE
                            fail(getString(R.string.pdf_open_fail, t?.message ?: getString(R.string.common_unknown_error)))
                        }
                    }
                })
                .load()
        } catch (e: Throwable) {
            spinner.visibility = View.GONE
            fail(getString(R.string.pdf_open_fail, e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * PDF 流地址。
     *
     * 服务端 /view 页面对 PDF 是 iframe，手机浏览器内核大多不渲 PDF（用户看到的
     * 「一片黑」就是这么来的），所以这里直接打原文件 —— 走 fileUrl 而不是 readUrl。
     */
    private fun pdfUrl(): String = BookLib.fileUrl(baseUrl, path)

    private fun fail(msg: String) {
        metaText.text = getString(R.string.book_cannot_open)
        metaText.setTextColor(Color.parseColor("#FF8A94"))
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        // 渲染不出来时给一条退路：系统里可能有别的 PDF 阅读器
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#171B22"))
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        row.addView(TextView(this).apply {
            text = msg
            setTextColor(Color.parseColor("#FF8A94"))
            textSize = 13f
            setPadding(0, 0, 0, dp(8))
        })
        row.addView(TextView(this).apply {
            text = getString(R.string.pdf_open_other_app)
            setTextColor(Color.parseColor("#E6E9EF"))
            textSize = 14f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1B222C"))
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { openWithOtherApp() }
        })
        val idx = if (rootLayout.childCount >= 3) 2 else rootLayout.childCount
        rootLayout.addView(row, idx, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun openWithOtherApp() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(android.net.Uri.parse(pdfUrl()), "application/pdf")
            })
        } catch (e: Throwable) {
            Toast.makeText(this, getString(R.string.pdf_no_app), Toast.LENGTH_LONG).show()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        // 下载线程是 daemon，不关掉的话大文件还在往 cacheDir 里写
        io.shutdownNow()
        super.onDestroy()
    }
}
