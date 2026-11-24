package com.littlesauce.usbnaslink

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * 沉浸式全屏（播放页 / 阅读页 / PDF 页 / 视频页共用）。
 *
 * 这些 Activity 全是代码手搭布局、没有布局 XML，所以不在 themes.xml 里加
 * `windowFullscreen`，而是按窗口 API 直接要全屏。
 *
 * ## 两段式（踩过的两个坑）
 *
 * **坑 1：`onCreate` 里碰 `insetsController` 会直接崩。**
 * `setContentView` 之前 `window.decorView` 还是 null：
 * `NullPointerException: Attempt to invoke virtual method 'android.view.WindowInsetsController
 * com.android.internal.policy.DecorView.getWindowInsetsController()' on a null object reference`
 * 栈是 `PhoneWindow.getInsetsController(PhoneWindow.java:3937)` → `Immersive.enter(Immersive.kt:36)`
 * → `PlayerActivity.onCreate`。所以拆成 [prepare]（只设 flag）与 [enter]（真正隐藏）。
 *
 * **坑 2：`FLAG_LAYOUT_NO_LIMITS` 会把 insets 全清成 0，`hide()` 就失效了。**
 * 真机实测：同时加 `LAYOUT_NO_LIMITS` 和 `setDecorFitsSystemWindows(false)` 之后，
 * `dumpsys window` 里 `Requested visibilities: ITYPE_STATUS_BAR: invisible` 是下发了，
 * 但窗口仍然是 `Frames: frame=[0,104][1080,2400]`、截图 0–103 行纯黑 —— 窗口没往上铺。
 * 因为 NO_LIMITS 让窗口「不受 insets 约束」，系统干脆不再为它计算 status bar inset，
 * `hide()` 于是没有可隐藏的对象。正确做法是**只**用 `setDecorFitsSystemWindows(false)`
 * （内容延伸到系统栏区域）+ `insetsController.hide()`（把栏本身藏掉），两者配合才铺满。
 *
 * ## 为什么还要在 onWindowFocusChanged 里再要一次
 *
 * 下拉通知栏、来电、切到桌面再回来，系统都会把状态栏还回来；只设一次的话第二次进来
 * 就又不是全屏。重新拿到焦点时再要一次，才是「一直全屏」。
 */
object Immersive {

    /** 第一段：onCreate 最开头调。只设窗口 flag，不碰 DecorView。 */
    fun prepare(activity: Activity) {
        val win = activity.window ?: return
        // 这台机器（红米 2106118C）是挖孔屏：`DisplayDeviceInfo` 里写着
        // `cutout DisplayCutout{insets=Rect(0, 104 - 0, 0)}` —— 顶部那 104px 是**挖孔安全区**，
        // 不是 statusBar。只 hide(statusBars) 是盖不住的（实测 hide 后窗口仍是
        // `Frames: frame=[0,104][1080,2400]`、截图 0–103 行纯黑）。
        // 必须让窗口允许画到挖孔区域里，才铺得满：
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            win.attributes = win.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // 系统栏颜色 = 页面背景色，靠「透明 + 内容延伸」实现，不靠涂同色号：
        // 页面换背景色（比如以后加浅色阅读主题）时，系统栏会自己跟着变。
        // themes.xml 里已经设了一遍，这里再设一次是因为部分 ROM（MIUI）会把
        // 主题里的 statusBarColor 在 inflate 时覆盖回主题的 colorPrimary。
        win.statusBarColor = android.graphics.Color.TRANSPARENT
        win.navigationBarColor = android.graphics.Color.TRANSPARENT
        win.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 内容延伸到状态栏/导航栏底下 —— 这是「窗口铺满」的那一步
            win.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            win.decorView?.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        }
    }

    /**
     * 第二段：setContentView 之后 / onWindowFocusChanged(true) / onConfigurationChanged 里调。
     * 真正把状态栏和导航栏藏掉。
     */
    fun enter(activity: Activity) {
        val win = activity.window ?: return
        // onCreate 早期 decorView 可能还是 null，此时静默返回，等 onWindowFocusChanged 那次再来
        val decor = win.decorView ?: return
        // 图标明暗：本 App 所有页面都是深底（#0E1116 / #0F141A / #171B22），
        // 所以状态栏/导航栏图标要亮色。若以后加浅色主题，把这两个改成 true 即可。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = decor.systemUiVisibility and
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = decor.systemUiVisibility and
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            win.setDecorFitsSystemWindows(false)
            val ctl = win.insetsController ?: decor.windowInsetsController ?: return
            ctl.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            // 从边缘划出才临时出现，松手自动收回 —— 真沉浸，不是把状态栏涂黑
            ctl.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = decor.systemUiVisibility or (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    /**
     * 「系统栏跟页面背景一致」—— 给**非全屏页**（扫描页 / 媒体库 / 管理页）用。
     *
     * 这几页要显示状态栏（时间、电量有用），只是不希望它顶一条蓝色或黑色横条。
     * 做法是把状态栏和手势栏都设成透明，再让页面内容延伸到栏底下
     * （`setDecorFitsSystemWindows(false)` + 根布局自己加 padding），
     * 于是栏里看到的就是页面自己的背景色。
     *
     * 真机实测（MIUI 14 / Android 13）：只在 `themes.xml` 里写
     * `<item name="android:statusBarColor">@android:color/transparent</item>` 不够，
     * inflate 之后 `statusBarColor` 会被主题的 `colorPrimary`（#2F6FED）覆盖回来 ——
     * 截图 y=0..103 仍是黑的、y=2330 是 `#676C74` 灰条。所以要在代码里再设一次。
     *
     * @param bgColor 页面根背景色；本 App 都是深底，图标固定亮色。以后加浅色主题时
     *                这里按 `bgColor` 的亮度决定 LIGHT_STATUS_BAR 即可。
     */
    fun tintBars(activity: Activity, bgColor: Int) {
        val win = activity.window ?: return
        val decor = win.decorView ?: return
        // ---- 铺到挖孔里 ----
        // 默认情况下系统不许窗口画到 cutout 区域，横屏时屏幕左侧会空出 104px
        // （`mAppBounds=Rect(104, 0 - 2400, 1036)`、`Requested w=2296`）。
        // 把这 104px 让出去之后，窗口 frame 才是真正的 [0,0][2400,1080]，
        // 状态栏底下看到的才是页面自己的底色，而不是系统画的一条黑边。
        //
        // SHORT_EDGES 只在「短边」允许延伸，正好是竖屏的顶边和横屏的左边；
        // 不用 ALWAYS 是因为转屏动画中途 ALWAYS 会连凹口侧边一起吞掉内容。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            win.attributes = win.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        win.statusBarColor = android.graphics.Color.TRANSPARENT
        win.navigationBarColor = android.graphics.Color.TRANSPARENT
        win.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 安卓 10 起系统会给「浅色背景上的导航栏」自动加一层半透明遮罩
            // （`isNavigationBarContrastEnforced`，默认 true）。深色页面上那层遮罩就是
            // 真机截图里 y=2350/2390 的 `#014CA0` / `#1A6D9D` —— 不是导航栏颜色，是遮罩。
            // 关掉它，导航栏才真的透出页面底色。
            win.isNavigationBarContrastEnforced = false
            win.isStatusBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            win.navigationBarDividerColor = android.graphics.Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            win.setDecorFitsSystemWindows(false)
        }
        // 深底 → 图标亮色（清掉 LIGHT_* 位）；浅底 → 置上 LIGHT_* 位
        val light = luminance(bgColor) > 0.6f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = if (light)
                decor.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            else
                decor.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = if (light)
                decor.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            else
                decor.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
    }

    /** 感知亮度（0..1），sRGB 归一化后按 0.2126/0.7152/0.0722 加权 */
    private fun luminance(color: Int): Float {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /**
     * 把系统栏让出来的那部分空间，转成根布局需要补的 padding。
     *
     * ## 为什么必须补
     *
     * 用了 `setDecorFitsSystemWindows(false)` 之后，窗口铺满整块屏（含挖孔区），
     * 但**内容坐标系也跟着挪到了 (0,0)**。原来窗口从 y=104 开始，现在从 y=0 开始 ——
     * 顶栏第一行会被状态栏的时间/电量压住，横向时最左边 104px 被挖孔吃掉。
     *
     * 所以「铺满」必须配「补 padding」：铺满是让背景色铺过去，补 padding 是让
     * 内容回到安全区。两者缺一个就是要么一条黑边、要么文字被挡。
     *
     * ## 回来的值
     *
     * `systemBars` —— 状态栏 + 手势栏（不含挖孔）。
     * `displayCutout` —— 挖孔/刘海本身。`safe` 是它相对**已消费 inset** 的剩余部分，
     * 服务端给的 104 在 `systemBars.top` 里已经算进去了，所以取 `safe` 不会重复计算；
     * 只有在「横屏挖孔在左、状态栏在上」这种组合下，`safe.left` 才会有非零值 ——
     * 那正是原来空着的那一条。
     *
     * @return `IntArray(4)` = left / top / right / bottom（像素）
     */
    fun paddings(activity: Activity): IntArray {
        val decor = activity.window?.decorView ?: return IntArray(4)
        val ins = decor.rootWindowInsets ?: return IntArray(4)
        var l = 0
        var t = 0
        var r = 0
        var b = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = ins.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            l = bars.left; t = bars.top; r = bars.right; b = bars.bottom
            val safe = ins.getInsets(WindowInsets.Type.displayCutout()).let {
                // getInsets(displayCutout) 在不同 ROM 上语义不一：MIUI 上返回的是
                // 全量 cutout inset（含已被 systemBar 覆盖的部分）。取 max 而不是相加，
                // 避免竖屏时 top 被算成 104 + 104。
                it
            }
            l = maxOf(l, safe.left)
            r = maxOf(r, safe.right)
            b = maxOf(b, safe.bottom)
        } else {
            @Suppress("DEPRECATION")
            run {
                l = ins.systemWindowInsetLeft
                t = ins.systemWindowInsetTop
                r = ins.systemWindowInsetRight
                b = ins.systemWindowInsetBottom
            }
        }
        return intArrayOf(l, t, r, b)
    }

    /**
     * 把 [paddings] 直接贴到某个 View 上（通常传顶栏或整个内容容器）。
     *
     * 用 `setOnApplyWindowInsetsListener` 而不是「读一次就设死」：转屏、分屏、
     * 手势条隐藏/出现都会重发 inset，读一次会留在旧值上。返回 `CONSUMED` 是
     * 因为 padding 已经由我们自己处理，不再往子 View 传，不然子 View 会
     * 再叠一层 padding，越套越深。
     */
    fun padBySystemBars(view: View) {
        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bar = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                v.setPadding(bar.left, bar.top, bar.right, bar.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
            }
            insets
        }
        // 已经分发过一次的 inset 不会因为新挂 listener 而重发，手动请求一次
        view.requestApplyInsets()
    }

    /**
     * 退出沉浸式：目前没有调用点，留着是为了以后做「长按顶栏看状态栏」之类的开关时
     * 不用把两套 API 再写一遍。
     */
    fun exit(activity: Activity) {
        val win = activity.window ?: return
        win.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            win.setDecorFitsSystemWindows(true)
            win.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            @Suppress("DEPRECATION")
            win.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }
}
