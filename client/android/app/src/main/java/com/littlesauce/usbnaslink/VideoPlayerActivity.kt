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

    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val p = mp
            if (p != null && prepared && !dragging) {
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
            text = if (name.isEmpty()) "视频" else name
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
        val back = btn("后退 15s") { skip(-15000) }
        val play = btn("暂停") { togglePlay(it as Button) }
        playBtn = play
        val fwd = btn("前进 15s") { skip(15000) }
        val other = btn("用其它播放器") { openExternal() }
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
        try {
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
            // 有账号时服务端要 sid：setDataSource(Context, Uri, headers) 是
            // MediaPlayer 唯一能带请求头的口子，所以 Cookie 走这里而不是 URL 参数
            val s = sid
            if (!s.isNullOrEmpty()) {
                p.setDataSource(this, android.net.Uri.parse(url), mapOf(Session.COOKIE to s))
            } else {
                p.setDataSource(url)
            }
            p.setOnPreparedListener { it ->
                spinner.visibility = View.GONE
                try {
                    it.start()
                } catch (e: Throwable) {
                    onPlayFailed("起播失败：$e")
                    return@setOnPreparedListener
                }
                subText.text = "${it.videoWidth}×${it.videoHeight}    ·    内嵌播放"
                playBtn.text = "暂停"
            }
            p.setOnErrorListener { _, what, extra ->
                onPlayFailed("解码失败（what=$what extra=$extra）")
                true
            }
            p.setOnCompletionListener {
                subText.text = "播放结束"
            }
            p.prepareAsync()
            mp = p
        } catch (e: Throwable) {
            onPlayFailed(e.toString())
        }
    }

    private fun onPlayFailed(msg: String) {
        spinner.visibility = View.GONE
        subText.text = msg
        subText.setTextColor(Color.parseColor("#FF8A94"))
        // 手机自带的 MediaPlayer 对 rmvb / 部分 hevc 封装无能为力，必须给条路
        Toast.makeText(this, "这个格式系统解码器放不了，试试「用其它播放器」", Toast.LENGTH_LONG).show()
    }

    private fun togglePlay(b: Button) {
        val p = mp ?: return
        try {
            if (p.isPlaying) {
                p.pause()
                b.text = "播放"
            } else {
                p.start()
                b.text = "暂停"
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
            Toast.makeText(this, "没有能播放这个格式的播放器", Toast.LENGTH_SHORT).show()
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
                playBtn.text = "播放"
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
