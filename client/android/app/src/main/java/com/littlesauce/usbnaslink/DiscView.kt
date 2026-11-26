package com.littlesauce.usbnaslink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View

/**
 * 播放页那张「一直在转的黑胶」。
 *
 * 为什么自己画一个 View 而不放 GIF/WebP 动画：动图要么几十帧几百 KB 撑大 APK，
 * 要么在低端机上循环重播时会抽帧；这里一帧 Canvas 只会画十几个圆 + 几段弧，
 * 而且转速和唱片角度严格跟播放进度绑定 —— 暂停就停在原角度、拖动进度条会
 * 立刻转到对应角度，而不是「跟播放没关系的自嗨动画」。
 *
 * 相位不用「每帧累加」而用「进度 + 单调时钟」两条腿：
 *  1) 进度条在动（正常播放、拖进度）→ 角度 = 时长占比 × 2160°（整 6 圈），
 *     保证「拖到 50% 就是转了 3 圈」，用户往回拖唱片也会倒着转，符合直觉；
 *  2) 进度条不动（还没进循环体、或者只有秒级粒度的位置回调）→ 用
 *     SystemClock.elapsedRealtime() 线性推算，保证肉眼看不到停顿。
 * 两条腿都进 [angleDeg] 同一把尺子，所以来回切不会出现「跳一格」。
 *
 * 几何一律按 min(w,h) 取半径：竖屏是一块大方盘，横屏被父容器压成左侧一条，
 * 就会自动缩成小盘居中 —— 同一个 onDraw 同时适配两种姿态，不需要两套布局。
 * 所有绘制都在 onDraw 里现算，不持有 Bitmap（封面图除外），不分配对象，
 * 每帧零 GC 压力。
 */
class DiscView(ctx: Context) : View(ctx) {

    companion object {
        /** 一整首歌转 6 圈：转太快显得廉价，转太慢又看不出在转 */
        private const val TOTAL_TURNS = 6f
        /** 唱针从盘面抬到驻停位的时长：切歌与暂停共用 */
        private const val ARM_MS = 200L
        /** 暂停时唱针驻停的位置（相对行程），播放时按进度铺满整条行程 */
        private const val ARM_REST = 0.10f
        /** 切歌：旧盘整块滑出 / 新盘整块滑入 */
        private const val SLIDE_OUT_MS = 320L
        private const val SLIDE_IN_MS = 360L
        /**
         * 唱针行程：针头从盘外缘走到最内圈。
         *
         * 内圈必须停在**封面贴纸之外**：[COVER_R] 是贴纸半径（盘半径的 0.34），
         * 原来 TRACK_IN 写 0.30 —— 比贴纸还小，于是一首歌快放完时针尖已经爬到
         * 封面正中间，看起来像「针扎在海报上」。留到 0.44 才不会压图，同时也
         * 保住了「越放越靠里」的观感。
         */
        private const val TRACK_OUT = 0.86f
        private const val TRACK_IN = 0.44f
        /** 中心封面贴纸半径（相对盘半径） */
        private const val COVER_R = 0.34f
    }

    /** 0f..1f，整首歌的位置；由 onProgress 喂进来 */
    private var frac = 0f
    /** frac 最近一次变化的时刻，用来在两次回调之间插值 */
    private var fracAt = 0L
    /** 最近一次回调拿到的位置（毫秒），暂停时就停在这里 */
    private var lastPosMs = 0L
    /**
     * 时间锚点：`(anchorPosMs, anchorAt)` 是「某个真实时刻在曲目里的位置」。
     *
     * 只在 [setProgress] / [setSpinning] 里更新，[recompute] 只读不改。
     * 曾经这里叫 beatAt/beatMs，而且 recompute 每帧末尾都写 `beatAt = now` ——
     * 于是下一帧 `now - beatAt` 永远只有 16/33ms，推算位置恒等于「上次回调位置 + 一帧」，
     * 盘在 200ms 内一动不动、回调一到就跳 200ms 的量，就是「一卡一卡」的机械成因。
     */
    private var anchorPosMs = 0L
    private var anchorAt = 0L
    /** 暂停时为 false：角度不再随时间涨，但用户拖进度条仍然会改 frac */
    private var spinning = false
    private var angleDeg = 0f
    /** 当前曲目总时长（毫秒），用来在两次进度回调之间插值针尖位置 */
    private var totalMsForArm = 0L

    private var notes: List<String> = emptyList()

    // ---------- 唱针 ----------
    /** 0f = 完全贴盘，1f = 停在驻停位；数值本身就带着 200ms 的缓动 */
    private var armT = 0f
    private var armFrom = 0f
    private var armTo = 0f
    private var armStart = 0L
    private var armDur = ARM_MS
    /** 切歌整段锁住：期间不许按播放/暂停改唱针目标，只由换盘回调放针 */
    private var switching = false

    // ---------- 切歌动画 ----------
    /** 0..1，滑出/滑入的进度；1f = 完全在场 */
    private var flip = 1f
    private var flipStart = 0L
    private var flipDur = SLIDE_IN_MS
    /** true 表示当前这一段是「滑出去」，到 1 时回调换盘再滑回来 */
    private var flipOut = false
    /** 滑出方向：-1 = 向左（上一首），+1 = 向右（下一首） */
    private var dir = 1f
    /** 上一首的封面：滑出那一段仍要贴旧图，否则旧盘中央会先变成默认贴纸 */
    private var oldCover: Bitmap? = null
    private var flipOutDone: (() -> Unit)? = null

    // ---------- 封面 ----------
    /** 已经解码好的原图；null 就画自绘贴纸 */
    private var coverBmp: Bitmap? = null
    /** 当前封面的标识（一般就是曲目路径），换人才重裁 */
    private var coverKey: String? = null
    /**
     * 裁好的方图，以及它对应的图 oid / 半径。
     *
     * 这里**故意不用 BitmapShader**：Shader 的坐标原点锚在画布 (0,0)，而唱片
     * 中心在 `cx-lr`（横屏时约 367px）。不额外塞 localMatrix 的话，圆里每个像素
     * 都在采样 bitmap 的 `x=cx-lr .. cx+lr`，早就越过图宽，CLAMP 把它们全钉在
     * 最右一列上 —— 画出来是一块拉丝的纯色，看起来就是「封面出不来」。
     * 直接 drawBitmap(dst=coverRect) 让框架自己做缩放，没有坐标系陷阱。
     */
    private var covBmp: Bitmap? = null
    private var covKey: String? = null
    private var covR = 0f
    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }
    private val coverClip = Path()
    private val coverRect = RectF()

    /**
     * 唱片区的方形背景跟着 App 主题走：夜色还是原来的近黑，日间换成浅底，
     * 免得白天整页是浅色、唯独唱片区一块黑方块。唱片本身（黑胶渐变、纹路、
     * 唱针）保持原状，内圈照旧显示从歌曲文件提取的封面。
     */
    private val bgColor get() = Palette.BG
    private val labelHi = Color.parseColor("#1F5FC4")
    private val labelLo = Color.parseColor("#123A78")
    private val labelSub = Color.parseColor("#AFC3DC")
    private val labelMain = Color.parseColor("#E6E9EF")

    /**
     * 着色器建好时的几何。盘体/高光两个 shader 的圆心与半径是烘进去的，
     * 窗口一变（横竖屏、分屏）就得重建成新的，否则渐变会错位成「光斑贴在角落」。
     */
    private var shaderR = 0f
    private var shaderCx = 0f
    private var shaderCy = 0f

    /** 贴纸渐变是按半径烘的，半径没变就不用重建（每帧 new LinearGradient 是白烧） */
    private var labelR = 0f

    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#22FFFFFF")
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#33E6E9EF")
    }
    private val hole = Paint(Paint.ANTI_ALIAS_FLAG)
    private val holeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#552F6FED")
    }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6E9EF")
        textAlign = Paint.Align.CENTER
        isSubpixelText = true
    }
    private val path = Path()
    private val arcRect = RectF()
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#2F80ED")
        alpha = 90
    }
    private val arm = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#B9C4D2")
        strokeCap = Paint.Cap.ROUND
    }
    private val armBase = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#8D9AA9")
    }
    private val armHead = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2F80ED")
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        // 让父容器在 WRAP_CONTENT 下也能量到一块方形区域
        minimumWidth = dp(120)
        minimumHeight = dp(120)
    }

    /**
     * 音频位置回调（200ms 一次）：把位置与时刻一起记下来。
     *
     * 这里**不**直接把 angleDeg 设成 `frac × 2160°`：200ms 的回调与 33ms 的重绘
     * 不同步，按回调值硬设会让盘「走 200ms → 停 → 再跳」，看起来就是抖。
     * 真正的角度由 [recompute] 用「位置 + 单调时钟」连续算出来。
     */
    fun setProgress(posMs: Long, totalMs: Long) {
        if (totalMs <= 0L) return
        totalMsForArm = totalMs
        lastPosMs = posMs
        val now = SystemClock.elapsedRealtime()
        // 位置与时刻成对更新，且新锚点不许比上一帧的推算值更靠前（回调有几十毫秒抖动）
        val predicted = anchorPosMs + (now - anchorAt)
        anchorPosMs = if (Math.abs(posMs - predicted) > 700L) posMs else Math.max(posMs, predicted)
        anchorAt = now
        if (!spinning) frac = (posMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
        recompute()
        invalidate()
    }

    /** 播放/暂停：暂停后唱针抬到驻停位停住（不摆动），播放时放回盘面 */
    fun setSpinning(on: Boolean) {
        if (spinning == on) return
        val now = SystemClock.elapsedRealtime()
        if (on) {
            // 从暂停恢复：以暂停时的位置起算，不要用旧锚点一次性补上一大段
            anchorPosMs = lastPosMs
            anchorAt = now
        } else {
            // 暂停：把推算位置定格这一次，之后再也不会因为时钟前进而转身
            lastPosMs = anchorPosMs.coerceAtLeast(0L)
            frac = if (totalMsForArm > 0L) (lastPosMs.toFloat() / totalMsForArm.toFloat()).coerceIn(0f, 1f) else frac
        }
        fracAt = now
        spinning = on
        // 切歌动画期间不放针：方向盘正在换，等换完由收尾逻辑放
        if (!switching) requestArm(if (on) 0f else 1f)
        recompute()
        invalidate()
    }

    /** 歌名/歌手，没有封面时印在唱片中心贴纸上 */
    fun setTitle(t: String, a: String) {
        notes = listOf(t, a)
        invalidate()
    }

    /**
     * 切歌动画：唱针先抬到驻停位，抬到位后胶片整块横向滑出（[prev] 决定方向：
     * 上一首向左，下一首向右），滑完那一刻调 [onOut] 换数据，再滑回来，落位后放针。
     *
     * 时间轴（全部线性推进，[recompute] 里唯一驱动）：
     *   抬针 ARM_MS → 滑出 SLIDE_OUT_MS ── 换盘 ──→ 滑入 SLIDE_IN_MS → 放针 ARM_MS
     * 中途重复点击直接吞掉，不排队也不打断，避免「按三次只换一首」或动画打架。
     */
    fun spinSwitch(prev: Boolean, onOut: () -> Unit) {
        if (switching) return
        val now = SystemClock.elapsedRealtime()
        switching = true
        // 抬针：抬到位才滑盘，滑盘期间针不再动
        requestArm(1f)
        flipOutDone = onOut
        flipOut = true
        dir = if (prev) -1f else 1f
        oldCover = coverBmp
        flipStart = now + ARM_MS
        flipDur = SLIDE_OUT_MS
        flip = 0f
        invalidate()
    }

    /** 切歌动画是否还在跑（父容器据此决定要不要继续排帧） */
    fun isAnimating(): Boolean = switching || flip < 1f || armMoving()

    /** 甩盘切歌是否正在进行；spinSwitch 在跑动画时会忽略新请求，调用方据此决定是否立刻换曲 */
    fun isSwitching(): Boolean = switching

    /**
     * 让封面按当前盘半径重裁一次。
     *
     * onDraw 里已经按 `covR != lr` 自动重裁了，这里只是给「父容器宽度变了但
     * 这一帧还没走到 onDraw」的时机留个显式入口（横竖屏切换时调用）。
     */
    fun requestCoverResize() {
        covR = 0f
        invalidate()
    }

    /**
     * 换封面。[bmp] 是已经解码好的原图（尺寸随便，这里负责裁方+缩放），
     * 传 null 就退回自绘贴纸；[key] 用来判重，同一张图重复设置不重磨。
     */
    fun setCover(bmp: Bitmap?, key: String?) {
        if (key != null && key == coverKey && bmp === coverBmp) return
        coverKey = key
        coverBmp = bmp
        covR = 0f
        // 没封面可画时把上一张的缩放副本放掉，否则切到「无封面」的曲子会一直
        // 攥着一张用不到的位图（滑出阶段用的 oldCover 不在这里动，它还要画）
        if (bmp == null) {
            val stale = covBmp
            covBmp = null
            covKey = null
            if (stale != null && stale !== oldCover && !stale.isRecycled) stale.recycle()
        }
        invalidate()
    }

    /** 缩放副本按图自身的 oid 认图：滑出期间新旧两张同时在场，各留一套不互相顶掉 */
    private fun skinOf(src: Bitmap): String = System.identityHashCode(src).toString()

    override fun onDraw(canvas: Canvas) {
        recompute()
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = Math.min(w, h) / 2f - dp(4).toFloat()
        if (r <= 1f) return

        canvas.drawColor(bgColor)

        // 切歌动画：**只有唱片平移**，唱针是固定在机身上的机构，不跟着走 ——
        // 真实留声机换唱片时，盘从叉架上下来/上去，音臂原地不动。
        //
        // 方向约定（这是「动效连贯」的关键）：
        //   dir = +1 表示「下一首」：旧盘往左滑出，新盘从**右边**滑进来
        //   dir = -1 表示「上一首」：旧盘往右滑出，新盘从**左边**滑进来
        // 也就是两个阶段用同一个符号 dir，只是阶段不同：
        //   滑出：dx 从 0 → -dir*w   （下一首：0 → -w，向左出画）
        //   滑入：dx 从 +dir*w → 0   （下一首：+w → 0，从右侧接上来）
        // 曾经把滑入写成 `-dir*w*(1-e)`，等于新盘也停在左边、原路倒回原位，
        // 视觉上像「旧盘出去又自己退回来」，完全不是翻页的连贯感。
        val sliding = flip < 1f
        if (sliding) {
            val e = easeInOut(flip)
            val dx = if (flipOut) -w * e * dir        // 旧盘：从 0 滑到 -dir*w（完全出画）
            else dir * w * (1f - e)                   // 新盘：从 +dir*w 滑回 0
            canvas.save()
            canvas.translate(dx, 0f)
        }

        // 盘体：中心偏亮、边缘吸光的径向渐变，模拟黑胶。
        // 渐变的颜色数组只建一次：每帧 new RadialGradient 会让 Skia 每帧重建一次着色器，
        // 是转起来一顿一顿的主因之一。但几何（圆心/半径）是烘进 shader 里的，
        // 窗口一变就得重建 —— 只判 `shader == null` 会让横竖屏切换后光斑错位到角落。
        val geoMoved = shaderR != r || shaderCx != cx || shaderCy != cy
        if (disc.shader == null || geoMoved) {
            disc.shader = android.graphics.RadialGradient(
                cx, cy, r,
                intArrayOf(Color.parseColor("#3A424D"), Color.parseColor("#171E26"), Color.parseColor("#0C1117")),
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
            shaderR = r
            shaderCx = cx
            shaderCy = cy
        }
        canvas.drawCircle(cx, cy, r, disc)

        // 纹路：8 圈 258° 的弧（留缺口），整体跟着 angleDeg 转
        groove.strokeWidth = Math.max(1f, resources.displayMetrics.density)
        val base = angleDeg - 90f
        for (i in 1..8) {
            val gr = r * (0.40f + 0.072f * i)
            arcRect.set(cx - gr, cy - gr, cx + gr, cy + gr)
            canvas.drawArc(arcRect, base + i * 11f, 258f, false, groove)
        }

        // 高光：一道跟着转的扫掠渐变，灯光掠过盘面。
        // 同样只建一次（见盘体那段注释），旋转靠 canvas.rotate
        if (sheen.shader == null || geoMoved) {
            sheen.shader = SweepGradient(
                cx, cy,
                intArrayOf(
                    Color.TRANSPARENT, Color.parseColor("#14FFFFFF"),
                    Color.TRANSPARENT, Color.TRANSPARENT,
                    Color.parseColor("#10FFFFFF"), Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.08f, 0.22f, 0.55f, 0.72f, 0.9f)
            )
        }
        canvas.save()
        canvas.rotate(angleDeg, cx, cy)
        canvas.drawCircle(cx, cy, r, sheen)
        canvas.restore()

        // 中心：有封面贴封面，没有就画蓝贴纸（印歌名 + 歌手）
        val lr = r * COVER_R
        // 滑出那一段用「切歌前那张封面」，否则旧盘中央会先变成默认贴纸
        val cov = if (flipOut) oldCover else coverBmp
        if (cov != null && !cov.isRecycled) {
            paintCover(canvas, cov, cx, cy, lr)
        } else {
            // 贴纸渐变按 lr 缓存：每帧 new LinearGradient 会白建一次着色器
            if (labelR != lr || label.shader == null) {
                label.shader = LinearGradient(
                    cx - lr, cy - lr, cx + lr, cy + lr,
                    intArrayOf(labelHi, labelLo),
                    null, Shader.TileMode.CLAMP
                )
                labelR = lr
            }
            canvas.drawCircle(cx, cy, lr, label)
            canvas.drawCircle(cx, cy, lr, labelRing)

            canvas.save()
            canvas.rotate(angleDeg, cx, cy)
            val t1 = notes.getOrNull(0).orEmpty().take(8)
            val t2 = notes.getOrNull(1).orEmpty().take(10)
            textPaint.textSize = lr * 0.30f
            textPaint.color = labelMain
            if (t1.isNotEmpty()) canvas.drawText(t1, cx, cy - lr * 0.10f, textPaint)
            textPaint.textSize = lr * 0.22f
            textPaint.color = labelSub
            if (t2.isNotEmpty()) canvas.drawText(t2, cx, cy + lr * 0.34f, textPaint)
            canvas.restore()
        }

        // 轴孔：盖在封面中央，让它一眼还是「一张唱片」而不是「一个头像」
        // 颜色跟着底色走，否则日间浅底上会留一个黑点。
        hole.color = bgColor
        canvas.drawCircle(cx, cy, r * 0.045f, hole)
        canvas.drawCircle(cx, cy, r * 0.045f, holeRing)

        // 外圈细边，把盘从背景里拎出来
        canvas.drawCircle(cx, cy, r, edge)

        // 盘画完再收掉平移，唱针在下面独立绘制：换盘时盘动、针不动
        if (sliding) canvas.restore()

        drawArm(canvas, cx, cy, r)

        // 还在转、或者有动画没跑完，就继续排帧；其余时候停掉，
        // 省电，也省得 uiautomator 永远等不到 idle。
        // 用 postInvalidateOnAnimation 而不是固定 33ms：33ms 与这块屏 16.67ms 的 vsync
        // 不同频，会出现「某帧等两拍、某帧等一拍」，看起来一快一慢。交给 vsync 后
        // 转速只由 angleDeg 决定，与帧率彻底解耦。
        if (spinning || flip < 1f || armMoving()) postInvalidateOnAnimation()
    }

    /** 圆到哪张图，就直接用 drawBitmap 铺满 coverRect：坐标系跟画布同一套，不会再错位 */
    private fun paintCover(canvas: Canvas, src: Bitmap, cx: Float, cy: Float, lr: Float) {
        if (src.isRecycled) {
            if (src === coverBmp) setCover(null, "none:recycled")
            return
        }
        // 两张图同时在场（滑出用的是旧封面），裁好的方图按「图」各留一套
        val key = skinOf(src)
        val d = (lr * 2f).toInt().coerceAtLeast(8)
        if (key != covKey || covR != lr || covBmp == null) {
            val side = Math.min(src.width, src.height)
            if (side <= 0) return
            val x0 = (src.width - side) / 2
            val y0 = (src.height - side) / 2
            val sq = Bitmap.createBitmap(src, x0, y0, side, side)
            val scaled = Bitmap.createScaledBitmap(sq, d, d, true)
            if (sq != src && sq != scaled) sq.recycle()
            // 上一张的缩放副本已经画完这一帧了，换新的之前收掉，别越切越涨
            val old = covBmp
            if (old != null && old != scaled && !old.isRecycled) old.recycle()
            covBmp = scaled
            covKey = key
            covR = lr
        }
        val pic = covBmp ?: return
        coverRect.set(cx - lr, cy - lr, cx + lr, cy + lr)
        canvas.save()
        coverClip.reset()
        coverClip.addCircle(cx, cy, lr, Path.Direction.CW)
        canvas.clipPath(coverClip)
        // 封面跟盘一起转：转的是圆里的图，视觉上是「贴纸在转」
        canvas.rotate(angleDeg, cx, cy)
        canvas.drawBitmap(pic, null, coverRect, coverPaint)
        canvas.restore()
        canvas.drawCircle(cx, cy, lr, labelRing)
    }

    /**
     * 唱针。像真机一样只有一条音臂：支点固定在机身上，针尖沿径向行走。
     *
     *  - 播放中：针尖随播放进度从外缘（[TRACK_OUT] r）走到接近轴心（[TRACK_IN] r），
     *    一首歌走完恰好走完全程 —— 这就是「播到哪儿、针在哪儿」，拖动进度条也会跟着挪；
     *  - 暂停：在当前位置抬起一个小角度（[ARM_REST] r），抬起来之后**完全静止**；
     *  - 换盘：盘在动，针保持自己的位置，不跟着平移。
     *
     * 针身用两段折臂（支点 → 肘 → 针尖）而不是一根直线：臂长随针尖位置变化时
     * 肘点按比例回折，视觉上像带配重臂的真实唱头，针尖走到内圈也不会拉出屏幕。
     */
    private fun drawArm(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val pivotX = cx + r * 0.80f
        val pivotY = cy - r * 0.86f

        // 针尖沿径向从外缘走向轴心：进度 0 → TRACK_OUT，进度 1 → TRACK_IN
        val prog = frac.coerceIn(0f, 1f)
        val rad = r * (TRACK_OUT - (TRACK_OUT - TRACK_IN) * prog)
        // 针尖所在角度：固定从左上 225° 方向进针（真实唱机也是从外到内顺时针收）
        val ang = Math.toRadians(225.0)
        val lift = r * ARM_REST * armT
        val tipX = cx + (rad * Math.cos(ang)).toFloat()
        val tipY = cy + (rad * Math.sin(ang)).toFloat() - lift

        // 支点底座：固定不动
        canvas.drawCircle(pivotX, pivotY, dp(7).toFloat().coerceAtLeast(4f), armBase)
        canvas.drawCircle(pivotX, pivotY, dp(4).toFloat().coerceAtLeast(2f), armHead)

        // 肘点：支点与针尖的中点向外侧微鼓，形成「折臂」形态
        val midX = (pivotX + tipX) / 2f
        val midY = (pivotY + tipY) / 2f
        val outX = midX + r * 0.10f
        val outY = midY - r * 0.12f

        arm.strokeWidth = dp(3).toFloat().coerceAtLeast(2f)
        path.reset()
        path.moveTo(pivotX, pivotY)
        path.lineTo(outX, outY)
        path.lineTo(tipX, tipY)
        canvas.drawPath(path, arm)

        // 针头：主题蓝小圆 + 一点点唱头方块，跟着针尖走
        canvas.drawCircle(tipX, tipY, Math.max(2f, 2.5f * resources.displayMetrics.density), armHead)
        canvas.drawCircle(tipX, tipY, Math.max(4f, 4.5f * resources.displayMetrics.density), labelRing)
    }

    // ---------- 唱针动画 ----------

    /** 把唱针的目标位置改成 [to]（0 贴盘 / 1 驻停位），从当前位置起步 */
    private fun requestArm(to: Float) {
        if (Math.abs(to - armTo) < 0.0001f) return
        armFrom = armT
        armTo = to
        armStart = SystemClock.elapsedRealtime()
        armDur = ARM_MS
    }

    private fun armMoving(): Boolean = Math.abs(armT - armTo) > 0.0001f

    private fun stepArm(now: Long) {
        if (!armMoving()) return
        val p = ((now - armStart).toFloat() / armDur.toFloat()).coerceIn(0f, 1f)
        armT = armFrom + (armTo - armFrom) * easeInOut(p)
        if (p >= 1f) armT = armTo
    }

    /**
     * 把 frac / 时钟合成一个角度，任何时刻调都自洽；顺带推进抬针与切歌动画。
     *
     * 转速的两条腿必须**同一个角速度**，否则每秒重排的那一帧会把两条腿的差当角度画出来，
     * 盘就会一跳一跳：
     *  - 进度腿：进度的 1/整曲时长 × 6 圈，即每秒 `360 * TOTAL_TURNS / (totalMs/1000)` 度；
     *  - 时钟腿：同一角速度 `360 * TOTAL_TURNS / 216000` 度每毫秒。
     * 两个 perMs 一乘，`frac * totalMs` 和 `elapsed * 216000` 都是「毫秒」单位 ——
     * 只有总时长恰好 3 分钟时二者才相等，否则每 200ms 回调一次就错开一截，肉眼就是抖。
     * 这里把进度腿改写成同一只米尺（见 [anchorPosMs]）。
     */
    private fun recompute() {
        val now = SystemClock.elapsedRealtime()
        val totalS = totalMsForArm / 1000f
        // 时间插值：按「整首歌 6 圈」折算成每秒多少度
        val perMs = 360f * TOTAL_TURNS / 216000f // 以 3 分钟一首为基准

        // 用锚点连续推算，绝不在这里改锚点：改了就等于每帧把插值清零（见字段注释）
        val posMs = if (spinning) (anchorPosMs + (now - anchorAt)).coerceAtLeast(0L) else lastPosMs
        // 位置 → 角度：同一条 6 圈尺子；乘 216000/实际总时长 让「进度腿」与「时钟腿」同速
        val scale = if (totalS > 1f) 216000f / totalMsForArm.toFloat() else 1f
        angleDeg = posMs.toFloat() * perMs * scale

        // 针尖位置反过来也要走同一条腿，否则盘在匀速转、针却按旧 frac 停着
        val f = if (totalMsForArm > 0L) (posMs.toFloat() / totalMsForArm.toFloat()).coerceIn(0f, 1f) else frac
        if (f != frac) frac = f

        // 角度只用来看相位，绝对值别无限长
        if (angleDeg > 100000f) angleDeg -= 100000f

        stepArm(now)

        if (flip < 1f && now >= flipStart) {
            val p = ((now - flipStart).toFloat() / flipDur.toFloat()).coerceIn(0f, 1f)
            flip = p
            if (p >= 1f) {
                if (flipOut) {
                    // 滑出去了：这一刻换盘，再转入「滑回来」
                    flipOut = false
                    flipStart = now
                    flipDur = SLIDE_IN_MS
                    flip = 0f
                    val cb = flipOutDone
                    flipOutDone = null
                    cb?.invoke()
                    // 新曲的时长/位置要等下一次 onProgress 才到，这里先把锚点对齐成
                    // 「从当前位置开始」，否则新盘会带着上一首的已播量先窜一下再被拉回来
                    anchorAt = SystemClock.elapsedRealtime()
                    anchorPosMs = 0L
                } else {
                    // 滑回来了：旧封面可以丢了，唱针按当前播放状态落位
                    flip = 1f
                    oldCover = null
                    switching = false
                    requestArm(if (spinning) 0f else 1f)
                }
            }
        }
    }

    private fun easeInOut(t: Float): Float {
        return if (t < 0.5f) 2f * t * t
        else 1f - Math.pow((2 - 2 * t).toDouble(), 2.0).toFloat() / 2f
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
