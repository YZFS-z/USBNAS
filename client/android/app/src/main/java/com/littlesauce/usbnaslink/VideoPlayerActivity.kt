package com.littlesauce.usbnaslink

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * 内嵌视频播放页：电影/剧集直接在 App 里播，不跳系统播放器。
 *
 * 这里用的是 Android 自带的 MediaPlayer —— 它底层就是 MediaCodec 硬解，
 * mp4/h264/aac/mkv 常见编码都能吃，等于用系统解码器，一份依赖都不用引。
 * 之所以不引 ExoPlayer/ijkplayer：
 *
 *  - ExoPlayer 会把 APK 撑到 9MB+，而我们只用「放个视频」这一件事；
 *  - ijkplayer 带一堆 .so，还得自己按 ABI 打包，几十 MB；
 *  - MediaPlayer 的短板是冷门编码（rmvb/部分 hevc 封装）会失败，
 *    这种情况给出「用其它播放器打开」的兜底按钮就够了。
 *
 * 画面用 TextureView 而不是 SurfaceView：在这台机器（MIUI 14、系统横屏、
 * 可用高度只有 338dp）上 SurfaceView 的 surfaceCreated 一次都没回调过，
 * 画面永远卡在转圈。TextureView 走 onSurfaceTextureAvailable，只要 View
 * 被测量并加进窗口就会触发，不依赖单独的 Surface 合成层。
 *
 * 这个页面全屏黑底，控制条浮在上面，点画面切换显隐。
 */
class VideoPlayerActivity : Activity() {

    companion object {
        const val EXTRA_URL = "play_url"
        const val EXTRA_NAME = "play_name"
        const val EXTRA_SID = "play_sid"

        fun intent(ctx: Context, url: String, name: String, sid: String?): Intent =
            Intent(ctx, VideoPlayerActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_SID, sid)
            }
    }

    private lateinit var surface: TextureView
    private lateinit var titleText: TextView
    private lateinit var subText: TextView
    private lateinit var seek: SeekBar
    private lateinit var timeText: TextView
    private lateinit var ctl: LinearLayout
    private lateinit var spinner: ProgressBar
    private lateinit var playBtn: Button

    private var mp: MediaPlayer? = null
    private var url: String = ""
    private var name: String = ""
    private var sid: String? = null
    private var dragging = false
    private var surfaceReady = false
    private var prepared = false

    /**
     * `onPrepared` 已经回调过。
     *
     * 必须和 [prepared] 分开：[prepared] 表示「已经发起过这次起播」（防重入），
     * 它在 `prepareAsync()` 之前就置 true 了。而 `currentPosition` / `duration`
     * 这些读数只能在 `onPrepared` 之后取 —— 提前取时 MediaPlayer 会直接把
     * `error (-38, 0)` 抛给错误监听器，界面上就会出现「解码失败（what=-38）」，
     * 而片子其实一点问题都没有。tick 是 400ms 一次，所以表现为每 400ms 刷一条。
     */
    private var ready = false

    private val ui = Handler(Looper.getMainLooper())

    /**
     * 探针用的后台线程。
     *
     * 只用它发一次 `Range: bytes=0-0` 的状态码探测，所以单线程足够，也不必复用
     * 别处的池子 —— 播放器是独立 Activity，跟着它一起结束更省心。
     */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "usbnas-vprobe").apply { isDaemon = true }
    }
    private val tick = object : Runnable {
        override fun run() {
            val p = mp
            // 只在 onPrepared 之后读读数：在那之前 duration/currentPosition 一碰就
            // 触发 error(-38,0)，界面会误报「解码失败」。tick 是 400ms 一次，
            // 不加这个门槛就是每 400ms 刷一条错误，看起来像片子坏了。
            if (p != null && ready && !dragging) {
                try {
                    val pos = p.currentPosition
                    val dur = p.duration
                    if (dur > 0) {
                        seek.progress = (pos * 1000 / dur)
                        timeText.text = fmt(pos) + " / " + fmt(dur)
                    }
                } catch (ignored: Throwable) {
                }
            }
            ui.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题必须在建界面之前定下来：Palette 的取值是"当时"算的，
        // 界面建完再改模式，已画好的控件不会自己重算颜色。
        ThemeCtl.prime(this)
        // 语言同理：字符串资源在建界面时就定死了，晚一步就已经拿旧语言 inflate 了。
        L.prime(this)
        url = intent.getStringExtra(EXTRA_URL) ?: ""
        name = intent.getStringExtra(EXTRA_NAME) ?: ""
        sid = intent.getStringExtra(EXTRA_SID)
        // 视频页横屏更舒服，但不强制 —— 用户系统锁竖屏时也照他
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Immersive.prepare(this)
        buildUi()
        // 顶栏「返回」之外还要能手势退：沉浸式下返回键由系统手势边栏接管，
        // 控制条上那颗按钮仍然有效，这里不再额外拦截

        // 兜底：TextureView 的回调在个别 ROM 上会晚于 setContentView，
        // 进页面 600ms 后主动再看一眼，只要纹理已可用就起播。
        ui.postDelayed({
            if (!prepared) {
                if (surface.isAvailable) surfaceReady = true
                maybeStart()
            }
        }, 600)
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        surface = TextureView(this)
        root.addView(surface, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (surface.isAvailable) {
            surfaceReady = true
        }
        surface.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surfaceReady = true
                maybeStart()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                surfaceReady = false
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        spinner = ProgressBar(this).apply {
            visibility = View.VISIBLE
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        }
        root.addView(spinner, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))

        // 控制条：底部，半透明黑，点画面切换显隐
        ctl = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC0E1116"))
            setPadding(dp(14), dp(8), dp(14), dp(12))
        }

        titleText = TextView(this).apply {
            text = if (name.isEmpty()) getString(R.string.video_title) else name
            setTextColor(Color.parseColor("#E6E9EF"))
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        subText = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#9AA4B2"))
            textSize = 11f
        }
        ctl.addView(titleText)
        ctl.addView(subText)

        seek = SeekBar(this).apply {
            max = 1000
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    dragging = true
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    dragging = false
                    val p = mp ?: return
                    try {
                        val dur = p.duration
                        if (dur > 0) p.seekTo((dur.toLong() * seek.progress / 1000).toInt())
                    } catch (ignored: Throwable) {
                    }
                }
            })
        }
        ctl.addView(seek, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = btn(getString(R.string.video_back15)) { skip(-15000) }
        val play = btn(getString(R.string.play_pause)) { togglePlay(it as Button) }
        playBtn = play
        val fwd = btn(getString(R.string.video_fwd15)) { skip(15000) }
        val other = btn(getString(R.string.video_other_player)) { openExternal() }
        row.addView(back)
        row.addView(play)
        row.addView(fwd)
        row.addView(other)

        timeText = TextView(this).apply {
            text = "00:00 / 00:00"
            setTextColor(Color.parseColor("#9AA4B2"))
            textSize = 11f
            setPadding(dp(10), 0, 0, 0)
        }
        row.addView(timeText)
        ctl.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(ctl, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM))

        // 点画面切控制条显隐（和大多数播放器一致）
        surface.setOnClickListener {
            ctl.visibility = if (ctl.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        setContentView(root)
        Immersive.enter(this)
        ui.post(tick)
    }

    private fun btn(text: String, onClick: (View) -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Palette.FG)
            // 视频控制条压在画面上，背景必须是不透明的 Palette.BTN，
            // 否则半透明会让字幕和画面颜色透过来。
            backgroundTintList = null
            stateListAnimator = null
            setPadding(dp(12), dp(6), dp(12), dp(6))
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            background = Palette.round(this@VideoPlayerActivity, Palette.BTN, 8f)
            setOnClickListener { onClick(it) }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.rightMargin = dp(6)
            layoutParams = lp
        }

    private fun maybeStart() {
        if (!surfaceReady || prepared || url.isEmpty()) return
        prepared = true
        // 起播前先问一次这个地址给不给（见 probeThenStart 的注释）：
        // MediaPlayer 拿到 401 的登录页 HTML 只会报 what=1 extra=-1004，
        // 那句话读起来像「这个影片的编码不支持」，用户会去删片。
        probeThenStart()
    }

    /**
     * 起播前探一次状态码，401 就直接说「登录过期」，别再进 MediaPlayer。
     *
     * 背景：服务端 `needLoginForFile()` 对音频/视频/图片/可预览格式一律要求登录，
     * 而三个清单端点（/_videos 等）是匿名可读的 —— 于是 sid 失效时**列表照常列出来**，
     * 点播才 401。用户的观感就是「原本能播的片子突然全播放不了」。
     *
     * 探针只发 `Range: bytes=0-0`，几十 GB 的片子也只回一个字节。
     * 探不通（返回 0，网络问题）不拦，交给 MediaPlayer 照常试 ——
     * 网络断了本来就该报网络错误，不该冒充登录问题。
     */
    private fun probeThenStart() {
        val u = url
        val ck = sid
        io.execute {
            val code = Session.statusOf(u, ck)
            // 打印 sid 前缀而不是长度：新旧 sid 都是 36 字符，长度对不上无用，
            // 前缀才能一眼看出「手里这个是不是网页端刚发的那个」。
            val sidTag = ck?.take(8) ?: "null"
            val body = Session.lastBody().take(120)
            android.util.Log.i("USBNASLink", "probe code=$code sid=$sidTag body=$body url=$u")
            ui.post {
                // 这期间用户可能已经退出或换了片子
                if (u != url) return@post
                if (code == 401) {
                    // 探到 401 就把 Prefs 里那份 sid 也对齐一次：网页端刚登录过的话，
                    // 这里能直接救回来，用户不用真的跑一趟登录页。
                    val fresh = Prefs(this@VideoPlayerActivity).sid
                    if (fresh != null && fresh.isNotEmpty() && fresh != ck) {
                        sid = fresh
                        android.util.Log.i("USBNASLink", "probe retry with fresh sid=${fresh.take(8)}")
                        probeThenStart()
                        return@post
                    }
                    onPlayFailed(getString(R.string.video_need_login), hint = false)
                    return@post
                }
                if (code == 404) {
                    onPlayFailed(getString(R.string.video_gone), hint = false)
                    return@post
                }
                startPlayer()
            }
        }
    }

    private fun startPlayer() {
        try {
            ready = false
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build())
            val st = surface.surfaceTexture
            if (st != null) {
                p.setSurface(android.view.Surface(st))
            }
            // 有账号时服务端要 sid。MediaPlayer 带请求头只有 (Context, Uri, Map)
            // 这一个口子，而它的 Map 是**原始 HTTP 头**：键必须是 `Cookie`，
            // 值才是 `usbnas_sid=xxx` 整串。
            //
            // 之前把键写成 Session.COOKIE（"usbnas_sid"）是错的：框架只认
            // `Cookie` 这个头名，其余键发出去也没人读，服务端按匿名处理回 401，
            // native 层报 `NuCachedSource2: source returned error -1`，
            // 每 3 秒重试一次，界面停在 00:00 不动。
            // logcat 里 `MediaHTTPService(...): Cookies: null` 就是框架在打印
            // 它从 headers 里取到的 `Cookie` 值 —— 打 null 说明键名没对上，
            // 不是「CookieHandler 为空」那么无害。
            val s = sid
            val hdrs = if (s.isNullOrEmpty()) null else mapOf("Cookie" to Session.cookieHeader(s))
            if (hdrs != null) {
                p.setDataSource(this, android.net.Uri.parse(url), hdrs)
            } else {
                p.setDataSource(url)
            }
            p.setOnPreparedListener { it ->
                spinner.visibility = View.GONE
                ready = true
                try {
                    it.start()
                } catch (e: Throwable) {
                    onPlayFailed(getString(R.string.video_start_fail, e))
                    return@setOnPreparedListener
                }
                subText.text = getString(R.string.video_embedded, it.videoWidth, it.videoHeight)
                playBtn.text = getString(R.string.play_pause)
                // 进度条从这一刻开始跟；tick 早就 post 上了，靠 ready 这个门槛拦住
                // onPrepared 之前的那几帧（不然每 400ms 一条 error(-38,0)）。
            }
            p.setOnErrorListener { _, what, extra ->
                // -38 单列一条：它是「在错误状态上调了 getDuration 之类」的信号，
                // 不是片源坏了。真实原因通常是网络断/被踢登录/服务端没起，说成
                // 「解码失败」会把用户引到「删片重下」上去。
                if (what == -38) {
                    onPlayFailed(getString(R.string.video_play_aborted), hint = false)
                } else {
                    onPlayFailed(getString(R.string.video_decode_fail, what, extra))
                }
                true
            }
            p.setOnCompletionListener {
                subText.text = getString(R.string.video_ended)
            }
            p.prepareAsync()
            mp = p
        } catch (e: Throwable) {
            onPlayFailed(e.toString())
        }
    }

    /**
     * 播放失败。
     *
     * @param hint 是否顺手弹一句「可能是编码不支持」。401/404 这种确定原因的失败
     *             不该弹 —— 那会让用户以为片子坏了，跑去删文件。
     */
    private fun onPlayFailed(msg: String, hint: Boolean = true) {
        spinner.visibility = View.GONE
        subText.text = msg
        subText.setTextColor(Color.parseColor("#FF8A94"))
        // 手机自带的 MediaPlayer 对 rmvb / 部分 hevc 封装无能为力，必须给条路
        if (hint) {
            Toast.makeText(this, getString(R.string.video_no_decoder_hint), Toast.LENGTH_LONG).show()
        }
    }

    private fun togglePlay(b: Button) {
        val p = mp ?: return
        try {
            if (p.isPlaying) {
                p.pause()
                b.text = getString(R.string.play_play)
            } else {
                p.start()
                b.text = getString(R.string.play_pause)
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun skip(ms: Int) {
        val p = mp ?: return
        try {
            val pos = p.currentPosition + ms
            val dur = p.duration
            p.seekTo(if (pos < 0) 0 else if (dur > 0 && pos > dur) dur else pos)
        } catch (ignored: Throwable) {
        }
    }

    private fun openExternal() {
        if (url.isEmpty()) return
        try {
            val it = Intent(Intent.ACTION_VIEW)
            it.setDataAndType(android.net.Uri.parse(url), mimeOf(name))
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(it)
        } catch (e: Throwable) {
            Toast.makeText(this, getString(R.string.video_no_player), Toast.LENGTH_SHORT).show()
        }
    }

    private fun mimeOf(n: String): String =
        when (n.substringAfterLast('.', "").toLowerCase()) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "wmv" -> "video/x-ms-wmv"
            "flv" -> "video/x-flv"
            "ts" -> "video/mp2t"
            "m4v" -> "video/mp4"
            else -> "video/*"
        }

    private fun fmt(ms: Int): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 下拉通知栏、来电、切到别的应用回来，系统都会把状态栏还回来；
        // 只有重新拿回焦点时再要一次，才是「一直全屏」
        if (hasFocus) Immersive.enter(this)
    }

    override fun onPause() {
        super.onPause()
        try {
            if (mp?.isPlaying == true) {
                mp?.pause()
                playBtn.text = getString(R.string.play_play)
            }
        } catch (ignored: Throwable) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        try {
            mp?.stop()
        } catch (ignored: Throwable) {
        }
        try {
            mp?.release()
        } catch (ignored: Throwable) {
        }
        mp = null
    }
}
