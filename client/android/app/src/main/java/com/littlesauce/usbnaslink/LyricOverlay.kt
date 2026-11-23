package com.littlesauce.usbnaslink

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 桌面歌词浮窗（跨应用悬挂在当前播放之上）。
 *
 * <p>技术上就是往 WindowManager 里塞一个 TYPE_APPLICATION_OVERLAY 的窗口：
 * 它属于系统层，不属于本 App，所以用户切到微信、刷微博、锁屏前的任意界面，
 * 歌词都还在。代价是要 SYSTEM_ALERT_WINDOW 权限（"显示在其他应用上层"）。
 *
 * <p>为什么不走通知栏常驻：通知的正文长度、字体、刷新频率都被系统限制，
 * 长歌词会被 ellipsize，200ms 一次的刷新还会被系统节流；而且用户不能拖动位置。
 *
 * <p>为什么用两行而不是一行：当前行之外还要能看到下一句，否则用户得盯着
 * 一行字等它跳 —— 真正的桌面歌词（酷狗、QQ音乐）都是两行。
 *
 * <p>权限被拒时 setEnabled 返回 false，调用方负责弹一次系统设置页；
 * 这里不主动 startActivity，避免用户只是想关掉开关时被跳走。
 */
class LyricOverlay(private val ctx: Context) {

    companion object {
        /** 浮窗默认落在屏幕下方偏上，避开底部手势区与输入法 */
        private const val BOTTOM_MARGIN_DP = 132

        fun canDraw(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return Settings.canDrawOverlays(ctx)
            }
            return true
        }
    }

    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var line1: TextView? = null
    private var line2: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    /** 已解析的歌词：每项 [timeMs: Long, text: String] */
    private var lrc: List<List<Any>> = emptyList()
    private var lastIdx = -2
    private var visible = false

    private val ui = Handler(Looper.getMainLooper())

    val isVisible: Boolean
        get() = visible

    /**
     * 打开浮窗。
     *
     * @return false 表示没有悬浮窗权限，什么都没发生
     */
    fun show(): Boolean {
        if (visible) return true
        if (!canDraw(ctx)) return false
        val w = wm ?: (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).also { wm = it }
        val p = buildParams()
        val v: View = root ?: (buildView().also { root = it as LinearLayout })
        return try {
            w.addView(v as View, p)
            params = p
            visible = true
            applyText()
            true
        } catch (e: Throwable) {
            visible = false
            false
        }
    }

    fun hide() {
        val w = wm ?: return
        val v = root ?: return
        try {
            if (visible) w.removeView(v)
        } catch (ignored: Throwable) {
        }
        visible = false
    }

    /** 关掉歌词时把内存里的也清掉，免得下次打开闪一下上一首的内容 */
    fun release() {
        hide()
        lrc = emptyList()
        lastIdx = -2
    }

    /** 换一首歌时调用；歌词由 Player 那边的 parseLrc 结果直接喂进来 */
    fun setLrc(parsed: List<List<Any>>) {
        lrc = parsed
        lastIdx = -2
        if (visible) ui.post { applyText() }
    }

    fun setNotice(text: String) {
        lrc = emptyList()
        lastIdx = -2
        line1?.text = text
        line2?.text = ""
    }

    /** 播放进度推过来，算出当前该显示哪两行 */
    fun tick(posMs: Long) {
        if (!visible) return
        if (lrc.isEmpty()) return
        var idx = -1
        for (i in lrc.indices) {
            val t = (lrc[i][0] as? Long) ?: continue
            if (t <= posMs) idx = i else break
        }
        // 还没到第一句时显示第一句（否则浮窗上是一片空白，像是坏了）
        if (idx < 0) idx = 0
        if (idx == lastIdx) return
        lastIdx = idx
        put(lrc[idx][1] as? String ?: "", if (idx + 1 < lrc.size) lrc[idx + 1][1] as? String ?: "" else "")
    }

    // ---------- 内部 ----------

    private fun buildParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // NOT_FOCUSABLE：别抢输入焦点，否则用户在别的应用打字会被打断
            // NOT_TOUCH_MODAL：窗口外的事件照常派发给下层应用
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        p.y = dp(BOTTOM_MARGIN_DP)
        return p
    }

    private fun buildView(): LinearLayout {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            // 半透明黑底：纯透明看不清白色字，太黑会挡住下面应用的内容
            setBackgroundColor(Color.parseColor("#CC10141A"))
        }
        line1 = TextView(ctx).apply {
            setTextColor(Color.parseColor("#EAF2FF"))
            textSize = 18f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            isSingleLine = true
        }
        line2 = TextView(ctx).apply {
            setTextColor(Color.parseColor("#8FA0B5"))
            textSize = 13f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }
        box.addView(line1, LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(line2, LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        attachDrag(box)
        return box
    }

    /**
     * 上下拖动改高度。
     *
     * 只做纵向：横向拖到屏幕外就再也拖不回来了，用户会以为浮窗丢了。
     * 拖动的是 y 偏移量，落点限制在屏幕内留边。
     */
    private fun attachDrag(v: View) {
        var startY = 0
        var startOffset = 0
        var dragging = false
        v.setOnTouchListener { _, e ->
            val p = params
            if (p == null) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = e.rawY.toInt()
                    startOffset = p.y
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = startY - e.rawY.toInt()
                    if (!dragging && Math.abs(dy) < dp(8)) return@setOnTouchListener false
                    dragging = true
                    p.y = (startOffset + dy).coerceIn(-dp(400), dp(600))
                    try {
                        wm?.updateViewLayout(root, p)
                    } catch (ignored: Throwable) {
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging
                else -> false
            }
        }
    }

    private fun applyText() {
        if (lrc.isEmpty()) {
            put("（暂无歌词）", "")
            return
        }
        val first = lrc[0][1] as? String ?: ""
        val second = if (lrc.size > 1) lrc[1][1] as? String ?: "" else ""
        put(first, second)
        lastIdx = 0
    }

    private fun put(a: String, b: String) {
        ui.post {
            line1?.text = if (a.isEmpty()) "♪" else a
            line2?.text = b
        }
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
