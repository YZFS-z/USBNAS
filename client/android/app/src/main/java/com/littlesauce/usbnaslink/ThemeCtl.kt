package com.littlesauce.usbnaslink

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * 配色的客户端侧控制中心（安卓）。
 *
 * ## 为什么需要它
 *
 * 用户要的是「App 的日夜跟同一台设备的网页端一致」。网页端的主题存在服务端
 * （`AppPrefs` 的 `theme` 键，`/_theme` 端点读写），所以安卓这边要做三件事：
 *
 *  1. **启动就上色**：趁早读本地缓存（[Prefs.theme]）定下 `Palette` 的模式，
 *     避免界面先画成夜间再闪成日间。
 *  2. **联网纠正**：后台问一次 `/_theme`，服务端说的才算数（可能别人在网页上
 *     刚刚切过）。拿到之后写回本地缓存。
 *  3. **切了就同步**：用户在 App 里点「日间/夜间」，先本地立刻重涂（不等网络），
 *     再把新值 POST 给服务端，让网页端下次打开也是这个色。
 *
 * ## 为什么颜色是「全局可变」而不是每个 Activity 各管一套
 *
 * `Palette` 的取值本来就是全局的（137 处调用点、8 个界面文件），改成参数传递
 * 会把每一处都改一遍，而且一定会漏。集中成一个 [Palette.mode]，配合
 * [rebuild] 让当前 Activity 重建一次，代价最小、也不容易漏。
 *
 * ## 重建而不是「遍历 View 改色」
 *
 * 因为界面全是代码手搭的（没有 XML、没有主题属性），遍历 View 树改色需要知道
 * 每个控件的语义（这是标题、那是次文字），等于把建界面的逻辑抄一遍。直接
 * `recreate()` 让 `onCreate` 用新色重搭一遍最可靠 —— 代价是丢掉滚动位置，
 * 而滚动位置在 `onSaveInstanceState` 里本来就没存，无所谓。
 */
object ThemeCtl {

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "usbnas-theme").apply { isDaemon = true }
    }
    private val ui = Handler(Looper.getMainLooper())

    /** 上一次重建的时间，用来挡住「同一瞬间被要求重建两次」 */
    @Volatile
    private var lastRebuild = 0L

    /** 两次重建之间的最小间隔。比一帧长、比一次网络往返短。 */
    private const val REBUILD_GAP_MS = 800L

    /** 上次问过服务端的时间，用于节流（避免每次 onResume 都发请求） */
    @Volatile
    private var lastSync = 0L

    /** 两次自动同步之间的最小间隔 */
    private const val MIN_GAP_MS = 20_000L

    /** 启动时调：先用本地缓存把 `Palette` 定下来。**必须在 `setContentView` 之前**调。 */
    fun prime(ctx: Context) {
        Palette.setFromServerTheme(Prefs(ctx).theme)
    }

    /**
     * 拉一次服务端主题并纠正本地。
     *
     * @param force true 时忽略节流（用户手动点刷新 / 刚登录完）
     */
    fun sync(ctx: Context, base: String?, ck: String?, force: Boolean = false, onDone: (() -> Unit)? = null) {
        // ⚠️ 这两条提前返回**绝对不能**回调 onDone。
        //
        // 调用方传进来的 onDone 是 `{ recreate() }`（为了用新色重搭界面）。
        // 而 recreate() 会立刻跑一遍 onCreate → onResume → 又进到这里。
        // 如果「没连上」和「节流中」这两种「什么都没发生」的情况也回调，
        // 就变成了：onResume → sync → 提前返回 → recreate → onResume → …
        // 实测一次 onResume 能打出 10 次 performCreate（间隔约 30ms），
        // 用户看到的就是画面一直在闪。节流越严格，闪得越快。
        //
        // 语义应当统一为：onDone 表示「颜色真的变了，你该重画了」，
        // 而不是「sync 这个函数回来了」。
        if (base.isNullOrEmpty()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSync < MIN_GAP_MS) return
        lastSync = now
        val p = Prefs(ctx)
        io.execute {
            val st = ThemeLib.fetch(base, ck, 6000)
            ui.post {
                // 请求失败就当没这回事：本地缓存已经上过色了（prime），
                // 服务端这一趟只用来纠偏，纠不了不必重建界面。
                if (!st.ok) return@post
                val before = Palette.mode
                Palette.setFromServerTheme(st.mode)
                p.theme = st.mode
                if (Palette.mode != before) onDone?.invoke()
            }
        }
    }

    /**
     * 切换日夜（App 内主动点的）。
     *
     * 立刻本地重涂并重建，然后把新值推给服务端。服务端拒绝（未登录 / 网络断）
     * 也保留本地选择 —— 用户点一下就看到变化比「点了没反应」重要。
     *
     * 这里重建之后 `onResume` 还会再走一次 [sync]，但那时 `Palette.mode`
     * 已经等于服务端即将返回的值，`Palette.mode != before` 不成立，
     * 不会再触发第二次重建。
     */
    fun toggle(activity: Activity, base: String?, ck: String?, onMsg: (String) -> Unit) {
        val next = if (Palette.isDay()) ThemeLib.DARK else ThemeLib.LIGHT
        Palette.setFromServerTheme(next)
        Prefs(activity).theme = next
        // 手动切过就把节流窗口重置掉：否则紧接着的 onResume 会拿
        // 「刚才那一趟 sync」当挡箭牌，服务端改了也不会再对账。
        lastSync = 0L
        val label = ThemeLib.name(next)
        io.execute {
            // base 为空说明这台手机压根没连上服务端；用可辨识的哨兵串而不是中文句子，
            // 免得翻译时把「判断条件」也翻掉（branch 键不能是人话）。
            val err = if (base.isNullOrEmpty()) NO_BASE else ThemeLib.save(base, ck, next, 6000)
            ui.post {
                val msg = when (err) {
                    null -> activity.getString(R.string.theme_switched, label)
                    NO_BASE -> activity.getString(R.string.theme_local_only, label)
                    else -> activity.getString(R.string.theme_switched_fail, label, err)
                }
                onMsg(msg)
                rebuild(activity)
            }
        }
    }

    private const val NO_BASE = "no-base"

    /**
     * 让界面用新色重搭一次。
     *
     * 不能用 `startActivity(Intent(this, LibraryActivity))` 再 `finish()` 的方式重启：
     * 那样会新开一个 Activity 实例压在同一个 task 里，返回键就会回到旧的那个
     * （旧实例还留着旧色），而且从 ScanActivity 过来时 task 里已经有一层
     * ScanActivity，一来一回点几次就会看到「点主题 → 界面退回到扫描页」。
     *
     * 正确做法是 `recreate()`：它复用同一个 ActivityRecord，栈不变、返回键行为不变，
     * 只是把 onCreate 再跑一遍 —— 而 onCreate 里 `ThemeCtl.prime()` 会读到新色。
     */
    fun rebuild(activity: Activity) {
        // 已经销毁（用户已经按返回 / 被系统回收）就别再重建了：recreate 一个
        // finishing 的 Activity 在部分 ROM 上会跳回上一个页面。
        if (activity.isFinishing || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed)) return
        // 最后一道闸：不管上游是谁、因为什么要求重建，800ms 内只认一次。
        // 真正的原因是上一条 sync() 把「什么都没发生」也回调成了「该重建了」，
        // 那条已经改掉了；这里留一道兜底 —— 配色切换本来也不需要更快的响应。
        val now = System.currentTimeMillis()
        if (now - lastRebuild < REBUILD_GAP_MS) return
        lastRebuild = now
        activity.recreate()
    }

    /**
     * 按钮文案：点一下能切到哪。与网页端 `themeButton` 的语义一致。
     *
     * 这里故意返回资源 id 而不是字符串：本对象没有 Context，
     * 而文案只在建 View 的那一刻用得上，让调用方自己 `getString` 最省事。
     */
    @androidx.annotation.StringRes
    fun labelRes(): Int = if (Palette.isDay()) R.string.lib_theme_to_night else R.string.lib_theme_to_day
}
