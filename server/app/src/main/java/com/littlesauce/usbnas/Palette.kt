package com.littlesauce.usbnas

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View

/**
 * 首页配色表 —— **固定暗色**，不跟系统的深色/浅色走。
 *
 * 早先这一组颜色是 `MainActivity` 里的 `C_XXX` 常量，后来为了「手机状态那个框
 * 跟着系统变」加过一套日间配色（`DAY`），再后来又去掉了。
 *
 * 去掉的原因：App 首页是常驻在床头/桌面当 NAS 面板用的，暗色才对。而且
 * 系统切深色在 MIUI 上不会把配置变更下发到已在前台的进程（实测
 * `cmd uimode night yes` 改完截图一个像素都不动），做出来的日间模式只能
 * 靠冷启动生效，反而比不做更别扭。配色统一交给网页端主题去管。
 *
 * 颜色用在 `setTextColor` 和自绘 drawable 上，不像布局 XML 那样能走
 * `values-night`，所以这套值就是最终值 —— 想改直接改下面的常量。
 */
object Palette {

    /** 深色（唯一一套） */
    private val NIGHT = longArrayOf(
        0xFF171B22, // bg / card
        0xFF1F262F, // div / line
        0xFFE6E9EF, // text
        0xFF9AA4B2, // label
        0xFF6B7480, // sub
        0xFF5AA9FF, // accent
        0xFF3DDC84, // ok
        0xFF8B949E, // off
        0xFF0B0E12  // inset
    )

    private const val I_BG = 0
    private const val I_DIV = 1
    private const val I_TEXT = 2
    private const val I_LABEL = 3
    private const val I_SUB = 4
    private const val I_ACCENT = 5
    private const val I_OK = 6
    private const val I_OFF = 7
    private const val I_INSET = 8

    /**
     * 取色。参数 `ctx` 保留但不再读 `uiMode` —— 签名不动是为了让
     * `C_BG get() = Palette.bg(this)` 那一组委托和所有调用点都不用改。
     */
    @Suppress("UNUSED_PARAMETER")
    private fun pick(ctx: Context, i: Int): Int = NIGHT[i].toInt()

    fun bg(ctx: Context) = pick(ctx, I_BG)
    fun card(ctx: Context) = pick(ctx, I_BG)
    fun line(ctx: Context) = pick(ctx, I_DIV)
    fun text(ctx: Context) = pick(ctx, I_TEXT)
    fun label(ctx: Context) = pick(ctx, I_LABEL)
    fun sub(ctx: Context) = pick(ctx, I_SUB)
    fun accent(ctx: Context) = pick(ctx, I_ACCENT)
    fun ok(ctx: Context) = pick(ctx, I_OK)
    fun off(ctx: Context) = pick(ctx, I_OFF)
    fun inset(ctx: Context) = pick(ctx, I_INSET)

    /**
     * 卡片背景：圆角矩形 + 1dp 描边。
     *
     * 替代 `R.drawable.bg_card` —— 那个 drawable 里写的是 `@color/surface`，
     * 走不了代码里的配色表。每次调用都新建一个 drawable（卡片数量很少，
     * 不值得缓存）。
     */
    fun cardBg(ctx: Context, radiusDp: Float = 16f): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = radiusDp * ctx.resources.displayMetrics.density
        d.setColor(card(ctx))
        d.setStroke(1, line(ctx), 0f, 0f)
        return d
    }

    /** 实心按钮背景：主色块 + 同色描边（与服务端网页端的主按钮一致） */
    fun solidBg(ctx: Context, color: Int, radiusDp: Float = 10f): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = radiusDp * ctx.resources.displayMetrics.density
        d.setColor(color)
        d.setStroke(1, color, 0f, 0f)
        return d
    }

    /** 药丸背景（二维码下方那个切换按钮、等级徽章之类） */
    fun pillBg(ctx: Context, radiusDp: Float = 20f): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = radiusDp * ctx.resources.displayMetrics.density
        d.setColor(inset(ctx))
        d.setStroke(1, line(ctx), 0f, 0f)
        return d
    }

    /** 服务状态圆点：运行中绿、已停止灰 */
    fun dotBg(ctx: Context, on: Boolean): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(if (on) ok(ctx) else off(ctx))
        return d
    }

    /**
     * 给已经建好的 View 树重新上色。
     *
     * 配色固定之后这个方法已经是空转，留着是因为 `MainActivity` 里还有两处
     * 调用点（`installSafeAreaPadding` 之后、`applyThemeNow()` 里）。真要清理
     * 就连调用点一起删；现在保留是为了这次改动面尽量小。
     */
    fun restyle(v: View?, ctx: Context) {
        // 固定暗色，不需要重新上色。此处刻意留空。
    }

    /** 把 #RRGGBB 加透明度，用于遮罩 */
    fun withAlpha(color: Int, alphaPct: Int): Int {
        val a = (alphaPct.coerceIn(0, 100) * 255) / 100
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }
}
