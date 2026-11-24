package com.littlesauce.usbnaslink

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Space
import android.widget.TextView

/**
 * 客户端唯一的调色板 —— 所有页面共用的颜色常量与「把控件画成这套颜色」的工厂。
 *
 * 为什么需要这个文件：
 *   Android 的 `Button` 默认套主题的 Material 背景，在深色页面上会画出 #D6D7D7 的
 *   灰底 + 深灰描边，于是首页顶栏那两个按钮和页签看起来跟播放页、图书页完全不像
 *   一家人。之前每个 Activity 各自写颜色字面量，同类控件也不可能保证一致。
 *
 * 取色依据是服务端网页的深色主题（`HttpServer` 里 `body{background:#0e1116}`、
 * `.root{background:#171b22;border:1px solid #232a34}`、主色 `#2f80ed`），
 * 桌面端 `Theme.java` 也是同一套。三端同色，视觉才是一件事。
 */
object Palette {

    const val MODE_NIGHT = "night"
    const val MODE_DAY = "day"

    /**
     * 当前配色模式：`night` / `day`。
     *
     * 为什么改用「可重涂的变量」而不是常量：
     *   用户要求 App 的日夜跟随同一台设备的网页端。主题是运行时才知道的
     *   （要从服务端 `/_theme` 问），而 `const val` 在编译期就固定成深色。
     *   改成 `get()` 之后，取值变成「读当前模式再选表」——
     *   全工程 137 处 `Palette.XXX` 的写法一行都不用改。
     *
     * 代价：颜色不能再出现在 `when` 的分支常量位置或注解参数里（本项目没有这种用法）。
     */
    @Volatile
    @JvmStatic
    var mode: String = MODE_NIGHT
        private set

    /** 一套色值。字段名与旧常量一一对应，方便对照改。 */
    class Skin(
        val BG: Int, val CARD: Int, val LINE: Int, val BTN: Int,
        val TAB_IDLE: Int, val FIELD: Int,
        val FG: Int, val FG2: Int, val DIM: Int, val DIM2: Int, val FAINT: Int,
        val ACCENT: Int, val ACCENT_HI: Int, val ACCENT_SOFT: Int,
        val WARN: Int, val WARN_HI: Int, val OK: Int,
        /** 选中页签的衬底，带透明度 */
        val ACCENT_WASH: Int
    )

    /**
     * 夜间：与网页端深色主题同值（`#0e1116` 底 / `#171b22` 卡 / `#232a34` 线 /
     * `#2f80ed` 主色）。客户端原来那套就是它，只是底略亮一点（`#0F141A`），
     * 因为手机上纯 `#0e1116` 和 AMOLED 黑贴在一起分不清边界。
     */
    private val NIGHT = Skin(
        BG = 0xFF0F141A.toInt(),
        CARD = 0xFF161B22.toInt(),
        LINE = 0xFF232A34.toInt(),
        BTN = 0xFF1B222C.toInt(),
        TAB_IDLE = 0xFF141A21.toInt(),
        FIELD = 0xFF11161D.toInt(),
        FG = 0xFFE6E9EF.toInt(),
        FG2 = 0xFFD8DEE6.toInt(),
        DIM = 0xFF9AA4B2.toInt(),
        DIM2 = 0xFF7D8590.toInt(),
        FAINT = 0xFF5C6470.toInt(),
        ACCENT = 0xFF2F6FED.toInt(),
        ACCENT_HI = 0xFF5AA9FF.toInt(),
        ACCENT_SOFT = 0xFF8AB4F8.toInt(),
        WARN = 0xFFC0392B.toInt(),
        WARN_HI = 0xFFFF8A94.toInt(),
        OK = 0xFF22A45D.toInt(),
        ACCENT_WASH = 0x1F2F80ED
    )

    /**
     * 日间：与网页端浅色主题对齐（`LIGHT_CSS` 里的 `#f2f4f7` 底 / `#ffffff` 卡 /
     * `#e3e7ec` 线 / `#1b1f26` 主字 / `#57606a` 次字 / `#2f80ed` 主色）。
     *
     * 两处刻意的偏离：
     *   - 卡片用纯白，而底用 `#f2f4f7`：手机上纯白底 + 纯白卡会失去层次，
     *     网页端也是这么配的。
     *   - 主色不动（`#2f80ed`）：品牌色日夜不该变，变的只有底和字。
     */
    private val DAY = Skin(
        BG = 0xFFF2F4F7.toInt(),
        CARD = 0xFFFFFFFF.toInt(),
        LINE = 0xFFE3E7EC.toInt(),
        BTN = 0xFFEDF0F4.toInt(),
        TAB_IDLE = 0xFFE9EDF2.toInt(),
        FIELD = 0xFFFAFBFC.toInt(),
        FG = 0xFF1B1F26.toInt(),
        FG2 = 0xFF2C323B.toInt(),
        DIM = 0xFF57606A.toInt(),
        DIM2 = 0xFF6B7480.toInt(),
        FAINT = 0xFF98A1AC.toInt(),
        ACCENT = 0xFF2F80ED.toInt(),
        ACCENT_HI = 0xFF1657B8.toInt(),   // 浅底上 `#5aa9ff` 太淡，压深一档才够对比
        ACCENT_SOFT = 0xFF2463B8.toInt(),
        WARN = 0xFFC0392B.toInt(),
        WARN_HI = 0xFFB3261E.toInt(),     // 浅底不能用 `#ff8a94`
        OK = 0xFF1C7A48.toInt(),
        ACCENT_WASH = 0x1A2F80ED
    )

    private val skin: Skin get() = if (mode == MODE_DAY) DAY else NIGHT

    /** 切换配色。调用方负责重建界面（颜色是取值时算的，不会自己刷到已建好的 View 上）。 */
    @JvmStatic
    fun setMode(m: String) {
        mode = if (m == MODE_DAY) MODE_DAY else MODE_NIGHT
    }

    @JvmStatic
    fun isDay(): Boolean = mode == MODE_DAY

    /** 由服务端主题（dark/light/ink）映射到客户端两态：只有 light 算日间。 */
    @JvmStatic
    fun setFromServerTheme(theme: String?) {
        setMode(if ("light" == theme) MODE_DAY else MODE_NIGHT)
    }

    // ---- 底 ----
    /** 页面底色。所有页面（含寻找 NAS）的 root 都是它。 */
    val BG: Int get() = skin.BG
    /** 卡片 / 列表块 / 输入框。 */
    val CARD: Int get() = skin.CARD
    /** 卡片描边、分割线、目录树行分隔。 */
    val LINE: Int get() = skin.LINE
    /** 按钮静默底（比卡片再深一点，压在卡片上时不糊）。 */
    val BTN: Int get() = skin.BTN
    /** 页签 / 分段控件的未选中底。 */
    val TAB_IDLE: Int get() = skin.TAB_IDLE
    /** 输入框底（比卡片略深，才能和卡片区分）。 */
    val FIELD: Int get() = skin.FIELD

    // ---- 字 ----
    /** 主文字（标题、正文）。 */
    val FG: Int get() = skin.FG
    /** 次文字（列表标题、按钮字）。 */
    val FG2: Int get() = skin.FG2
    /** 弱文字（计数、按钮说明）。 */
    val DIM: Int get() = skin.DIM
    /** 更弱（提示行）。 */
    val DIM2: Int get() = skin.DIM2
    /** 最弱（占位、hint）。 */
    val FAINT: Int get() = skin.FAINT

    // ---- 主色与状态 ----
    /** 主题蓝，与网页 #2f80ed、桌面 Theme.ACCENT 同值。 */
    val ACCENT: Int get() = skin.ACCENT
    /** 主色的亮版，链接与选中页签文字。 */
    val ACCENT_HI: Int get() = skin.ACCENT_HI
    /** 主色的淡版，顶栏文字按钮。 */
    val ACCENT_SOFT: Int get() = skin.ACCENT_SOFT
    /** 危险（失败、删除）。 */
    val WARN: Int get() = skin.WARN
    /** 危险文字的浅色版（深底上可读）。 */
    val WARN_HI: Int get() = skin.WARN_HI
    /** 成功 / 已连接。 */
    val OK: Int get() = skin.OK

    /** 主色的 12% 透明底，选中页签的衬底。 */
    val ACCENT_WASH: Int get() = skin.ACCENT_WASH

    // ------------------------------------------------------------ 工厂

    /** 圆角实心块。[radiusDp] 单位 dp；[stroke] 为透明表示不描边。 */
    @JvmOverloads
    fun round(ctx: Context, fill: Int, radiusDp: Float = 9f,
              stroke: Int = Color.TRANSPARENT, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setColor(fill)
            if (stroke != Color.TRANSPARENT) setStroke(dp(ctx, strokeDp), stroke)
        }

    /** 胶囊（状态点、徽标）。 */
    fun pill(ctx: Context, fill: Int, sizeDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setSize(dp(ctx, sizeDp), dp(ctx, sizeDp))
        }

    /**
     * 「只有字、没有块」的按钮：顶栏那种。
     *
     * 必须显式 `stateListAnimator = null` 与 `backgroundTintList = null`，
     * 否则 Material 主题还会叠一层水波纹色和阴影，跟自绘背景打架。
     */
    @JvmOverloads
    fun ghost(ctx: Context, text: String, color: Int = ACCENT_SOFT,
              fill: Int = BTN): Button =
        Button(ctx).apply {
            this.text = text
            isAllCaps = false
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            stripMaterial(this)
            setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
            background = round(ctx, fill, 8f)
        }

    /** 实心块按钮（主色底）。主动作专用，一屏最多一个。 */
    @JvmOverloads
    fun solid(ctx: Context, text: String, fill: Int = ACCENT,
              fg: Int = Color.WHITE): Button =
        Button(ctx).apply {
            this.text = text
            isAllCaps = false
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            stripMaterial(this)
            setPadding(dp(ctx, 16), dp(ctx, 9), dp(ctx, 16), dp(ctx, 9))
            background = round(ctx, fill, 9f)
        }

    /** 描边块按钮（深底 + 细线）。次要动作用。 */
    fun outline(ctx: Context, text: String, fg: Int = FG2): Button =
        Button(ctx).apply {
            this.text = text
            isAllCaps = false
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            stripMaterial(this)
            setPadding(dp(ctx, 12), dp(ctx, 7), dp(ctx, 12), dp(ctx, 7))
            background = round(ctx, BTN, 9f, LINE, 1f)
        }

    /** 页签。选中态「主色淡底 + 主色字 + 主色描边」，未选中「近底灰 + 弱字」。 */
    fun tab(ctx: Context, text: String, on: Boolean): TextView =
        TextView(ctx).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isClickable = true
            isFocusable = true
            paintTab(this, on)
        }

    /** 把已有的 [TextView]（含 [Button]）刷成页签的选中/未选中态。 */
    fun paintTab(v: TextView, on: Boolean) {
        v.setTextColor(if (on) ACCENT_HI else DIM)
        v.typeface = Typeface.create(
            Typeface.DEFAULT, if (on) Typeface.BOLD else Typeface.NORMAL)
        v.background = round(v.context, if (on) ACCENT_WASH else TAB_IDLE, 9f,
            if (on) ACCENT else Color.TRANSPARENT, 1f)
    }

    /** 深色输入框：圆角 + 内边距 + 去掉 Material 下划线。 */
    fun field(ctx: Context, hintText: String): EditText =
        EditText(ctx).apply {
            hint = hintText
            setTextColor(FG)
            setHintTextColor(FAINT)
            textSize = 14f
            setSingleLine()
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            backgroundTintList = null
            background = round(ctx, FIELD, 9f, LINE, 1f)
        }

    /** 卡片容器：圆角 + 描边 + 内边距。 */
    fun card(ctx: Context): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
            background = round(ctx, CARD, 12f, LINE, 1f)
        }

    /** 卡片标题。 */
    fun cardTitle(ctx: Context, text: String): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
        }

    /** 卡片里的说明文字。 */
    fun note(ctx: Context, text: String, color: Int = DIM2, sp: Float = 11f): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setLineSpacing(dp(ctx, 3).toFloat(), 1f)
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
        }

    /** 细进度条（页面顶部 3dp 那条）。 */
    fun hairline(ctx: Context): ProgressBar =
        ProgressBar(ctx).apply {
            isIndeterminate = true
            progressTintList = tint(ACCENT)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 3))
        }

    /** 竖直留白。 */
    fun gap(ctx: Context, dp: Int): View =
        Space(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(1, this@Palette.dp(ctx, dp))
        }

    /** 把 [Material] 那层默认背景/阴影/最小宽度从 Button 上摘干净。 */
    fun stripMaterial(b: Button) {
        b.backgroundTintList = null
        b.stateListAnimator = null
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = 0
        b.minimumHeight = 0
    }

    /** dp → px（整数）。 */
    fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    /** dp → px（小数）。 */
    fun dp(ctx: Context, v: Float): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    /** 需要着色时用（CheckBox 方块、ProgressBar）。 */
    fun tint(color: Int): ColorStateList = ColorStateList.valueOf(color)
}
