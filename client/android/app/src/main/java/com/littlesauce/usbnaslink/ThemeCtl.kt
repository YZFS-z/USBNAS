package com.littlesauce.usbnaslink

import android.app.Activity
import android.content.Context
import android.content.Intent
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
        if (base.isNullOrEmpty()) {
            onDone?.invoke()
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastSync < MIN_GAP_MS) {
            onDone?.invoke()
            return
        }
        lastSync = now
        val p = Prefs(ctx)
        io.execute {
            val st = ThemeLib.fetch(base, ck, 6000)
            ui.post {
                if (st.ok) {
                    val before = Palette.mode
                    Palette.setFromServerTheme(st.mode)
                    p.theme = st.mode
                    // 本地模式变了才叫重建；否则每次 onResume 报一次都重建就太吵了
                    if (Palette.mode != before) onDone?.invoke()
                } else {
                    onDone?.invoke()
                }
            }
        }
    }

    /**
     * 切换日夜（App 内主动点的）。
     *
     * 立刻本地重涂并重建，然后把新值推给服务端。服务端拒绝（未登录 / 网络断）
     * 也保留本地选择 —— 用户点一下就看到变化比「点了没反应」重要。
     */
    fun toggle(activity: Activity, base: String?, ck: String?, onMsg: (String) -> Unit) {
        val next = if (Palette.isDay()) ThemeLib.DARK else ThemeLib.LIGHT
        Palette.setFromServerTheme(next)
        Prefs(activity).theme = next
        val label = ThemeLib.name(next)
        io.execute {
            val err = if (base.isNullOrEmpty()) "没连接，只改了这台手机" else ThemeLib.save(base, ck, next, 6000)
            ui.post {
                onMsg(if (err == null) "已切到$label（三端同步）" else "已切到$label · 服务端没存上：$err")
                rebuild(activity)
            }
        }
    }

    /** 让界面用新色重搭一次。 */
    fun rebuild(activity: Activity) {
        val it = activity.intent
        val again = Intent(it).apply {
            // 重建要带上原来的参数，否则打开网页页签的那几个 Activity 回来会是一张白页
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        activity.startActivity(again)
        activity.overridePendingTransition(0, 0)
        activity.finish()
        activity.overridePendingTransition(0, 0)
    }

    /** 按钮文案：点一下能切到哪。与网页端 `themeButton` 的语义一致。 */
    fun label(): String = if (Palette.isDay()) "夜间" else "日间"
}
