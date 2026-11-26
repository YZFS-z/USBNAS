package com.littlesauce.usbnas

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        /** App 界面状态刷新周期：与网页端、采集器保持一致 */
        const val TICK_MS = 5_000L

        /** 二维码两种载荷：站点地址 / 本机热点凭据 */
        private const val QR_SERVER = 0
        private const val QR_HOTSPOT = 1

        /**
         * 首页最多预建几行「存储 · U 盘」。
         *
         * 状态行是 buildHome 一次性建好的，插拔时重建整页会闪，所以先建够行数再
         * 用 GONE/VISIBLE 控制。实测这台机器两块盘同时挂载很常见（8,97 与 8,113），
         * 取 4 已经能覆盖扩展坞场景；再多也不会崩，只是超出的盘不显示。
         */
        private const val MAX_USB_ROWS = 4

        /** 无操作多久后进入屏保：5 分钟 */
        private const val SCREENSAVER_IDLE_MS = 5 * 60_000L

        /**
         * 像素位移幅度，单位 dp。
         *
         * 静态界面在 AMOLED 上长时间不动，最容易被烧的是固定不动的文字和边框。
         * 要真正避免烧屏只有两个办法：让像素断电（黑屏），或者让发光区域一直在动。
         * 这里做后者 —— 每 60 秒把整个首页随机平移 (0~3dp, 0~3dp)，
         * 人眼几乎看不出来，但每个像素的累计点亮时间被摊平了。
         */
        private const val SHIFT_MAX_DP = 3

        /** 位移间隔：太快会显得界面在抖，太慢起不到摊平作用 */
        private const val SHIFT_MS = 60_000L

        /**
         * App 版本号，唯一真源。
         *
         * 这里特意不读 BuildConfig.VERSION_NAME：网页端 /_status 也要吐同一个号
         * （HttpServer 里另有 APP_VER 常量），两边必须一致，所以发版时两处一起改，
         * 并在 RELEASING.md 里写清楚。只改 gradle 的 versionName 不会影响这里。
         */
        const val APP_VER = "1.1.0"
    }

    // 两套布局的控件并不完全一致（竖屏首页在 homeScroll 里，横屏分两列各自滚），
    // 所以统一按可空绑定，用到的地方判空，避免横竖屏切换重建时 NPE。
    private var tvTitle: TextView? = null
    private var tvRoots: TextView? = null
    private var tvLog: TextView? = null
    private var tvUsers: TextView? = null
    private var btnToggle: Button? = null
    private var btnBattery: Button? = null
    private var btnPerm: Button? = null
    private var btnOpen: Button? = null
    private var btnClear: Button? = null
    private var btnUsers: Button? = null
    private var btnTitle: Button? = null
    private var btnAntiBurn: Button? = null
    private var btnScreenOff: Button? = null
    private var btnUsbSleep: Button? = null
    private var btnUsbSleepNow: Button? = null
    private var btnMusicDirs: Button? = null
    private var btnVideoDirs: Button? = null

    /** 自动更新入口（默认隐藏到设置区，见 updateUi()）与它下面那行版本说明 */
    private var btnUpdate: Button? = null
    private var tvUpdate: TextView? = null

    /*
     * 首页配色不再用常量写死。
     *
     * 原来这里是 companion object 里的 C_BG / C_CARD / C_TEXT 一组深色常量，
     * App 却跑在 Theme.Material3.DayNight 下 —— 手机切日间模式后框架变浅、
     * 卡片还是深的，用户看到的就是「手机状态那个框一直是夜间模式」。
     *
     * 现在统一走 Palette（按 uiMode 自动给两套值）。这些必须放在实例作用域
     * 而非 companion 里：`Palette.bg(this)` 的 this 要是 Context，
     * 而 companion 里的 this 是 Companion 本身，编译不过。
     * 切主题时由 Palette.restyle() 把已建好的 View 逐个重新上色。
     */
    private val C_BG get() = Palette.bg(this)
    private val C_DIV get() = Palette.line(this)
    private val C_TEXT get() = Palette.text(this)
    private val C_LABEL get() = Palette.label(this)
    private val C_SUB get() = Palette.sub(this)
    private val C_ACCENT get() = Palette.accent(this)
    private val C_OK get() = Palette.ok(this)
    private val C_OFF get() = Palette.off(this)
    private val C_CARD get() = Palette.card(this)
    private val C_LINE get() = Palette.line(this)
    private val C_INSET get() = Palette.inset(this)

    /** 首页刷新计数器：U 盘休眠状态每 6 拍（约 30 秒）查一次就够了 */
    private var usbTick = 0
    private var scrollLog: ScrollView? = null

    /** 首页容器：横屏是第 1 页的横向根，竖屏是底部状态区，二者共用同一个 id */
    private var homeRoot: LinearLayout? = null
    private var homeScroll: ScrollView? = null

    /** 仅横屏存在：两页容器与底部指示条 */
    private var flipper: ViewFlipper? = null
    private var pageDots: List<TextView> = emptyList()

    private val logLines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val clockFmt = SimpleDateFormat("HH:mm", Locale.US)
    // 首页时钟只显示到分钟，秒跳得人眼花
    private val dateFmt = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)

    /** 首页各元素的数值 TextView，key 与下面 buildHome 里用的一致 */
    private val homeValues = HashMap<String, TextView>()
    private val homeSubs = HashMap<String, TextView>()
    /** 需要按状态改颜色的圆点（目前只有服务状态） */
    private val homeDots = HashMap<String, View>()

    /** 已渲染的排版签名，变了才重建，避免每 5 秒把界面拆了重搭 */
    private var homeSig: String? = null
    private var lastQrUrl: String? = null
    /** 二维码当前展示的内容：站点地址 / 热点凭据 */
    private var qrMode = QR_SERVER
    private var lastQrKey: String? = null
    private var qrSwitch: TextView? = null

    /** 防烧屏遮罩：全屏盖住内容，只在屏保期间显示 */
    private var veil: View? = null
    /** 屏保里的 GIF 小宠物（透明背景） */
    private var veilPet: android.widget.ImageView? = null
    private var veilHint: TextView? = null
    /** 遮罩容器：宠物在里面按九宫格循环换位 */
    private var veilBox: LinearLayout? = null

    /** 屏保是否正在显示 */
    private var veilOn = false
    /** 最后一次触摸的时间戳，用来算空闲多久 */
    private var lastTouchAt = 0L
    /** 遮罩上时钟停在九宫格的第几格 */
    private var veilCell = 0

    /** 点击时钟卡 → 防烧屏设置 */
    private var onClockCardClick: (() -> Unit)? = null

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** 进入屏保的定时检查 */
    private val idleCheck = object : Runnable {
        override fun run() {
            val idle = System.currentTimeMillis() - lastTouchAt
            if (!veilOn && idle >= SCREENSAVER_IDLE_MS) showVeil()
            mainHandler.postDelayed(this, 20_000L)
        }
    }
    /** 像素位移 */
    private val shiftTick = object : Runnable {
        override fun run() {
            applyPixelShift()
            mainHandler.postDelayed(this, SHIFT_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupFullscreen()
        setContentView(R.layout.activity_main)
        tvRoots = findViewById(R.id.tvRoots)
        tvLog = findViewById(R.id.tvLog)
        tvUsers = findViewById(R.id.tvUsers)
        btnToggle = findViewById(R.id.btnToggle)
        btnBattery = findViewById(R.id.btnBattery)
        btnPerm = findViewById(R.id.btnPerm)
        btnOpen = findViewById(R.id.btnOpen)
        btnTitle = findViewById(R.id.btnTitle)
        btnAntiBurn = findViewById(R.id.btnAntiBurn)
        btnScreenOff = findViewById(R.id.btnScreenOff)
        btnUsbSleep = findViewById(R.id.btnUsbSleep)
        btnUsbSleepNow = findViewById(R.id.btnUsbSleepNow)
        // 自动更新控件可能不在旧布局里，按可空处理
        btnUpdate = findViewById(R.id.btnUpdate)
        tvUpdate = findViewById(R.id.tvUpdate)
        btnMusicDirs = findViewById(R.id.btnMusicDirs)
        btnVideoDirs = findViewById(R.id.btnVideoDirs)
        btnClear = findViewById(R.id.btnClear)
        btnUsers = findViewById(R.id.btnUsers)
        scrollLog = findViewById(R.id.scrollLog)
        homeRoot = findViewById(R.id.homeRoot)
        homeScroll = findViewById(R.id.homeScroll)
        // 站名不再固定绑在布局里：它跟时间/二维码一样是首页里动态生成的卡片，
        // 由 buildHome() 每次重建时调用 applyTitle(AppPrefs.title(this)) 写入。

        setupFlipper()

        btnToggle?.setOnClickListener { toggleServer() }
        btnBattery?.setOnClickListener { requestIgnoreBattery() }
        btnPerm?.setOnClickListener { requestStorage() }
        btnOpen?.setOnClickListener { openInBrowser() }
        btnUsers?.setOnClickListener { showUsersDialog() }
        btnTitle?.setOnClickListener { showTitleDialog() }
        btnAntiBurn?.setOnClickListener { showAntiBurnDialog() }
        btnScreenOff?.setOnClickListener { screenOffAndStop() }
        btnUsbSleep?.setOnClickListener { showUsbSleepDialog() }
        btnUsbSleepNow?.setOnClickListener { suspendUsbNow() }
        // 音乐目录 / 影视目录不再放在本机 App 里：它们是「设一次、所有客户端共用」
        // 的全局配置，改由网页端 /_admin 和 exe/apk 客户端的管理界面维护。
        // 布局里的按钮保留但隐藏，老版本升级上来也不会因为 findViewById 变 null 而崩。
        btnMusicDirs?.visibility = android.view.View.GONE
        btnVideoDirs?.visibility = android.view.View.GONE
        btnClear?.setOnClickListener {
            logLines.clear()
            tvLog?.text = ""
        }

        requestNotificationPermission()
        refreshUi()
        setupAntiBurn()

        // 自动更新：默认关闭，打开后每次回前台最多 12 小时查一次（Updater 内部有两道闸）。
        // 这里只挂「手动检查」的入口，自动那条走 onResume。
        btnUpdate?.setOnClickListener { showUpdateDialog() }
        updateUi()

        val ticker = object : Runnable {
            override fun run() {
                refreshUi()
                btnToggle?.postDelayed(this, TICK_MS)
            }
        }
        btnToggle?.postDelayed(ticker, TICK_MS)
    }

    override fun onResume() {
        super.onResume()
        NasService.logSink = { line -> runOnUiThread { appendLog(line) } }
        synchronized(NasService.recentLogs) {
            NasService.recentLogs.forEach { appendLog(it) }
        }
        if (NasService.isRunning) SysInfo.get(this).start()
        // 排版可能在别处被改过，回前台强制重建
        homeSig = null
        // 重建一次首页，把后台期间变过的数据补齐
        buildHome(true)
        refreshUi()
        // 自动更新：开关关着时 due() 直接 false，一个请求都不发
        Updater.autoCheck(this, APP_VER, false)
        updateUi()
        // 回到前台重新开始计空闲
        lastTouchAt = System.currentTimeMillis()
        mainHandler.removeCallbacks(idleCheck)
        mainHandler.postDelayed(idleCheck, 20_000L)
        mainHandler.removeCallbacks(shiftTick)
        mainHandler.postDelayed(shiftTick, SHIFT_MS)
        // 从后台/锁屏回来，系统会把状态栏放回去，这里补收一次
        hideSystemBars()
    }

    /** 首页那个「自动更新」按钮上的字：显示开关状态 + 上次检查时间 */
    private fun updateUi() {
        val on = AppPrefs.autoUpdate(this)
        btnUpdate?.text = if (on) "自动更新 开" else "自动更新 关"
        tvUpdate?.text = if (on) {
            val t = AppPrefs.lastUpdateCheck(this)
            if (t <= 0L) "版本 $APP_VER · 还没检查过"
            else "版本 $APP_VER · 上次检查 " +
                    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(t))
        } else {
            "版本 $APP_VER · 已关闭，不会联网"
        }
    }

    /**
     * 自动更新设置 + 手动检查。
     *
     * 三个动作放一个对话框里，是因为用户的真实提问永远是这三句：
     * 「现在什么版本」「你自己会不会升级」「怎么立刻查一次」。
     */
    private fun showUpdateDialog() {
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(4))
        }
        val sw = android.widget.Switch(this).apply {
            text = "联网检查新版本"
            setTextColor(C_TEXT)
            textSize = 14f
            isChecked = AppPrefs.autoUpdate(this@MainActivity)
        }
        box.addView(sw)
        box.addView(TextView(this).apply {
            text = "打开后每次回到首页最多查一次 GitHub，间隔不少于 12 小时。" +
                    "关着就一个请求都不发。"
            setTextColor(C_SUB)
            textSize = 11f
            setPadding(0, dp(10), 0, 0)
        })
        box.addView(TextView(this).apply {
            text = "当前版本 $APP_VER"
            setTextColor(C_LABEL)
            textSize = 12f
            setPadding(0, dp(14), 0, 0)
        })
        val skip = AppPrefs.skipVersion(this)
        if (skip.isNotEmpty()) {
            box.addView(TextView(this).apply {
                text = "已跳过版本：$skip"
                setTextColor(C_SUB)
                textSize = 11f
                setPadding(0, dp(8), 0, 0)
                setOnClickListener {
                    AppPrefs.setSkipVersion(this@MainActivity, "")
                    Toast.makeText(this@MainActivity, "已清除跳过标记", Toast.LENGTH_SHORT).show()
                }
            })
        }
        sw.setOnCheckedChangeListener { _, checked ->
            AppPrefs.setAutoUpdate(this, checked)
            updateUi()
        }

        AlertDialog.Builder(this)
            .setTitle("自动更新")
            .setView(box)
            .setPositiveButton("立即检查") { _, _ ->
                Toast.makeText(this, "正在检查…", Toast.LENGTH_SHORT).show()
                // force=true：手动点就绕过开关和 12 小时间隔
                Updater.autoCheck(this, APP_VER, true)
            }
            .setNeutralButton("发布页") { _, _ ->
                Updater.openUrl(this, "https://github.com/" + Updater.OWNER + "/" + Updater.REPO + "/releases")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 系统切换深色/浅色、或改变字体/字号时会走这里。
     *
     * 配色已经是固定暗色（见 `Palette`），所以这里**不重上色** ——
     * 但字号/屏幕尺寸变化后首页的动态高度算不出来，还是得重建一遍。
     * 横竖屏切换也是从这条路进来的（Manifest 没声明 `orientation`，
     * 由 `buildHome` 内部按 `isPortrait()` 走两套排版）。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyThemeNow()
    }

    /**
     * 配置变化后重建首页。
     *
     * 以前这里叫「重新配色」，现在配色固定了，只剩重建这一个作用：
     * 字号、屏幕尺寸、横竖屏一变，卡片高度和 StatusRow 的换行都要重算。
     *
     * 附带说明 `UiModeManager.OnApplyNightModeListener` 那条路走不通：
     * 这个类型在当前 compileSdk 上不存在（Kotlin 报 Unresolved reference），
     * 而且 MIUI 上 `cmd uimode night yes` 也不会把配置变更下发到已在
     * 前台的进程 —— 这也是当初放弃跟随系统的原因之一。
     */
    private fun applyThemeNow() {
        buildHome(true)
        updatePageDots()
        refreshUi()
    }

    /**
     * ViewFlipper 只把当前页的子 View 纳入状态保存，
     * 而 ViewHierarchyState 是按位置索引存放的，
     * 横竖屏切换 / 窗口缩放（Crash-Tag: window_resize）后索引错位，
     * AbsSavedState.EMPTY_STATE 会被投给 ScrollView 触发 ClassCastException。
     *
     * 界面本来就是每次 onCreate 重建并主动刷新的，直接不吃旧状态最稳。
     */
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        // 不调用 super：跳过整棵视图树的状态恢复
    }

    override fun onPause() {
        super.onPause()
        NasService.logSink = null
        mainHandler.removeCallbacks(idleCheck)
        mainHandler.removeCallbacks(shiftTick)
    }

    // night mode 监听器已移除：`UiModeManager.OnApplyNightModeListener` 在这个
    // compileSdk 上不存在（Kotlin 报 Unresolved reference），死路。

    override fun onDestroy() {
        super.onDestroy()
    }

    /**
     * 任何触摸都算「有人在场」：退出屏保 + 重置空闲计时。
     *
     * 必须用 dispatchTouchEvent 而不是给每个 View 加监听 —— 首页是一棵
     * 深好几层的动态视图树，逐层挂监听既啰嗦又容易漏掉不接收事件的空白区域。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            lastTouchAt = System.currentTimeMillis()
            if (veilOn) hideVeil()
        }
        return super.dispatchTouchEvent(ev)
    }

    // ---------- 两页翻页（横竖屏共用） ----------

    /**
     * 横竖屏都是「概览 / 控制」两页：横屏左右滑，竖屏上下滑。
     * 找不到 flipper（布局没改到）就跳过，不影响其它功能。
     */
    private fun setupFlipper() {
        val flipper = findViewById<ViewFlipper?>(R.id.flipper) ?: return
        this.flipper = flipper
        findViewById<View?>(R.id.pageBar)?.visibility = View.VISIBLE

        val dot0 = findViewById<TextView?>(R.id.dot0)
        val dot1 = findViewById<TextView?>(R.id.dot1)
        pageDots = listOfNotNull(dot0, dot1)

        fun go(page: Int) {
            flipper.displayedChild = page
            updatePageDots()
        }

        dot0?.setOnClickListener { go(0) }
        dot1?.setOnClickListener { go(1) }

        // 竖屏上下滑、横屏左右滑
        val portrait = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_PORTRAIT
        val threshold = 70 * resources.displayMetrics.density
        var downX = 0f
        var downY = 0f
        flipper.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    false
                }
                MotionEvent.ACTION_UP -> {
                    val dx = e.x - downX
                    val dy = e.y - downY
                    if (portrait) {
                        when {
                            dy < -threshold && flipper.displayedChild == 0 -> { go(1); true }
                            dy > threshold && flipper.displayedChild == 1 -> { go(0); true }
                            else -> false
                        }
                    } else {
                        when {
                            dx < -threshold && flipper.displayedChild == 0 -> { go(1); true }
                            dx > threshold && flipper.displayedChild == 1 -> { go(0); true }
                            else -> false
                        }
                    }
                }
                else -> false
            }
        }
        updatePageDots()
    }

    private fun updatePageDots() {
        val cur = flipper?.displayedChild ?: 0
        // 选中页给胶囊底色，未选中给透明底，只靠颜色区分，不再画 ● ○ 符号
        pageDots.getOrNull(0)?.let {
            it.text = "概览"
            it.setTextColor(if (cur == 0) C_ACCENT else C_SUB)
            it.background = if (cur == 0) Palette.pillBg(this) else null
        }
        pageDots.getOrNull(1)?.let {
            it.text = "控制"
            it.setTextColor(if (cur == 1) C_ACCENT else C_SUB)
            it.background = if (cur == 1) Palette.pillBg(this) else null
        }
    }

    // ---------- 全屏 ----------

    // ---------- 防烧屏 ----------

    /**
     * AMOLED 长时间显示同一画面，像素会衰减得不一样，留下残影。
     *
     * 这里做三件事，按「代价从低到高」排：
     *
     * 1. **像素位移**：每 60 秒把首页内容随机平移 0~3dp。人眼看不出来，
     *    但每个像素的点亮时长被摊平了。这一项永远生效，不需要用户开。
     *    平移作用在 content 的第一个子 View 上（根布局），不能作用在
     *    DecorView 上，那会把系统栏一起推走。
     *
     * 2. **屏保遮罩**：空闲 5 分钟盖一层黑色半透明遮罩，把全屏亮度压下去。
     *    遮罩上显示一个走时的大钟，位置在九宫格里轮换 —— 一块会动的 UI
     *    不会烧，而全屏黑的地方对 OLED 是真正断电。
     *
     * 3. **保持亮屏**：当 NAS 就是要一直看得见，所以主动 FLAG_KEEP_SCREEN_ON，
     *    否则系统到点自己息屏，整个服务在手机上看不到状态。
     *    这一条不受防烧屏开关影响，本来就是 NAS 的常态。
     */
    private fun setupAntiBurn() {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lastTouchAt = System.currentTimeMillis()
        buildVeil()
    }

    /** 建遮罩骨架：一个填满屏幕的黑层，中间放一个时钟 + 一行提示 */
    private fun buildVeil() {
        val content = findViewById<View?>(android.R.id.content) as? ViewGroup ?: return
        val layer = android.widget.FrameLayout(this).apply {
            // 关掉点击穿透：屏保期间点在遮罩上应该是「唤醒」而不是点到下面的按钮
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        // 屏保主体：静态图（白底已抠成透明），直接压在黑遮罩上
        val pet = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.pet_saver)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(168), dp(188))
            // 纯黑遮罩下太亮会刺眼，压到一个跟"屏保"相符的灰度
            alpha = 0.55f
        }
        val hint = TextView(this).apply {
            text = "触摸屏幕唤醒"
            setTextColor(0xFF26303C.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        box.addView(pet)
        box.addView(hint)

        // 宠物按九宫格摆，下一轮换一格
        layer.addView(box)
        content.addView(layer)

        veil = layer
        veilBox = box
        veilHint = hint
        veilPet = pet

        // 点遮罩任意位置退出屏保
        layer.setOnClickListener { hideVeil() }
    }

    /** 进屏保：盖遮罩、把时钟挪到一个新的格子、开始走秒 */
    private fun showVeil() {
        if (!AppPrefs.antiBurn(this)) return
        val layer = veil ?: return
        veilOn = true
        val pct = AppPrefs.dimPercent(this)
        // 黑色 + 高不透明度：OLED 上是真断电，LCD 上也只是亮度变低
        layer.setBackgroundColor(android.graphics.Color.argb((pct * 255) / 100, 0, 0, 0))
        // 防烧屏模式打开时整页保持常亮，否则系统会自己息屏，遮罩也就没意义了
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        moveVeilClock()
        layer.visibility = View.VISIBLE
        layer.bringToFront()
        hideSystemBars()
        mainHandler.removeCallbacks(veilTick)
        mainHandler.post(veilTick)
    }

    private fun hideVeil() {
        if (!veilOn) return
        veilOn = false
        veil?.visibility = View.GONE
        mainHandler.removeCallbacks(veilTick)
    }

    /** 屏保轮询：每 60 秒把宠物挪到下一格 */
    private val veilTick = object : Runnable {
        private var rounds = 0
        override fun run() {
            if (!veilOn) return
            rounds++
            // 60 秒换一次位置：换太快像是故障，换太慢在那一格上停留太久
            if (rounds % 60 == 0) moveVeilClock()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    /**
     * 把时钟挪到九宫格的下一格。
     *
     * 用 gravity + 平移做，比直接改 layoutParams 便宜，
     * 而且不会触发整棵视图树重新测量。
     */
    private fun moveVeilClock() {
        val box = veilBox ?: return
        veilCell = (veilCell + 1) % 9
        val col = veilCell % 3
        val row = veilCell / 3
        val lp = box.layoutParams as? android.widget.FrameLayout.LayoutParams ?: return
        lp.gravity = when {
            row == 0 -> Gravity.TOP
            row == 1 -> Gravity.CENTER_VERTICAL
            else -> Gravity.BOTTOM
        } or when {
            col == 0 -> Gravity.START
            col == 1 -> Gravity.CENTER_HORIZONTAL
            else -> Gravity.END
        }
        // 留出边距，别贴到屏幕边缘
        val m = dp(48)
        lp.setMargins(m, m, m, m)
        box.layoutParams = lp
    }

    /**
     * 像素位移：每 60 秒把首页随机平移一点点。
     *
     * 用 translationX/Y 而不是 padding —— translation 走渲染层，不触发布局，
     * 每 5 秒刷新数值的场景下不会互相打架。
     * 位移量控制在 SHIFT_MAX_DP 以内，肉眼察觉不到，但足以让同一像素
     * 不至于连续几百小时只显示一个颜色。
     */
    private fun applyPixelShift() {
        val host = findViewById<View?>(android.R.id.content)
            ?.let { it as? ViewGroup }?.getChildAt(0) ?: return
        val rnd = java.util.Random()
        val maxPx = dp(SHIFT_MAX_DP)
        // 在正负 maxPx 之间取，且不取 0：停在原位就等于没动
        fun pick(): Float {
            var v = 0
            while (v == 0) v = rnd.nextInt(maxPx * 2 + 1) - maxPx
            return v.toFloat()
        }
        host.translationX = pick()
        host.translationY = pick()
    }

    // ---------- 防烧屏设置对话框 ----------

    private fun showAntiBurnDialog() {
        val on = AppPrefs.antiBurn(this)
        val pcts = intArrayOf(50, 60, 70, 80, 90)
        val cur = pcts.indexOf(AppPrefs.dimPercent(this)).coerceAtLeast(2)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(4))
        }
        val sw = android.widget.Switch(this).apply {
            text = "防烧屏（空闲 5 分钟后进入屏保）"
            setTextColor(C_TEXT)
            tag = "text"
            textSize = 14f
            isChecked = on
        }
        box.addView(sw)

        val label = TextView(this).apply {
            text = "屏保亮度压暗：${pcts[cur]}%"
            setTextColor(C_LABEL)
            tag = "label"
            textSize = 12f
            setPadding(0, dp(14), 0, dp(6))
        }
        box.addView(label)

        val seek = android.widget.SeekBar(this).apply {
            max = pcts.size - 1
            progress = cur
            isEnabled = on
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    label.text = "屏保亮度压暗：${pcts[p]}%"
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
            })
        }
        box.addView(seek)

        box.addView(TextView(this).apply {
            text = "另外每 60 秒会把界面平移几个像素，避免固定文字长期压在同一批点上。"
            setTextColor(C_SUB)
            tag = "sub"
            textSize = 11f
            setPadding(0, dp(12), 0, 0)
        })

        sw.setOnCheckedChangeListener { _, checked ->
            seek.isEnabled = checked
        }

        AlertDialog.Builder(this)
            .setTitle("防烧屏")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                AppPrefs.setAntiBurn(this, sw.isChecked)
                AppPrefs.setDimPercent(this, pcts[seek.progress])
                if (!sw.isChecked) hideVeil()
                refreshUi()
                Toast.makeText(this, if (sw.isChecked) "已开启防烧屏" else "已关闭防烧屏",
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * U 盘休眠设置。
     *
     * 机械盘不转 = 没有嗡嗡声，是这个对话框存在的唯一理由。
     * 默认 60 秒：既能让盘在真正闲置时停下来，又不会连续翻几个目录就反复唤醒。
     */
    private fun showUsbSleepDialog() {
        val on = AppPrefs.usbSleep(this)
        val secs = intArrayOf(30, 60, 120, 300, 600)
        val cur = secs.indexOf(AppPrefs.usbSleepSec(this)).let { if (it < 0) 1 else it }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(4))
        }
        val sw = android.widget.Switch(this).apply {
            text = "空闲后让外接盘停转"
            setTextColor(C_TEXT)
            tag = "text"
            textSize = 14f
            isChecked = on
        }
        box.addView(sw)

        val label = TextView(this).apply {
            text = "空闲等待：${secs[cur]} 秒"
            setTextColor(C_LABEL)
            tag = "label"
            textSize = 12f
            setPadding(0, dp(14), 0, dp(6))
        }
        box.addView(label)

        val seek = android.widget.SeekBar(this).apply {
            max = secs.size - 1
            progress = cur
            isEnabled = on
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    label.text = "空闲等待：${secs[p]} 秒"
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
            })
        }
        box.addView(seek)

        // 把当前每块盘的真实状态写出来，用户能自己确认到底停没停
        val st = USBSleep.read()
        val stat = TextView(this).apply {
            text = if (st.isEmpty()) "没检测到外接盘"
            else st.joinToString("\n") { "· " + it.label() }
            setTextColor(C_SUB)
            tag = "sub"
            textSize = 11f
            setPadding(0, dp(12), 0, 0)
        }
        box.addView(stat)

        box.addView(TextView(this).apply {
            text = "只要有访问就会自动唤醒（实测 25ms），平时感觉不到。"
            setTextColor(C_SUB)
            tag = "sub"
            textSize = 11f
            setPadding(0, dp(10), 0, 0)
        })

        sw.setOnCheckedChangeListener { _, checked -> seek.isEnabled = checked }

        AlertDialog.Builder(this)
            .setTitle("U 盘休眠")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                AppPrefs.setUsbSleep(this, sw.isChecked)
                AppPrefs.setUsbSleepSec(this, secs[seek.progress])
                Toast.makeText(this, "正在应用…", Toast.LENGTH_SHORT).show()
                // root 调用会阻塞几秒，绝不能放主线程
                Thread {
                    NasService.applyUsbSleep(this)
                    runOnUiThread {
                        refreshUi()
                        val after = USBSleep.read()
                        val msg = if (after.isEmpty()) "没检测到外接盘"
                        else after.joinToString("\n") { "· " + it.label() }
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }.apply { isDaemon = true }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 立即让盘停转：给「我要马上安静下来」的场景一个一键出口 */
    private fun suspendUsbNow() {
        btnUsbSleepNow?.isEnabled = false
        btnUsbSleepNow?.text = "停转中…"
        Thread {
            val n = USBSleep.suspendNow()
            runOnUiThread {
                btnUsbSleepNow?.isEnabled = true
                refreshUi()
                val msg = when {
                    n < 0 -> "需要 root 权限"
                    n == 0 -> "没能停转，盘上可能还有读写"
                    else -> "$n 块盘已停转"
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 立即熄屏：给「我一整天都不看这块屏」的场景一个一键出口。
     *
     * 只熄屏、不停服务 —— NAS 的 HTTP 服务跑在前台 Service 里，
     * 屏幕黑了照样能访问，这才是「防烧屏」最彻底的做法。
     */
    private fun screenOffAndStop() {
        // 先把常亮标志摘掉，否则系统会立刻把屏幕点亮回来
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "usbnas:screenoff"
        )
        try {
            // 用距离感应器熄屏：盖住屏幕顶部就黑，拿开就亮，不需要任何权限
            wl.acquire(2 * 60 * 60 * 1000L)
            Toast.makeText(this, "屏幕已熄灭，服务继续运行", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            // 没有距离感应器的机器退回普通息屏
            Toast.makeText(this, "已请求熄屏，服务继续运行", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 沉浸式全屏：贴边显示、隐藏状态栏与导航栏。
     * 从顶部/底部边缘下滑仍可临时唤出系统栏（BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE）。
     */    @Suppress("DEPRECATION")
    private fun setupFullscreen() {
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        // 这台机器常年横屏插在底座上当相框，挖孔区（顶部 104px）必须让内容画进去，
        // 否则 setDecorFitsSystemWindows(false) 之后顶部照样留一条黑边。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        installSafeAreaPadding()
        installBarsHider()
    }

    /**
     * 系统栏只靠 decor 的 visibility 回调抓不住 —— 下拉通知栏、解锁、从别的
     * Activity 回来，状态栏都会自己滑回来。窗口焦点一拿到就再收一次，这是
     * 唯一稳定的钩子。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /**
     * setDecorFitsSystemWindows(false) 之后 DecorView 铺到 1080x2400，
     * 但真正可画的区间仍受 mAppBounds（顶部状态栏 104px + 底部手势区 44dp）限制。
     * 底部翻页条压在 2400-44dp 那条带里，点不到，所以这里手动把导航栏高度让出来。
     * 只在竖屏有 pageBar，横屏那条窄，让不让都行，一并让出来保持左右对称。
     */
    /** 系统栏让位后的可用高度，布局跑完第一遍后回写给根布局的底部 padding */
    private var bottomInsetPx = 0

    /**
     * 让底部翻页条落在可点区域。
     *
     * 这里必须操作根布局（content 的第一个子 View）：
     * 把 padding 加在 android.R.id.content 上完全没用 —— DecorView 铺满 2400，
     * 而 MIUI 会把 insets 回调两次，第二次全 0，任何基于回调的 padding 都会被抹掉。
     * 所以先记下最大的一次 inset，等布局完成、根布局有真实高度之后，
     * 再一次性给它设 paddingBottom，必要时让根布局再测一遍。
     */
    private fun installSafeAreaPadding() {
        val root = findViewById<View?>(android.R.id.content) ?: return
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            if (bottom > bottomInsetPx) {
                bottomInsetPx = bottom
                applyBottomInset()
            }
            insets
        }

        // 布局第一遍之后再兜一次：那时根布局已经量出真实高度
        root.post { applyBottomInset() }
        root.viewTreeObserver.addOnGlobalLayoutListener(object :
            android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                applyBottomInset()
            }
        })
    }

    private fun applyBottomInset() {
        val host = findViewById<View?>(android.R.id.content)
            ?.let { it as? android.view.ViewGroup }
            ?.getChildAt(0) as? android.view.ViewGroup ?: return
        if (bottomInsetPx <= 0) return
        if (host.paddingBottom == bottomInsetPx) return
        host.setPadding(host.paddingLeft, host.paddingTop, host.paddingRight, bottomInsetPx)
        host.requestLayout()
    }
    private fun installBarsHider() {
        val decor = window.decorView
        val hide = Runnable { hideSystemBars() }
        decor.setOnSystemUiVisibilityChangeListener { vis ->
            if (vis and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                decor.postDelayed(hide, 1200)
            }
        }
        decor.postDelayed(hide, 200)
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            c.hide(android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars())
            c.systemBarsBehavior = android.view.WindowInsetsController
                    .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    // ---------- 首页排版 ----------

    private fun isPortrait(): Boolean =
        resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_PORTRAIT

    /**
     * 首页排版固定，不再由用户拼装：
     *   横屏 —— 左列【时间 · 二维码】，右列【服务状态 · 手机状态】
     *   竖屏 —— 纵向【时间 · 二维码 · 服务状态 · 手机状态】
     * 每 5 秒只刷新数值，签名不变就不重建视图。
     */
    private fun buildHome(force: Boolean) {
        val host = homeRoot ?: return
        val sig = if (isPortrait()) "p" else "l"
        if (!force && sig == homeSig) return
        homeSig = sig

        host.removeAllViews()
        homeValues.clear()
        homeSubs.clear()
        homeDots.clear()
        lastQrUrl = null
        lastQrKey = null

        if (isPortrait()) {
            host.orientation = LinearLayout.VERTICAL
            host.gravity = Gravity.TOP
            // 站名 -> 时间 -> 二维码 -> 服务状态 -> 手机状态
            host.addView(titleCard(titleSp = 22f))
            host.addView(timeCard(clockSp = 32f, dateSp = 14f))
            host.addView(qrCard())
            host.addView(statusCard())
            host.addView(divider())
            addStatusRows(host)
        } else {
            // 横屏左右两列各自可滚：
            // 以前直接把卡片塞进 homeRoot（match_parent 的 LinearLayout），
            // 右列 7 项加上左列的二维码在 937px 里放不下，
            // 「已运行」「网络」被裁掉又滚不动。
            // 服务状态挪到右列，跟手机状态贴在一起。
            host.orientation = LinearLayout.HORIZONTAL
            host.gravity = Gravity.TOP

            val left = newColumn(1f)
            // 站名卡排在最前，跟时间卡同一种卡片样式，不再是横跨整宽的大标题栏
            left.addView(titleCard(titleSp = 26f, gap = 6))
            left.addView(timeCard(clockSp = 50f, dateSp = 18f, gap = 6))
            left.addView(qrCard(scale = 0.74f, gap = 0))
            host.addView(scrollColumn(left))

            val right = newColumn(1.15f)
            right.addView(statusCard())
            addStatusRows(right, compact = 18)
            host.addView(scrollColumn(right))
        }

        // 站名卡是新建的，先把当前标题写进去再刷数值
        applyTitle(AppPrefs.title(this))
        updateHomeValues()

        // 竖屏内容比屏幕高时，默认停在顶部看二维码；滚到底也能看到「网络」
        homeScroll?.post { homeScroll?.scrollTo(0, 0) }
    }

    /**
     * 服务状态卡：彩色圆点（绿=运行中，灰=已停止）+ 标签 + 服务地址。
     *
     * 和手机状态那几条长得一样，但它们之间默认不画分隔线：
     * 横屏时这一张紧跟着 CPU/GPU/… 一起排，视觉上是一整组。
     */
    private fun statusCard(first: Boolean = false): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Palette.cardBg(this@MainActivity)
            tag = "cardbg"
            setPadding(dp(13), dp(4), dp(13), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(3) }
        }
        // 圆点：不用文字符号，直接给一个圆形 drawable，颜色跟着服务状态变
        val dot = View(this).apply {
            background = Palette.dotBg(this@MainActivity, false)
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(8) }
        }
        card.addView(dot)
        card.addView(TextView(this).apply {
            text = "服务状态"
            setTextColor(C_LABEL)
            tag = "label"
            textSize = 12f
            letterSpacing = 0.03f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        // 地址靠右，单行、中间省略；没有网络时显示占位
        val sub = TextView(this).apply {
            text = "—"
            setTextColor(C_SUB)
            tag = "sub"
            textSize = 10f
            gravity = Gravity.END
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f
            ).apply { marginStart = dp(8) }
        }
        card.addView(sub)

        homeDots["status"] = dot
        homeSubs["status-sub"] = sub
        return card
    }
    /**
     * 手机状态整组：CPU / GPU / 内存 / 存储 / U 盘×N / 温度 / 已运行 / 网络。
     *
     * 「存储」这一项随插拔变化：只插内部存储时一行搞定，
     * 插了 U 盘就再补若干行「U 盘」，跟「存储」共用同一套卡片样式
     * （左侧标签＋副标题，右侧主值）。
     *
     * 但状态行是 buildHome 一次性建好的，插拔时重建整页会闪，
     * 所以先按 MAX_USB_ROWS 预建出足够多的行，用 GONE / VISIBLE 控制，
     * 值照常每 5 秒刷。建多了不显示不占位，比每次插拔重排整页稳。
     */
    private fun addStatusRows(parent: LinearLayout, compact: Int? = null) {
        val cp = compact
        addStatusRow(parent, "cpu", "CPU", cp)
        addStatusRow(parent, "gpu", "GPU", cp)
        addStatusRow(parent, "mem", "内存", cp)
        addStatusRow(parent, "disk", "存储 · " + diskLabelOf(StorageUtils.listRoots(this)), cp)
        // 外接盘：最多支持 MAX_USB_ROWS 个，多余的盘不显示（一般人也插不到 4 个）
        for (i in 0 until MAX_USB_ROWS) {
            addStatusRow(parent, usbKey(i), "存储 · U 盘", cp)
        }
        addStatusRow(parent, "temp", "CPU 温度", cp)
        addStatusRow(parent, "uptime", "服务已运行", cp)
        addStatusRow(parent, "usbsleep", "U 盘休眠", cp)
        addStatusRow(parent, "net", "网络", cp)
    }

    /** 第 i 个外接盘用的 key，跟单独一行「disk」区分开 */
    private fun usbKey(i: Int) = "usb$i"

    /** 把某个状态卡整行显示/隐藏；隐藏时连卡片底和间距一起收掉 */
    private fun showRow(key: String, visible: Boolean) {
        val v = homeValues[key]?.parent?.parent as? android.view.View ?: return
        v.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
    }

    /**
     * 当前插着的所有可移除卷（U 盘 / SD 卡），按路径排序保证刷新顺序稳定。
     *
     * 排除的几种：
     *  - /storage/emulated、/data/media：那是内部存储本身，已经有一行「存储」
     *  - /mnt/installer、/mnt/runtime：系统给 app 的视图层，跟 media_rw 是同一块盘
     */
    private fun usbRoots(roots: List<java.io.File>): List<java.io.File> =
        roots.filter {
            val p = it.absolutePath
            !p.startsWith("/storage/emulated") &&
                !p.startsWith("/data/media") &&
                !p.startsWith("/mnt/installer") &&
                !p.startsWith("/mnt/runtime")
        }.sortedBy { it.absolutePath }

    private fun newColumn(weight: Float): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
            setPadding(dp(6), 0, dp(6), 0)
            tag = weight
        }

    /**
     * 横屏专用：把一列内容包进可滚动的 ScrollView。
     *
     * 横屏只有 937px 高，右列 7 张状态卡会超出去；
     * 直接塞进 match_parent 的横向 LinearLayout 会被压扁或裁掉。
     * 包一层 ScrollView，每列按自己的高度铺开、各自滚动。
     */
    private fun scrollColumn(col: LinearLayout): ScrollView {
        val w = (col.tag as? Float) ?: 1f
        return ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(col, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(C_DIV)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(6)
            bottomMargin = dp(2)
        }
    }

    /**
     * 二维码卡片：白底圆角，四周留白，扫码更稳。
     *
     * @param scale 二维码边长相对 142dp 的倍数；横屏时放大，
     *              让左列高度追平右边 8 行状态卡。
     */
    private fun qrCard(scale: Float = 1f, gap: Int = 6): View {
        val side = (142 * scale).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Palette.cardBg(this@MainActivity)
            tag = "cardbg"
            // 卡片内部再加一层，二维码不贴边
            setPadding(dp(14), dp(10), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(gap) }
        }
        val qr = ImageView(this).apply {
            id = R.id.ivQrView
            setBackgroundColor(0xFFFFFFFF.toInt())
            contentDescription = "服务二维码"
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(side), dp(side))
        }
        card.addView(qr)

        // 两个二维码内容不同：一个是进网页的站点地址，一个是让别的设备连本机热点的 WIFI: 串
        val toggle = TextView(this).apply {
            id = R.id.btnQrSwitch
            text = qrLabel()
            setTextColor(C_TEXT)
            tag = "text"
            textSize = 11f
            gravity = Gravity.CENTER
            background = Palette.pillBg(this@MainActivity)
            tag = "pillbg"
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(7) }
            setOnClickListener {
                qrMode = if (qrMode == QR_SERVER) QR_HOTSPOT else QR_SERVER
                text = qrLabel()
                lastQrKey = null
                refreshUi()
            }
        }
        card.addView(toggle)
        qrSwitch = toggle

        lastQrKey = null
        return card
    }

    /** 按钮文案 = 当前展示的类型；热点模式下把读到的 SSID 一并显示，读不到就说读不到 */
    private fun qrLabel(): String = when (qrMode) {
        QR_SERVER -> "服务器二维码"
        else -> {
            val ssid = NetUtils.hotspotSsid(this)
            if (ssid.isNullOrBlank()) "热点二维码" else "热点二维码 · $ssid"
        }
    }

    /** 热点二维码的载荷：标准 WIFI: 串，微信/相机扫到能直接连 */
    private fun hotspotPayload(): String? {
        val ssid = NetUtils.hotspotSsid(this) ?: return null
        val pass = NetUtils.hotspotPassword(this)
        val sb = StringBuilder("WIFI:")
        sb.append("S:").append(escapeWifi(ssid)).append(';')
        if (!pass.isNullOrEmpty()) {
            sb.append("T:").append(wifiType(pass)).append(';')
            sb.append("P:").append(escapeWifi(pass)).append(';')
        } else {
            sb.append("T:nopass;")
        }
        sb.append("H:false;;")
        return sb.toString()
    }

    /** WIFI: 串里这几个字符必须转义 */
    private fun escapeWifi(s: String): String {
        val out = StringBuilder()
        for (c in s) {
            when (c) {
                '\\', ';', ',', ':', '"' -> out.append('\\')
            }
            out.append(c)
        }
        return out.toString()
    }

    private fun wifiType(pass: String): String = when {
        pass.length >= 12 && pass.any { !it.isLetterOrDigit() } -> "WPA2"
        else -> "WPA"
    }

    /**
     * 时间卡片：大号时钟 + 日期。
     *
     * @param clockSp 时钟字号；竖屏 26sp，横屏放大到 44sp 补高度。
     */
    private fun timeCard(clockSp: Float = 26f, dateSp: Float = 12f, gap: Int = 6): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Palette.cardBg(this@MainActivity)
            tag = "cardbg"
            setPadding(dp(14), dp(8), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(gap) }
        }
        val clock = TextView(this).apply {
            text = "--:--"
            setTextColor(C_TEXT)
            tag = "text"
            textSize = clockSp
            letterSpacing = 0.04f
            gravity = Gravity.CENTER
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }
        // 日期跟时钟一起放大，否则上下一大一小反差太怪
        val date = TextView(this).apply {
            text = ""
            setTextColor(C_SUB)
            tag = "sub"
            textSize = dateSp
            letterSpacing = 0.03f
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        }
        card.addView(clock)
        card.addView(date)
        homeValues["time"] = clock
        homeSubs["time"] = date
        return card
    }

    /** 一行「标签 —— 数值」，数值交给 callback 挂到 homeValues */
    private fun textLine(
        label: String,
        initial: String,
        bold: Boolean = false,
        onValue: (TextView) -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Palette.cardBg(this@MainActivity)
            tag = "cardbg"
            setPadding(dp(14), dp(11), dp(14), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(C_LABEL)
            tag = "label"
            textSize = 12f
            letterSpacing = 0.03f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val value = TextView(this).apply {
            text = initial
            setTextColor(C_TEXT)
            tag = "text"
            textSize = if (bold) 14f else 13f
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.END
        }
        row.addView(value)
        onValue(value)
        return row
    }

    /**
     * 状态卡：左侧标签 + 右侧主值，主值下面一行小字副标题。
     * 每项独立成卡，竖屏纵向一张张排，横屏按分栏铺。
     */
    private fun addStatusRow(
        parent: LinearLayout,
        key: String,
        label: String,
        minHeightDp: Int? = null
    ) {
        // 压缩后每项只占一行：左「标签＋小字说明」，右「主值」。
        // 之前标签一行、数值一行、说明一行，7 项在竖屏要占 1.3 屏，永远看不全。
        // minHeightDp 用于横屏：给一个固定最小高度把 8 行撑满整列，抵消左列高度。
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Palette.cardBg(this@MainActivity)
            tag = "cardbg"
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(4), dp(13), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(3) }
            if (minHeightDp != null) minimumHeight = dp(minHeightDp)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // 左边一列：标签 + 说明（说明在同一行里换行，不再单独占一行高度）
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(TextView(this).apply {
            text = label
            setTextColor(C_LABEL)
            tag = "label"
            textSize = 12f
            letterSpacing = 0.03f
        })
        val tvSub = TextView(this).apply {
            text = ""
            setTextColor(C_SUB)
            tag = "sub"
            textSize = 9.5f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        left.addView(tvSub)
        head.addView(left)

        val tvValue = TextView(this).apply {
            text = "—"
            setTextColor(C_TEXT)
            tag = "text"
            textSize = 13.5f
            gravity = Gravity.END
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
        }
        head.addView(tvValue)
        card.addView(head)

        parent.addView(card)

        homeValues[key] = tvValue
        homeSubs["$key-sub"] = tvSub
    }

    /** 每 5 秒把数值刷进首页已有的 TextView，不重建视图 */
    private fun updateHomeValues() {
        val sys = SysInfo.get(this)
        val roots = StorageUtils.listRoots(this)
        val ip = NetUtils.bestIp(this)
        val url = if (ip != null) "http://$ip:${NasService.PORT}/" else null

        homeValues["cpu"]?.text = sys.cpuUsageText()
        homeSubs["cpu-sub"]?.text = sys.cpuFreqText()
        homeValues["gpu"]?.text = sys.gpuLoadText()
        homeSubs["gpu-sub"]?.text = sys.gpuFreqText()
        homeValues["mem"]?.text = sys.memUsedText()
        homeSubs["mem-sub"]?.text = sys.memTotalText()
        homeValues["disk"]?.text = SysInfo.diskUsedText(roots)
        homeSubs["disk-sub"]?.text = SysInfo.diskTotalText(roots)

        // 外接盘行：有几块显示几行，样式跟「存储」完全同款（已用量 + 共/剩余）。
        // 预建的行比实际盘多时，多的那些 GONE 掉，不占位也不留空白。
        //
        // 空闲省电：服务端已经判断出「很久没人访问」时，就别再对 U 盘做 StatFs ——
        // 机械盘只在有人真正用的时候才被唤醒，几分钟就自动停转。
        val usbs = usbRoots(roots)
        val skipUsb = NasService.isIdle()
        for (i in 0 until MAX_USB_ROWS) {
            val d = usbs.getOrNull(i)
            showRow(usbKey(i), d != null)
            if (d != null) {
                if (skipUsb) {
                    // 保留上一次的数字，不碰磁盘
                    if (homeValues[usbKey(i)]?.text.isNullOrEmpty()) {
                        homeValues[usbKey(i)]?.text = "—"
                    }
                    if (homeSubs[usbKey(i) + "-sub"]?.text.isNullOrEmpty()) {
                        homeSubs[usbKey(i) + "-sub"]?.text =
                            d.name.ifEmpty { "U 盘" } + " · 空闲已休眠"
                    }
                } else {
                    homeValues[usbKey(i)]?.text = SysInfo.diskUsedText(d)
                    homeSubs[usbKey(i) + "-sub"]?.text =
                        d.name.ifEmpty { "U 盘" } + " · " + SysInfo.diskTotalText(d)
                }
            }
        }
        homeValues["temp"]?.text = sys.tempText()
        homeSubs["temp-sub"]?.text = sys.batteryText()

        // 「已运行」= 服务自身跑了多久，不是手机开机多久
        homeValues["uptime"]?.text = SysInfo.serviceUptimeText(NasService.startedAt)

        // U 盘休眠：每 5 秒查一次会开 root shell，代价太高，改为跟着 ticker 计数降频
        if (!skipUsb && usbTick++ % 6 == 0) {
            homeValues["usbsleep"]?.text = USBSleep.summary() ?: "—"
        } else if (skipUsb) {
            homeValues["usbsleep"]?.text = "—"
        }
        homeValues["net"]?.text = sys.netText()
        homeSubs["status-sub"]?.text = url ?: "无网络地址"

        val running = NasService.isRunning
        // 圆点用 Palette 自绘，换成 setBackground 而不是 setBackgroundResource ——
        // bg_dot_on/off 里写死了颜色，日间模式下绿点/灰点在浅底上都不对。
        homeDots["status"]?.background = Palette.dotBg(this, running)


        val now = Date()
        homeValues["time"]?.text = clockFmt.format(now)
        homeSubs["time"]?.text = dateFmt.format(now)

        updateQr(url)
    }

    // ---------- 二维码 ----------

    /**
     * 刷新二维码。不再是「url 变了才画」——现在有两种载荷，
     * 切模式时必须重画，所以用 mode + 载荷内容拼成的 key 做去重。
     */
    private fun updateQr(url: String?) {
        val payload = if (qrMode == QR_SERVER) url else hotspotPayload()
        val key = qrMode.toString() + "|" + payload
        if (key == lastQrKey) return
        lastQrKey = key

        val qr = findViewById<ImageView?>(R.id.ivQrView) ?: return
        if (payload.isNullOrEmpty()) {
            qr.setImageDrawable(null)
            qr.setBackgroundColor(C_DIV)
            return
        }
        val bmp = QrUtil.make(payload, 620)
        if (bmp == null) {
            qr.setImageDrawable(null)
            qr.setBackgroundColor(C_DIV)
        } else {
            qr.setBackgroundColor(Color.WHITE)
            qr.setImageBitmap(bmp)
        }
    }

    // ---------- 标题设置 ----------

    /**
     * 首页与网页共用同一个标题：改完立刻通知 NasService 热更新 Config.title，
     * 网页的 <title>、顶栏、页脚、zip 文件名都会跟着换，不用重启服务。
     */
    private fun showTitleDialog() {
        val input = EditText(this).apply {
            setText(AppPrefs.title(this@MainActivity))
            setSelection(text.length)
            hint = "最多 ${AppPrefs.TITLE_MAX} 个字"
            setSingleLine(true)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(0))
        }
        box.addView(TextView(this).apply {
            text = "留空则恢复默认名称（${AppPrefs.defaultTitle(this@MainActivity)}）"
            setTextColor(C_SUB)
            tag = "sub"
            textSize = 11f
            setPadding(0, 0, 0, dp(10))
        })
        box.addView(input)

        AlertDialog.Builder(this)
            .setTitle("站点名称")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                val saved = AppPrefs.saveTitle(this, input.text.toString())
                NasService.reloadTitle(this)
                applyTitle(saved)
                homeSig = null
                buildHome(true)
                refreshUi()
                Toast.makeText(this, "已改为「$saved」", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 站名卡：和「时间」同一级别的普通卡片，占满所在列的宽度。
     *
     * 以前站名是布局里横跨整宽的一条标题栏，既吃掉了 145px 高度，
     * 又让横屏左列比右列短一大截。现在挪进首页动态生成：
     * 竖屏排第一、横屏排左列第一，跟时间/二维码一起参与列高分配。
     */
    private fun titleCard(titleSp: Float = 20f, gap: Int = 6): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Palette.cardBg(this@MainActivity)
            tag = "cardbg"
            setPadding(dp(14), dp(9), dp(14), dp(9))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(gap) }
        }
        // 不再带服务地址副行：右列「服务状态」已经有地址了，这里只留站名本身
        val name = TextView(this).apply {
            text = AppPrefs.title(this@MainActivity)
            setTextColor(C_TEXT)
            tag = "text"
            textSize = titleSp
            gravity = Gravity.CENTER
            letterSpacing = 0.02f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        card.addView(name)
        homeValues["title"] = name
        return card
    }

    /** 把站名刷到首页卡片；页面重建前这个 TextView 可能是 null */
    private fun applyTitle(title: String) {
        tvTitle?.text = title
        homeValues["title"]?.text = title
    }
    // ---------- 操作 ----------

    private fun toggleServer() {
        if (NasService.isRunning) {
            startService(Intent(this, NasService::class.java).setAction(NasService.ACTION_STOP))
        } else {
            ContextCompat.startForegroundService(
                this, Intent(this, NasService::class.java).setAction(NasService.ACTION_START)
            )
        }
        btnToggle?.postDelayed({ refreshUi() }, 500)
    }

    private fun openInBrowser() {
        val ip = NetUtils.bestIp(this)
        if (ip == null) {
            Toast.makeText(this, "没有网络地址，请先连 Wi-Fi 或开热点", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://$ip:${NasService.PORT}/")))
    }

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(this, "已拥有全部文件访问权限", Toast.LENGTH_SHORT).show()
                return
            }
            try {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                )
            }
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ), 1001
            )
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1002)
            }
        }
    }

    private fun requestIgnoreBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "已忽略电池优化", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            startActivity(
                Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        }
    }

    // ---------- 账号管理 ----------

    private fun showUsersDialog() {
        val anon = Users.allowAnonymous(this)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        val tvAnon = TextView(this).apply {
            text = "匿名访问（只读）：" + if (anon) "已开启" else "已关闭"
            setTextColor(C_LABEL)
            tag = "label"
            textSize = 13f
            setPadding(0, 0, 0, dp(4))
            setOnClickListener {
                val next = !Users.allowAnonymous(this@MainActivity)
                Users.setAllowAnonymous(this@MainActivity, next)
                text = "匿名访问（只读）：" + if (next) "已开启" else "已关闭"
                NasService.reloadAuth(this@MainActivity)
                refreshUi()
                Toast.makeText(
                    this@MainActivity,
                    if (next) "匿名用户可浏览与播放" else "所有访问都需登录",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        box.addView(tvAnon)

        box.addView(TextView(this).apply {
            text = "点账号可修改，长按可删除"
            setTextColor(0xFF5A626C.toInt())
            textSize = 11f
            setPadding(0, dp(6), 0, dp(6))
        })

        val sv = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(240)
            )
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(list)
        box.addView(sv)

        fun rebuild() {
            list.removeAllViews()
            val cur = Users.list(this)
            if (cur.isEmpty()) {
                list.addView(TextView(this).apply {
                    text = "还没有账号，所有访问都是匿名只读。"
                    setTextColor(C_SUB)
                    tag = "sub"
                    textSize = 12f
                    setPadding(0, dp(8), 0, dp(8))
                })
                return
            }
            cur.sortedBy { it.name }.forEach { u ->
                list.addView(TextView(this).apply {
                    val scope = if (u.scopes.isEmpty()) "全部目录"
                    else u.scopes.joinToString(", ") { Users.shortScope(it) }
                    text = "${u.name}    ${Users.levelName(u.level)}\n$scope"
                    setTextColor(C_TEXT)
                    tag = "text"
                    textSize = 13f
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                    setOnClickListener { editUser(u.name) }
                    setOnLongClickListener {
                        confirmDelete(u.name) { rebuild() }
                        true
                    }
                })
                list.addView(View(this).apply {
                    setBackgroundColor(C_DIV)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
                    )
                })
            }
        }
        rebuild()

        AlertDialog.Builder(this)
            .setTitle("账号与权限")
            .setView(box)
            .setPositiveButton("新增账号") { _, _ -> editUser(null, ::rebuild) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun editUser(name: String?, onDone: (() -> Unit)? = null) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        val etName = EditText(this).apply {
            hint = "用户名"
            setText(name ?: "")
            isEnabled = name == null
            setTextColor(C_TEXT)
            tag = "text"
            setHintTextColor(C_SUB)
        }
        val etPass = EditText(this).apply {
            hint = if (name == null) "密码" else "新密码（留空则不修改）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(C_TEXT)
            tag = "text"
            setHintTextColor(C_SUB)
        }
        box.addView(etName)
        box.addView(etPass)

        val tvLevel = TextView(this).apply {
            val cur = name?.let { n -> Users.list(this@MainActivity).find { it.name == n }?.level }
                ?: Users.LEVEL_WRITE
            text = "权限：" + Users.levelName(cur) + "\n" + Users.levelDesc(cur)
            setTextColor(C_LABEL)
            tag = "label"
            textSize = 12f
            setPadding(0, dp(12), 0, dp(4))
            var lv = cur
            setOnClickListener {
                lv = (lv + 1) % 3
                text = "权限：" + Users.levelName(lv) + "\n" + Users.levelDesc(lv)
                tag = lv
            }
            tag = cur
        }
        box.addView(tvLevel)

        // 目录范围：空 = 不限目录，选了就限制在这些目录里读写
        val scopes = ArrayList<String>()
        name?.let { n ->
            Users.list(this).find { it.name == n }?.let { scopes.addAll(it.scopes) }
        }
        val tvScope = TextView(this).apply {
            text = scopeLabel(scopes)
            setTextColor(0xFFD6B45A.toInt())
            textSize = 12f
            setPadding(0, dp(6), 0, dp(10))
            setOnClickListener { pickScopes(scopes, this) }
        }
        box.addView(tvScope)

        AlertDialog.Builder(this)
            .setTitle(if (name == null) "新增账号" else "修改 $name")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                val n = etName.text.toString().trim()
                val p = etPass.text.toString()
                val lv = (tvLevel.tag as? Int) ?: Users.LEVEL_WRITE
                val err = Users.upsertEx(this, n, lv, p, scopes)
                if (err == null) {
                    NasService.reloadAuth(this)
                    refreshUi()
                    Toast.makeText(this, "已保存，立即生效", Toast.LENGTH_SHORT).show()
                    onDone?.invoke()
                } else {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 目录范围的一行文字说明 */
    private fun scopeLabel(scopes: List<String>): String =
        if (scopes.isEmpty()) "目录范围：全部目录（点此限制）"
        else "目录范围（点此修改）：\n" + scopes.joinToString("\n") { "· " + Users.shortScope(it) }

    /**
     * 目录选择：列出所有共享目录的顶层子目录，勾选即加入范围。
     * 不做无限层级下钻——真要精确到三级目录，用户在网页上建好目录后
     * 在这里选它的上级即可，够用且不容易点错。
     */
    private fun pickScopes(scopes: MutableList<String>, tv: TextView) {
        val roots = StorageUtils.listRoots(this)
        val labels = ArrayList<String>()
        val paths = ArrayList<String>()

        labels.add("不限目录（全盘）")
        paths.add("")

        if (scopes.isNotEmpty()) {
            labels.add("清空已选")
            paths.add("__clear__")
        }

        // 盘号跟网页端 /_status 的编号一致：内部存储不算，其余按顺序叫盘0、盘1…
        var removable = 0
        roots.forEach { root ->
            val rn = if (SysInfo.isInternal(root)) "内部存储" else "盘" + removable++
            runCatching { root.listFiles() }.getOrNull()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() }
                ?.forEach {
                    labels.add("$rn / ${it.name}")
                    paths.add(it.absolutePath)
                }
        }

        val checked = BooleanArray(labels.size) { i ->
            paths[i].isNotEmpty() && paths[i] != "__clear__" && scopes.contains(paths[i])
        }
        AlertDialog.Builder(this)
            .setTitle("选择目录范围")
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("确定") { _, _ ->
                if (checked.getOrNull(0) == true) {
                    scopes.clear()
                } else if (checked.indices.any { it > 0 && checked[it] && paths[it] == "__clear__" }) {
                    scopes.clear()
                } else {
                    scopes.clear()
                    for (i in paths.indices) {
                        if (i > 0 && checked[i] && paths[i] != "__clear__") {
                            scopes.add(paths[i])
                        }
                    }
                }
                tv.text = scopeLabel(scopes)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 音乐目录设置：勾选若干目录，存进 AppPrefs 并热更新到运行中的服务。
     *
     * 为什么要预设而不是让客户端每次自己扫：曲库几千首，客户端每次打开都遍历
     * 全盘的话机械盘要转很久、手机也跟着耗电。在这里指一次，之后 EXE 和 APK
     * 两个客户端拉同一份 /_music 清单，打开就是完整曲库。
     */
    @Suppress("unused")
    private fun showMusicDirsDialog() {
        val cur = AppPrefs.musicDirs(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(0))
        }
        box.addView(TextView(this).apply {
            text = "勾选曲库所在目录。客户端（EXE / APK）播放器会直接读取这份清单，" +
                    "不用每次打开都扫全盘。"
            setTextColor(C_SUB)
            textSize = 11f
            setPadding(0, 0, 0, dp(10))
        })

        val roots = StorageUtils.listRoots(this)
        val labels = ArrayList<String>()
        val paths = ArrayList<String>()
        var removable = 0
        roots.forEach { root ->
            val rn = if (SysInfo.isInternal(root)) "内部存储" else "盘" + removable++
            runCatching { root.listFiles() }.getOrNull()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() }
                ?.forEach {
                    labels.add("$rn / ${it.name}")
                    paths.add(it.absolutePath)
                }
        }
        // 已配的目录若不在顶层子目录里（比如多级路径），单独补成一行显示，
        // 否则用户会以为设置丢了。
        cur.forEach { d ->
            if (!paths.contains(d)) {
                labels.add(d)
                paths.add(d)
            }
        }

        if (labels.isEmpty()) {
            Toast.makeText(this, "没有可选的目录，请先插上存储", Toast.LENGTH_SHORT).show()
            return
        }

        val checked = BooleanArray(labels.size) { i -> cur.contains(paths[i]) }
        val tvInfo = TextView(this).apply {
            setTextColor(C_SUB)
            textSize = 11f
            setPadding(0, dp(10), 0, 0)
            text = musicDirsLabel(checked, paths)
        }
        box.addView(tvInfo)

        val dlg = AlertDialog.Builder(this)
            .setTitle("音乐目录")
            .setView(ScrollView(this).apply { addView(box) })
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
                tvInfo.text = musicDirsLabel(checked, paths)
            }
            .setPositiveButton("保存") { _, _ ->
                val picked = ArrayList<String>()
                for (i in paths.indices) if (checked[i]) picked.add(paths[i])
                AppPrefs.setMusicDirs(this, picked)
                NasService.reloadMusicDirs(this)
                homeSig = null
                buildHome(true)
                Toast.makeText(this, "音乐目录已更新（${picked.size} 个）", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .create()
        dlg.show()
    }

    @Suppress("unused")
    private fun musicDirsLabel(checked: BooleanArray, paths: List<String>): String {
        var n = 0
        for (i in paths.indices) if (checked[i]) n++
        return if (n == 0) "未选择：客户端会让你先设一个音乐目录"
        else "已选 $n 个目录"
    }

    /**
     * 影视目录设置：与音乐目录同一套做法，但明确允许多个同时存在。
     *
     * 多目录是重点：常见布局是「盘0/电影」「盘1/电影」各放一部分，
     * 客户端影视板块会把它们分成两块并排显示（对应 dirs 里的每一项）。
     */
    @Suppress("unused")
    private fun showVideoDirsDialog() {
        val cur = AppPrefs.videoDirs(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(0))
        }
        box.addView(TextView(this).apply {
            text = "勾选影视所在目录，可以多选 —— 盘0 和 盘1 各放一部分时，" +
                    "客户端影视板块会分开显示。刮削信息读同目录下的 .nfo 与海报图。"
            textSize = 11f
            setTextColor(C_SUB)
            setPadding(0, 0, 0, dp(10))
        })

        val roots = StorageUtils.listRoots(this)
        val labels = ArrayList<String>()
        val paths = ArrayList<String>()
        var removable = 0
        roots.forEach { root ->
            val rn = if (SysInfo.isInternal(root)) "内部存储" else "盘" + removable++
            runCatching { root.listFiles() }.getOrNull()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name.lowercase() }
                ?.forEach {
                    labels.add("$rn / ${it.name}")
                    paths.add(it.absolutePath)
                }
        }
        cur.forEach { d ->
            if (!paths.contains(d)) {
                labels.add(d)
                paths.add(d)
            }
        }

        if (labels.isEmpty()) {
            Toast.makeText(this, "没有可选的目录，请先插上存储", Toast.LENGTH_SHORT).show()
            return
        }

        val checked = BooleanArray(labels.size) { i -> cur.contains(paths[i]) }
        val tvInfo = TextView(this).apply {
            setTextColor(C_SUB)
            textSize = 11f
            setPadding(0, dp(10), 0, 0)
            text = videoDirsLabel(checked)
        }
        box.addView(tvInfo)

        AlertDialog.Builder(this)
            .setTitle("影视目录")
            .setView(ScrollView(this).apply { addView(box) })
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
                tvInfo.text = videoDirsLabel(checked)
            }
            .setPositiveButton("保存") { _, _ ->
                val picked = ArrayList<String>()
                for (i in paths.indices) if (checked[i]) picked.add(paths[i])
                AppPrefs.setVideoDirs(this, picked)
                NasService.reloadVideoDirs(this)
                homeSig = null
                buildHome(true)
                Toast.makeText(this, "影视目录已更新（${picked.size} 个）", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    @Suppress("unused")
    private fun videoDirsLabel(checked: BooleanArray): String {
        var n = 0
        for (b in checked) if (b) n++
        return if (n == 0) "未选择：客户端会让你先设一个影视目录"
        else "已选 $n 个目录，客户端会分成 $n 块显示"
    }

    private fun confirmDelete(name: String, onDone: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("删除账号")
            .setMessage("确定删除 $name ？")
            .setPositiveButton("删除") { _, _ ->
                Users.remove(this, name)
                NasService.reloadAuth(this)
                refreshUi()
                onDone()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 界面 ----------

    private fun refreshUi() {
        val running = NasService.isRunning
        btnToggle?.text = if (running) "停止服务" else "启动服务"

        val ip = NetUtils.bestIp(this)
        val url = if (ip != null) "http://$ip:${NasService.PORT}/" else null

        val roots = StorageUtils.listRoots(this)
        tvRoots?.text = if (roots.isEmpty()) {
            "未找到可共享目录（插上 U 盘后重新进入本页）"
        } else {
            roots.joinToString("\n") { "📁 " + it.absolutePath }
        }


        val users = Users.list(this)
        val anon = Users.allowAnonymous(this)
        tvUsers?.text = if (users.isEmpty()) {
            "🔐 未设账号 · 全部匿名只读"
        } else {
            "🔐 ${users.size} 个账号 · " + if (anon) "匿名可读" else "需登录"
        }

        // 横屏「系统权限」是 2×2 布局，每个按钮只占半栏宽。
        // 原来写成「存储权限 ✔」这种"文案+空格+勾"在窄栏里会被折成两行，
        // 换成"已授权 ◉"这种短词，再统一由 BtnGhostTight 的 maxLines=1 兜住。
        btnPerm?.text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()
        ) "存储权限 ◉" else "存储权限 ✕"

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        btnBattery?.text =
            if (pm.isIgnoringBatteryOptimizations(packageName)) "省电白名单 ◉" else "省电白名单 ✕"

        btnAntiBurn?.text = if (AppPrefs.antiBurn(this)) "防烧屏 ◉" else "防烧屏 ✕"
        // U 盘休眠按钮只用文案区分开关，状态摘要放进首页那一行，避免按钮文字过长被截断
        btnUsbSleep?.text = if (AppPrefs.usbSleep(this)) "U 盘休眠 ◉" else "U 盘休眠 ✕"

        buildHome(false)
        updateHomeValues()
    }

    private fun appendLog(line: String) {
        val s = timeFmt.format(Date()) + "  " + line
        logLines.addLast(s)
        while (logLines.size > 300) logLines.removeFirst()
        tvLog?.text = logLines.joinToString("\n")
        scrollLog?.post { scrollLog?.fullScroll(View.FOCUS_DOWN) }
    }

    /** 给首个共享目录取一个能看的名字。emulated 卷的 name 只是 "0"。 */
    private fun diskLabelOf(roots: List<java.io.File>): String {
        val first = roots.firstOrNull() ?: return "内部存储"
        val p = first.absolutePath
        if (p.startsWith("/storage/emulated")) return "内部存储"
        val n = first.name
        return if (n.isEmpty()) "存储" else n
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
