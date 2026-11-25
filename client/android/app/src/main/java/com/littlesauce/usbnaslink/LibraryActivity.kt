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

        /** 多选批量删除单批上限：一次 POST 删太多会让服务端一口气卡住好几秒 */
        private const val PHOTO_DEL_BATCH = 40

        /**
         * 账号动作的机器可读代号。
         *
         * 原来是拿中文 label 当 `when` 的分支键，翻译一改逻辑就断。现在代号是常量、
         * 显示名走 opLabel() —— 加了语言之后这两件事必须分开。
         */
        const val OP_ADD = "add"
        const val OP_PW = "pw"
        const val OP_LEVEL = "level"
        const val OP_KICK = "kick"
        const val OP_DEL = "del"

        /**
         * 缩略图缓存的字节预算。
         *
         * 16MB 是「够填满几屏、又不至于把中端机逼到 OOM」的折中：相册一格
         * 330×330 的 ARGB_8888 是 435KB，16MB 能留 38 张，加上影视海报
         * 也在同一个池子里，滑一屏刚好、回滑基本都命中。用字节而不是条数，
         * 是因为海报（竖版大图）和缩略图（正方形小图）体量差一个数量级。
         */
        private const val POSTER_CACHE_BYTES = 16 * 1024 * 1024

        /**
         * 相册墙一屏先挂多少格。
         *
         * 685 张一次全建 = 685 个 FrameLayout + 2055 个子 View，主线程一口气
         * 排版几分钟；而且其中绝大多数根本不在屏幕上。所以按「批」挂：
         * 先给一屏半（[PHOTO_PAGE] 张），滚到底部时再追加下一批。
         * 这个数字不是精确算出来的 —— 3 列 × 约 6 行 ≈ 18 格，
         * 留一倍余量让首次追加不会立刻触发。
         */
        private const val PHOTO_PAGE = 36

        /**
         * 相册缩略图请求的边长（长边，单位 px）。
         *
         * 格子在 1080 宽屏上是 330px，但横屏 2400 宽时能到 700px 上下。
         * 取 480：竖屏略有余量、横屏也不至于糊成马赛克，单张 JPEG 约 25–40KB。
         * 服务端 PHOTO_THUMB_MAX 也是 480，两边对齐，超了会被服务端夹回去。
         */
        private const val PHOTO_THUMB_PX = 480
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

    /**
     * 「登录过期」提示条（可点，点了去网页端登录）。
     *
     * 只在 [checkAuth] 确定 sid 失效时才出现，默认 GONE。
     */
    private lateinit var authBar: TextView
    private lateinit var spinner: ProgressBar

    /**
     * 五个页签共用的外层滚动容器。
     *
     * 相册的分批挂载（[appendPhotos]）挂在它的滚动回调上：只有这一列滚动时
     * 才可能触发。别的页签滚到底不会误加图片 —— 回调里用 currentTab 判过。
     */
    private lateinit var scroll: ScrollView

    /** 三个页签按钮，切页时只刷它们的选中态（不重建视图） */
    private val tabViews = ArrayList<TextView>()

    private var vlib: VideoLib.Lib? = null
    private var mlib: Music.Lib? = null
    private var blib: BookLib.Lib? = null
    private var plib: PhotoLib.Lib? = null

    /** 正在全屏看的那张图；null 表示没开。关闭时置回 null，避免持有 Dialog 泄漏 */
    private var photoDlg: android.app.Dialog? = null

    /**
     * 缩略图与海报的位图缓存，按<b>字节数</b>做 LRU 淘汰。
     *
     * 原来是 `HashMap<String, Bitmap?>`，只加不减、连失败（null）也缓存。
     * 相册 685 张、每张几十万像素的位图全留着，滑到一半就 OOM；而失败的那些
     * 又会永久停在「空图」状态，网络恢复也不重试。两个毛病一起治：
     *   - 换成 LruCache，超预算丢最久没用的那张；
     *   - 失败的不进缓存（见 [posterLoading]），下次重建视图还能再试一次。
     */
    private val posterCache = object : android.util.LruCache<String, Bitmap>(POSTER_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    /**
     * 正在下载中的缩略图 key。
     *
     * 同一张图可能被两个视图先后要求（滑出屏幕再滑回来），没有这个集合就会
     * 同时排两次队。用完即删 —— 它只防「同一时刻重复」，不防「失败后重试」。
     */
    private val posterLoading = HashSet<String>()

    /**
     * 还在路上的清单请求。
     *
     * 影/音/书三条腿各跑各的（见 [reload]），全靠它判断「最后一个回来了没」——
     * 集合空了才关转圈。用可变集合是因为每个腿在自己的线程里收尾，
     * 只在 `ui.post` 里改，天然串行。
     */
    private val pending = LinkedHashSet<Int>()

    /**
     * 「服务端认得这个 sid 吗」的最近一次答案。
     *
     * 为什么需要单独一个字段：三个清单端点（/_videos /_music /_books）在服务端
     * 是**匿名可读**的，sid 过期它们照样 200 并返回完整列表 —— 于是从清单本身
     * 完全看不出登录已经失效。真正会 401 的是「取文件」（播放、缩略图、读正文），
     * 而那时用户已经点了播放，体验上就变成「能看见片名但放不了」。
     *
     * 所以进库时顺手问一次 /_admin/api 的 logged 字段（见 [checkAuth]），
     * 失效就在每页顶上挂一条提示，用户不必等到点了播放才发现。
     */
    private var authLost = false

    /** 正在问登录态，防抖用（onResume + 切页签可能同时触发） */
    private var authChecking = false

    private var currentTab = 0

    /** 顶栏那颗「日间/夜间」按钮。重建后文案要跟着新的 [Palette.mode] 走。 */
    private var themeBtn: Button? = null

    /**
     * 「网页」在页签行里的下标。
     *
     * 它和其它页签长得一样、排在一起，但点它不换页（`selectTab(5)` 没有对应的
     * 内容容器），而是直接调 `openWeb()` 交给系统浏览器。之所以做成页签而不是
     * 顶栏按钮：顶栏那个按钮的字号/底色/高度跟下面一排页签都对不上，视觉上是
     * 页面里最扎眼的一处不统一。
     */
    private val TAB_WEB = 5

    private val TAB_VIDEO = 0
    private val TAB_MUSIC = 1
    private val TAB_BOOK = 2
    private val TAB_PHOTO = 3

    /**
     * 单页清单请求的超时。
     *
     * 45 秒看着夸张，但服务端首次扫 1197 首音乐确实要 18 秒（fuse 元数据 + 逐个
     * 读 id3）。原来的 15 秒比这个数字还小，等于「第一次打开音乐必然超时」。
     * 超时又长又不碍事的理由是：三条腿已经拆开并行，慢的那条不拖快的那条。
     */
    private val LOAD_TIMEOUT_MS = 45000

    /** 音乐页当前视图：0 平铺 / 1 按歌手 / 2 按专辑（与桌面端同一套档位） */
    private var musicView = 0
    private val openedBuckets = HashSet<String>()
    private var firstBucketOpened = false
    private var query = ""
    private val searchTick = Runnable { buildMusic() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 先把配色定下来再建界面：Palette 的取值是「当时」算的，界面建完再改
        // 只会影响之后新建的控件，已画的那些不会自己变色。
        ThemeCtl.prime(this)
        // 语言同理：字符串资源在建界面时就定死了，晚一步就已经拿旧语言 inflate 了。
        L.prime(this)
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
            text = intent.getStringExtra(EXTRA_NAME) ?: getString(R.string.lib_library)
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

        // 顶栏只留一个「日间/夜间」小按钮。
        //
        // 文案是「点它能切到什么」，与网页端 themeButton 一致 —— 页面右上角那颗
        // 按钮上写「日间」，意思就是「现在是夜里，点一下变白天」。
        //
        // 切换后调 ThemeCtl.toggle：它先本地重涂再重建界面（颜色是取值时算的，
        // 已建好的 View 不会自己变），同时把新值 POST 给服务端 `/_theme`，
        // 让同一台设备的网页端下次打开也是这个色。
        themeBtn = Palette.ghost(this, getString(ThemeCtl.labelRes())).apply {
            setOnClickListener {
                android.util.Log.i("USBNASLink", "theme click, now=" + Palette.mode +
                        " day=" + Palette.isDay() + " base=" + baseUrl + " sid=" + sid)
                ThemeCtl.toggle(this@LibraryActivity, baseUrl, sid) { toast(it) }
            }
        }
        top.addView(themeBtn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply { rightMargin = dp(4) })
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
        for ((i, label) in tabLabels().withIndex()) {
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
                text = getString(R.string.lib_photo_sel, 0)
                setTextColor(Palette.FG)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            addView(photoSelCount, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            photoSelAll = Palette.ghost(this@LibraryActivity, getString(R.string.lib_select_all)).apply {
                setOnClickListener { toggleSelectAllPhotos() }
            }
            addView(photoSelAll, LinearLayout.LayoutParams(dp(64), dp(34)))
            addView(Palette.outline(this@LibraryActivity, getString(R.string.common_delete)).apply {
                setTextColor(Palette.WARN_HI)
                setOnClickListener { confirmDeletePhotos() }
            }, LinearLayout.LayoutParams(dp(64), dp(34)).apply { leftMargin = dp(6) })
            addView(Palette.ghost(this@LibraryActivity, getString(R.string.common_cancel)).apply {
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
            text = getString(R.string.common_loading)
        }
        root.addView(hint)

        // 「登录过期」的常驻提示条：清单能拿（匿名可读），但点播放/看大图/读正文
        // 会被 401 挡回来，那时才发现就太晚了。放在 hint 下面而不是弹窗，
        // 是因为它不是错误而是「现状说明」，不该打断用户浏览。
        authBar = TextView(this).apply {
            setTextColor(Palette.WARN_HI)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            visibility = View.GONE
            setOnClickListener { openWebForLogin() }
        }
        root.addView(authBar)

        scroll = ScrollView(this)
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

        // 相册滚到快见底时继续挂下一批格子。
        // 阈值取「距底 1.5 屏」而不是「到底」：手指还在滑的时候就要有内容，
        // 等真到底才建 View 会看到一段空白。另外这里必须用剩余量判断，
        // 不能用 scrollY 和内容高度直接比 —— 内容高度每追加一批就变一次。
        scroll.setOnScrollChangeListener { _, _, top, _, _ ->
            if (currentTab != TAB_PHOTO || photosDone) return@setOnScrollChangeListener
            val content = scroll.getChildAt(0)?.height ?: return@setOnScrollChangeListener
            if (content - (top + scroll.height) < scroll.height * 1.5f) appendPhotos(PHOTO_PAGE)
        }

        setContentView(root)
        // 状态栏/手势栏透明，看到的永远是 root 的 #0F141A（详见 Immersive.tintBars）
        Immersive.tintBars(this, Palette.BG)
        // 铺满之后内容坐标从 (0,0) 起算，顶栏会被状态栏压住、横屏左侧会被挖孔吃掉。
        // 让 root 自己吃下 systemBars + cutout 的 inset，背景色照旧铺到全屏，
        // 顶栏文字回到安全区。
        Immersive.padBySystemBars(root)
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
        // 影/音/书同理：冷启动只拉当前那页（见 reload），点过来时才补。
        // 空着是因为「还没要」而不是「没有」，所以先给一句正在加载，
        // 免得用户看到「还没有影视」以为库是空的。
        if (idx != 4 && idx != 3 && idx != TAB_WEB) {
            val loaded = when (idx) {
                TAB_VIDEO -> vlib != null
                TAB_MUSIC -> mlib != null
                TAB_BOOK -> blib != null
                else -> true
            }
            if (!loaded) {
                val host = when (idx) {
                    TAB_VIDEO -> videoHost
                    TAB_MUSIC -> musicHost
                    else -> bookHost
                }
                if (host.childCount == 0) {
                    host.removeAllViews()
                    host.addView(msg(getString(R.string.common_loading)))
                }
                ensureLoaded(idx)
            }
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
            currentTab == TAB_MANAGE -> getString(R.string.lib_hint_manage)
            currentTab == TAB_PHOTO -> {
                if (p == null) getString(R.string.lib_photo_loading)
                else getString(R.string.lib_hint_photo, p.photos.size, p.albumCount())
            }
            // 三个库现在是各拉各的，当前页那份还没回来就报「正在加载」。
            // 早先这里是「三个都 null 才算加载中」，并行之后必然会有
            // 「影视已回来、音乐还在路上」的中间态，按当前页判断才准。
            currentTab == TAB_VIDEO && v == null -> getString(R.string.common_loading)
            currentTab == TAB_MUSIC && m == null -> getString(R.string.common_loading)
            currentTab == TAB_BOOK && b == null -> getString(R.string.common_loading)
            v == null && m == null && b == null -> getString(R.string.common_loading)
            currentTab == TAB_VIDEO -> getString(R.string.lib_hint_video, v?.videos?.size ?: 0, v?.groups?.size ?: 0)
            currentTab == TAB_MUSIC -> {
                val base = getString(R.string.lib_hint_music, m?.tracks?.size ?: 0, m?.dirs?.size ?: 0)
                when (musicView) {
                    1 -> getString(R.string.lib_hint_music_artist, base)
                    2 -> getString(R.string.lib_hint_music_album, base)
                    else -> base
                }
            }
            else -> getString(R.string.lib_hint_book, b?.books?.size ?: 0, b?.groups?.size ?: 0)
        }
    }

    /**
     * 拉影/音/书三份清单。
     *
     * 早先是「一个 post 里依次 fetch 三份，全都回来才统一 build」。问题在于：
     * 服务端音乐首次扫描要 18 秒（1197 首走 fuse 读元数据），而三份是串行
     * 排队的，于是影视清单明明 0.02 秒就好了，却要陪音乐一起等 18 秒；客户端
     * 那边 15000ms 超时先到，`m.ok=false`，界面就只剩「还没有音乐」。用户看到
     * 的「有时加载不出来」就是这么来的 —— 不是没扫到，是等超时了。
     *
     * 现在改成三条腿各自独立：谁先回来谁先上屏，互不阻塞。超时也拉长到 45s，
     * 让服务端首扫能跑完；真超时了也只影响它自己那一页。
     *
     * 另外只拉当前页 + 已建过视图的页：冷启动落在影视页时不该为音乐/图书付钱，
     * 用户点过去时 [ensureLoaded] 再补，页面当场变「正在加载…」而不是空。
     */
    private fun reload() {
        spinner.visibility = View.VISIBLE
        pending.clear()
        if (currentTab == 0) pending.add(TAB_VIDEO)
        if (currentTab == 1) pending.add(TAB_MUSIC)
        if (currentTab == 2) pending.add(TAB_BOOK)
        if (pending.isEmpty()) {
            // 落在设置/相册页时不预取：用户还没要看，别替他决定要不要扫一万首
            spinner.visibility = View.GONE
            updateHint()
            return
        }
        for (tab in pending.toList()) fetchTab(tab)
    }

    /** 某一页还没数据就现在去拉；已经拉过的不重复。 */
    private fun ensureLoaded(tab: Int) {
        val done = when (tab) {
            TAB_VIDEO -> vlib != null
            TAB_MUSIC -> mlib != null
            TAB_BOOK -> blib != null
            TAB_PHOTO -> plib != null
            else -> true
        }
        if (done) return
        if (tab == TAB_PHOTO) {
            loadPhotos()
            return
        }
        pending.add(tab)
        fetchTab(tab)
    }

    /** 收尾：最后一个在途请求回来时关掉转圈。 */
    private fun settle(tab: Int) {
        pending.remove(tab)
        if (pending.isEmpty()) spinner.visibility = View.GONE
        updateHint()
    }

    /** 单页拉取。每条腿自己起线程、自己上屏、自己收尾。 */
    private fun fetchTab(tab: Int) {
        val base = baseUrl
        val ck = sid
        spinner.visibility = View.VISIBLE
        post {
            when (tab) {
                TAB_VIDEO -> {
                    val v = VideoLib.fetch(base, ck, null, LOAD_TIMEOUT_MS)
                    ui.post {
                        vlib = v
                        if (currentTab == TAB_VIDEO || videoHost.childCount > 0) buildVideos()
                        settle(tab)
                        if (!v.ok) toast(getString(R.string.lib_all_failed, v.error ?: ""))
                    }
                }
                TAB_MUSIC -> {
                    val m = Music.fetch(base, ck, LOAD_TIMEOUT_MS)
                    ui.post {
                        mlib = m
                        if (currentTab == TAB_MUSIC || musicHost.childCount > 0) buildMusic()
                        settle(tab)
                        if (!m.ok) toast(getString(R.string.lib_all_failed, m.error ?: ""))
                    }
                }
                TAB_BOOK -> {
                    val b = BookLib.fetch(base, ck, null, LOAD_TIMEOUT_MS)
                    ui.post {
                        blib = b
                        if (currentTab == TAB_BOOK || bookHost.childCount > 0) buildBooks()
                        settle(tab)
                        if (!b.ok) toast(getString(R.string.lib_all_failed, b.error ?: ""))
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 影视

    private fun buildVideos() {
        videoHost.removeAllViews()
        val lib = vlib
        if (lib == null) {
            videoHost.addView(msg("…"))
            return
        }
        // 「取失败」和「真的没有片」必须分开说。
        // 原来这里只有 `lib.videos.isEmpty()` 一条分支，于是服务端重启导致 sid 失效
        // （/_videos 照样 200，但每个文件 401）或者网络断了，界面都只写
        // 「还没有影视」—— 用户以为硬盘上的片子没了，实际是登录过期。
        if (!lib.ok) {
            videoHost.addView(loginOrFailCard(lib.error ?: ""))
            return
        }
        if (lib.videos.isEmpty()) {
            videoHost.addView(msg(getString(R.string.lib_empty_video)))
            return
        }
        // 按服务端给的 groups 分块：盘0 / 盘1 / 内部存储 各成一段，
        // 这正是需求里「电影板块可以有盘0和盘1两部分」的呈现
        for (g in lib.groups) {
            val inGroup = lib.videos.filter { under(it.path, g.path.let { p -> if (p.endsWith("/")) p.trimEnd('/') else p }) }
            if (inGroup.isEmpty()) continue
            videoHost.addView(sectionHeader(g.label ?: g.path, getString(R.string.lib_group_videos, inGroup.size), g.path))
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
            videoHost.addView(sectionHeader(getString(R.string.lib_group_other), getString(R.string.lib_group_videos, orphan.size), ""))
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
        val key = v.path ?: return
        val cached = posterCache.get(key)
        if (cached != null) {
            img.setImageBitmap(cached)
            return
        }
        val base = baseUrl
        val ck = sid
        post {
            val meta = VideoLib.fetchMeta(base, ck, key, 12000)
            val poster = meta?.posterUrl
            if (poster.isNullOrEmpty()) {
                // 没有海报也是一条结论，直接给占位图，别让格子里一直空着
                ui.post { img.setImageResource(android.R.drawable.ic_media_play) }
                return@post
            }
            val bmp = fetchImage(VideoLib.artUrl(base, poster), ck)
            ui.post {
                if (bmp != null) {
                    posterCache.put(key, bmp)
                    img.setImageBitmap(bmp)
                } else {
                    img.setImageResource(android.R.drawable.ic_media_play)
                }
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
        if (lib == null) {
            musicHost.addView(msg("…"))
            return
        }
        // 同 buildVideos：取失败 ≠ 真没有，见 loginOrFailCard 的注释
        if (!lib.ok) {
            musicHost.addView(loginOrFailCard(lib.error ?: ""))
            return
        }
        if (lib.tracks.isEmpty()) {
            musicHost.addView(msg(getString(R.string.lib_empty_music)))
            return
        }
        val hits = Music.filter(lib.tracks, query)
        musicHost.addView(musicBar(hits.size))
        if (hits.isEmpty()) {
            musicHost.addView(msg(getString(R.string.lib_no_match, query)))
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
            musicHost.addView(sectionHeader(folderName(dir), getString(R.string.lib_group_songs, songs.size), dir))
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
        musicHost.addView(msg(getString(R.string.lib_buckets, buckets.size, groupLabel(by))))
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
        val labels = arrayOf(getString(R.string.lib_music_view_flat), getString(R.string.lib_music_view_artist), getString(R.string.lib_music_view_album))
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
            hint = getString(R.string.lib_search_hint, n)
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
            if (t.hasLyrics()) bits.add(getString(R.string.lib_has_lyrics))
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
        // 内嵌封面标记也要跟着走：上面的 covers 只覆盖「目录里有同名图」的情况，
        // 内嵌封面（ID3 APIC / FLAC PICTURE）在 track.cover 上是 null，全靠这个标记。
        // 漏传的话 PlayerActivity 里的 Track 全是 embedded=false，
        // loadCover 的 hasCover() 直接假 → 有内嵌封面的歌也只画贴纸。
        i.putExtra(PlayerActivity.EXTRA_EMBEDS, album.map { if (it.embedded) "1" else "" }.toTypedArray())
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
            bookHost.addView(loginOrFailCard(lib.error ?: ""))
            return
        }
        if (lib.notConfigured()) {
            bookHost.addView(msg(getString(R.string.lib_books_empty)))
            return
        }
        if (lib.books.isEmpty()) {
            bookHost.addView(msg(getString(R.string.lib_shelf_empty)))
            return
        }
        // 按目录分层架：技术 / 科幻 / 各自一段，目录名本身就是分类
        val shelves = LinkedHashMap<String, MutableList<Book>>()
        for (b in lib.books) {
            shelves.getOrPut(b.dir) { ArrayList() }.add(b)
        }
        for ((dir, list) in shelves) {
            bookHost.addView(sectionHeader(folderName(dir), getString(R.string.lib_group_books, list.size), dir))
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
        photoHost.addView(msg(getString(R.string.lib_photo_loading)))
        val base = baseUrl
        val ck = sid
        post {
            // 超时跟影视/音乐/图书统一走 LOAD_TIMEOUT_MS：相册清单本身很快，
            // 但它和另外三条腿共用一个线程池，慢的时候是被别人挤的，
            // 给 15 秒会在这时候误报「加载失败」。
            val lib = PhotoLib.fetch(base, ck, null, LOAD_TIMEOUT_MS)
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
     *
     * <p><b>分批挂载</b>：这里只把清单排成「计划」（[photoPlan]），
     * 真正建 View 的活交给 [appendPhotos]，一次 [PHOTO_PAGE] 格。
     * 原来是双层 for 一口气建完 —— 685 张图 = 685 个 FrameLayout +
     * 2055 个子 View + 685 次布局，全在主线程，滚轮还没摸到就先卡几秒。
     * 现在滚到快到底才继续追加，代价是首次看到的那一屏还是同一个流程，
     * 但后面几百格的开销摊到了滚动过程里。
     */
    private fun buildPhotos() {
        photoHost.removeAllViews()
        photoCellRefs.clear()
        photoPlan = emptyList()
        planAlbum = 0
        planIndex = 0
        planRow = null
        photosDone = false
        val lib = plib
        if (lib == null) {
            photoHost.addView(msg("…"))
            return
        }
        if (!lib.ok) {
            // 相册清单本身是匿名的（/=_photos 白名单里放行），所以拿不到基本只有
            // 「连不上」一种可能；但如果是 401（服务端配了不许匿名 + 全是私图），
            // 也走同一张卡片，反正按钮都是「去登录 / 重试」。
            photoHost.addView(loginOrFailCard(lib.error ?: ""))
            return
        }
        if (lib.notConfigured()) {
            // 「没配」和「配了但是空的」必须分开说：前者要去管理设置动一下，
            // 后者用户自己知道那个目录里没照片。
            photoHost.addView(msg(getString(R.string.lib_photos_empty)))
            return
        }
        if (lib.photos.isEmpty()) {
            photoHost.addView(msg(getString(R.string.lib_photos_dir_empty)))
            return
        }

        // 按目录分组成相册，顺序沿用服务端清单里第一次出现的先后
        val albums = LinkedHashMap<String, MutableList<Photo>>()
        for (p in lib.photos) {
            albums.getOrPut(p.dir ?: "") { ArrayList() }.add(p)
        }
        photoPlan = albums.map { it.key to it.value }
        appendPhotos(PHOTO_PAGE)
    }

    /** 分批挂载的游标：当前在第几个相册、该相册的第几张、当前那一行 */
    private var photoPlan: List<Pair<String, List<Photo>>> = emptyList()
    private var planAlbum = 0
    private var planIndex = 0
    private var planRow: LinearLayout? = null

    /** 全部格子挂完了没。挂完就不再响应滚动追加，免得重复加总结行 */
    private var photosDone = false

    /**
     * 接着往下挂最多 [limit] 个格子。
     *
     * 行是「攒满 3 个才 add 一次」以外的另一条路：每 3 格新建一个横向
     * LinearLayout 并立刻 add 到 photoHost（行本身是空的也算一次排版），
     * 所以 limit 按「格」算而不是按「行」算 —— 调用方关心的是数量，
     * 3 列只是排版细节。
     */
    private fun appendPhotos(limit: Int) {
        if (photosDone) return
        var added = 0
        while (added < limit) {
            if (planAlbum >= photoPlan.size) {
                finishPhotos()
                return
            }
            val dir = photoPlan[planAlbum].first
            val list = photoPlan[planAlbum].second
            if (planIndex == 0) {
                photoHost.addView(sectionHeader(folderName(dir),
                    getString(R.string.lib_group_photos, list.size), dir))
            }
            if (planIndex >= list.size) {
                flushPlanRow(list.size)
                planAlbum++
                planIndex = 0
                planRow = null
                continue
            }
            if (planIndex % PHOTO_COLS == 0) {
                planRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, 0)
                }
                photoHost.addView(planRow, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val cell = photoCard(list[planIndex])
            planRow!!.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                // 留出 6dp 缝，靠右/下补在容器上；最后一列不多留
                rightMargin = if (planIndex % PHOTO_COLS == PHOTO_COLS - 1) 0 else dp(6)
                bottomMargin = dp(6)
                if (planIndex % PHOTO_COLS == 0) leftMargin = dp(14)
            })
            planIndex++
            added++
        }
    }

    /** 补满刚才那一行：不足 3 个时塞透明占位，避免剩下的格子被拉宽 */
    private fun flushPlanRow(total: Int) {
        val row = planRow ?: return
        val rest = total % PHOTO_COLS
        if (rest == 0) return
        for (k in rest until PHOTO_COLS) {
            row.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f).apply {
                rightMargin = if (k == PHOTO_COLS - 1) 0 else dp(6)
            })
        }
    }

    /** 收尾：总结行 + 多选工具条的初始状态 */
    private fun finishPhotos() {
        if (photosDone) return
        photosDone = true
        val lib = plib ?: return
        photoHost.addView(TextView(this).apply {
            text = getString(R.string.lib_photos_summary, lib.photos.size, lib.albumCount())
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
        photoSelCount?.text = getString(R.string.lib_photo_sel, photoSel.size)
        photoSelAll?.text = getString(if (photoSel.size >= albumOrdered().size) R.string.lib_photo_sel_full else R.string.lib_select_all)
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
        val dlg = Palette.dialog(
            this,
            getString(R.string.lib_del_confirm_title, picks.size),
            Palette.dialogText(this, getString(R.string.lib_del_confirm_msg)),
            listOf(
                Palette.outline(this, getString(R.string.common_cancel)).apply {
                    setOnClickListener { dlgRef?.dismiss() }
                },
                // 批量删图同样是不可逆动作，走警示色
                Palette.outline(this, getString(R.string.common_delete), Palette.WARN_HI).apply {
                    setOnClickListener {
                        dlgRef?.dismiss()
                        doDeletePhotos(picks)
                    }
                }))
        dlgRef = dlg
    }

    private fun doDeletePhotos(picks: List<String>) {
        if (adminState?.admin != true) {
            // 删文件属于写操作：不是管理员就先告诉用户去登录，而不是发一个注定 403 的请求
            Toast.makeText(this,
                getString(R.string.lib_del_need_admin),
                Toast.LENGTH_LONG).show()
            return
        }
        val base = baseUrl
        val ck = sid
        msg(getString(R.string.lib_del_running, picks.size))
        post {
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
                    Toast.makeText(this@LibraryActivity, getString(R.string.lib_del_done, done), Toast.LENGTH_SHORT).show()
                    exitPhotoSel()
                    loadPhotos()
                } else {
                    Toast.makeText(this@LibraryActivity,
                        getString(R.string.lib_del_partial, done, failed, lastErr ?: getString(R.string.common_unknown_error)),
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

        // 缩略图走 /_photos/thumb：服务端按目标边长降采样后再 JPEG 编码，
        // 一张 1.9MB 的原图变成 20KB 左右。以前直连原图 —— 685 张就是 1.3GB
        // 传输 + 每张 48MB 的 ARGB_8888 解码，卡顿与 OOM 都从那儿来。
        val url = PhotoLib.thumbUrl(baseUrl, p.path, PHOTO_THUMB_PX)
        posterInto(img, "p:" + p.path, url, sid)
        return box
    }

    /**
     * 把一张图异步贴进 ImageView：命中缓存立刻贴，未命中排队下载。
     *
     * 抽出来是因为影视海报和相册缩略图的逻辑完全一样，之前是两份拷贝
     * （一份在 [loadPoster]、一份在 [photoCard]），缓存策略一改就要改两处。
     *
     * <p>{@code key} 必须能把「同一张图的不同用途」分开：相册缩略图用
     * {@code "p:"} 前缀，影视海报直接用视频路径 —— 两者不会撞。
     */
    private fun posterInto(img: ImageView, key: String, url: String, ck: String?) {
        val hit = posterCache.get(key)
        if (hit != null) {
            img.setImageBitmap(hit)
            return
        }
        img.setImageBitmap(null)
        // 同一张图只排一次队。滑出去再滑回来会重复请求，而 io 池只有 3 个线程，
        // 重复排队等于把真正的进度往后压。
        val fresh = synchronized(posterLoading) { posterLoading.add(key) }
        if (!fresh) return
        post {
            val bmp = fetchImage(url, ck)
            ui.post {
                synchronized(posterLoading) { posterLoading.remove(key) }
                // 成功才进缓存：失败（网络断了、服务端还在扫）留个空，
                // 下次重建视图会重新请求，而不是永远显示空白。
                if (bmp != null) posterCache.put(key, bmp)
                if (bmp != null && img.isAttachedToWindow) img.setImageBitmap(bmp)
            }
        }
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
        post {
            // 全屏图要看细节，不能沿用缩略图的采样，单独按原图解一次
            val bmp = fetchImageFull(url, ck)
            ui.post {
                if (bmp == null) {
                    cap.text = getString(R.string.lib_photo_open_failed, p.name)
                    return@post
                }
                photoBitmaps[key] = bmp
                iv.setImageBitmap(bmp)
                iv.post { fit(bmp) }
            }
        }
    }

    /**
     * 列表用的缩略图解码。
     *
     * 正常情况下拿到的已经是服务端压好的小图（[PhotoLib.thumbUrl]），直接解就行。
     * 但仍按目标边长做一次降采样兜底，为的是两种场景：
     *   - 服务端还是旧版本（没有 /_photos/thumb，落到原图那条路）；
     *   - 影视海报走的是 /art 直链，那个端点是原图，没人替我们缩。
     * 没有这一层，一张 4000×3000 的海报就是 48MB —— 滑两张就 OOM。
     */
    private fun fetchImage(url: String, ck: String?): Bitmap? {
        var c: HttpURLConnection? = null
        return try {
            // 第一趟只要头：拿到真实尺寸才能算出正确的 inSampleSize。
            // inJustDecodeBounds 不分配像素内存，代价接近零。
            c = openImage(url, ck)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.inputStream.use { BitmapFactory.decodeStream(it, null, bounds) }
            c.disconnect()

            var sample = 1
            val longSide = maxOf(bounds.outWidth, bounds.outHeight)
            // 每翻一倍，像素数降 4 倍；留 2 倍余量给不同 dpi 的屏幕
            while (longSide / (sample * 2) >= PHOTO_THUMB_PX * 2) sample *= 2

            // 第二趟真解。注意这里要重新连 —— HttpURLConnection 的流读完了，
            // 同一个连接不能再要第二次。
            c = openImage(url, ck)
            val opt = BitmapFactory.Options().apply { inSampleSize = sample }
            c.inputStream.use { BitmapFactory.decodeStream(it, null, opt) }
        } catch (e: Throwable) {
            null
        } finally {
            c?.disconnect()
        }
    }

    private fun openImage(url: String, ck: String?): HttpURLConnection {
        val hc = URL(url).openConnection() as HttpURLConnection
        hc.connectTimeout = 8000
        hc.readTimeout = 20000
        hc.setRequestProperty("User-Agent", "USBNASLink/1.0")
        if (!ck.isNullOrEmpty()) hc.setRequestProperty("Cookie", "${Session.COOKIE}=$ck")
        return hc
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

    /**
     * 「取不到」的说明卡片。
     *
     * 光靠一句话分不清三件事：服务端真没这个东西 / 登录过期 / 网络断了。
     * 三者的处置完全不同（去配目录 / 去登录 / 等等再来），所以这里给一句
     * 具体原因 + 一个对症的按钮：
     *   - 是 401 或身份不明 → 主按钮直接进网页端登录页，次按钮是「已经登好了，刷新」；
     *   - 其它错误（超时、连不上）→ 只给一个「重试」。
     *
     * 为什么不在发现 401 时自动弹窗：拉清单是在 onResume / 切页签时静默发生的，
     * 一进 App 就糊一个「登录过期」的对话框很打扰；而且用户可能只是想看一眼上
     * 次连的是哪台机器。写成卡片，用户滑到哪页就在哪页看到，不打断。
     */
    private fun loginOrFailCard(reason: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(10))
        }
        // HTTP 401 是本 App 里唯一能确定「就是登录的事」的信号；
        // 其它错误一律按「取不到 + 原因」呈现，不去猜。
        val authIssue = reason.contains("401")
        box.addView(Palette.note(this,
            if (authIssue) getString(R.string.lib_need_login)
            else getString(R.string.lib_all_failed, reason),
            if (authIssue) Palette.WARN_HI else Palette.DIM).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(4).toFloat(), 1f)
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        if (authIssue) {
            row.addView(Palette.solid(this, getString(R.string.lib_need_login_go)).apply {
                setOnClickListener { openWebForLogin() }
            }, LinearLayout.LayoutParams(dp(120), dp(38)).apply { rightMargin = dp(8) })
            row.addView(Palette.outline(this, getString(R.string.lib_need_login_retry)).apply {
                setOnClickListener { retryAll() }
            }, LinearLayout.LayoutParams(dp(180), dp(38)))
        } else {
            row.addView(Palette.outline(this, getString(R.string.common_retry)).apply {
                setOnClickListener { retryAll() }
            }, LinearLayout.LayoutParams(dp(96), dp(38)))
        }
        box.addView(row)
        return box
    }

    /** 按 [authLost] 决定那条提示条显不显示；文案里带上「点一下就去登录」。 */
    private fun renderAuthBar() {
        if (authLost) {
            authBar.text = getString(R.string.lib_auth_lost_bar)
            authBar.visibility = View.VISIBLE
        } else {
            authBar.visibility = View.GONE
        }
    }

    /**
     * 把三条腿的缓存全作废、重新拉一遍。
     *
     * 登录完从网页回来、或者网络恢复之后用。清 `vlib/mlib/blib/plib` 是关键：
     * [ensureLoaded] 靠「已经是 null 了吗」判断要不要拉，不清就只会重建视图、
     * 拿的还是上次那个失败的结果。
     */
    private fun retryAll() {
        vlib = null
        mlib = null
        blib = null
        plib = null
        // sid 也可能刚在网页里换过：重读一次，别拿内存里那个旧的去撞 401
        refreshSid()
        reload()
        if (currentTab == TAB_PHOTO) loadPhotos()
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
        // 用 loginIntent：用户从这条红条进来只为了交一次密码，首屏就直接是表单。
        startActivity(BrowseActivity.loginIntent(
            this, baseUrl, intent.getStringExtra(EXTRA_NAME)))
    }

    override fun onResume() {
        super.onResume()
        // 「管理设置」里的自动更新也可能走到「去开安装权限」那一步，
        // 从系统设置回来时在这里把留着没装的包装掉（没留包时是个空调用）。
        ApkUpdater.resumePending(this)
        // 先把 sid 从 Prefs 重读一次，任何页签都重读 —— 不能只在「管理设置」页里做。
        //
        // 踩过的坑：用户在网页端登录成功后，BrowseActivity.onPageFinished 会把
        // 服务端新发的 sid 写进 Prefs，而本 Activity 的 sid 字段还停在打开网页
        // 之前那个（已经失效的）。原来的重读被 `currentTab == TAB_MANAGE` 挡住了，
        // 于是从影视/音乐页回来时，列表是新的、播放却一直 401 —— 表现成
        // 「原本能播的片子突然全都播放不了」，查起来极费劲。
        refreshSid()
        if (currentTab == TAB_MANAGE && manageHost.childCount > 0) {
            loadManage(false)
        }
        // 每次回前台顺手确认一次「服务端还认不认这个 sid」。
        // 从网页登录完回来时这一步尤其重要：sid 换了，得重新判定一次。
        checkAuth()
        // 配色跟着服务端走：每次回到前台问一次（ThemeCtl 内部有 20 秒节流，
        // 切页签/开网页回来都不会真的打请求）。问到的和服务端不一致时
        // ThemeCtl 会回调这里重建界面 —— 只重建、不弹提示，因为这不是用户的操作。
        ThemeCtl.sync(this, baseUrl, sid) { recreate() }
    }

    /**
     * 把内存里的 sid 和服务端发给我们的那份对齐。
     *
     * @return true 表示确实换了（调用方需要重建列表的话可以据此判断）
     */
    private fun refreshSid(): Boolean {
        val fresh = Prefs(this).sid
        if (fresh.isNullOrEmpty() || fresh == sid) return false
        sid = fresh
        return true
    }

    /**
     * 问一次服务端「这个 sid 还算数吗」，失效时在内容区顶部挂一条提示。
     *
     * 为什么必须单独问：清单端点匿名可读（见 [authLost] 的注释），清单拿得回来
     * 不代表能放片。等到用户点播才报 401，他只会觉得「这个 App 坏了」。
     *
     * 判定结果只保留「确定失效」这一种：`logged()` 返回 null 表示问不到
     * （网络断了、服务端没起、老版本没这个字段），那时**不要**提示登录 ——
     * 网络问题被说成登录过期，用户会白跑一趟网页端。
     */
    private fun checkAuth() {
        if (authChecking) return
        val base = baseUrl
        val ck = sid
        if (ck.isNullOrEmpty()) {
            // 压根没有 sid：可能是这台机器根本没开账号，也可能是从没登过。
            // 分不清就不提示，等真发生 401 时 [loginOrFailCard] 会说。
            return
        }
        authChecking = true
        post {
            val sess = Session(base)
            sess.setSid(ck)
            val ok = sess.logged()
            ui.post {
                authChecking = false
                // 请求期间用户可能换了服务器/重新登录，结果就作废了
                if (base != baseUrl || ck != sid) return@post
                val lost = ok == false
                if (lost != authLost) {
                    authLost = lost
                    renderAuthBar()
                    // 状态变了才重画：每次都重画会把用户正在滚的位置弹回顶部
                    if (currentTab != TAB_MANAGE && currentTab != TAB_WEB) {
                        when (currentTab) {
                            TAB_VIDEO -> buildVideos()
                            TAB_MUSIC -> buildMusic()
                            TAB_BOOK -> buildBooks()
                        }
                    }
                }
            }
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
        manageHost.addView(blockHeader(getString(R.string.set_server_section),
            getString(R.string.set_server_section_note)))
        manageHost.addView(serverStatusCard())
        manageHost.addView(nasSettingsCard())
        manageHost.addView(accountsCard())
        manageHost.addView(shareCard())
        manageHost.addView(blockHeader(getString(R.string.set_client_section),
            getString(R.string.set_client_section_note)))
        manageHost.addView(languageCard())
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
        serverStatusRow = Palette.note(this, getString(R.string.set_checking_login), Palette.DIM).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        row.addView(serverStatusRow, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Palette.outline(this, getString(R.string.common_refresh)).apply {
            setOnClickListener { refreshManage(true) }
        }, LinearLayout.LayoutParams(dp(72), dp(36)).apply { leftMargin = dp(8) })
        return row
    }

    /**
     * 「分享管理」入口卡片。
     *
     * 服务端的 /_share/new|drop|list 早就有了，网页端也在用；这里给手机端一个
     * 入口，做到三端同步 —— 生成短链后能直接用 ACTION_SEND 发出去。真实现全在
     * [ShareActivity]，这一行只是导航，避免把大段 UI 塞进本页。
     */
    private fun shareCard(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(2), dp(16), dp(10))
        }
        row.addView(TextView(this).apply {
            text = "分享管理"
            textSize = 14f
            setTextColor(Palette.FG)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(8)
        })
        row.addView(Palette.outline(this, "打开").apply {
            setOnClickListener {
                startActivity(ShareActivity.intent(this@LibraryActivity, baseUrl, sid))
            }
        }, LinearLayout.LayoutParams(dp(80), dp(36)))
        return row
    }

    /**
     * 「语言 / Language」卡片。
     *
     * 放在「设置客户端」块的最前面而不是塞进自动更新那块：语言比更新更常用，
     * 而且改完会整页重绘 —— 用户点完立刻就看到了结果，顺序上应该紧挨着块标题。
     *
     * 用两行单选（RadioGroup）而不是下拉或多次点击切换：
     * 语言是「二选一 + 当前值要一眼看见」的选项，单选最直白。
     * 注意 `which` 直接当选项序号用，不要再套 `2 - which` 之类的换算。
     */
    private fun languageCard(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(14))
        }
        box.addView(TextView(this).apply {
            text = getString(R.string.set_language)
            setTextColor(Palette.FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        box.addView(Palette.note(this, getString(R.string.set_language_note), Palette.DIM2).apply {
            setPadding(0, dp(4), 0, dp(8))
        })
        val group = android.widget.RadioGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val labels = L.labels()
        for ((i, label) in labels.withIndex()) {
            val rb = android.widget.RadioButton(this).apply {
                id = 1000 + i
                text = label
                isAllCaps = false
                setTextColor(Palette.FG2)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                buttonTintList = android.content.res.ColorStateList.valueOf(Palette.ACCENT)
                if ((i == 0 && !L.isEnglish()) || (i == 1 && L.isEnglish())) isChecked = true
            }
            group.addView(rb, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val which = checkedId - 1000
            val lang = if (which == 1) L.Lang.EN else L.Lang.ZH
            // 回调用「用户真的选了另一个」才切，避免初始化时 isChecked 触发的假事件
            if (lang != L.current) L.switchTo(this, lang)
        }
        box.addView(group)
        return box
    }

    /** 页签文案（顺序必须和 TAB_* 常量一致，否则点「影视」会跳到音乐）。 */
    private fun tabLabels(): List<String> = listOf(
        getString(R.string.lib_tab_video),
        getString(R.string.lib_tab_music),
        getString(R.string.lib_tab_book),
        getString(R.string.lib_tab_photo),
        getString(R.string.lib_tab_manage),
        getString(R.string.lib_tab_web)
    )

    /** 音乐分组方式的显示名。`Music.GroupBy.label` 是服务端同款的中文枚举名，不能直接用。 */
    private fun groupLabel(by: Music.GroupBy): String = when (by) {
        Music.GroupBy.ARTIST -> getString(R.string.lib_music_view_artist)
        else -> getString(R.string.lib_music_view_album)
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

    // ---------------------------------------------------------------- 账号管理

    private var accountsBox: LinearLayout? = null

    /**
     * 账号管理卡片。
     *
     * 为什么放在客户端而不只放网页端：这台机器常年插在底座上当相框，想加个人
     * 还得先去电脑前开浏览器 —— 而手机就在手里。服务端那三个动作（建号 / 改人 /
     * 踢线 / 删号）早就有了，客户端补一张脸即可。
     *
     * 三条保护（不能删自己、不能把自己降级、不能删掉最后一个管理员）一律由
     * 服务端判定，这里只把它的原话弹出来 —— 保护逻辑只有一份，改一处就够。
     */
    private fun accountsCard(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(18))
        }
        accountsBox = box
        return box
    }

    /** 重画账号列表（管理员才画表单，其他人只看到一句「需要管理员」） */
    private fun fillAccounts(st: Admin.State) {
        val box = accountsBox ?: return
        box.removeAllViews()
        box.addView(TextView(this).apply {
            text = getString(R.string.set_accounts)
            setTextColor(Palette.FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        box.addView(Palette.note(this,
            getString(R.string.set_accounts_note)))

        if (!st.admin) {
            box.addView(Palette.note(this, getString(R.string.set_accounts_need_admin), Palette.DIM2),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            return
        }

        val accounts = st.accounts
        if (accounts.isEmpty()) {
            box.addView(Palette.note(this,
                getString(R.string.set_accounts_old_server), Palette.DIM2),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        } else {
            for (a in accounts) box.addView(accountRow(a, st))
        }

        // 建号：昵称 + 密码 + 等级，一行放不下就分两行
        val adder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        adder.addView(Palette.note(this, getString(R.string.set_new_account), Palette.DIM))
        val nameIn = Palette.field(this, getString(R.string.set_account_name))
        val pwIn = Palette.field(this, getString(R.string.set_password))
        val levelSp = android.widget.Spinner(this).apply {
            background = Palette.round(this@LibraryActivity, Palette.FIELD, 8f, Palette.LINE, 1f)
            adapter = android.widget.ArrayAdapter(this@LibraryActivity,
                android.R.layout.simple_spinner_dropdown_item,
                levelNames())
            setSelection(1)
        }
        adder.addView(nameIn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(6) })
        adder.addView(pwIn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(6) })
        adder.addView(levelSp, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(6) })
        adder.addView(Palette.solid(this, getString(R.string.set_create_account)).apply {
            setOnClickListener {
                val n = nameIn.text.toString().trim()
                val p = pwIn.text.toString()
                if (n.isEmpty() || p.isEmpty()) {
                    toast(getString(R.string.set_need_name_pw))
                    return@setOnClickListener
                }
                // 下拉项与等级值同向：0 只读 / 1 读写 / 2 管理员
                val lv = levelSp.selectedItemPosition.coerceIn(0, 2)
                accountOp(OP_ADD, n, p, lv) {
                    nameIn.setText("")
                    pwIn.setText("")
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
            .apply { topMargin = dp(8) })
        box.addView(adder)
    }

    /** 一个账号一行：昵称 + 等级 + 在线数 + 三个按钮 */
    private fun accountRow(a: Admin.Account, st: Admin.State): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = Palette.round(this@LibraryActivity, Palette.FIELD, 10f, Palette.LINE, 1f)
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) }
        box.layoutParams = params

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = a.name
            setTextColor(Palette.FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (a.me) top.addView(Palette.note(this, getString(R.string.set_you_badge), Palette.ACCENT_SOFT))
        box.addView(top)

        val state = buildString {
            append(a.levelName.ifEmpty { getString(R.string.set_level_unknown) })
            if (a.scoped) append(getString(R.string.set_scoped))
            append(if (a.online > 0) getString(R.string.set_online, a.online) else getString(R.string.set_offline))
            if (a.me) append(getString(R.string.set_that_is_you))
        }
        box.addView(Palette.note(this, state,
            if (a.online > 0) Palette.OK else Palette.DIM2))

        val acts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        acts.addView(Palette.outline(this, getString(R.string.set_change_level)).apply {
            setOnClickListener { pickLevel(a) }
        }, LinearLayout.LayoutParams(0, dp(38), 1f))
        acts.addView(Palette.outline(this, getString(R.string.set_change_pw)).apply {
            setOnClickListener { askPassword(a) }
        }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(6) })
        if (a.online > 0 && !a.me) {
            acts.addView(Palette.outline(this, getString(R.string.set_kick)).apply {
                setOnClickListener { confirmKick(a) }
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(6) })
        }
        if (!a.me) {
            acts.addView(Palette.outline(this, getString(R.string.common_delete)).apply {
                setTextColor(Palette.WARN_HI)
                setOnClickListener { confirmDelete(a) }
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { leftMargin = dp(6) })
        }
        box.addView(acts, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) })
        return box
    }

    private fun pickLevel(a: Admin.Account) {
        // 列表顺序与等级值同向：0 只读 / 1 读写 / 2 管理员。
        // 之前这里写成 lv = 2 - which，而预选位置传的又是 cur，
        // 两边方向相反 —— 点第一项「只读」实际把人变成了管理员。
        val opts = levelNames()
        val cur = a.level.coerceIn(0, 2)
        val col = Palette.dialogChoices(this, opts, cur) { which ->
            if (which != a.level) accountOp(OP_LEVEL, a.name ?: "", "", which)
        }
        val dlg = Palette.dialog(
            this,
            getString(R.string.set_level_title, a.name ?: "?"),
            col,
            listOf(Palette.outline(this, getString(R.string.common_cancel)).apply {
                setOnClickListener { dlgRef?.dismiss() }
            }))
        // 选项行要拿到窗口句柄才能自己关掉自己：`dialog()` 是先 show 后返回，
        // 而 `dialogChoices` 在构造期就要闭包，所以把句柄挂在 tag 上传递。
        // 这样点选项 = 选了就关，点「取消」= 只关不选，跟系统那套行为一致。
        dlgRef = dlg
        for (i in 0 until col.childCount) col.getChildAt(i).tag = dlg
    }

    /** 当前打开的账号对话框。同一时刻只留一个，避免叠窗。 */
    private var dlgRef: android.app.Dialog? = null

    private fun askPassword(a: Admin.Account) {
        val input = Palette.field(this, getString(R.string.set_new_pw)).apply {
            setInputType(android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        }
        // 输入框在自绘对话框里不再需要套 FrameLayout 撑内边距：`dialog()` 的
        // box 自己带 20dp 左右留白，直接当 body 塞进去就是对齐的。
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        body.addView(Palette.dialogText(this, getString(R.string.set_pw_msg)))
        body.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(10) })

        val dlg = Palette.dialog(
            this,
            getString(R.string.set_pw_title, a.name ?: "?"),
            body,
            listOf(
                Palette.outline(this, getString(R.string.common_cancel)).apply {
                    setOnClickListener { dlgRef?.dismiss() }
                },
                Palette.solid(this, getString(R.string.set_pw_do)).apply {
                    setOnClickListener {
                        val p = input.text.toString()
                        if (p.isEmpty()) {
                            toast(getString(R.string.set_pw_empty))
                        } else {
                            dlgRef?.dismiss()
                            accountOp(OP_PW, a.name, p, a.level)
                        }
                    }
                }))
        dlgRef = dlg
    }

    private fun confirmKick(a: Admin.Account) {
        val dlg = Palette.dialog(
            this,
            getString(R.string.set_kick_title, a.name ?: "?"),
            Palette.dialogText(this, getString(R.string.set_kick_msg, a.online)),
            listOf(
                Palette.outline(this, getString(R.string.common_cancel)).apply {
                    setOnClickListener { dlgRef?.dismiss() }
                },
                Palette.solid(this, getString(R.string.set_kick)).apply {
                    setOnClickListener {
                        dlgRef?.dismiss()
                        accountOp(OP_KICK, a.name, "", a.level)
                    }
                }))
        dlgRef = dlg
    }

    private fun confirmDelete(a: Admin.Account) {
        val dlg = Palette.dialog(
            this,
            getString(R.string.set_del_title, a.name ?: "?"),
            Palette.dialogText(this, getString(R.string.set_del_msg)),
            listOf(
                Palette.outline(this, getString(R.string.common_cancel)).apply {
                    setOnClickListener { dlgRef?.dismiss() }
                },
                // 删除是不可逆动作，主按钮走警示色而不是主色，跟账号卡片上
                // 那颗「删除」描边按钮同色，两处指向同一件事。
                Palette.outline(this, getString(R.string.common_delete), Palette.WARN_HI).apply {
                    setOnClickListener {
                        dlgRef?.dismiss()
                        accountOp(OP_DEL, a.name, "", a.level)
                    }
                }))
        dlgRef = dlg
    }

    /** 四个账号动作统一走这里：后台请求 → toast → 重新拉一遍清单 */
    private fun accountOp(op: String, name: String, pw: String, level: Int, onOk: () -> Unit = {}) {
        if (adminState?.admin != true) {
            toast(getString(R.string.admin_not_admin_toast))
            return
        }
        val base = baseUrl
        val ck = sid
        adminPost {
            val r = try {
                when (op) {
                    OP_ADD -> Admin.addAccount(base, ck, name, pw, level, 9000)
                    OP_PW -> Admin.setAccount(base, ck, name, pw, level, 9000)
                    OP_LEVEL -> Admin.setAccount(base, ck, name, "", level, 9000)
                    OP_KICK -> Admin.kickAccount(base, ck, name, 9000)
                    else -> Admin.removeAccount(base, ck, name, 9000)
                }
            } catch (t: Throwable) {
                Admin.Saved().apply { ok = false; error = "${t.message}" }
            }
            ui.post {
                if (r.ok) {
                    toast(r.msg?.ifEmpty { getString(R.string.set_op_ok, opLabel(op)) } ?: getString(R.string.set_op_ok, opLabel(op)))
                    onOk()
                    loadManage()
                } else {
                    toast(getString(R.string.set_op_fail, opLabel(op), r.error ?: getString(R.string.common_unknown_error)))
                }
            }
        }
    }

    /** 动作代号的显示名（toast 用）。 */
    private fun opLabel(op: String): String = when (op) {
        OP_ADD -> getString(R.string.set_op_add)
        OP_PW -> getString(R.string.set_op_pw)
        OP_LEVEL -> getString(R.string.set_op_level)
        OP_KICK -> getString(R.string.set_op_kick)
        else -> getString(R.string.set_op_del)
    }

    /**
     * 三个等级的下拉项文案，**顺序必须与 Users.LEVEL_READ/WRITE/ADMIN = 0/1/2 对齐**。
     * 服务端认的是数字，这里只负责显示；`selectedItemPosition` 直接当 level 用，
     * 千万不要写成 `2 - which` 之类的换算 —— 那样管理员会变成只读。
     */
    private fun levelNames(): List<String> = listOf(
        getString(R.string.set_level_read),
        getString(R.string.set_level_write),
        getString(R.string.set_level_admin)
    )

    /** 短提示：同时打到 logcat，MIUI 的 Toast 在 uiautomator / dumpsys 里都抓不到 */
    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
        Diag.i("toast: $s")
    }

    /** 带用户反馈的刷新：转一下转圈提示，成功后 toast 报身份 */
    private fun refreshManage(showToast: Boolean) {
        val row = serverStatusRow
        row?.text = getString(R.string.set_refreshing)
        row?.setTextColor(Palette.DIM)
        loadManage(showToast)
    }

    private fun loadManage(showToast: Boolean = false) {
        val card = adminCard ?: return
        if (baseUrl.isEmpty()) return
        val base = baseUrl
        val ck = sid
        adminPost {
            val st = try {
                Admin.fetch(base, ck, 9000)
            } catch (t: Throwable) {
                Admin.State().apply { ok = false; error = "${t.message}" }
            }
            ui.post {
                adminState = st
                fillServerStatus(st)
                fillManage(st)
                fillAccounts(st)
                if (showToast) {
                    Toast.makeText(
                        this@LibraryActivity,
                        when {
                            !st.ok -> getString(R.string.set_refresh_failed, st.error ?: getString(R.string.common_no_response))
                            st.admin -> getString(R.string.set_refreshed_admin, st.who ?: "?")
                            st.logged -> getString(R.string.set_refreshed_user, st.who ?: "?", st.levelName ?: "?")
                            else -> getString(R.string.set_refreshed_anon)
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
                row.text = getString(R.string.set_cannot_reach, st.error ?: getString(R.string.common_no_response))
                row.setTextColor(Palette.WARN_HI)
            }
            st.admin -> {
                row.text = getString(R.string.set_logged_admin, st.who ?: "?", st.levelName ?: "?")
                row.setTextColor(Palette.OK)
            }
            st.logged -> {
                row.text = getString(R.string.set_logged_user, st.who ?: "?", st.levelName ?: "?")
                row.setTextColor(Palette.WARN_HI)
            }
            else -> {
                row.text = getString(R.string.set_logged_out)
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
                getString(R.string.set_config_failed, st.error ?: getString(R.string.common_no_response)),
                Palette.WARN_HI))
            card.addView(Palette.outline(this, getString(R.string.common_retry)).apply { setOnClickListener { loadManage() } },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38))
                    .apply { topMargin = dp(10) })
            return
        }
        if (!st.admin) {
            card.addView(Palette.note(this, if (st.logged)
                getString(R.string.set_need_admin_user, st.who ?: "?", st.levelName ?: "?")
            else
                getString(R.string.set_need_admin_login), Palette.DIM))
            // 只留一个按钮：刷新交给上面状态行右边那颗，这里再放一颗就成了
            // 「刷新」和「刷新会话」两个说法做同一件事，用户第一反应是「哪个算数」。
            val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            btns.addView(Palette.solid(this, getString(if (st.logged) R.string.admin_switch_user else R.string.admin_go_login)).apply {
                setOnClickListener {
                    openWebForLogin()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
            card.addView(btns, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) })
            // 非管理员也要能看见当前配置是什么（只读体面地显示出来）
            if (!st.logged) return
        } else {
            card.addView(Palette.note(this, getString(R.string.admin_current_user, st.who ?: "?", st.levelName ?: "?"), Palette.OK),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }

        val editable = st.admin
        titleBox = Palette.field(this, getString(R.string.admin_site_title_hint)).apply {
            setText(st.title ?: "")
            isEnabled = editable
        }
        card.addView(manageSection(getString(R.string.admin_site_title),
            getString(R.string.admin_site_title_note),
            titleBox, null, editable) {
            save("title", getString(R.string.admin_site_title), titleBox?.text?.toString() ?: "")
        })

        musicTree = DirTree(this).apply { setPaths(st.musicDirs ?: emptyList()) }
        card.addView(manageSection(getString(R.string.admin_music_dirs),
            getString(R.string.admin_music_dirs_note),
            null, musicTree, editable) {
            save("music", getString(R.string.admin_music_dirs), musicTree?.checkedPaths()?.joinToString("\n") ?: "")
        })
        musicTree!!.rebuild(st.choices ?: emptyList(), st.musicDirs ?: emptyList())

        videoTree = DirTree(this).apply { setPaths(st.videoDirs ?: emptyList()) }
        card.addView(manageSection(getString(R.string.admin_video_dirs),
            getString(R.string.admin_video_dirs_note),
            null, videoTree, editable) {
            save("video", getString(R.string.admin_video_dirs), videoTree?.checkedPaths()?.joinToString("\n") ?: "")
        })
        videoTree!!.rebuild(st.choices ?: emptyList(), st.videoDirs ?: emptyList())

        bookTree = DirTree(this).apply { setPaths(st.bookDirs ?: emptyList()) }
        card.addView(manageSection(getString(R.string.admin_book_dirs),
            getString(R.string.admin_book_dirs_note_short),
            null, bookTree, editable) {
            save("book", getString(R.string.admin_book_dirs), bookTree?.checkedPaths()?.joinToString("\n") ?: "")
        })
        bookTree!!.rebuild(st.choices ?: emptyList(), st.bookDirs ?: emptyList())

        // 相册：第四个库。特意排在最后 —— 前三个是「本来就有内容」的库，
        // 相册是「你要先指个目录」才有内容的，放最后不打乱前三个的肌肉记忆。
        photoTree = DirTree(this).apply { setPaths(st.photoDirs ?: emptyList()) }
        card.addView(manageSection(getString(R.string.admin_photo_dirs),
            getString(R.string.admin_photo_dirs_note),
            null, photoTree, editable) {
            save("photo", getString(R.string.admin_photo_dirs), photoTree?.checkedPaths()?.joinToString("\n") ?: "")
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
            box.addView(Palette.note(this, getString(R.string.admin_picked_paths)))
            box.addView(tree.editor(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (editable) {
            box.addView(Palette.solid(this, getString(R.string.admin_save_prefix, title), Palette.BTN, Palette.FG2).apply {
                setOnClickListener { onSave() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42))
                .apply { topMargin = dp(10) })
        }
        return box
    }

    private fun save(action: String, label: String, value: String) {
        if (adminState?.admin != true) {
            Toast.makeText(this, getString(R.string.admin_not_admin_toast), Toast.LENGTH_SHORT).show()
            return
        }
        val base = baseUrl
        val ck = sid
        val dirs = value.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        adminPost {
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
                    if (r.ok) getString(R.string.admin_saved, label, r.msg ?: getString(R.string.admin_saved_ok))
                    else getString(R.string.admin_save_failed, label, r.error ?: getString(R.string.common_unknown_error)),
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
        // 先立旗再关池：recreate()（切换主题）和正常退出都会走到这里，
        // 但已经在路上的 ui.post 回调还会跑一轮 —— 那时候 io 已经 Terminated，
        // 往里面 execute 会抛 RejectedExecutionException 直接把进程带走
        // （实测在 buildVideos → loadPoster 这条链上炸过）。
        dead = true
        io.shutdownNow()
        adminIo.shutdownNow()
        super.onDestroy()
    }

    /** 这个实例是否已销毁。销毁后一切后台提交都要静默丢弃。 */
    @Volatile
    private var dead = false

    /** 安全提交：池已关闭就当作没这回事，别让界面收尾阶段把进程炸了。 */
    private fun post(work: () -> Unit) {
        if (dead) return
        try {
            io.execute(work)
        } catch (ignored: java.util.concurrent.RejectedExecutionException) {
            // recreate/shutdown 期间的迟到任务，丢掉即可
        }
    }

    /** 管理页那条 IO 池的安全提交，理由同 post()。 */
    private fun adminPost(work: () -> Unit) {
        if (dead) return
        try {
            adminIo.execute(work)
        } catch (ignored: java.util.concurrent.RejectedExecutionException) {
            // 同上
        }
    }
}
