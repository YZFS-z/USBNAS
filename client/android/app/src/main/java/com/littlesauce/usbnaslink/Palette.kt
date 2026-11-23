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

    // ---- 底 ----
    /** 页面底色。所有页面（含寻找 NAS）的 root 都是它。 */
    const val BG = 0xFF0F141A.toInt()
    /** 卡片 / 列表块 / 输入框。 */
    const val CARD = 0xFF161B22.toInt()
    /** 卡片描边、分割线、目录树行分隔。 */
    const val LINE = 0xFF232A34.toInt()
    /** 按钮静默底（比卡片再深一点，压在卡片上时不糊）。 */
    const val BTN = 0xFF1B222C.toInt()
    /** 页签 / 分段控件的未选中底。 */
    const val TAB_IDLE = 0xFF141A21.toInt()
    /** 输入框底（比卡片略深，才能和卡片区分）。 */
    const val FIELD = 0xFF11161D.toInt()

    // ---- 字 ----
    /** 主文字（标题、正文）。 */
    const val FG = 0xFFE6E9EF.toInt()
    /** 次文字（列表标题、按钮字）。 */
    const val FG2 = 0xFFD8DEE6.toInt()
    /** 弱文字（计数、按钮说明）。 */
    const val DIM = 0xFF9AA4B2.toInt()
    /** 更弱（提示行）。 */
    const val DIM2 = 0xFF7D8590.toInt()
    /** 最弱（占位、hint）。 */
    const val FAINT = 0xFF5C6470.toInt()

    // ---- 主色与状态 ----
    /** 主题蓝，与网页 #2f80ed、桌面 Theme.ACCENT 同值。 */
    const val ACCENT = 0xFF2F6FED.toInt()
    /** 主色的亮版，链接与选中页签文字。 */
    const val ACCENT_HI = 0xFF5AA9FF.toInt()
    /** 主色的淡版，顶栏文字按钮。 */
    const val ACCENT_SOFT = 0xFF8AB4F8.toInt()
    /** 危险（失败、删除）。 */
    const val WARN = 0xFFC0392B.toInt()
    /** 危险文字的浅色版（深底上可读）。 */
    const val WARN_HI = 0xFFFF8A94.toInt()
    /** 成功 / 已连接。 */
    const val OK = 0xFF22A45D.toInt()

    /** 主色的 12% 透明底，选中页签的衬底。 */
    val ACCENT_WASH: Int get() = Color.argb(0x1F, 0x2F, 0x80, 0xED)

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
