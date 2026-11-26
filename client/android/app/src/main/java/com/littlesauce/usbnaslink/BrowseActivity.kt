package com.littlesauce.usbnaslink

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.URLDecoder

/**
 * 用 WebView 打开 NAS 网页端。
 *
 * 为什么不再自己画一套文件管理器界面：
 *   NAS 的网页端（HttpServer.java 里 7400 行）已经把上传/下载/重命名/在线播放/
 *   OOXML 预览全做完了，且一直在迭代。客户端只负责「找到它、连上它、把它塞进全屏
 *   WebView」，功能就自动跟着服务端升级走 —— 这是维护成本最低的做法。
 *
 * 两个坑：
 *   1) 服务端下发的是 http（明文），WebView 默认会拦混合内容，必须 MIXED_CONTENT_ALWAYS_ALLOW；
 *   2) 服务端 cookie 没有 SameSite=None/Secure，跨进程写 CookieManager 时要注意
 *      用 setCookie(url, ...) 而不是 setAcceptThirdPartyCookies（我们同源，没事）。
 */
class BrowseActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"

        /**
         * 直接落在登录表单上，而不是先给用户看一遍首页再让他自己去点「登录」。
         *
         * 为什么要有这个开关：从「媒体库 → 登录过期提示条」点进来时，用户的诉求
         * 只有一件事 —— 把密码敲进去。先加载首页意味着 WebView 还要跑一遍整个
         * 文件列表、滚一遍缩略图，然后他得自己找到右上角那个「登录」；在手机上
         * 首页那一坨东西加载完还要等好几秒，观感像「点了没反应」。
         */
        const val EXTRA_LOGIN = "login"

        fun intent(ctx: android.content.Context, baseUrl: String, name: String?): android.content.Intent {
            val i = android.content.Intent(ctx, BrowseActivity::class.java)
            i.putExtra(EXTRA_URL, baseUrl)
            i.putExtra(EXTRA_NAME, name)
            return i
        }

        /**
         * 同 [intent]，但首屏直接是登录表单。
         *
         * 所有「你还没登录 / 登录过期了」的入口都该走这个：用户点下去的目的是
         * 交凭据，不是浏览文件。先给他首页，他得自己找右上角那个「登录」，
         * 而在手机上首页要跑几秒才长得出来。
         */
        fun loginIntent(ctx: android.content.Context, baseUrl: String, name: String?): android.content.Intent {
            val i = intent(ctx, baseUrl, name)
            i.putExtra(EXTRA_LOGIN, true)
            return i
        }
    }

    private lateinit var web: WebView
    private lateinit var loading: ProgressBar
    private lateinit var err: TextView
    private lateinit var prefs: Prefs
    private var baseUrl = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题必须在建界面之前定下来：Palette 的取值是"当时"算的，
        // 界面建完再改模式，已画好的控件不会自己重算颜色。
        ThemeCtl.prime(this)
        // 语言同理：字符串资源在建界面时就定死了，晚一步就已经拿旧语言 inflate 了。
        L.prime(this)
        // 网页页签也走全屏沉浸：主题里 statusBarColor 是透明的，但不调
        // Immersive.prepare 就没有 setDecorFitsSystemWindows(false)，横屏时
        // 状态栏那一条不会被页面覆盖，露出一条黑边。其它页面都调了，这里之前漏了。
        Immersive.prepare(this)
        setContentView(R.layout.activity_browse)
        prefs = Prefs(this)

        baseUrl = (intent.getStringExtra(EXTRA_URL) ?: prefs.lastUrl ?: "").trimEnd('/')
        supportActionBar?.title = intent.getStringExtra(EXTRA_NAME) ?: prefs.lastName ?: "USBNAS"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        web = findViewById(R.id.web)
        loading = findViewById(R.id.loading)
        err = findViewById(R.id.err)

        if (baseUrl.isEmpty()) {
            fail(getString(R.string.browse_no_url))
            return
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE   // 服务端一直在改版，别让老缓存骗人
            javaScriptCanOpenWindowsAutomatically = true
            @Suppress("DEPRECATION")
            allowFileAccess = false
            allowContentAccess = false
        }

        CookieManager.getInstance().setAcceptCookie(true)
        // 把已知 sid 直接灌进 WebView，省一次登录
        prefs.sid?.let { sid ->
            if (sid.isNotEmpty()) {
                CookieManager.getInstance().setCookie(baseUrl, "${Session.COOKIE}=$sid; Path=/")
            }
        }
        CookieManager.getInstance().flush()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val u = request.url.toString()
                // 只在本机 NAS 的 host:port 内跳，其余交给系统浏览器
                if (u.startsWith(baseUrl)) return false
                return try {
                    startActivity(android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(u)))
                    true
                } catch (t: Throwable) {
                    true
                }
            }

            override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                loading.visibility = View.VISIBLE
                err.visibility = View.GONE
            }

            override fun onPageFinished(v: WebView?, url: String?) {
                loading.visibility = View.GONE
                web.visibility = View.VISIBLE
                // 服务端可能在页面里更新了 cookie（例如登录成功后），回写一份给下次启动用
                CookieManager.getInstance().getCookie(baseUrl)?.let { c ->
                    val sid = Session.extractSid(c)
                    if (sid.isNotEmpty()) prefs.sid = sid
                }
            }

            override fun onReceivedError(
                v: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                // 只处理主文档的失败，子资源（图标之类）失败不打扰用户
                if (request?.isForMainFrame == true) {
                    fail(getString(R.string.browse_open_fail, baseUrl, error?.description ?: ""))
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView?, newProgress: Int) {
                loading.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }
        // 网页里的 `<a download>` 和「下载所选」都靠这一句落地。
        // WebView 本身不处理下载：没有 DownloadListener 时点击会被直接吞掉，
        // 页面上看起来就是「下载没反应」。
        installDownloader()

        // 首屏地址：手上已经有 sid 就向服务端要一张一次性交接票，
        // 让 WebView 里的 Cookie 是服务端亲手种下的（走到 /_login/sso 后 303 跳回来），
        // 而不是只靠 setCookie 猜。拿不到票就退回普通地址，顶多让用户登一次。
        //
        // 两种地址都带上 `?theme=`：WebView 里跑的就是服务端那套页面，它自己的
        // localStorage 是空的，不带主题就会以服务端的 dark 起手 —— 如果 App 此时
        // 是日间，用户一进「网页」页签就会看到一块黑色的页面，像另一个 App。
        val startUrl = when {
            // 「登录过期」点进来的：用户唯一想干的事就是把密码敲进去，直接落到
            // 表单上。别先加载首页让他自己找右上角的「登录」—— 手机上首页那一坨
            // 文件列表要跑好几秒才长得出来，观感像「点了没反应」。
            intent.getBooleanExtra(EXTRA_LOGIN, false) -> loginUrl()
            else -> handoffUrl() ?: themeUrl(baseUrl)
        }
        web.loadUrl(startUrl)
    }

    /**
     * 把网页端的下载交给系统 DownloadManager。
     *
     * 为什么不用浏览器 Intent：WebView 和浏览器的 cookie 不共享，而服务端默认
     * 可能要求登录；DownloadManager 可以手动带上当前 sid。它还会自己发通知、
     * 落进系统「下载」目录，用户能直接看到进度和文件。
     */
    private fun installDownloader() {
        web.setDownloadListener { url, userAgent, disposition, mimeType, _ ->
            val name = nameFromDisposition(disposition)
                ?: Uri.parse(url).lastPathSegment?.let { decodeName(it) }
                ?: "download"
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(name)
                setDescription(getString(R.string.browse_download_start, name))
                if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedNetworkTypes(
                    DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)
                // 分享链接不需要登录，多带一个 cookie 也无害；需要登录的链接靠它放行。
                prefs.sid?.takeIf { it.isNotEmpty() }?.let {
                    addRequestHeader("Cookie", "${Session.COOKIE}=$it")
                }
            }
            try {
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                val id = dm.enqueue(request)
                // 下载本身有系统通知，但 MIUI 上通知不一定弹出来；给一句即时反馈，
                // 用户才知道刚才那一下确实点中了，而不是「没反应」。
                Toast.makeText(this@BrowseActivity,
                    getString(R.string.browse_download_start, name), Toast.LENGTH_SHORT).show()
                android.util.Log.i("USBNASLink", "download $name -> id=$id")
            } catch (t: Throwable) {
                android.util.Log.e("USBNASLink", "enqueue download failed", t)
            }
        }
    }

    /** 服务端用的是 `filename*=UTF-8''...`，同时也兼容老式 `filename="..."` */
    private fun nameFromDisposition(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        var i = disposition.indexOf("filename*=", ignoreCase = true)
        if (i >= 0) {
            var v = disposition.substring(i + 10).trim()
            if (v.startsWith("UTF-8''", ignoreCase = true)) v = v.substring(7)
            v = v.substringBefore(';').trim().trim('"')
            if (v.isNotEmpty()) return decodeName(v)
        }
        i = disposition.indexOf("filename=", ignoreCase = true)
        if (i >= 0) {
            val v = disposition.substring(i + 9).trim()
                .substringBefore(';').trim().trim('"')
            if (v.isNotEmpty()) return decodeName(v)
        }
        return null
    }

    private fun decodeName(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (t: Throwable) {
        s
    }

    /** 登录表单的地址：`back` 由服务端拼进隐藏域，主题照旧拼在 query 上。 */
    private fun loginUrl(): String {
        // back 必须以 '/' 开头，否则服务端会按「不是站内路径」丢掉它，
        // 登录完跳回裸首页、把主题参数也一起丢了。
        val back = "/" + themeQuery()
        val mode = if (Palette.mode == Palette.MODE_DAY) ThemeLib.LIGHT else ThemeLib.DARK
        return "$baseUrl/_login?back=" + java.net.URLEncoder.encode(back, "UTF-8") + "&theme=" + mode
    }

    /**
     * 给站内地址补上当前主题参数。
     *
     * 服务端请求入口会把 `?theme=` 记成全局配色（并落盘到 AppPrefs），
     * 所以这一次跳转既给 WebView 定了色，也顺手确认了三端一致。
     */
    private fun themeUrl(url: String): String {
        val m = Palette.mode
        val t = if (m == Palette.MODE_DAY) ThemeLib.LIGHT else ThemeLib.DARK
        return if (url.contains("theme=")) url
        else url + (if (url.contains('?')) "&theme=" else "?theme=") + t
    }

    /**
     * 换一张一次性票拼成首屏地址。票只能用一次、120 秒过期，所以不会像把 sid 塞进
     * URL 那样「链接泄露 = 长期失守」。
     */
    private fun handoffUrl(): String? {
        val sid = prefs.sid ?: return null
        if (sid.isEmpty()) return null
        return try {
            val sess = Session(baseUrl)
            sess.setSid(sid)
            val t = sess.handoff()
            // back 里同样带上主题：交接票换完 sid 之后是 303 跳到 back，
            // 不带就是跳到一个没有主题参数的首页（服务端会用 global 配色，
            // 而那份配色可能刚好是别的客户端改的）。
            if (t.isNullOrEmpty()) null
            else "$baseUrl/_login/sso?t=$t&back=" + java.net.URLEncoder.encode(
                    "/" + themeQuery(), "UTF-8")
        } catch (e: Throwable) {
            null
        }
    }

    /** `?theme=day` 对应的服务端参数值（只有服务端认识的 light/dark 两种入口）。 */
    private fun themeQuery(): String =
            "?theme=" + if (Palette.mode == Palette.MODE_DAY) ThemeLib.LIGHT else ThemeLib.DARK

    private fun fail(msg: String) {
        loading.visibility = View.GONE
        web.visibility = View.INVISIBLE
        err.text = msg
        err.visibility = View.VISIBLE
    }

    /** 物理返回键：网页能退就退网页，退到头了才退出 Activity */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && this::web.isInitialized && web.canGoBack()) {
            web.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onPause() {
        web.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.enter(this)
    }

    override fun onDestroy() {
        if (this::web.isInitialized) {
            web.stopLoading()
            web.destroy()
        }
        super.onDestroy()
    }
}
