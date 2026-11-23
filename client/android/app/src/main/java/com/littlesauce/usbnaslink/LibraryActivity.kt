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
    private lateinit var manageHost: LinearLayout
    private lateinit var hint: TextView
    private lateinit var spinner: ProgressBar

    /** 三个页签按钮，切页时只刷它们的选中态（不重建视图） */
    private val tabViews = ArrayList<TextView>()

    private var vlib: VideoLib.Lib? = null
    private var mlib: Music.Lib? = null
    private var blib: BookLib.Lib? = null
    private val posterCache = HashMap<String, Bitmap?>()

    private var currentTab = 0

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

        // 顶栏两个按钮走 Palette.ghost：自绘深色圆角底 + 主色字。
        // 用裸 Button 会套 Material 默认背景，在深色页面上是 #D6D7D7 灰块，
        // 跟播放页的按钮完全不像一家人（这就是「UI 不统一」的根因）。
        val btnWeb = Palette.ghost(this, "网页", Palette.ACCENT_SOFT).apply {
            setOnClickListener { openWeb() }
        }
        top.addView(btnWeb)

        // 「设置」：自动更新开关与手动检查都挂在这里（详见 showSettings）。
        // 放在顶栏而不是藏进菜单，是因为它是「App 自己的事」，
        // 和「这台 NAS 里有什么」明显不是一路。
        val btnSet = Palette.ghost(this, "设置", Palette.DIM).apply {
            setOnClickListener { showSettings() }
        }
        top.addView(btnSet)
        root.addView(top)

        // --- 页签 ---
        // 选中态是「主色淡底 + 主色字 + 主色描边」，未选中是「近底灰 + 弱字」。
        // 之前三个页签只换文字、不换底色，屏幕上一排灰块，看不出在哪一页。
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        for ((i, label) in listOf("影视", "音乐", "图书", "管理设置").withIndex()) {
            val t = Palette.tab(this, label, false).apply { setOnClickListener { selectTab(i) } }
            tabViews.add(t)
            tabs.addView(t, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                if (i > 0) leftMargin = dp(8)
            })
        }
        root.addView(tabs)

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
        manageHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        host.addView(videoHost)
        host.addView(musicHost)
        host.addView(bookHost)
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
        manageHost.visibility = if (idx == 3) View.VISIBLE else View.GONE
        // 设置页首次进入才建视图：里面要读 prefs、挂监听，没必要冷启动就做
        if (idx == 3 && manageHost.childCount == 0) {
            manageHost.addView(ApkUpdater.settingsView(this))
        }
        for ((i, t) in tabViews.withIndex()) Palette.paintTab(t, i == idx)
        updateHint()
    }

    private fun updateHint() {
        val v = vlib
        val m = mlib
        val b = blib
        hint.text = when {
            // 设置页不显示媒体统计：那一行的句式和「影视 N 部」完全不同族
            currentTab == 3 -> "App 自身的设置，与这台 NAS 里有什么无关"
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
     * 设置：只有自动更新这一件 App 自己的事。
     *
     * 不新开 Activity：里面就一个开关和两个按钮，为它再写一个页面、
     * 再挂一次 Manifest，代价比收益大。对话框用 ScrollView 包着，
     * 小屏横屏时也能滚到底部的「立即检查」。
     */
    /**
     * 设置：从「顶栏弹窗」改成第四个页签「管理设置」。
     *
     * 原来的对话框只有「关闭」一个按钮、内容还跟媒体列表共用一屏，改开关时
     * 底下几万条曲目还在加载。现在它和桌面端「管理设置」页签对齐：进页签才
     * 创建视图，切走就只换可见性，滚动位置自然保留。
     */
    private fun showSettings() {
        selectTab(3)
    }

    private fun copy(s: String) {
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", s))
    }

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
        super.onDestroy()
    }
}
