package com.littlesauce.usbnaslink

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * 单曲播放器。
 *
 * 为什么用 MediaPlayer 而不是 ExoPlayer：服务端吐的是原始音频字节
 * （mp3/flac/wav 都有可能），MediaPlayer 直接吃 HTTP URL，系统自带解码器
 * 覆盖最广，也不用往包里塞 10MB+ 的库。代价是非 WAV 的首字节要等
 * BufferingUpdate，进度条会有一次跳变 —— 可以接受。
 *
 * 播放列表逻辑不在这里，用 core 里那份 Music.Playlist（桌面端也在用同一份），
 * 免得两端「下一首」行为不一致。
 */
class Player(private val ctx: Context) {

    interface Listener {
        /** 换曲/重播，UI 该刷新标题了 */
        fun onTrack(track: Track, index: Int)

        fun onState(playing: Boolean)

        fun onProgress(posMs: Long, totalMs: Long)

        fun onLyric(text: String?)

        fun onEnded()

        /**
         * 整首歌词的时间轴换了（新歌、或刚刮削成功）。
         *
         * 与 [onLyric] 的分工：onLyric 是「这一瞬间该显示哪一句」的增量回调，
         * 播放页只拿它刷一行大字；桌面歌词浮窗需要全量时间轴来自己算「下一句」，
         * 所以单独给一条。
         */
        fun onLrcLoaded(parsed: List<List<Any>>) {
            // 默认空实现：老调用方（只有播放页）不用改
        }
    }

    val playlist = Music.Playlist()

    var listener: Listener? = null

    private var mp: MediaPlayer? = null
    private var baseUrl = ""
    private var sid: String? = null
    private var lrc: List<List<Any>> = emptyList()   // [timeMs:Long, text:String]
    private var lastLyricIdx = -2

    /**
     * 歌词时间轴的人手位移（毫秒）：正数 = 歌词往后推（字幕变慢），负数 = 提前。
     *
     * 关键约定：位移只改「按 pos 找哪一行」，**不改音频进度**，也不改已解析的
     * `[timeMs,text]` 列表本身 —— 所以切歌、刮削重读、浮窗 setLrc 都不受影响，
     * 一处 nudge 之后播放页大字和桌面歌词浮窗同时生效。
     *
     * 落 SharedPreferences（和桌面端 LrcView.offsetMs 一个语义），跨重启保留。
     */
    private var lyricOffsetMs: Long = 0L
    private var prefsLoaded = false

    private fun ensurePrefs() {
        if (prefsLoaded) return
        prefsLoaded = true
        try {
            lyricOffsetMs = Prefs(ctx).lyricOffsetMs
        } catch (ignored: Throwable) {
        }
    }

    /** 当前位移毫秒；UI 拿来显示 "+0.5s" 这种标签 */
    fun lyricOffset(): Long {
        ensurePrefs()
        return lyricOffsetMs
    }

    /** 位移 ±ms，返回钳位后的新值。调用方拿它刷标签 */
    fun nudgeLyric(deltaMs: Int): Long {
        ensurePrefs()
        lyricOffsetMs = (lyricOffsetMs + deltaMs).coerceIn(-LYRIC_OFFSET_LIMIT, LYRIC_OFFSET_LIMIT)
        try {
            Prefs(ctx).lyricOffsetMs = lyricOffsetMs
        } catch (ignored: Throwable) {
        }
        // 强制下一 tick 重算当前行（否则位移后要等到下一句才变）
        lastLyricIdx = -2
        val p = mp
        if (p != null) {
            try { emitLyric(p.currentPosition.toLong()) } catch (ignored: Throwable) {}
        }
        return lyricOffsetMs
    }

    fun resetLyricOffset(): Long {
        ensurePrefs()
        lyricOffsetMs = 0L
        try {
            Prefs(ctx).lyricOffsetMs = 0L
        } catch (ignored: Throwable) {
        }
        lastLyricIdx = -2
        val p = mp
        if (p != null) {
            try { emitLyric(p.currentPosition.toLong()) } catch (ignored: Throwable) {}
        }
        return 0L
    }

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "usbnas-player").apply { isDaemon = true }
    }

    private val tick = object : Runnable {
        override fun run() {
            val p = mp ?: return
            try {
                val pos = p.currentPosition.toLong()
                val total = try {
                    p.duration.toLong()
                } catch (e: IllegalStateException) {
                    0L
                }
                listener?.onProgress(pos, if (total > 0) total else 0L)
                emitLyric(pos)
            } catch (ignored: Throwable) {
            }
            ui.postDelayed(this, 200)
        }
    }

    fun setTarget(base: String, cookie: String?) {
        if (base != baseUrl || cookie != sid) {
            baseUrl = base
            sid = cookie
        }
    }

    fun setPlaylist(album: List<Track>, start: Track?) {
        playlist.setTracks(album)
        val i = if (start == null) -1 else playlist.indexOf(start)
        if (i >= 0) playlist.select(i)
        playCurrent()
    }

    fun toggle() {
        val p = mp ?: run { playCurrent(); return }
        try {
            if (p.isPlaying) p.pause() else p.start()
            listener?.onState(isPlaying())
        } catch (ignored: Throwable) {
        }
    }

    fun next() {
        if (playlist.next() != null) playCurrent()
    }

    fun prev() {
        if (playlist.prev() != null) playCurrent()
    }

    fun seekTo(frac: Float) {
        val p = mp ?: return
        try {
            val total = p.duration
            if (total > 0) p.seekTo((total * frac).toInt().coerceIn(0, total))
        } catch (ignored: Throwable) {
        }
    }

    fun isPlaying(): Boolean = try {
        mp?.isPlaying == true
    } catch (e: IllegalStateException) {
        false
    }

    /** 当前播放进度；桌面歌词浮窗自己算行号，不需要每 200ms 收一次 onProgress */
    fun position(): Long {
        val p = mp ?: return 0L
        return try {
            p.currentPosition.toLong()
        } catch (e: IllegalStateException) {
            0L
        }
    }

    /** 当前这首（可能是 null，播放列表空时） */
    fun current(): Track? = playlist.current()

    /** 当前这首的歌词时间轴（给浮窗切歌时先垫一份，免得要等一轮 IO） */
    fun currentLrc(): List<List<Any>> = lrc

    fun release() {
        ui.removeCallbacksAndMessages(null)
        stopMp()
        io.shutdownNow()
    }

    // ------------------------------------------------------------------ 内核

    private fun stopMp() {
        val p = mp ?: return
        mp = null
        try {
            p.stop()
        } catch (ignored: Throwable) {
        }
        try {
            p.release()
        } catch (ignored: Throwable) {
        }
        ui.removeCallbacks(tick)
    }

    private fun playCurrent() {
        val t = playlist.current() ?: return
        stopMp()
        if (baseUrl.isEmpty()) return
        listener?.onTrack(t, playlist.index)
        listener?.onState(false)
        listener?.onLyric(null)

        val url = Music.audioUrl(baseUrl, t.path)
        val p = MediaPlayer()
        mp = p
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // 有账号时服务端要 sid。MediaPlayer 带请求头只有 (Context, Uri, Map)
            // 这一个口子，而它的 Map 是**原始 HTTP 头**：键必须是 `Cookie`，
            // 值才是 `usbnas_sid=xxx` 整串。
            //
            // 之前把键写成 Session.COOKIE（"usbnas_sid"）是错的：框架只认
            // `Cookie` 这个头名，其余键原样发出去也没人读，服务端按匿名处理
            // 回 401，native 层就报 `NuCachedSource2: source returned error -1`
            // 然后每 3 秒重试一次，界面停在 0:00 不动。
            // logcat 里 `MediaHTTPService(...): Cookies: null` 就是框架在打印
            // 它从 headers 取到的 `Cookie` 值 —— 打 null 说明键名没对上。
            val s = sid
            val hdrs = if (s.isNullOrEmpty()) null else mapOf("Cookie" to Session.cookieHeader(s))
            if (hdrs != null) {
                p.setDataSource(ctx, android.net.Uri.parse(url), hdrs)
            } else {
                p.setDataSource(url)
            }
            p.setOnPreparedListener {
                it.start()
                listener?.onState(true)
                ui.removeCallbacks(tick)
                ui.post(tick)
            }
            p.setOnCompletionListener {
                listener?.onState(false)
                val again = playlist.onEnded()
                if (again != null) playCurrent() else listener?.onEnded()
            }
            p.setOnErrorListener { _, _, _ ->
                listener?.onState(false)
                // 解不了的格式（ape/wma 之类）不在这里死磕：直接跳下一首，
                // 用户至少不会卡在一首放不出来的歌上
                if (playlist.next() != null) playCurrent() else listener?.onEnded()
                true
            }
            p.prepareAsync()
        } catch (e: Throwable) {
            stopMp()
            listener?.onState(false)
        }

        loadLyric(t)
    }

    private fun loadLyric(t: Track) {
        lrc = emptyList()
        lastLyricIdx = -2
        listener?.onLrcLoaded(emptyList())
        if (!t.hasLyrics()) return
        val base = baseUrl
        val ck = sid
        io.execute {
            val raw = Music.fetchLyrics(base, ck, t.path, 8000)
            val parsed = parseLrc(raw)
            ui.post {
                // 拉歌词要一两百毫秒，这期间用户可能已经切歌了：
                // 只认「响应回来时还在播的那首歌」，否则会把上一首的歌词贴到新歌上
                if (playlist.current() !== t) return@post
                lrc = parsed
                lastLyricIdx = -2
                listener?.onLrcLoaded(parsed)
            }
        }
    }

    /**
     * 刮削成功后由播放页调用：把新写好的 .lrc 重新读一遍。
     *
     * 不能只改 [Track.lrcName] 就完事 —— loadLyric 在 track 没歌词时直接 return，
     * 而 [lrc] 里存的是解析结果，不重新拉就永远是空的。
     */
    fun reloadLyricFor(t: Track) {
        loadLyric(t)
    }

    private fun emitLyric(pos: Long) {
        if (lrc.isEmpty()) return
        // 与桌面端 LrcView 同语义：tick(pos + offsetMs)。
        // 正数 = 显示「更靠后」的那句（音比词慢时用），负数 = 提前那句。
        // 音频进度 pos 本身一动不动，只改去时间轴里查哪一行。
        val at = pos + lyricOffset()
        var idx = -1
        for (i in lrc.indices) {
            val stamp = lrc[i][0] as Long
            if (stamp <= at) idx = i else break
        }
        if (idx == lastLyricIdx) return
        lastLyricIdx = idx
        listener?.onLyric(if (idx < 0) null else lrc[idx][1] as String)
    }

    companion object {
        /** 歌词位移上限 10s，和桌面端 LrcView.OFFSET_LIMIT 一致 */
        const val LYRIC_OFFSET_LIMIT = 10_000L

        /** LRC → (毫秒, 文本) 列表；与桌面端 LyricWindow.parse 同一套规则 */
        fun parseLrc(raw: String?): List<List<Any>> {
            if (raw.isNullOrEmpty()) return emptyList()
            var offset = 0L
            val out = ArrayList<LongArray>()
            val txt = ArrayList<String>()
            for (line0 in raw.split("\r\n", "\n", "\r")) {
                val s = line0.trim()
                if (s.isEmpty()) continue
                if (s.startsWith("[") && s.indexOf(']') > 0) {
                    val inner = s.substring(1, s.indexOf(']'))
                    if (inner.startsWith("offset:")) {
                        offset = inner.substring(7).trim().toLongOrNull() ?: 0L
                        continue
                    }
                }
                val stamps = ArrayList<Long>()
                var rest = s
                while (rest.startsWith("[")) {
                    val close = rest.indexOf(']')
                    if (close < 0) break
                    val t = stamp(rest.substring(1, close))
                    if (t < 0) break
                    stamps.add(t)
                    rest = rest.substring(close + 1)
                }
                if (stamps.isEmpty()) continue
                val text = rest.trim()
                for (t in stamps) {
                    out.add(longArrayOf(t + offset))
                    txt.add(text)
                }
            }
            val order = out.indices.sortedBy { out[it][0] }
            return order.map { listOf<Any>(out[it][0], txt[it]) }
        }

        private fun stamp(s: String): Long {
            if (s.length < 4) return -1
            val c1 = s.indexOf(':')
            if (c1 < 0) return -1
            return try {
                val mm = s.substring(0, c1).trim().toInt()
                val rest = s.substring(c1 + 1)
                var dot = -1
                for (i in rest.indices) {
                    val ch = rest[i]
                    if (ch == '.' || ch == ':') {
                        dot = i
                        break
                    }
                }
                var secPart = rest
                var frac = 0
                if (dot >= 0) {
                    secPart = rest.substring(0, dot)
                    val f = rest.substring(dot + 1)
                    if (f.isNotEmpty()) {
                        frac = when {
                            f.length >= 3 -> f.substring(0, 3).toInt()
                            f.length == 2 -> f.toInt() * 10
                            else -> f.toInt() * 100
                        }
                    }
                }
                mm * 60000L + secPart.trim().toInt() * 1000L + frac
            } catch (e: NumberFormatException) {
                -1
            }
        }
    }
}
