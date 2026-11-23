package com.littlesauce.usbnaslink

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.TextView

/**
 * 客户端唯一的调色板 —— 所有页面共用的颜色常量与几个「把控件画成这套颜色」的工厂。
 *
 * 为什么需要这个文件：
 *   Android 的 `Button` 默认套主题的 Material 背景，在深色页面上会画出 #D6D7D7 的
 *   灰底 + 深灰描边，于是首页顶栏那两个按钮（网页 / 设置）和页签三个按钮看起来
 *   跟播放页、图书页完全不像一家人。之前每个 Activity 各自写颜色字面量，
 *   同类控件也不可能保证一致。这里把色值集中一份，按钮统一走 [flat]/[ghost]。
 *
 * 取色依据是服务端网页的深色主题（`HttpServer` 里 `body{background:#0e1116}`、
 * `.root{background:#171b22;border:1px solid #232a34}`、主色 `#2f80ed`），
 * 桌面端 `Theme.java` 也是同一套。三端同色，视觉才是一件事。
 */
object Palette {

    // ---- 底 ----
    /** 页面底色。播放页、媒体库、图书页、PDF 页的 root 都是它。 */
    const val BG = 0xFF0F141A.toInt()
    /** 卡片 / 列表块 / 输入框。 */
    const val CARD = 0xFF161B22.toInt()
    /** 卡片描边、分割线。 */
    const val LINE = 0xFF232A34.toInt()
    /** 按钮静默底（比卡片再深一点，压在卡片上时不糊）。 */
    const val BTN = 0xFF1B222C.toInt()
    /** 未选中的页签底（几乎等于页面底，靠文字和主色区分）。 */
    const val TAB_IDLE = 0xFF141A21.toInt()

    // ---- 字 ----
    /** 主文字。 */
    const val FG = 0xFFE6E9EF.toInt()
    /** 次文字（列表标题、按钮字）。 */
    const val FG2 = 0xFFD8DEE6.toInt()
    /** 弱文字（提示行、路径、计数）。 */
    const val DIM = 0xFF9AA4B2.toInt()
    /** 更弱（当前页面里 hint 用的那一档）。 */
    const val DIM2 = 0xFF7D8590.toInt()
    /** 最弱（hint 文字、占位）。 */
    const val FAINT = 0xFF5C6470.toInt()

    // ---- 主色 ----
    /** 主题蓝，与网页 #2f80ed、桌面 Theme.ACCENT 同值。 */
    const val ACCENT = 0xFF2F6FED.toInt()
    /** 主色的亮版，用于链接与 URL 行。 */
    const val ACCENT_HI = 0xFF5AA9FF.toInt()
    /** 主色的淡版，用于顶栏文字按钮。 */
    const val ACCENT_SOFT = 0xFF8AB4F8.toInt()
    /** 危险色（删除、失败）。 */
    const val WARN = 0xFFC0392B.toInt()
    /** 成功色。 */
    const val OK = 0xFF2FBF71.toInt()

    /** 主色的 12% 透明底，用作选中页签的衬底。 */
    val ACCENT_WASH: Int get() = Color.argb(0x1F, 0x2F, 0x80, 0xED)

    // ------------------------------------------------------------ 工厂

    /**
     * 圆角实心块。[radius] 单位是 dp。
     *
     * @param fill 填充色
     * @param stroke 描边色，[Color.TRANSPARENT] 表示不描边
     * @param strokeDp 描边宽度（dp）
     */
    fun round(ctx: Context, fill: Int, radiusDp: Float = 9f,
              stroke: Int = Color.TRANSPARENT, strokeDp: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setColor(fill)
            if (stroke != Color.TRANSPARENT) setStroke(dp(ctx, strokeDp), stroke)
        }

    /**
     * 顶栏那种「只有字、没有块」的按钮。
     *
     * 必须显式 `stateListAnimator = null` 与 `backgroundTintList = null`，
     * 否则 Material 主题还会叠一层水波纹色和阴影，跟自绘背景打架。
     */
    fun ghost(ctx: Context, text: String, color: Int = ACCENT_SOFT): Button =
        Button(ctx).apply {
            this.text = text
            isAllCaps = false
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            backgroundTintList = null
            stateListAnimator = null
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
            background = round(ctx, BTN, 8f)
        }

    /**
     * 实心块按钮（主色底）。用于「立即检查」「去下载」这类主动作。
     */
    fun solid(ctx: Context, text: String, fill: Int = ACCENT, fg: Int = 0xFFFFFFFF.toInt()): Button =
        Button(ctx).apply {
            this.text = text
            isAllCaps = false
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            backgroundTintList = null
            stateListAnimator = null
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(ctx, 16), dp(ctx, 9), dp(ctx, 16), dp(ctx, 9))
            background = round(ctx, fill, 9f)
        }

    /**
     * 页签。选中态是「主色淡底 + 主色字 + 主色下划线」，未选中是「近底灰 + 弱字」。
     * 三个页签永远只换这两套，所以能一眼看出在哪一页。
     */
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
        val ctx = v.context
        v.setTextColor(if (on) ACCENT_HI else DIM)
        v.typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT, if (on) android.graphics.Typeface.BOLD
            else android.graphics.Typeface.NORMAL)
        v.background = round(ctx, if (on) ACCENT_WASH else TAB_IDLE, 9f,
            if (on) ACCENT else Color.TRANSPARENT, 1f)
    }

    /** dp → px。 */
    fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun dp(ctx: Context, v: Float): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    /** 单选/复选之类需要着色时用。 */
    fun tint(color: Int): ColorStateList = ColorStateList.valueOf(color)
}
