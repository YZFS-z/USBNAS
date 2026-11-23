package com.littlesauce.usbnaslink

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 媒体库：影视 + 音乐两个页签，全部代码建视图。
 *
 * 为什么不用 RecyclerView + 一堆 item layout：
 *   这里两类内容（海报墙、歌单）排版差别大、数量最多几千条，用动态生成的
 *   LinearLayout 一屏塞得下就够了；再拆 recycler 反而要为两种 item 写两套
 *   ViewHolder，代码量翻倍还不一定更快。
 *
 * 数据源是服务端的 /_videos、/_videos/meta、/_music —— 管理员在网页端
 * `/_admin` 里指目录，客户端不做任何目录配置，这是刻意的分工。
 */
class LibraryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val EXTRA_SID = "sid"

        /** 相册网格列数。3 列在 1080 宽上每格约 330px，缩略图够看又不至于滑半天 */
        private const val PHOTO_COLS = 3

        /** 缩略图格子的边长（dp）。正方形，图片居中裁切填满 */
        private const val PHOTO_CELL = 104

        /** 单个页签的最小宽度（dp）。六个页签铺满屏会挤，宁可横滑 */
        private const val TAB_MIN_W = 92

        /** 「管理设置」页签下标（影视 0 / 音乐 1 / 图书 2 / 相册 3 / 管理设置 4 / 网页 5） */
        private const val TAB_MANAGE = 4

        /** 从网页回来时用来判断「用户是去登录的」，服务端不用，纯本地意图标记 */
        private const val EXTRA_LOGIN_TRY = "login_try"

        /** 多选批量删除单批上限：一次 POST 删太多会让服务端一口气卡住好几秒 */
        private const val PHOTO_DEL_BATCH = 40
    }

    private lateinit var prefs: Prefs
    private lateinit var baseUrl: String
    private var sid: String? = null

    private val io = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "usbnas-lib").apply { isDaemon = true }
    }
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var videoHost: LinearLayout
    private lateinit var musicHost: LinearLayout
    private lateinit var bookHost: LinearLayout
    private lateinit var photoHost: LinearLayout
    private lateinit var manageHost: LinearLayout
    private lateinit var hint: TextView
    private lateinit var spinner: ProgressBar

    /** 三个页签按钮，切页时只刷它们的选中态（不重建视图） */
    private val tabViews = ArrayList<TextView>()

    private var vlib: VideoLib.Lib? = null
    private var mlib: Music.Lib? = null
    private var blib: BookLib.Lib? = null
    private var plib: PhotoLib.Lib? = null

    /** 正在全屏看的那张图；null 表示没开。关闭时置回 null，避免持有 Dialog 泄漏 */
    private var photoDlg: android.app.Dialog? = null
    private val posterCache = HashMap<String, Bitmap?>()

    private var currentTab = 0

    /**
     * 「网页」在页签行里的下标。
     *
     * 它和其它页签长得一样、排在一起，但点它不换页（`selectTab(5)` 没有对应的
     * 内容容器），而是直接调 `openWeb()` 交给系统浏览器。之所以做成页签而不是
     * 顶栏按钮：顶栏那个按钮的字号/底色/高度跟下面一排页签都对不上，视觉上是
     * 页面里最扎眼的一处不统一。
     */
    private val TAB_WEB = 5

    /** 音乐页当前视图：0 平铺 / 1 按歌手 / 2 按专辑（与桌面端同一套档位） */
    private var musicView = 0
    private val openedBuckets = HashSet<String>()
    private var firstBucketOpened = false
    private var query = ""
    private val searchTick = Runnable { buildMusic() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        baseUrl = intent.getStringExtra(EXTRA_URL) ?: run {
            finish(); return
        }
        sid = intent.getStringExtra(EXTRA_SID) ?: prefs.sid

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
        }

        // --- 顶栏 ---
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(8))
        }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(TextView(this).apply {
            text = intent.getStringExtra(EXTRA_NAME) ?: "媒体库"
            setTextColor(Palette.FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        titleBox.addView(TextView(this).apply {
            text = baseUrl
            setTextColor(Palette.ACCENT_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })
        top.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 顶栏不再放按钮：原来这里有一个「网页」按钮，字号、底色、高度都和下面
        // 一排页签不同，看上去像从别的 App 里剪过来贴上去的。
        // 现在它变成第五个页签「网页」，和「影视 · 音乐 · 图书 · 管理设置」同高同宽
        // 同字体，选中态也由同一套 Palette.tab/paintTab 画。
        root.addView(top)

        // --- 页签 ---
        // 选中态是「主色淡底 + 主色字 + 主色描边」，未选中是「近底灰 + 弱字」。
        // 之前三个页签只换文字、不换底色，屏幕上一排灰块，看不出在哪一页。
        //
        // 「网页」是最后一个页签。它不是网页 View，点了直接开系统浏览器 ——
        // 之所以还占一个页签位，是因为它和另外几个一样是「从这一页去看另一处东西」，
        // 放在页签行里比在顶栏单独放一个按钮更容易被找到，也不再和顶栏标题抢宽度。
        //
        // 六个页签（影视/音乐/图书/相册/管理设置/网页）等分后每个只有 ~150px，
        // 「管理设置」四个字就会挤成两行。所以套一层 HorizontalScrollView：
        // 正常宽度下还是等分铺满，字号变大或屏幕更窄时改成可横向滑，不再压缩文字。
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        for ((i, label) in listOf("影视", "音乐", "图书", "相册", "管理设置", "网页").withIndex()) {
            val t = Palette.tab(this, label, false).apply {
                if (i == TAB_WEB) setOnClickListener { openWeb() } else setOnClickListener { selectTab(i) }
            }
            tabViews.add(t)
            // 六个页签在 1080 宽上每格只有 150px，「管理设置」会被挤成两行，
            // 所以给一个最小宽度、放不下就让外面那层 HorizontalScrollView 横滑
            tabs.addView(t, LinearLayout.LayoutParams(dp(TAB_MIN_W), dp(42)).apply {
                if (i > 0) leftMargin = dp(6)
            })
        }
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(tabScroll)

        // 相册多选工具条：默认隐藏，长按任意一张图才出现。
        // 放在页签和内容之间而不是贴底：贴底会被 MIUI 手势条压住，也会被
        // 内容区的滚动吞掉；放上面高度固定、永远点得到。
        photoSelBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setBackgroundColor(Palette.CARD)
            setPadding(dp(14), dp(6), dp(10), dp(6))
            photoSelCount = TextView(this@LibraryActivity).apply {
                text = "已选 0 张"
                setTextColor(Palette.FG)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            addView(photoSelCount, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            photoSelAll = Palette.ghost(this@LibraryActivity, "全选").apply {
                setOnClickListener { toggleSelectAllPhotos() }
            }
            addView(photoSelAll, LinearLayout.LayoutParams(dp(64), dp(34)))
            addView(Palette.outline(this@LibraryActivity, "删除").apply {
                setTextColor(Palette.WARN_HI)
                setOnClickListener { confirmDeletePhotos() }
            }, LinearLayout.LayoutParams(dp(64), dp(34)).apply { leftMargin = dp(6) })
            addView(Palette.ghost(this@LibraryActivity, "取消").apply {
                setOnClickListener { exitPhotoSel() }
            }, LinearLayout.LayoutParams(dp(64), dp(34)).apply { leftMargin = dp(6) })
        }
        root.addView(photoSelBar)

        spinner = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
        }
        root.addView(spinner)

        hint = TextView(this).apply {
            setTextColor(Palette.DIM2)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            text = "加载中…"
        }
        root.addView(hint)

        val scroll = ScrollView(this)
        val host = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        videoHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        musicHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bookHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        photoHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        manageHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        host.addView(videoHost)
        host.addView(musicHost)
        host.addView(bookHost)
        host.addView(photoHost)
        host.addView(manageHost)
        scroll.addView(host)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        // 状态栏/手势栏透明，看到的永远是 root 的 #0F141A（详见 Immersive.tintBars）
        Immersive.tintBars(this, Palette.BG)
        selectTab(0)
        reload()

        // 自动更新：冷启动查一次（内部有 6 小时闸 + 用户开关），
        // manual=false 意味着没新版本时完全静默，不会打扰正在找片的人。
        ApkUpdater.checkOnStart(this, false)
    }

    private fun selectTab(idx: Int) {
        currentTab = idx
        // 页签不做真 TabHost：几个容器同时存在，只切可见性，来回切不重排
        videoHost.visibility = if (idx == 0) View.VISIBLE else View.GONE
        musicHost.visibility = if (idx == 1) View.VISIBLE else View.GONE
        bookHost.visibility = if (idx == 2) View.VISIBLE else View.GONE
        photoHost.visibility = if (idx == 3) View.VISIBLE else View.GONE
        manageHost.visibility = if (idx == 4) View.VISIBLE else View.GONE
        // 设置页首次进入才建视图：里面要读 prefs、挂监听、还要请求服务端
        if (idx == 4 && manageHost.childCount == 0) {
            buildManage()
        }
        // 相册首次进入才拉清单：几千张图的 JSON 不该在冷启动时和影音书一起抢带宽
        if (idx == 3 && photoHost.childCount == 0) {
            loadPhotos()
        }
        for ((i, t) in tabViews.withIndex()) {
            // 「网页」页签永远不标选中：它不是一页内容，点完就跳浏览器再回来，
            // 给它可以留下一个假的选中态会让用户以为停在了某个「网页页」上。
            Palette.paintTab(t, i == idx && i != TAB_WEB)
        }
        updateHint()
    }

    private fun updateHint() {
        val v = vlib
        val m = mlib
        val b = blib
        val p = plib
        hint.text = when {
            // 设置页不显示媒体统计：那一行的句式和「影视 N 部」完全不同族
            currentTab == TAB_MANAGE -> "服务器端：站点与四个库的目录 · 客户端：自动更新与缓存"
            currentTab == 3 -> {
                if (p == null) "相册加载中…"
                else "相册 ${p.photos.size} 张 · ${p.albumCount()} 个相册"
            }
            v == null && m == null && b == null -> "加载中…"
            currentTab == 0 -> {
                val groups = v?.groups?.size ?: 0
                "影视 ${v?.videos?.size ?: 0} 部 · ${groups} 个目录"
            }
            currentTab == 1 -> {
                val base = "音乐 ${m?.tracks?.size ?: 0} 首 · ${m?.dirs?.size ?: 0} 个目录"
                when (musicView) {
                    1 -> "$base · 按歌手"
                    2 -> "$base · 按专辑"
                    else -> base
                }
            }
            else -> "图书 ${b?.books?.size ?: 0} 本 · ${b?.groups?.size ?: 0} 个书架"
        }
    }

    private fun reload() {
        spinner.visibility = View.VISIBLE
        val base = baseUrl
        val ck = sid
        io.execute {
            val v = VideoLib.fetch(base, ck, null, 15000)
            val m = Music.fetch(base, ck, 15000)
            val b = BookLib.fetch(base, ck, null, 15000)
            ui.post {
                spinner.visibility = View.GONE
                vlib = v
                mlib = m
                blib = b
                buildVideos()
                buildMusic()
                buildBooks()
                updateHint()
                if (!v.ok && !m.ok && !b.ok) {
                    hint.text = "三个库都没取到：" + (v.error ?: m.error ?: b.error ?: "")
                }
            }
        }
    }

    // ---------------------------------------------------------------- 影视

    private fun buildVideos() {
        videoHost.removeAllViews()
        val lib = vlib
        if (lib == null || lib.videos.isEmpty()) {
            videoHost.addView(msg(if (lib == null) "…" else
                "还没有影视。管理员在网页端「影视目录」里指一个目录就会出来。"))
            return
        }
        // 按服务端给的 groups 分块：盘0 / 盘1 / 内部存储 各成一段，
        // 这正是需求里「电影板块可以有盘0和盘1两部分」的呈现
        for (g in lib.groups) {
            val inGroup = lib.videos.filter { under(it.path, g.path.let { p -> if (p.endsWith("/")) p.trimEnd('/') else p }) }
            if (inGroup.isEmpty()) continue
            videoHost.addView(sectionHeader(g.label ?: g.path, "${inGroup.size} 部", g.path))
            var i = 0
            while (i < inGroup.size) {
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(12), 0, dp(12), dp(10))
                }
                for (k in 0 until 3) {
                    if (i + k < inGroup.size) {
                        line.addView(videoCard(inGroup[i + k]))
                    } else {
                        line.addView(android.view.View(this).apply {
                            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                        })
                    }
                }
                videoHost.addView(line)
                i += 3
            }
        }
        // 兜底：不在任何 group 里的（理论上不会有）
        val orphan = lib.videos.filter { v -> lib.groups.none { under(v.path, it.path) } }
        if (orphan.isNotEmpty()) {
            videoHost.addView(sectionHeader("其他", "${orphan.size} 部", ""))
            for (v in orphan) videoHost.addView(videoRow(v))
        }
    }

    private fun videoCard(v: Video): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(4), 0, dp(4), 0)
            isClickable = true
            setOnClickListener { openVideo(v) }
        }
        val img = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Palette.CARD)
        }
        box.addView(img)

        val nm = TextView(this).apply {
            text = if (v.title.isNullOrEmpty()) v.name else v.title
            setTextColor(Palette.FG2)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 2
            setPadding(0, dp(5), 0, 0)
        }
        box.addView(nm)

        val tag = if (v.isTv) v.tag() else (v.mtime.let { "" })
        val sub = TextView(this).apply {
            text = if (v.isTv) v.tag() else folderName(v.dir)
            setTextColor(Palette.DIM2)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 1
        }
        box.addView(sub)

        loadPoster(v, img)
        return box
    }

    private fun videoRow(v: Video) = TextView(this).apply {
        text = "· ${if (v.title.isNullOrEmpty()) v.name else v.title}"
        setTextColor(Palette.FG2)
        setPadding(dp(16), dp(6), dp(16), dp(6))
        isClickable = true
        setOnClickListener { openVideo(v) }
    }

    private fun loadPoster(v: Video, img: ImageView) {
        val cached = posterCache[v.path]
        if (cached != null) {
            img.setImageBitmap(cached)
            return
        }
        val base = baseUrl
        val ck = sid
        io.execute {
            val meta = VideoLib.fetchMeta(base, ck, v.path, 12000)
            val poster = meta?.posterUrl
            val bmp = if (poster.isNullOrEmpty())
                null else fetchImage(VideoLib.artUrl(base, poster), ck)
            ui.post {
                posterCache[v.path] = bmp
                if (bmp != null) img.setImageBitmap(bmp)
                else img.setImageResource(android.R.drawable.ic_media_play)
            }
        }
    }

    private fun openVideo(v: Video) {
        val base = baseUrl
        val url = VideoLib.videoUrl(base, v.path)
        // 内嵌播放：MediaPlayer + SurfaceView 走系统硬解，常见编码都能吃，
        // 不依赖设备上装没装播放器。冷门封装失败时 VideoPlayerActivity
        // 自己会给「用其它播放器」的兜底按钮。
        startActivity(VideoPlayerActivity.intent(this, url, v.name, sid))
    }

    // ---------------------------------------------------------------- 音乐

    private fun buildMusic() {
        musicHost.removeAllViews()
        val lib = mlib
        if (lib == null || lib.tracks.isEmpty()) {
            musicHost.addView(msg(if (lib == null) "…" else
                "还没有音乐。管理员在网页端「音乐目录」里指一个目录就会出来。"))
            return
        }
        val hits = Music.filter(lib.tracks, query)
        musicHost.addView(musicBar(hits.size))
        if (hits.isEmpty()) {
            musicHost.addView(msg("没有匹配「$query」的歌。"))
            return
        }
        when (musicView) {
            1 -> buildMusicGrouped(hits, Music.GroupBy.ARTIST)
            2 -> buildMusicGrouped(hits, Music.GroupBy.ALBUM)
            else -> buildMusicFlat(hits)
        }
    }

    /**
     * 平铺视图：按目录分专辑，同一个目录里的歌顺序播放。
     *
     * 目录只有一层时（很多人的曲库就是「音乐/歌手 - 歌名.mp3」全平铺），
     * 这里会得到唯一一个大块 —— 那种情况请用「按歌手 / 按专辑」。
     */
    private fun buildMusicFlat(hits: List<Track>) {
        val albums = LinkedHashMap<String, MutableList<Track>>()
        for (t in hits) albums.getOrPut(t.dir) { ArrayList() }.add(t)
        for ((dir, songs) in albums) {
            musicHost.addView(sectionHeader(folderName(dir), "${songs.size} 首", dir))
            for (t in songs) musicHost.addView(trackRow(songs, t))
        }
    }

    /**
     * 分组视图：一个歌手（或一张专辑）一行，点开才铺开里面的歌。
     *
     * 为什么默认折叠：真实曲库 1116 首会拆出三百多个歌手，全展开等于把
     * 几百行塞进一个 LinearLayout，滚动会卡、首屏还会白等。
     */
    private fun buildMusicGrouped(hits: List<Track>, by: Music.GroupBy) {
        val buckets = Music.group(hits, by)
        val openKey = openedBuckets
        // 第一次进来默认展开第一个桶，免得用户面对一屏折叠无处下手
        if (openKey.isEmpty() && buckets.isNotEmpty() && !firstBucketOpened) {
            firstBucketOpened = true
            openKey.add(by.label + "/" + buckets[0].name)
        }
        for (b in buckets) {
            val key = by.label + "/" + b.name
            val open = openKey.contains(key)
            musicHost.addView(bucketHead(b, open) {
                if (open) openKey.remove(key) else openKey.add(key)
                buildMusic()
            })
            if (!open) continue
            for (t in b.tracks) musicHost.addView(trackRow(b.tracks, t))
        }
        musicHost.addView(msg("共 ${buckets.size} 个${by.label}，点名字展开"))
    }

    private fun bucketHead(b: Music.Bucket, open: Boolean, toggle: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            setOnClickListener { toggle() }
            addView(TextView(this@LibraryActivity).apply {
                text = if (open) "▾" else "▸"
                setTextColor(Palette.ACCENT_HI)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(0, 0, dp(8), 0)
            })
            addView(TextView(this@LibraryActivity).apply {
                text = b.name
                setTextColor(Palette.ACCENT_HI)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                maxLines = 1
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@LibraryActivity).apply {
                text = b.summary()
                setTextColor(Palette.DIM2)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            })
        }

    /** 音乐页顶部的视图切换：平铺 / 按歌手 / 按专辑 + 搜索框 */
    private fun musicBar(n: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(4))
        val row = LinearLayout(this@LibraryActivity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val labels = arrayOf("平铺", "按歌手", "按专辑")
        for ((idx, label) in labels.withIndex()) {
            val on = musicView == idx
            // 与顶部三页签同一套视觉：选中主色底、未选中 Palette.BTN，
            // 都走自绘圆角，避免裸 Button 的 Material 灰块。
            val b = Button(this@LibraryActivity).apply {
                text = label
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(if (on) 0xFFFFFFFF.toInt() else Palette.DIM)
                backgroundTintList = null
                stateListAnimator = null
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                background = Palette.round(this@LibraryActivity,
                    if (on) Palette.ACCENT else Palette.BTN, 9f)
                setOnClickListener {
                    musicView = idx
                    openedBuckets.clear()
                    firstBucketOpened = false
                    buildMusic()
                }
            }
            row.addView(b, LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                marginEnd = dp(6)
            })
        }
        addView(row)
        val search = android.widget.EditText(this@LibraryActivity).apply {
            setSingleLine()
            hint = "搜歌手 / 专辑 / 歌名（共 $n 首）"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Palette.FG2)
            setHintTextColor(Palette.FAINT)
            setBackgroundColor(Palette.CARD)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            if (query.isNotEmpty()) setText(query)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val v = s?.toString()?.trim() ?: ""
                    if (v == query) return
                    query = v
                    openedBuckets.clear()
                    firstBucketOpened = false
                    ui.removeCallbacks(searchTick)
                    ui.postDelayed(searchTick, 220)
                }
            })
        }
        addView(search, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })
    }

    private fun trackRow(album: List<Track>, t: Track) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(11), dp(16), dp(11))
        isClickable = true
        setBackgroundColor(Color.TRANSPARENT)
        addView(TextView(this@LibraryActivity).apply {
            text = "♪"
            setTextColor(Palette.ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, 0, dp(10), 0)
        })
        val col = LinearLayout(this@LibraryActivity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this@LibraryActivity).apply {
            text = t.displayTitle()
            setTextColor(Palette.FG2)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
        })
        col.addView(TextView(this@LibraryActivity).apply {
            // 副标题给「歌手 · 专辑」，这两样才是找歌时要看的；只有大小时等于没信息
            val a = t.artist()
            val al = t.guessAlbum()
            val bits = ArrayList<String>()
            if (a.isNotEmpty()) bits.add(a)
            if (al.isNotEmpty() && al != a) bits.add(al)
            bits.add(Music.humanSize(t.size))
            if (t.hasLyrics()) bits.add("有歌词")
            text = bits.joinToString("   ·  ")
            setTextColor(Palette.DIM2)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1
        })
        addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        setOnClickListener { openPlayer(album, t) }
    }

    private fun openPlayer(album: List<Track>, t: Track) {
        val i = Intent(this, PlayerActivity::class.java)
        i.putExtra(PlayerActivity.EXTRA_URL, baseUrl)
        i.putExtra(PlayerActivity.EXTRA_SID, sid)
        i.putExtra(PlayerActivity.EXTRA_FILES, album.map { it.path }.toTypedArray())
        i.putExtra(PlayerActivity.EXTRA_NAMES, album.map { it.name }.toTypedArray())
        i.putExtra(PlayerActivity.EXTRA_TITLES, album.map { if (it.title.isNullOrEmpty()) "" else it.title }.toTypedArray())
        i.putExtra(PlayerActivity.EXTRA_LRCS, album.map { it.lrcName ?: "" }.toTypedArray())
        i.putExtra(PlayerActivity.EXTRA_SIZES, album.map { it.size }.toLongArray())
        i.putExtra(PlayerActivity.EXTRA_DIRS, album.map { it.dir }.toTypedArray())
        // 封面绝对路径（可能是 null）：播放页拿去拼直链、在后台线程解码，
        // 解不出来就退回自绘蓝贴纸，不会因为一张图报错而影响播放
        i.putExtra(PlayerActivity.EXTRA_COVERS, album.map { it.cover ?: "" }.toTypedArray())
        i.putExtra(PlayerActivity.EXTRA_START, album.indexOf(t))
        startActivity(i)
    }

    // ---------------------------------------------------------------- 图书

    /**
     * 书库：按目录分层架，一行一本。
     *
     * 和音乐列表同构（都不画封面），理由见 Book 类注释：电子书没有统一的封面
     * 来源，硬造占位图只会更乱；读者要的是「书名 + 格式 + 找到它」。
     */
    private fun buildBooks() {
        bookHost.removeAllViews()
        val lib = blib
        if (lib == null) {
            bookHost.addView(msg("…"))
            return
        }
        if (!lib.ok) {
            bookHost.addView(msg("书库取不到：" + lib.error))
            return
        }
        if (lib.notConfigured()) {
            bookHost.addView(msg("还没有书库。管理员在网页端「图书目录」里指一个目录就会出来。"))
            return
        }
        if (lib.books.isEmpty()) {
            bookHost.addView(msg("书架上还没有书。"))
            return
        }
        // 按目录分层架：技术 / 科幻 / 各自一段，目录名本身就是分类
        val shelves = LinkedHashMap<String, MutableList<Book>>()
        for (b in lib.books) {
            shelves.getOrPut(b.dir) { ArrayList() }.add(b)
        }
        for ((dir, list) in shelves) {
            bookHost.addView(sectionHeader(folderName(dir), "${list.size} 本", dir))
            for (b in list) bookHost.addView(bookRow(b))
        }
    }

    private fun bookRow(b: Book) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(11), dp(16), dp(11))
        isClickable = true
        setBackgroundColor(Color.TRANSPARENT)
        addView(TextView(this@LibraryActivity).apply {
            text = "📖"
            setTextColor(Palette.ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, 0, dp(10), 0)
        })
        val col = LinearLayout(this@LibraryActivity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this@LibraryActivity).apply {
            text = if (b.title.isNullOrEmpty()) b.name else b.title
            setTextColor(Palette.FG2)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
        })
        col.addView(TextView(this@LibraryActivity).apply {
            text = b.tag() + "   " + Music.humanSize(b.size)
            setTextColor(Palette.DIM2)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })
        addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        setOnClickListener { openBook(b) }
    }

    /**
     * 打开一本书。
     *
     * 内嵌阅读：服务端 /_books/text 把 docx/doc/epub/txt 统一抽成纯文本，
     * 客户端只负责排版，所以不跳浏览器、也不需要设备上装阅读器。
     * PDF 走另一条：包里带了 PDFium（jniLibs 里那 13MB 原生库就是干这个的），
     * 直接进 PdfActivity 渲染，不再甩给浏览器（手机浏览器把 PDF 塞 iframe 会一片黑）。
     */
    private fun openBook(b: Book) {
        if (b.isPdf()) {
            startActivity(PdfActivity.intent(this, baseUrl, sid, b.path, b.name))
            return
        }
        startActivity(BookReaderActivity.intent(this, baseUrl, sid, b.path, b.name))
    }

    // ---------------------------------------------------------------- 相册

    /**
     * 拉相册清单。
     *
     * 服务端一次返回全部图（上限 4000 张），不做分页：一页几十张的接口意味着
     * 用户每滑一屏就要等一次网络，而清单本身只是一堆路径 + 宽高，4000 条也就
     * 几百 KB。真正的负载在缩略图解码上，那部分交给 [photoCard] 逐张异步解。
     */
    private fun loadPhotos() {
        photoHost.removeAllViews()
        photoHost.addView(msg("相册加载中…"))
        val base = baseUrl
        val ck = sid
        io.execute {
            val lib = PhotoLib.fetch(base, ck, null, 15000)
            ui.post {
                plib = lib
                buildPhotos()
                updateHint()
            }
        }
    }

    /**
     * 相册页：按目录分相册，每个相册一幅三列缩略图墙。
     *
     * 为什么不和影视一样一个卡片一行：图的价值就在「一眼看到很多」，
     * 一行一张会把 100 张图变成 100 屏。
     */
    private fun buildPhotos() {
        photoHost.removeAllViews()
        photoCellRefs.clear()
        val lib = plib
        if (lib == null) {
            photoHost.addView(msg("…"))
            return
        }
        if (!lib.ok) {
            photoHost.addView(msg("相册取不到：" + lib.error))
            photoHost.addView(Palette.outline(this, "重试").apply {
                setOnClickListener { loadPhotos() }
            }, LinearLayout.LayoutParams(dp(108), dp(38)).apply {
                leftMargin = dp(16); topMargin = dp(4); bottomMargin = dp(12)
            })
            return
        }
        if (lib.notConfigured()) {
            // 「没配」和「配了但是空的」必须分开说：前者要去管理设置动一下，
            // 后者用户自己知道那个目录里没照片。
            photoHost.addView(msg("还没有相册。到「管理设置 → 相册目录」里指一个放照片的目录就会出来。"))
            return
        }
        if (lib.photos.isEmpty()) {
            photoHost.addView(msg("相册目录里还没有图。"))
            return
        }

        // 按目录分组成相册，顺序沿用服务端清单里第一次出现的先后
        val albums = LinkedHashMap<String, MutableList<Photo>>()
        for (p in lib.photos) {
            albums.getOrPut(p.dir ?: "") { ArrayList() }.add(p)
        }
        for ((dir, list) in albums) {
            photoHost.addView(sectionHeader(folderName(dir), "${list.size} 张", dir))
            // 三列网格：每行一个横向 LinearLayout，装 3 个等宽格子
            var row: LinearLayout? = null
            for ((i, p) in list.withIndex()) {
                if (i % PHOTO_COLS == 0) {
                    row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 0, 0, 0)
                    }
                    photoHost.addView(row, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                val cell = photoCard(p)
                row!!.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    // 留出 6dp 缝，靠右/下补在容器上；最后一列不多留
                    rightMargin = if (i % PHOTO_COLS == PHOTO_COLS - 1) 0 else dp(6)
                    bottomMargin = dp(6)
                    if (i % PHOTO_COLS == 0) leftMargin = dp(14)
                })
            }
            // 补满最后一行：不足 3 个时塞透明占位，避免剩下的格子被拉宽
            val rest = list.size % PHOTO_COLS
            if (rest != 0) {
                for (k in rest until PHOTO_COLS) {
                    row!!.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f).apply {
                        rightMargin = if (k == PHOTO_COLS - 1) 0 else dp(6)
                    })
                }
            }
        }
        photoHost.addView(TextView(this).apply {
            text = "共 ${lib.photos.size} 张 · ${lib.albumCount()} 个相册"
            setTextColor(Palette.FAINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(16), dp(10), dp(16), dp(20))
        })
        renderSelectionBar()
    }

    // ------------------------------------------------------- 相册多选（长按进入）

    /** 长按任意一格进入多选，点格子切换选中；这个集合就是被选中的图片路径 */
    private val photoSel = LinkedHashSet<String>()

    /** 缩略图 View 与它的路径一一对应，选中态要回头去改对应格子的角标和描边 */
    private val photoCellRefs = HashMap<String, Pair<FrameLayout, TextView>>()

    private fun albumOrdered(): List<Photo> =
        plib?.photos ?: emptyList()

    private fun enterPhotoSel(p: Photo) {
        if (photoSel.isEmpty()) {
            // 进入多选时把长按的那一张先选上：长按的本意就是「我要操作它」
            photoSel.add(p.path)
            renderSelectionBar()
            refreshPhotoSelection()
        }
    }

    private fun exitPhotoSel() {
        if (photoSel.isEmpty()) return
        photoSel.clear()
        renderSelectionBar()
        refreshPhotoSelection()
    }

    private fun togglePhotoSel(p: Photo) {
        if (!photoSel.remove(p.path)) photoSel.add(p.path)
        renderSelectionBar()
        refreshPhotoSelection()
    }

    /** 只改角标和边框，不重建网格（重建会让所有缩略图重新解码，几千张会卡死） */
    private fun refreshPhotoSelection() {
        val on = photoSel.isNotEmpty()
        for ((path, ref) in photoCellRefs) {
            val (box, badge) = ref
            val picked = photoSel.contains(path)
            box.background = if (picked) {
                Palette.round(this, Palette.ACCENT_SOFT, 8f, Palette.ACCENT, 2f)
            } else {
                Palette.round(this, Palette.CARD, 8f, Palette.LINE, 1f)
            }
            badge.visibility = if (on) View.VISIBLE else View.GONE
            badge.text = if (picked) "✓" else ""
            badge.setBackgroundColor(
                if (picked) Palette.ACCENT else Color.argb(0x99, 0x0B, 0x0E, 0x12)
            )
        }
    }

    /**
     * 多选工具条：贴在页签行下面的那一条。
     *
     * 做成常驻容器 + 按需显示，而不是临时 addView/removeView —— 后者会让下方
     * 网格整体上下跳，手指按在格子上时会被这一跳错位。
     */
    private fun renderSelectionBar() {
        val bar = photoSelBar ?: return
        val on = photoSel.isNotEmpty()
        bar.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) return
        photoSelCount?.text = "已选 ${photoSel.size} 张"
        photoSelAll?.text = if (photoSel.size >= albumOrdered().size) "取消全选" else "全选"
    }

    private fun toggleSelectAllPhotos() {
        val all = albumOrdered()
        if (photoSel.size >= all.size) {
            photoSel.clear()
        } else {
            for (p in all) photoSel.add(p.path)
        }
        renderSelectionBar()
        refreshPhotoSelection()
    }

    /**
     * 批量删除：先弹确认框，再按批 POST。
     *
     * 服务端删的是「这个目录里的这一张」，路径必须是相册目录下的（越界会被拒），
     * 客户端这边只挑当前选中集合里确实还在清单里的路径，避免删到一半清单刷新
     * 后对不上号。
     */
    private fun confirmDeletePhotos() {
        val picks = photoSel.toList()
        if (picks.isEmpty()) return
        val dlg = AlertDialog.Builder(this)
            .setTitle("删除 ${picks.size} 张照片？")
            .setMessage("会从 NAS 的磁盘上真的删掉（不进回收站）。这个操作不能撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> doDeletePhotos(picks) }
            .create()
        dlg.show()
        dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.setTextColor(Palette.WARN_HI)
    }

    private fun doDeletePhotos(picks: List<String>) {
        if (adminState?.admin != true) {
            // 删文件属于写操作：不是管理员就先告诉用户去登录，而不是发一个注定 403 的请求
            Toast.makeText(this,
                "删除需要管理员权限，先到上面「设置服务器端」里登录",
                Toast.LENGTH_LONG).show()
            return
        }
        val base = baseUrl
        val ck = sid
        msg("正在删除 ${picks.size} 张…")
        io.execute {
            var done = 0
            var failed = 0
            var lastErr: String? = null
            for (batch in picks.chunked(PHOTO_DEL_BATCH)) {
                val r = try {
                    PhotoLib.delete(base, ck, batch, 20000)
                } catch (t: Throwable) {
                    PhotoLib.Deleted().apply { ok = false; error = "${t.message}" }
                }
                if (r.ok) done += r.deleted else {
                    failed += batch.size
                    lastErr = r.error
                }
            }
            ui.post {
                if (failed == 0) {
                    Toast.makeText(this@LibraryActivity, "已删除 $done 张", Toast.LENGTH_SHORT).show()
                    exitPhotoSel()
                    loadPhotos()
                } else {
                    Toast.makeText(this@LibraryActivity,
                        "删了 $done 张，失败 $failed 张：${lastErr ?: "未知错误"}",
                        Toast.LENGTH_LONG).show()
                    loadPhotos()
                }
            }
        }
    }

    /**
     * 一个缩略图格子：正方形，先画底色，图到了再贴上去。
     *
     * 先占位再加载是必须的：4000 张图如果等解码完才 addView，界面会先空白几秒
     * 然后一次性跳出来，用户以为卡死。
     */
    private fun photoCard(p: Photo): View {
        val ctx = this
        val box = FrameLayout(this).apply {
            background = Palette.round(this@LibraryActivity, Palette.CARD, 8f, Palette.LINE, 1f)
            isClickable = true
            isLongClickable = true
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.TRANSPARENT)
        }
        box.addView(img, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(PHOTO_CELL)))

        // 右上角选中角标：多选时出现，未选中是一个空的半透明圆
        val badge = TextView(this).apply {
            text = ""
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            visibility = if (photoSel.isEmpty()) View.GONE else View.VISIBLE
            background = Palette.round(this@LibraryActivity,
                Color.argb(0x99, 0x0B, 0x0E, 0x12), 11f, Color.argb(0xCC, 0xFF, 0xFF, 0xFF), 1f)
        }
        box.addView(badge, FrameLayout.LayoutParams(dp(22), dp(22)).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(5)
            rightMargin = dp(5)
        })

        // 底部一条半透明条显示文件名：图上看不出这是哪一张，尤其连拍
        val tag = TextView(this).apply {
            text = p.title()
            setTextColor(Palette.FG2)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setBackgroundColor(Color.argb(0xCC, 0x0B, 0x0E, 0x12))
            setPadding(dp(5), dp(2), dp(5), dp(2))
        }
        box.addView(tag, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM })

        // 单击：多选态 = 勾选/取消；平常 = 全屏预览
        box.setOnClickListener {
            if (photoSel.isNotEmpty()) togglePhotoSel(p) else showPhoto(p)
        }
        // 长按：进入多选（而不是弹菜单 —— 弹菜单还得再点一次「选择」）
        box.setOnLongClickListener {
            if (photoSel.isEmpty()) enterPhotoSel(p) else exitPhotoSel()
            true
        }

        photoCellRefs[p.path] = box to badge
        if (photoSel.contains(p.path)) {
            box.background = Palette.round(this, Palette.ACCENT_SOFT, 8f, Palette.ACCENT, 2f)
            badge.visibility = View.VISIBLE
            badge.text = "✓"
            badge.setBackgroundColor(Palette.ACCENT)
        }

        // 缩略图走 /music 同一个服务（Music.audioUrl 按段编码路径）
        val url = PhotoLib.imageUrl(baseUrl, p.path)
        val ck = sid
        val key = "p:" + p.path
        val hit = posterCache[key]
        if (hit != null) {
            img.setImageBitmap(hit)
        } else {
            img.setImageBitmap(null)
            io.execute {
                val bmp = fetchImage(url, ck)
                ui.post {
                    posterCache[key] = bmp
                    // 视图可能已经被回收重建，setTag 记的路径还在才贴
                    if (bmp != null && box.isAttachedToWindow) img.setImageBitmap(bmp)
                }
            }
        }
        return box
    }

    /**
     * 点开一张图：全屏黑底 + 双指缩放 + 单指拖动 + 双击回弹，点空白退出。
     *
     * 为什么不用 PhotoView 之类的库：整个项目没有图片加载依赖，为了一个
     * 「看大图」拉一个几十 KB 的第三方库不值；Matrix + ScaleGestureDetector
     * 不到一百行，行为还完全可控（比如放大后拖动不能把图拖出屏幕）。
     */
    private fun showPhoto(p: Photo) {
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.MATRIX
            setBackgroundColor(Color.parseColor("#000000"))
        }
        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        photoDlg = dlg

        val host = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        host.addView(iv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 顶部一条标题：文件名 + 页码，轻手一划就看不见了（半透明，不挡图）
        val cap = TextView(this).apply {
            text = "${p.title()}　·　${p.width}×${p.height}"
            setTextColor(Color.argb(0xE6, 0xFF, 0xFF, 0xFF))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackgroundColor(Color.argb(0x66, 0x00, 0x00, 0x00))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        host.addView(cap, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP })

        dlg.setContentView(host, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dlg.show()
        dlg.setOnDismissListener { photoDlg = null }

        val matrix = android.graphics.Matrix()
        val m = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        var baseScale = 1f          // FIT_CENTER 后的初始倍率
        var minScale = 1f           // 最小倍率 = 初始（不允许缩得比铺满还小）
        val maxScale = 6f           // 最大 6 倍：再大就是马赛克

        fun applyMatrix() {
            iv.imageMatrix = matrix
        }

        /** 把图摆成 FIT_CENTER 并记下初始倍率 */
        fun fit(bmp: Bitmap) {
            matrix.reset()
            val vw = iv.width.toFloat().takeIf { it > 0f } ?: resources.displayMetrics.widthPixels.toFloat()
            val vh = iv.height.toFloat().takeIf { it > 0f } ?: resources.displayMetrics.heightPixels.toFloat()
            val s = Math.min(vw / bmp.width, vh / bmp.height)
            baseScale = s
            minScale = s
            // 先缩放到铺满，再居中
            matrix.setScale(s, s)
            matrix.postTranslate((vw - bmp.width * s) / 2f, (vh - bmp.height * s) / 2f)
            m[0] = s
            applyMatrix()
        }

        /**
         * 把图拉回可视范围：放大后拖动、或者双指缩放把图挪出去了，都要落回边界。
         * 小于等于初始倍率时强制居中 —— 否则会留一块黑边看起来像图偏了。
         */
        fun clamp(bmp: Bitmap) {
            matrix.getValues(m)
            val vw = iv.width.toFloat()
            val vh = iv.height.toFloat()
            val sw = bmp.width * m[0]
            val sh = bmp.height * m[4]
            val dx = if (sw <= vw) (vw - sw) / 2f else m[2].coerceIn(vw - sw, 0f)
            val dy = if (sh <= vh) (vh - sh) / 2f else m[5].coerceIn(vh - sh, 0f)
            m[2] = dx
            m[5] = dy
            matrix.setValues(m)
            applyMatrix()
        }

        val scaleDetector = android.view.ScaleGestureDetector(this,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                    val f = d.scaleFactor
                    val cur = m[0]
                    // 夹在 [min, max] 里：超界就按边界比例缩，手感不跳
                    val target = (cur * f).coerceIn(minScale, maxScale)
                    val real = if (cur <= 0f) 1f else target / cur
                    matrix.postScale(real, real, d.focusX, d.focusY)
                    matrix.getValues(m)
                    applyMatrix()
                    return true
                }

                override fun onScaleEnd(d: android.view.ScaleGestureDetector) {
                    val b = photoBitmaps["p:" + p.path]
                    if (b != null) clamp(b)
                }
            })

        val tapDetector = android.view.GestureDetector(this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: android.view.MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                    // 单指轻点：退出。放大状态下误触退出很烦，所以放大时单点不退出
                    if (m[0] <= minScale * 1.02f) dlg.dismiss()
                    return true
                }

                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    val b = photoBitmaps["p:" + p.path] ?: return true
                    val cur = m[0]
                    if (cur > minScale * 1.5f) {
                        fit(b)
                    } else {
                        val target = (minScale * 2.5f).coerceAtMost(maxScale)
                        val real = if (cur <= 0f) 1f else target / cur
                        matrix.postScale(real, real, e.x, e.y)
                        matrix.getValues(m)
                        applyMatrix()
                        clamp(b)
                    }
                    return true
                }

                override fun onScroll(
                    e1: android.view.MotionEvent?,
                    e2: android.view.MotionEvent,
                    dx: Float,
                    dy: Float
                ): Boolean {
                    // 只有放大之后才允许拖动；不然单指划一下图就飘出去了
                    if (m[0] <= minScale * 1.02f) return true
                    if (scaleDetector.isInProgress) return true
                    matrix.postTranslate(-dx, -dy)
                    matrix.getValues(m)
                    applyMatrix()
                    return true
                }
            })

        iv.setOnTouchListener { v, e ->
            // 先喂给缩放手势，再喂给拖动/点击；两者互斥由内部判断
            val scaled = scaleDetector.onTouchEvent(e)
            tapDetector.onTouchEvent(e)
            if (e.actionMasked == android.view.MotionEvent.ACTION_UP ||
                e.actionMasked == android.view.MotionEvent.ACTION_CANCEL
            ) {
                val b = photoBitmaps["p:" + p.path]
                if (b != null && !scaleDetector.isInProgress) clamp(b)
                v.performClick()
            }
            scaled || true
        }

        val ck = sid
        val key = "p:" + p.path
        val hit = photoBitmaps[key]
        if (hit != null) {
            // 视图还没完成布局，width/height 此时是 0；等一帧再 fit，否则按全屏尺寸算会被裁
            iv.post { fit(hit) }
            iv.setImageBitmap(hit)
            return
        }
        val url = PhotoLib.imageUrl(baseUrl, p.path)
        io.execute {
            // 全屏图要看细节，不能沿用缩略图的采样，单独按原图解一次
            val bmp = fetchImageFull(url, ck)
            ui.post {
                if (bmp == null) {
                    cap.text = "这张图打不开：${p.name}"
                    return@post
                }
                photoBitmaps[key] = bmp
                iv.setImageBitmap(bmp)
                iv.post { fit(bmp) }
            }
        }
    }

    /** 列表用的缩略图：解码时按目标尺寸降采样，否则 4000 张原图会瞬间 OOM */
    private fun fetchImage(url: String, ck: String?): Bitmap? {
        var c: HttpURLConnection? = null
        return try {
            c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 12000
            c.setRequestProperty("User-Agent", "USBNASLink/1.0")
            if (!ck.isNullOrEmpty()) c.setRequestProperty("Cookie", "${Session.COOKIE}=$ck")
            c.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Throwable) {
            null
        } finally {
            c?.disconnect()
        }
    }

    /** 全屏查看用的解码：先量尺寸，再按 2048 上限降采样，兼顾清晰与内存 */
    private fun fetchImageFull(url: String, ck: String?): Bitmap? {
        var c: HttpURLConnection? = null
        return try {
            val bytes = URL(url).openConnection().let { raw ->
                val hc = raw as HttpURLConnection
                c = hc
                hc.connectTimeout = 8000
                hc.readTimeout = 30000
                hc.setRequestProperty("User-Agent", "USBNASLink/1.0")
                if (!ck.isNullOrEmpty()) hc.setRequestProperty("Cookie", "${Session.COOKIE}=$ck")
                hc.inputStream.use { it.readBytes() }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
            val opt = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
        } catch (e: Throwable) {
            null
        } finally {
            c?.disconnect()
        }
    }

    // ---------------------------------------------------------------- 小工具

    private fun sectionHeader(title: String, count: String, path: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(6))
        addView(TextView(this@LibraryActivity).apply {
            text = "$title    $count"
            setTextColor(Palette.FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (path.isNotEmpty()) addView(TextView(this@LibraryActivity).apply {
            text = path
            setTextColor(Palette.FAINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            maxLines = 1
        })
    }

    private fun msg(s: String) = TextView(this).apply {
        text = s
        setTextColor(Palette.DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(dp(18), dp(20), dp(18), dp(20))
    }

    private fun openWeb() {
        val i = Intent(this, BrowseActivity::class.java)
        i.putExtra(BrowseActivity.EXTRA_URL, baseUrl)
        i.putExtra(BrowseActivity.EXTRA_NAME, intent.getStringExtra(EXTRA_NAME))
        startActivity(i)
    }

    /**
     * 从「管理设置 → 去网页端登录」进网页，带上回到本页的标记。
     *
     * 为什么需要这个标记：网页端和 App 用的是同一个 session cookie，用户在网页里
     * 登录成功之后，App 手里那个 sid 往往已经换了新的（服务端登录会重发 session），
     * 但 App 一直在用内存里那个旧的 —— 回来一看还写着「要登录」。
     * 所以回程 onResume 里补一次 `Admin.fetch`，把真实身份（以及要不要提示）
     * 重新拉一遍，而不是让用户去杀进程。
     */
    private fun openWebForLogin() {
        val i = Intent(this, BrowseActivity::class.java)
        i.putExtra(BrowseActivity.EXTRA_URL, baseUrl)
        i.putExtra(BrowseActivity.EXTRA_NAME, intent.getStringExtra(EXTRA_NAME))
        i.putExtra(EXTRA_LOGIN_TRY, true)
        startActivity(i)
    }

    override fun onResume() {
        super.onResume()
        // 只在「管理设置」页签可见时才刷新：其它页签刷新没必要，还会多打一次请求
        if (currentTab == TAB_MANAGE && manageHost.childCount > 0) {
            loadManage(false)
        }
    }

    /** 第四个页签「管理设置」：先建 App 自身设置（同步、不联网），NAS 全局配置异步填 */
    private fun buildManage() {
        manageHost.removeAllViews()
        // 分成两大块，顺序按「先看这台 NAS 怎么样，再改我自己的 App」：
        //   1. 设置服务器端 —— 站点名称 + 四个库的目录，存在服务端，所有客户端共用
        //   2. 设置客户端   —— 自动更新、缓存占用，只影响手上这台手机
        // 两块的标题下面都带一条状态行：服务器端那块写「登录身份」，客户端那块写
        // 「App 版本」，这样一眼能看出「我到底登没登进去」—— 原来只有一句
        // 静态说明，登录过期了也照样写着要登录，用户会以为按钮坏了。
        manageHost.addView(blockHeader("1 · 设置服务器端",
            "站点名称与四个库的目录是全局配置，存在服务端，所有客户端共用；改它需要管理员账号。"))
        manageHost.addView(serverStatusCard())
        manageHost.addView(nasSettingsCard())
        manageHost.addView(blockHeader("2 · 设置客户端",
            "下面这些只影响这台手机上的 App，不上传、不同步、改完立刻生效。"))
        manageHost.addView(ApkUpdater.settingsView(this))
        loadManage()
    }

    /** 大板块标题：主色小字 + 粗体标题 + 一行说明 */
    private fun blockHeader(title: String, hintText: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(14), dp(4), dp(2))
        }
        box.addView(TextView(this).apply {
            text = title
            setTextColor(Palette.ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.06f
        })
        box.addView(Palette.note(this, hintText, Palette.DIM2).apply {
            setPadding(0, dp(3), 0, 0)
        })
        return box
    }

    // ---------------------------------------------------------------- 管理设置

    /** 服务端全局配置的状态（站点名称 / 音乐 / 影视 / 图书 / 相册目录） */
    private var adminState: Admin.State? = null
    private var adminCard: LinearLayout? = null
    private var serverStatusRow: TextView? = null
    private var titleBox: EditText? = null
    private var musicTree: DirTree? = null
    private var videoTree: DirTree? = null
    private var bookTree: DirTree? = null
    private var photoTree: DirTree? = null

    /** 相册多选的工具条容器 / 计数 / 全选按钮（常驻，按需显示） */
    private var photoSelBar: LinearLayout? = null
    private var photoSelCount: TextView? = null
    private var photoSelAll: Button? = null

    /** 全屏预览用的大图缓存，键与缩略图缓存分开（采样倍率不同，不能混用） */
    private val photoBitmaps = HashMap<String, Bitmap>()
    private val adminIo = Executors.newSingleThreadExecutor { r ->
        Thread(r, "usbnas-admin").apply { isDaemon = true }
    }

    /**
     * 「登录身份」状态行 + 刷新按钮。
     *
     * 为什么单独做一行：登录态是服务端内存里的 session，改密码、服务端重启、
     * 手机在网页端登录又登出，都会让手上这个 session 突然变匿名；而 App 这边
     * 一直缓存着旧 sid，界面还停在「管理员」或「要登录」，只能靠杀进程才更新。
     * 有了这一行 + 刷新按钮，用户看到「要登录」时点一下就知道到底是谁的问题。
     */
    private fun serverStatusCard(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(12), dp(6))
        }
        serverStatusRow = Palette.note(this, "正在确认登录状态…", Palette.DIM).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        row.addView(serverStatusRow, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Palette.outline(this, "刷新").apply {
            setOnClickListener { refreshManage(true) }
        }, LinearLayout.LayoutParams(dp(72), dp(36)).apply { leftMargin = dp(8) })
        return row
    }

    private fun nasSettingsCard(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        adminCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(20))
        }
        box.addView(adminCard)
        return box
    }

    /** 带用户反馈的刷新：转一下转圈提示，成功后 toast 报身份 */
    private fun refreshManage(showToast: Boolean) {
        val row = serverStatusRow
        row?.text = "正在刷新…"
        row?.setTextColor(Palette.DIM)
        loadManage(showToast)
    }

    private fun loadManage(showToast: Boolean = false) {
        val card = adminCard ?: return
        if (baseUrl.isEmpty()) return
        val base = baseUrl
        val ck = sid
        adminIo.execute {
            val st = try {
                Admin.fetch(base, ck, 9000)
            } catch (t: Throwable) {
                Admin.State().apply { ok = false; error = "${t.message}" }
            }
            ui.post {
                adminState = st
                fillServerStatus(st)
                fillManage(st)
                if (showToast) {
                    Toast.makeText(
                        this@LibraryActivity,
                        when {
                            !st.ok -> "刷新失败：${st.error ?: "没有响应"}"
                            st.admin -> "已刷新：管理员「${st.who}」"
                            st.logged -> "已刷新：账号「${st.who}」（${st.levelName}），不是管理员"
                            else -> "已刷新：服务端看不到登录态，需要重新登录"
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /** 只重画顶部那一行身份，不动下面的表单（避免刷新时把正在编辑的输入框冲掉） */
    private fun fillServerStatus(st: Admin.State) {
        val row = serverStatusRow ?: return
        when {
            !st.ok -> {
                row.text = "连不上服务端：${st.error ?: "没有响应"}"
                row.setTextColor(Palette.WARN_HI)
            }
            st.admin -> {
                row.text = "已登录：管理员 ${st.who}（${st.levelName}）"
                row.setTextColor(Palette.OK)
            }
            st.logged -> {
                row.text = "已登录：${st.who}（${st.levelName}）· 改配置需要管理员"
                row.setTextColor(Palette.WARN_HI)
            }
            else -> {
                row.text = "未登录（会话已过期或从未登录）"
                row.setTextColor(Palette.WARN_HI)
            }
        }
    }

    private fun fillManage(st: Admin.State) {
        val card = adminCard ?: return
        card.removeAllViews()
        titleBox = null
        musicTree = null
        videoTree = null
        bookTree = null
        photoTree = null

        if (!st.ok) {
            card.addView(Palette.note(this,
                "读不到服务端配置：${st.error ?: "没有响应"}（多半是它还不支持 /_admin/api）",
                Palette.WARN_HI))
            card.addView(Palette.outline(this, "重试").apply { setOnClickListener { loadManage() } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38))
                    .apply { topMargin = dp(10) })
            return
        }
        if (!st.admin) {
            card.addView(Palette.note(this, if (st.logged)
                "当前账号「${st.who}」是${st.levelName}，改设置需要管理员。"
            else
                "还没登录，或者登录已过期；改设置需要管理员账号。", Palette.DIM))
            // 两个按钮并排：去网页登录 + 刷新。只给「去网页端登录」时，用户在网页端
            // 登完回来页面上仍旧写着要登录（sid 只在自己进程里缓存），会以为没登上。
            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            btns.addView(Palette.solid(this, if (st.logged) "换管理员账号" else "去网页端登录").apply {
                setOnClickListener {
                    openWebForLogin()
                }
            }, LinearLayout.LayoutParams(0, dp(46), 1.6f))
            btns.addView(Palette.outline(this, "刷新会话").apply {
                setOnClickListener { refreshManage(true) }
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(8) })
            card.addView(btns, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) })
            // 非管理员也要能看见当前配置是什么（只读体面地显示出来）
            if (!st.logged) return
        } else {
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            bar.addView(Palette.note(this, "管理员：${st.who}（${st.levelName}）", Palette.OK),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(Palette.outline(this, "刷新").apply {
                setOnClickListener { refreshManage(true) }
            }, LinearLayout.LayoutParams(dp(72), dp(36)))
            card.addView(bar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) })
        }

        val editable = st.admin
        titleBox = Palette.field(this, "显示在浏览器标题栏和响应头里").apply {
            setText(st.title ?: "")
            isEnabled = editable
        }
        card.addView(manageSection("站点名称",
            "最多 24 个字符；留空会用默认的「一只肥鼠的NAS」。",
            titleBox, null, editable) {
            save("title", "站点名称", titleBox?.text?.toString() ?: "")
        })

        musicTree = DirTree(this).apply { setPaths(st.musicDirs ?: emptyList()) }
        card.addView(manageSection("音乐目录",
            "客户端播放器只在这些目录里找歌。点方框勾选、点文字展开。",
            null, musicTree, editable) {
            save("music", "音乐目录", musicTree?.checkedPaths()?.joinToString("\n") ?: "")
        })
        musicTree!!.rebuild(st.choices ?: emptyList(), st.musicDirs ?: emptyList())

        videoTree = DirTree(this).apply { setPaths(st.videoDirs ?: emptyList()) }
        card.addView(manageSection("影视目录",
            "电影和剧可以分在不同盘里，客户端按目录分块显示；海报和 .nfo 从视频同目录读。",
            null, videoTree, editable) {
            save("video", "影视目录", videoTree?.checkedPaths()?.joinToString("\n") ?: "")
        })
        videoTree!!.rebuild(st.choices ?: emptyList(), st.videoDirs ?: emptyList())

        bookTree = DirTree(this).apply { setPaths(st.bookDirs ?: emptyList()) }
        card.addView(manageSection("图书目录",
            "「图书」页只显示这里的书；不配就是空书库（不会去扫全盘找 txt）。",
            null, bookTree, editable) {
            save("book", "图书目录", bookTree?.checkedPaths()?.joinToString("\n") ?: "")
        })
        bookTree!!.rebuild(st.choices ?: emptyList(), st.bookDirs ?: emptyList())

        // 相册：第四个库。特意排在最后 —— 前三个是「本来就有内容」的库，
        // 相册是「你要先指个目录」才有内容的，放最后不打乱前三个的肌肉记忆。
        photoTree = DirTree(this).apply { setPaths(st.photoDirs ?: emptyList()) }
        card.addView(manageSection("相册目录",
            "「相册」页只显示这里的图。认 jpg/jpeg/png/webp/bmp/gif/heic；不配就没有相册。",
            null, photoTree, editable) {
            save("photo", "相册目录", photoTree?.checkedPaths()?.joinToString("\n") ?: "")
        })
        photoTree!!.rebuild(st.choices ?: emptyList(), st.photoDirs ?: emptyList())
    }

    /** 一段配置：标题 + 说明 +（输入框 | 目录树）+ 保存按钮 */
    private fun manageSection(
        title: String,
        hintText: String,
        editor: View?,
        tree: DirTree?,
        editable: Boolean,
        onSave: () -> Unit
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, dp(8))
        }
        box.addView(TextView(this).apply {
            text = title
            setTextColor(Palette.FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        box.addView(Palette.note(this, hintText))
        if (editor != null) {
            box.addView(editor, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (tree != null) {
            // 不要把树塞进内层 ScrollView：外层已有滚动容器，嵌套同向滚动在测量时
            // 拿不到高度，树会渲染成 0 高度（连「目录树 · 已选 N 个」都看不见）。
            box.addView(tree.component(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
            box.addView(Palette.note(this, "已选路径（保存的就是这里的每一行，也可以直接手改）"))
            box.addView(tree.editor(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (editable) {
            box.addView(Palette.solid(this, "保存$title", Palette.BTN, Palette.FG2).apply {
                setOnClickListener { onSave() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42))
                .apply { topMargin = dp(10) })
        }
        return box
    }

    private fun save(action: String, label: String, value: String) {
        if (adminState?.admin != true) {
            Toast.makeText(this, "当前会话不是管理员", Toast.LENGTH_SHORT).show()
            return
        }
        val base = baseUrl
        val ck = sid
        val dirs = value.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        adminIo.execute {
            val r = try {
                when (action) {
                    "title" -> Admin.saveTitle(base, ck, value, 9000)
                    "music" -> Admin.saveMusicDirs(base, ck, dirs, 9000)
                    "book" -> Admin.saveBookDirs(base, ck, dirs, 9000)
                    "photo" -> Admin.savePhotoDirs(base, ck, dirs, 9000)
                    else -> Admin.saveVideoDirs(base, ck, dirs, 9000)
                }
            } catch (t: Throwable) {
                Admin.Saved().apply { ok = false; error = "${t.message}" }
            }
            ui.post {
                Toast.makeText(
                    this@LibraryActivity,
                    if (r.ok) "$label 已保存（${r.msg ?: "已生效"}）"
                    else "$label 没保存：${r.error ?: "未知错误"}",
                    if (r.ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()
                if (r.ok) {
                    loadManage()
                    reload()
                }
            }
        }
    }

    private fun copy(s: String) {
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", s))
    }

    private fun under(path: String, root: String): Boolean =
        path == root || path.startsWith(if (root.endsWith("/")) root else "$root/")

    private fun folderName(dir: String): String {
        val p = dir.trimEnd('/')
        val i = p.lastIndexOf('/')
        return if (i < 0) p else p.substring(i + 1)
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts", "m2ts" -> "video/mp2t"
        else -> "video/*"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        io.shutdownNow()
        adminIo.shutdownNow()
        super.onDestroy()
    }
}
