package com.littlesauce.usbnaslink

import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 播放页：封面位置放大字歌词，底下一排控制。
 *
 * 播放列表是通过 Intent 整批传进来的（路径/名字/标题/大小/目录/lrc 名），
 * 这样播放页不依赖媒体库对象，也能从「继续播放」这类入口单独拉起来。
 *
 * 歌词跟着 200ms 的进度回调走，不做动画 —— 手机上匀速滚动反而更晃眼。
 */
class PlayerActivity : AppCompatActivity(), Player.Listener {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_SID = "sid"
        const val EXTRA_FILES = "files"
        const val EXTRA_NAMES = "names"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_LRCS = "lrcs"
        const val EXTRA_SIZES = "sizes"
        const val EXTRA_DIRS = "dirs"
        const val EXTRA_COVERS = "covers"
        const val EXTRA_EMBEDS = "embeds"
        const val EXTRA_START = "start"

        // 图标按钮用可着色的 Unicode 字形，不用彩色 emoji —— 这样日夜主题换的是 Palette.FG，
        // 图标自动跟着变深/变浅，不会在白天留一个always-black 的符号。
        private const val GLYPH_PLAY = "▶"
        // ⏸ 在 MIUI 上即便加 FE0E 也会被 emoji 字体画成橙色，不跟 Palette.FG 换色；
        // 用纯文本的双竖线才算真的「图标 + 日夜适配」。
        private const val GLYPH_PAUSE = "‖"
        private const val GLYPH_PREV = "\u23EE\uFE0E"
        private const val GLYPH_NEXT = "\u23ED\uFE0E"
    }

    private lateinit var player: Player

    /** 顶部标题与副标题（竖屏那一份） */
    private lateinit var title: TextView
    private lateinit var sub: TextView
    /** 横屏那一份副本：同一个 View 不能挂在两个父容器上，纯文字就多造一个 */
    private lateinit var titleL: TextView
    private lateinit var subL: TextView

    /** 竖屏容器 / 横屏容器；两者同时存在，靠可见性切换 */
    private lateinit var portraitRow: LinearLayout
    private lateinit var landscapeRow: LinearLayout
    private lateinit var leftCol: LinearLayout
    private lateinit var rightCol: LinearLayout
    private var isLandscape = false
    /** 上一次按标题拉过封面的歌曲路径，避免每 200ms 的回调重复起线程 */
    private var coverFor: String? = null
    private lateinit var lyric: TextView
    /** 当前句与下一句歌词文本（分开存，两个回调谁先到都能正确重画） */
    private var curLyricText: String? = null
    private var nextLyricText: String? = null
    private lateinit var timeText: TextView
    private lateinit var bar: SeekBar
    private lateinit var playBtn: Button
    private lateinit var modeBtn: Button
    private lateinit var scrapeBtn: Button
    private lateinit var overlayBtn: Button
    /** 刮削歌词 + 桌面歌词 + 歌词位移，统一装在这一张圆角卡片里 */
    private lateinit var lyricToolsCard: LinearLayout
    /** 收起态：刮削歌词 / 桌面歌词 / ▼ */
    private lateinit var toolsRow: LinearLayout
    private lateinit var lyricsExpandBtn: Button
    /** 展开态：调整歌词时间： / −0.5s / 位移 / +0.5s / 取消 */
    private lateinit var adjustRow: LinearLayout
    private lateinit var offsetLabel: TextView

    /** 顶部转圈的黑胶；进度、播放态、歌名都在 Listener 回调里喂给它 */
    private lateinit var disc: DiscView

    /** 唱片区在「有歌词 / 无歌词」两种高度之间切换用的行数权重 */
    private var discOpen = true   // 保留字段：历史版本用它做点击收起，现已固定展开，不再参与布局

    private var dragging = false

    /** 桌面歌词浮窗；只有用户点过一次才创建，免得没权限时白占内存 */
    private var overlay: LyricOverlay? = null

    /** 浮窗开着时，每 200ms 的进度回调要喂给它 */
    private var overlayOn = false

    private var baseUrl = ""
    private var sid: String? = null

    /** 刮削对话框（搜完列出候选），存着是为了切歌时能关掉 */
    private var scrapeDialog: androidx.appcompat.app.AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题必须在建界面之前定下来：Palette 的取值是"当时"算的，
        // 界面建完再改模式，已画好的控件不会自己重算颜色。
        ThemeCtl.prime(this)
        // 语言同理：字符串资源在建界面时就定死了，晚一步就已经拿旧语言 inflate 了。
        L.prime(this)

        player = Player(this)
        player.listener = this
        // 播放页全屏：第一步只加窗口 flag（onCreate 里 decorView 还没建，不能碰 insetsController），
        // 真正的隐藏动作在 setContentView 之后和 onWindowFocusChanged 里做
        Immersive.prepare(this)

        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val sid = intent.getStringExtra(EXTRA_SID)
        baseUrl = url
        this.sid = sid
        player.setTarget(url, sid)

        val files = intent.getStringArrayExtra(EXTRA_FILES) ?: run { finish(); return }
        val names = intent.getStringArrayExtra(EXTRA_NAMES) ?: files
        val titles = intent.getStringArrayExtra(EXTRA_TITLES) ?: Array(files.size) { "" }
        val lrcs = intent.getStringArrayExtra(EXTRA_LRCS) ?: Array(files.size) { "" }
        val sizes = intent.getLongArrayExtra(EXTRA_SIZES) ?: LongArray(files.size)
        val dirs = intent.getStringArrayExtra(EXTRA_DIRS) ?: Array(files.size) { "" }
        val covers = intent.getStringArrayExtra(EXTRA_COVERS) ?: Array(files.size) { "" }
        // 内嵌封面标记单独一个数组：Track 构造器收起不了这么多字段，
        // 而且 embedded 是 boolean，和 covers（图路径）混在一个 String[] 里
        // 只能靠 "1"/"" 编码，还不如明说。列表页（LibraryActivity.openPlayer）
        // 传的是 album.map { if (it.embedded) "1" else "" }。
        val embeds = intent.getStringArrayExtra(EXTRA_EMBEDS) ?: Array(files.size) { "" }
        val start = intent.getIntExtra(EXTRA_START, 0)

        val tracks = ArrayList<Track>(files.size)
        for (i in files.indices) {
            val t = Track(files[i], names[i], titles[i], dirs[i], sizes[i], 0L)
            // 构造器没有 lrc/cover 参数，这三个要单独挂：Player.loadLyric 只看 hasLyrics()
            if (lrcs[i].isNotEmpty()) t.lrcName = lrcs[i]
            if (i < covers.size && covers[i].isNotEmpty()) t.cover = covers[i]
            // 少了这一行，服务端报的 473 首 i:1 到这里全变成 embedded=false，
            // loadCover 里 hasCover() 判空直接 return，内嵌封面一张都画不出来。
            if (i < embeds.size && embeds[i].isNotEmpty()) t.embedded = embeds[i] != "0"
            tracks.add(t)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dp(20), dp(24), dp(20), dp(20))
        }

        // 横屏时把「唱片区」和「文字/按钮区」左右分栏：横屏高度只有 1080，
        // 竖排的话唱片会被压成一条 60px 的扁片（实测过），而宽度还有 2400 在空着。
        portraitRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        landscapeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        leftCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rightCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        title = TextView(this).apply { titleStyle(this) }
        sub = TextView(this).apply { subStyle(this) }
        // 唱片区（给标题/副标题用第二份，两个模式各一份，靠切换可见性换位置）
        titleL = TextView(this).apply { copyTitleStyle() }
        subL = TextView(this).apply { copySubStyle() }

        // 唱片：不做点击缩放/收起。点一下唱片就跳一下版面，像在「点一张会伸缩的图片」，
        // 真实留声机上是动的只有盘和针，盘本身尺寸是固定的。
        disc = DiscView(this)

        lyric = TextView(this).apply {
            setTextColor(Palette.DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            gravity = Gravity.CENTER
            setLineSpacing(dp(8).toFloat(), 1f)
            setPadding(dp(8), 0, dp(8), 0)
            text = "♪"
        }

        timeText = TextView(this).apply {
            setTextColor(Palette.DIM2)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            text = "00:00 / 00:00"
        }

        bar = SeekBar(this).apply {
            max = 1000
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar?) { dragging = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    dragging = false
                    sb?.let { player.seekTo(it.progress / 1000f) }
                }
            })
        }

        val hint = TextView(this).apply {
            text = getString(R.string.play_seek_hint)
            setTextColor(Palette.FAINT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(10))
        }

        // 一行按钮：播放模式/上一首/播放暂停/下一首改成图标，和
        // 刮削歌词/桌面歌词/▼ 并排。原来两行合成一行，省出来的一行高度留给歌词。
        toolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        modeBtn = ctlIcon(modeGlyph(), modeLabel()) {
            player.playlist.mode = player.playlist.mode.next()
            modeBtn.text = modeGlyph()
            modeBtn.contentDescription = modeLabel()
        }
        val prev = ctlIcon(GLYPH_PREV, getString(R.string.play_prev)) { goSwitch(true) }
        playBtn = ctlIcon(GLYPH_PLAY, getString(R.string.play_play)) { player.toggle() }
        val next = ctlIcon(GLYPH_NEXT, getString(R.string.play_next)) { goSwitch(false) }
        for (b in listOf(modeBtn, prev, playBtn, next)) {
            toolsRow.addView(b, LinearLayout.LayoutParams(0, dp(40), 0.5f).apply {
                marginStart = dp(3); marginEnd = dp(3); topMargin = dp(2)
            })
        }
        scrapeBtn = ctlButton(getString(R.string.play_scrape)) { askScrape() }
        overlayBtn = ctlButton(getString(R.string.play_overlay_on)) { toggleOverlay() }
        lyricsExpandBtn = ctlButton(getString(R.string.play_lyrics_expand)) { showLyricsAdjust(true) }
        toolsRow.addView(scrapeBtn, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            marginStart = dp(3); marginEnd = dp(3); topMargin = dp(2)
        })
        toolsRow.addView(overlayBtn, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            marginStart = dp(3); marginEnd = dp(3); topMargin = dp(2)
        })
        toolsRow.addView(lyricsExpandBtn, LinearLayout.LayoutParams(dp(48), dp(40)).apply {
            marginStart = dp(3); marginEnd = dp(3); topMargin = dp(2)
        })

        // 展开态：调整歌词时间： / −0.5s / 位移 / +0.5s / 取消。
        // 与桌面端 PlayerWindow / 悬浮窗共用 Player.lyricOffsetMs，点取消只收起面板、不归零。
        adjustRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        adjustRow.addView(TextView(this).apply {
            text = getString(R.string.play_lyric_adjust_title)
            setTextColor(Palette.FG2)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setSingleLine(true)
        }, LinearLayout.LayoutParams(0, dp(38), 1.25f).apply {
            marginStart = dp(4); marginEnd = dp(2); topMargin = dp(4)
        })
        val earlierBtn = ctlButton(getString(R.string.play_lyric_minus)) { nudgeLyric(-500) }
        offsetLabel = TextView(this).apply {
            setTextColor(Palette.FG2)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setSingleLine(true)
            minWidth = dp(52)
        }
        val laterBtn = ctlButton(getString(R.string.play_lyric_plus)) { nudgeLyric(500) }
        val cancelBtn = ctlButton(getString(R.string.play_lyric_cancel)) { showLyricsAdjust(false) }
        adjustRow.addView(earlierBtn, LinearLayout.LayoutParams(0, dp(38), 0.8f).apply {
            marginStart = dp(2); marginEnd = dp(2); topMargin = dp(4)
        })
        adjustRow.addView(offsetLabel, LinearLayout.LayoutParams(0, dp(38), 0.9f).apply {
            marginStart = dp(2); marginEnd = dp(2); topMargin = dp(4)
        })
        adjustRow.addView(laterBtn, LinearLayout.LayoutParams(0, dp(38), 0.8f).apply {
            marginStart = dp(2); marginEnd = dp(2); topMargin = dp(4)
        })
        adjustRow.addView(cancelBtn, LinearLayout.LayoutParams(0, dp(38), 0.75f).apply {
            marginStart = dp(2); marginEnd = dp(4); topMargin = dp(4)
        })
        updateOffsetLabel()

        // 刮削歌词 / 桌面歌词 / 歌词时间微调共用同一张圆角卡片；
        // 收起和展开都只占一行的高度，横竖屏换父容器时只要搬这一块。
        lyricToolsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = Palette.round(this@PlayerActivity, Palette.CARD, 12f, Palette.LINE, 1f)
        }
        lyricToolsCard.addView(toolsRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        lyricToolsCard.addView(adjustRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- 竖屏：标题/副标题 + 唱片 + 歌词 + 进度 + 按钮，一路竖着排 ----
        // 这里只放 title/sub 两个竖屏专属控件；其余八个「会搬家」的由 attachOrientation 分发
        portraitRow.addView(title)
        portraitRow.addView(sub)

        // ---- 横屏：左边整块只放唱片，右边从上到下是标题/歌词/进度/按钮 ----
        // disc/lyric/timeText/bar/hint/ctl/lyricToolsCard 这些是「会搬家」的控件：
        // 竖屏挂在 portraitRow，横屏要换挂到 leftCol/rightCol。所以这里不预先 add，
        // 只把两个容器挂好，真正归属交给 attachOrientation(竖屏) 一次性分发 ——
        // 同一个 View 挂两个父容器会直接抛
        // IllegalStateException: The specified child already has a parent.
        // 只有纯文字的 titleL/subL 是横屏专属副本，一直留在 rightCol 里。
        rightCol.addView(titleL)
        rightCol.addView(subL)

        landscapeRow.addView(leftCol, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.MATCH_PARENT, 0.9f))
        landscapeRow.addView(rightCol, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.MATCH_PARENT, 1.3f))

        root.addView(portraitRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(landscapeRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        // 窗口挂上 DecorView 之后才藏状态栏（onCreate 开头调用会 NPE）
        Immersive.enter(this)

        // 按当前姿态把「会搬家」的控件挂一遍，再切可见性。
        // 注意不能走 applyOrientation()：它开头有 `if (isLandscape == landscape) return` 的
        // 去重判断，而这里 isLandscape 还停在初始值 false —— 竖屏启动时会被整段跳过，
        // 结果 disc/lyric/按钮一个都没挂上去（实测表现为 DiscView 0,0-0,0、整页空白）
        hintView = hint
        isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        attachOrientation(isLandscape)
        portraitRow.visibility = if (isLandscape) View.GONE else View.VISIBLE
        landscapeRow.visibility = if (isLandscape) View.VISIBLE else View.GONE
        if (isLandscape) {
            titleL.text = title.text
            subL.text = sub.text
        }
        applyDiscWeight()

        player.setPlaylist(tracks, tracks.getOrNull(start))
    }

    /**
     * 把「会搬家」的控件挂到当前姿态该去的父容器里。
     *
     * disc/lyric/timeText/bar/hint/lyricToolsCard 这些控件两种姿态都要用，
     * 但一个 View 只能有一个父容器，所以换姿态时只能真的挪（先 removeView 再 addView）。
     * 挪之前记下它在竖屏里的期望高度/权重，挪之后重新下发 LayoutParams：
     * disc 竖屏是 height=0+weight，横屏是 match_parent×match_parent+weight=1。
     */
    private var hintView: TextView? = null

    private fun attachOrientation(landscape: Boolean) {
        val hint = hintView ?: return
        val movers = listOf<Pair<android.view.View, LinearLayout.LayoutParams>>(
            disc to LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.15f).apply { topMargin = dp(10) },
            lyric to LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(16) },
            timeText to LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            bar to LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            hint to LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            lyricToolsCard to LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            }
        )
        for ((v, lp) in movers) {
            (v.parent as? android.view.ViewGroup)?.removeView(v)
            if (landscape) {
                if (v === disc) {
                    leftCol.addView(v, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                } else {
                    rightCol.addView(v, lp)
                }
            } else {
                // 播放控制并进歌词工具卡后只剩一行按钮，竖屏这里把歌词权重从 0.8
                // 提到 1.0（和唱片持平），多出来的高度正好多显示一句「还没唱到」的歌词。
                // 工具卡仍给 EXACTLY 高度，防止被权重挤到 0。
                val portraitLp = when {
                    v === disc -> LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f).apply { topMargin = dp(10) }
                    v === lyric -> LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f).apply { topMargin = dp(12) }
                    v === lyricToolsCard -> LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(6) }
                    else -> lp
                }
                portraitRow.addView(v, portraitLp)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        // 旋转会让窗口重新布局、insets 重新下发，全屏要再要一次
        Immersive.enter(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 从桌面/通知栏/锁屏回来，系统会把状态栏还回来；重新拿焦点时再要一次
        if (hasFocus) Immersive.enter(this)
    }

    /**
     * 横竖屏切换。
     *
     * 不是「重建 Activity」而是手动换父容器：重建会把播放停掉、把当前进度和
     * 浮窗一起丢掉，用户转一下手机就断歌是很糟的体验。这里只调整视图归属，
     * 播放器对象（MediaPlayer/歌词/浮窗）一个都不动。
     *
     * 一份控件只能有一个父容器，所以纯文字的 title/sub 额外准备了
     * titleL/subL 两个副本 —— 换模式时靠可见性挑一份显示，文字在 onTrack 里双写。
     */
    private fun applyOrientation(landscape: Boolean, remount: Boolean = true) {
        if (remount && isLandscape == landscape) return
        isLandscape = landscape
        // 先把控件挪到目标姿态的父容器里，再切可见性；
        // 顺序反了会有一帧「竖屏容器隐藏但控件还在里面」，横屏左栏一片空白
        if (remount) attachOrientation(landscape)
        portraitRow.visibility = if (landscape) View.GONE else View.VISIBLE
        landscapeRow.visibility = if (landscape) View.VISIBLE else View.GONE
        if (landscape) {
            titleL.text = title.text
            subL.text = sub.text
        } else {
            title.text = titleL.text
            sub.text = subL.text
        }
        applyDiscWeight()
        // 换姿态后父容器宽度变了：封面缓存按「盘中心半径」判重，这里把半径清零，
        // 让下一帧按新半径重裁一次，避免横屏用小图拼、竖屏用大图缩
        disc.requestCoverResize()
    }

    private fun titleStyle(t: TextView) {
        t.setTextColor(Palette.FG)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
        t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        t.maxLines = 2
    }

    private fun subStyle(t: TextView) {
        t.setTextColor(Palette.DIM2)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        t.setPadding(0, dp(4), 0, 0)
        t.maxLines = 1
    }

    private fun TextView.copyTitleStyle() = titleStyle(this)
    private fun TextView.copySubStyle() = subStyle(this)

    /**
     * 唱片区占多少行权重。
     *
     * 有歌词时 1.15 : 2.3（歌词拿大头），无歌词时让唱片占满剩下的空间 ——
     * 否则一首没歌词的歌打开来是一堆「♪」占着半屏。
     */
    /**
     * 唱片区占多少行权重。
     *
     * 竖屏：有歌词时 1.15 : 2.3（歌词拿大头），无歌词时让唱片占满剩下的空间 ——
     * 否则一首没歌词的歌打开来是一堆「♪」占着半屏。
     * 横屏：唱片区永远 match_parent×match_parent 占满左栏，权重只在竖屏有意义。
     */
    private fun applyDiscWeight() {
        val lp = disc.layoutParams as? LinearLayout.LayoutParams ?: return
        if (isLandscape) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.weight = 1f
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = 0
            // 1.15f 时 disc+lyric 把竖屏剩余高度吃光，位移排被压到 0，
            // 与 attachOrientation 里的 portraitLp 保持一致降到 1.0f
            lp.weight = 1.0f
        }
        disc.layoutParams = lp
        disc.invalidate()
    }

    private fun modeLabel(): String = when (player.playlist.mode) {
        Music.Mode.SEQUENTIAL -> getString(R.string.play_mode_seq)
        Music.Mode.LOOP_ALL -> getString(R.string.play_mode_all)
        Music.Mode.LOOP_ONE -> getString(R.string.play_mode_one)
        Music.Mode.SHUFFLE -> getString(R.string.play_mode_shuffle)
    }

    /**
     * 图标按钮：字形用 Palette.FG 着色，背景用 Palette.BTN，
     * 所以切到日间主题时不用重建布局也会跟着换色（ThemeCtl 换肤走同一套 Palette）。
     */
    private fun ctlIcon(glyph: String, desc: String, onClick: () -> Unit) = Button(this).apply {
        text = glyph
        contentDescription = desc
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        setTextColor(Palette.FG)
        backgroundTintList = null
        stateListAnimator = null
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        background = Palette.round(this@PlayerActivity, Palette.BTN, 9f)
        setOnClickListener { onClick() }
    }

    /** 播放模式的图形记号；文字语义仍放在 contentDescription 里 */
    private fun modeGlyph(): String = when (player.playlist.mode) {
        Music.Mode.SEQUENTIAL -> "⇥"
        Music.Mode.LOOP_ALL -> "↻"
        Music.Mode.LOOP_ONE -> "1↻"
        Music.Mode.SHUFFLE -> "⤨"
    }

    private fun ctlButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(Palette.FG2)
        // setBackgroundColor 会把 Material 背景整个换成方角纯色；
        // 走 Palette.round 才有圆角，且与媒体库顶栏按钮同一套色。
        backgroundTintList = null
        stateListAnimator = null
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        background = Palette.round(this@PlayerActivity, Palette.BTN, 9f)
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------ 切歌动画

    /**
     * 上一首 / 下一首。
     *
     * 不能直接调 `player.next()`：那样歌是瞬间换掉的，唱片却停在上一个角度，
     * 看起来像「点了没反应」。这里先让唱片甩出去（转 + 缩），甩完的那一刻才
     * 真正切歌，再收回 —— 画面和数据在同一条时间轴上，永远不会错位。
     *
     * 列表到头（没有下一首）时什么都不做，也不放动画：放了动画却没换歌更迷惑。
     */
    private fun goSwitch(prev: Boolean) {
        val canGo = if (prev) player.playlist.hasPrev() else player.playlist.hasNext()
        if (!canGo) {
            toast(if (prev) getString(R.string.play_at_first) else getString(R.string.play_at_last))
            return
        }
        disc.spinSwitch(prev) {
            if (prev) player.prev() else player.next()
        }
    }

    // ------------------------------------------------------------ Listener

    override fun onTrack(track: Track, index: Int) {
        val t = if (track.title.isNullOrEmpty()) track.name else track.title
        title.text = t
        sub.text = track.dir
        titleL.text = t
        subL.text = track.dir
        curLyricText = null
        nextLyricText = null
        renderLyric()
        disc.setTitle(t, track.artist().ifEmpty { "" })
        disc.setProgress(0L, 1L)
        // 注意：这里不要 setSpinning(false)。切歌动画里唱针正抬着，落针由 DiscView 在
        // 新盘滑回来之后自己决定；手动置 false 会把「切歌」误当成「用户暂停」。
        loadCover(track)
        // 底部那行「第 N/M 首 · 歌词名」已按反馈去掉；信息仍打进日志，不再占播放页面高度
        // 打一行日志：播放页刷新太密，uiautomator 抓不到 idle，出问题时只能靠日志定位
        android.util.Log.i("USBNASLink", "track ${index + 1}/${player.playlist.items.size} ${track.name} lrc=${track.lrcName ?: "-"}")
        // 换歌时上一首的候选列表必须关掉，否则点了会把 A 的歌词存给 B
        scrapeDialog?.dismiss()
        scrapeDialog = null
        if (overlayOn) overlay?.setNotice(getString(R.string.play_lyrics_loading))
    }

    /**
     * 拉封面。
     *
     * 走后台线程是因为它要过网络（服务端可能还在机械盘上）：主线上一等就是
     * 几百毫秒，播放页会明显卡一下。解码后再 OOM 兜一层 —— 一张 20MB 的
     * 扫描图不该把播放页带走，失败就保持自绘贴纸。
     */
    private fun loadCover(track: Track) {
        coverFor = track.path
        // 判断依据必须是 coverUrl 而不是 track.cover：内嵌封面（ID3 APIC /
        // FLAC PICTURE）走的是 /cover?f=<音频路径>，track.cover 此时是 null。
        // 之前写成 `val key = if (track.hasCover()) track.cover else null`，
        // 于是「只有内嵌封面、目录里没有 folder.jpg」的歌全部落进 key == null
        // 分支，直接画贴纸 —— 服务端报了 473 首 i:1，客户端一张都没显示。
        val url = track.coverUrl(baseUrl)
        if (!track.hasCover() || url.isNullOrEmpty()) {
            disc.setCover(null, "none:${track.path}")
            return
        }
        android.util.Log.i("USBNASLink", "cover get ${track.cover ?: "embedded"} -> $url")
        Thread {
            var bmp: android.graphics.Bitmap? = null
            try {
                // 先按 2x 上限解码，别把原图整个读进内存
                val opts = android.graphics.BitmapFactory.Options()
                opts.inJustDecodeBounds = true
                Music.openPublic(url, sid, 8000).let { c ->
                    c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
                }
                opts.inSampleSize = sampleFor(opts.outWidth, opts.outHeight)
                opts.inJustDecodeBounds = false
                Music.openPublic(url, sid, 15000).let { c ->
                    c.inputStream.use { bmp = android.graphics.BitmapFactory.decodeStream(it, null, opts) }
                }
            } catch (e: Throwable) {
                bmp = null
            }
            val got = bmp
            runOnUiThread {
                // 网络回来时用户可能已经切歌了，只认还在播的那一首
                if (coverFor != track.path) return@runOnUiThread
                if (got == null) {
                    disc.setCover(null, "fail:${track.path}")
                    android.util.Log.i("USBNASLink", "cover miss ${track.cover ?: "embedded"}")
                } else {
                    disc.setCover(got, track.path)
                    android.util.Log.i("USBNASLink", "cover ok ${got.width}x${got.height} ${track.cover ?: "embedded"}")
                }
            }
        }.start()
    }

    /** 目标边长 720px 足够：唱片中心直径最大也就 260dp 左右 */
    private fun sampleFor(w: Int, h: Int): Int {
        var s = 1
        var m = Math.max(w, h)
        while (m / 2 >= 720) {
            m /= 2
            s *= 2
        }
        return s
    }

    override fun onLrcLoaded(parsed: List<List<Any>>) {
        if (!overlayOn) return
        val o = overlay ?: return
        if (parsed.isEmpty()) {
            val t = player.current()
            o.setNotice(if (t != null && !t.hasLyrics()) getString(R.string.play_lyrics_none_scrape) else getString(R.string.play_lyrics_none))
        } else {
            o.setLrc(parsed)
        }
    }

    override fun onState(playing: Boolean) {
        playBtn.text = if (playing) GLYPH_PAUSE else GLYPH_PLAY
        playBtn.contentDescription = getString(if (playing) R.string.play_pause else R.string.play_play)
        disc.setSpinning(playing)
    }

    override fun onProgress(posMs: Long, totalMs: Long) {
        if (!dragging && totalMs > 0) {
            bar.progress = ((posMs * 1000) / totalMs).toInt().coerceIn(0, 1000)
        }
        timeText.text = fmt(posMs) + " / " + fmt(totalMs)
        disc.setProgress(posMs, totalMs)
        // 浮窗自己按 t<=pos 找行，所以把位移加进喂它的 pos 里，
        // 才能跟播放页大字（Player.emitLyric 里的 pos+offset）始终同一句。
        if (overlayOn) overlay?.tick(posMs + player.lyricOffset())
    }

    override fun onLyric(text: String?) {
        curLyricText = text
        renderLyric()
    }

    override fun onNextLyric(text: String?) {
        nextLyricText = text
        renderLyric()
    }

    /**
     * 当前句 + 下一句一起显示。下一句用更暗更小的字，视觉上是「预告」而不是正文；
     * 两行按钮并成一行后腾出来的高度正好给它。
     */
    private fun renderLyric() {
        if (!::lyric.isInitialized) return
        val cur = curLyricText ?: "♪"
        val next = nextLyricText
        if (next.isNullOrEmpty()) {
            lyric.text = cur
            return
        }
        val sb = android.text.SpannableStringBuilder()
        sb.append(cur).append('\n').append(next)
        val from = cur.length + 1
        sb.setSpan(android.text.style.ForegroundColorSpan(Palette.FAINT),
            from, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(android.text.style.RelativeSizeSpan(0.72f),
            from, sb.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        lyric.text = sb
    }

    override fun onEnded() {
        playBtn.text = GLYPH_PLAY
        playBtn.contentDescription = getString(R.string.play_play)
        disc.setSpinning(false)
    }

    /**
     * 一首歌自然播完自动切下一首，也走和手动点「下一首」一样的甩盘动画。
     * Player 在 completion 里把真正的切换动作包成 advance 交过来；这里先让
     * 唱片滑出去，到位了才执行 advance（换曲、onTrack 刷新）。
     * 万一上一次动画还没结束（切歌动画期间播完这种极短曲），spinSwitch 会直接忽略，
     * 此时必须自己调 advance，否则歌就卡在播完那一首不动了。
     */
    override fun onAutoAdvance(advance: () -> Unit) {
        android.util.Log.i("USBNASLink", "auto advance -> disc switch")
        if (!::disc.isInitialized || disc.isSwitching()) {
            advance()
            return
        }
        disc.spinSwitch(false) { advance() }
    }

    /**
     * 圆角卡片里两行二选一：收起时是「刮削歌词 / 桌面歌词 / ▼」，
     * 点箭头换成「调整歌词时间： / −0.5s / 位移 / +0.5s / 取消」。
     * 取消只收面板，不动已经调好的 offset —— 归零是另一件事，别把用户调的值偷偷抹掉。
     */
    private fun showLyricsAdjust(show: Boolean) {
        if (!::toolsRow.isInitialized || !::adjustRow.isInitialized) return
        toolsRow.visibility = if (show) View.GONE else View.VISIBLE
        adjustRow.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** 位移按钮：delta 毫秒；reset=true 时归零。改完刷标签，浮窗下一 tick 自动同步。 */
    private fun nudgeLyric(deltaMs: Int, reset: Boolean = false) {
        val v = if (reset) player.resetLyricOffset() else player.nudgeLyric(deltaMs)
        updateOffsetLabel()
        // Player.nudgeLyric 内部已 lastLyricIdx=-2 并 emitLyric 重取当前行，这里只刷标签
        if (v == 0L && !reset) toast(getString(R.string.play_lyric_offset, "0.0s"))
    }

    private fun updateOffsetLabel() {
        if (!::offsetLabel.isInitialized) return
        val ms = player.lyricOffset()
        val sign = if (ms > 0) "+" else if (ms < 0) "−" else ""
        val sec = String.format(java.util.Locale.US, "%.1f", Math.abs(ms) / 1000f)
        offsetLabel.text = getString(R.string.play_lyric_offset, sign + sec + "s")
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    // ------------------------------------------------------------ 刮削歌词

    /**
     * 「刮削歌词」：先搜，列候选，点一条就存到音频同目录。
     *
     * 为什么分两步而不是「搜到第一条就存」：同一首歌在 lrclib 上常有七八个
     * 版本（现场版、电视剧原声、翻唱），时长差几十秒歌词就会对不上；
     * 自动选一条大概率是错的，让用户看一眼专辑名再点更靠谱。
     */
    private fun askScrape() {
        val t = player.current() ?: run {
            toast(getString(R.string.play_not_started))
            return
        }
        val st = ScrapeState()
        scrapeBtn.isEnabled = false
        scrapeBtn.text = getString(R.string.play_searching)
        Thread {
            val r = Lyrics.search(baseUrl, sid, t.path, null, 20000)
            runOnUiThread {
                scrapeBtn.isEnabled = true
                scrapeBtn.text = getString(R.string.play_scrape)
                if (!r.ok) {
                    toast(getString(R.string.play_search_fail, r.error ?: getString(R.string.common_unknown_error)))
                    return@runOnUiThread
                }
                if (r.items.isEmpty()) {
                    toast(getString(R.string.play_search_empty, r.query))
                    return@runOnUiThread
                }
                showCandidates(t, r, st)
            }
        }.start()
    }

    /** 一个对话框里的可变状态：只剩「是否覆盖」这一个开关 */
    private class ScrapeState {
        var overwrite = false
    }

    private fun showCandidates(t: Track, r: Lyrics.Result, st: ScrapeState) {
        val names = r.items.map { it.line() }.toTypedArray()
        val checked = booleanArrayOf(st.overwrite)
        scrapeDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.play_pick_lyrics))
            .setSingleChoiceItems(names, -1) { d, which ->
                val c = r.items[which]
                d.dismiss()
                scrapeDialog = null
                doSave(t, c, st.overwrite)
            }
            .setNegativeButton(getString(R.string.common_cancel)) { d, _ ->
                d.dismiss()
                scrapeDialog = null
            }
            .setNeutralButton(getString(R.string.play_overwrite)) { _, _ ->
                st.overwrite = !st.overwrite
                checked[0] = st.overwrite
                // 这个按钮只切状态，不关框 —— 关了就看不到候选了
            }
            .setPositiveButton(getString(R.string.play_search_word)) { d, _ ->
                d.dismiss()
                scrapeDialog = null
                askKeyword(t)
            }
            .create()
        scrapeDialog?.show()
    }

    /** 搜索词不对时手工改一次（服务端按文件名猜的歌手经常是错的） */
    private fun askKeyword(t: Track) {
        val box = android.widget.EditText(this).apply {
            setSingleLine()
            hint = getString(R.string.play_search_hint)
            setText(t.artist() + " " + t.displayTitle())
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.play_search_title))
            .setView(box)
            .setPositiveButton(getString(R.string.play_search)) { d, _ ->
                d.dismiss()
                val kw = box.text.toString().trim()
                if (kw.isEmpty()) return@setPositiveButton
                searchByKeyword(t, kw)
            }
            .setNegativeButton(getString(R.string.common_cancel)) { d, _ -> d.dismiss() }
            .show()
    }

    private fun searchByKeyword(t: Track, kw: String) {
        scrapeBtn.isEnabled = false
        scrapeBtn.text = getString(R.string.play_searching)
        Thread {
            val r = Lyrics.search(baseUrl, sid, t.path, kw, 20000)
            runOnUiThread {
                scrapeBtn.isEnabled = true
                scrapeBtn.text = getString(R.string.play_scrape)
                if (!r.ok || r.items.isEmpty()) {
                    toast(getString(R.string.play_search_none, kw))
                } else {
                    showCandidates(t, r, ScrapeState())
                }
            }
        }.start()
    }

    /**
     * 保存前先确认会话还在。
     *
     * 服务端的 session 是内存态的，App 里存的 sid 在服务重启后就成了废纸；此时
     * /lyrics/save 会以匿名身份被拒，回报「没有该目录的写权限」—— 这句错误会把人
     * 往「权限配置错了」的方向带，实际只是要重新登录。所以先问一次 /_admin/api
     * 的 logged，过期就明说。
     *
     * 返回 true 表示可以继续（也包含「问不到但先试一下」的情况）。
     */
    private fun sessionReady(): Boolean {
        val s = sid
        if (s.isNullOrEmpty()) {
            toast(getString(R.string.play_need_login_write))
            return false
        }
        val ok = try {
            Session(baseUrl).apply { setSid(s) }.logged()
        } catch (e: Throwable) {
            null
        }
        if (ok == false) {
            toast(getString(R.string.play_login_expired))
            return false
        }
        return true
    }

    private fun doSave(t: Track, c: Lyrics.Cand, overwrite: Boolean) {
        if (!sessionReady()) return
        scrapeBtn.isEnabled = false
        scrapeBtn.text = getString(R.string.play_saving)
        Thread {
            var res = Lyrics.save(baseUrl, sid, t.path, c.token, overwrite, 20000)
            // 同名文件已存在时，服务端拒绝得很干脆：直接覆盖一次，别让用户再点一遍
            if (!res.ok && res.alreadyExists && !overwrite) {
                res = Lyrics.save(baseUrl, sid, t.path, c.token, true, 20000)
            }
            val finalRes = res
            runOnUiThread {
                scrapeBtn.isEnabled = true
                scrapeBtn.text = getString(R.string.play_scrape)
                if (!finalRes.ok) {
                    val e = finalRes.error ?: getString(R.string.common_unknown_error)
                    // 会话刚好在这几秒里过期时，仍然会走到这条分支。
                    // 注意这里匹配的是「服务端返回的中文报错」，不是本机文案，
                    // 所以翻译成英文界面也依然是这个中文子串 —— 正是要它。
                    val looksAuth = e.contains("写权限") || e.contains("登录")
                    if (looksAuth) {
                        toast(getString(R.string.play_save_fail_login, e))
                    } else {
                        toast(getString(R.string.play_save_fail, e))
                    }
                    return@runOnUiThread
                }
                // 存好了要让「当前这首」知道自己有歌词了，否则下一次 loadLyric 仍然早退
                t.lrcName = finalRes.file
                player.reloadLyricFor(t)
                toast(getString(R.string.play_saved_toast, finalRes.file))
            }
        }.start()
    }

    // ------------------------------------------------------------ 桌面歌词

    /**
     * 开关浮窗。
     *
     * 权限没给时不申请「求你了」的对话框，直接把用户送到系统那一页 ——
     * MIUI 上这个开关叫「显示在其他应用上层」，藏在「应用管理 → 特殊权限」里，
     * 用户自己找要翻好几层。
     */
    private fun toggleOverlay() {
        if (overlayOn) {
            overlayOn = false
            overlay?.hide()
            overlayBtn.text = getString(R.string.play_overlay_on)
            toast(getString(R.string.play_overlay_off_done))
            return
        }
        if (!LyricOverlay.canDraw(this)) {
            toast(getString(R.string.play_need_overlay_perm))
            try {
                startActivity(android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                ))
            } catch (ignored: Throwable) {
                toast(getString(R.string.play_overlay_perm_how))
            }
            return
        }
        val o = overlay ?: LyricOverlay(this).also { overlay = it }
        if (!o.show()) {
            toast(getString(R.string.play_overlay_failed))
            return
        }
        overlayOn = true
        overlayBtn.text = getString(R.string.play_overlay_off)
        val cur = player.current()
        val cached = player.currentLrc()
        if (cached.isNotEmpty()) {
            o.setLrc(cached)
            o.tick(player.position())
        } else {
            o.setNotice(if (cur != null && !cur.hasLyrics()) getString(R.string.play_lyrics_none_scrape) else getString(R.string.play_lyrics_none))
        }
        toast(getString(R.string.play_overlay_on_done))
    }

    /**
     * 提示走「Toast + 日志」双写。
     *
     * MIUI 的 Toast 既不进 logcat 文本、也不出现在 uiautomator 的树里，
     * 所以额外打一条日志。原先还会把提示写进播放页底部的状态行，
     * 但那一行常驻显示「第 N/M 首」占地方，已按反馈移除。
     */
    private fun toast(s: String) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
        try {
            android.util.Log.i("USBNASLink", "toast: $s")
        } catch (ignored: Throwable) {
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scrapeDialog?.dismiss()
        scrapeDialog = null
        overlay?.release()
        overlay = null
        overlayOn = false
        player.release()
        super.onDestroy()
    }
}
