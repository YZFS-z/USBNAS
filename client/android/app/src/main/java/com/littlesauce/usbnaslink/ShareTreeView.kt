package com.littlesauce.usbnaslink

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * 内嵌在「管理设置」里的分享管理树。
 *
 * 取消原来的二级 ShareActivity：现在直接把一棵懒加载的文件/目录树铺在管理页里，
 * 点每行的「分享」生成免登录短链并弹出系统分享面板，底部列出已有分享可撤销。
 * 与网页端 /_share 用同一个 /_share/tree|new|drop|list 端点，三端同步。
 *
 * 树是懒加载的（点 ▸ 才拉下一层），和网页端一致 —— 一次列全盘几万个文件会卡死。
 */
object ShareTreeView {

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "usbnas-share-tree").apply { isDaemon = true }
    }
    private val ui = Handler(Looper.getMainLooper())

    /**
     * 构建整块视图。调用方（LibraryActivity）把它 addView 到管理页容器里。
     * [onChanged] 在生成/撤销后回调，便于外部刷新状态（可空）。
     */
    fun build(ctx: Context, base: String, sid: String?, onChanged: (() -> Unit)? = null): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(ctx, 4), 0, 0)
        }

        val hint = TextView(ctx).apply {
            text = "点 ▸ 展开目录，点每行「分享」生成免登录短链（局域网内可直接打开，重启失效）"
            textSize = 12f
            setTextColor(Palette.FG2)
            setPadding(dp(ctx, 2), 0, dp(ctx, 2), dp(ctx, 6))
        }
        root.addView(hint)

        // 树区域：限高，避免把管理页撑爆
        val tree = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val treeScroll = ScrollView(ctx).apply {
            addView(tree, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setBackgroundColor(Palette.CARD)
            background = Palette.round(ctx, Palette.CARD, 9f, Palette.LINE, 1f)
        }
        root.addView(treeScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 240)).apply {
            topMargin = dp(ctx, 2)
        })

        // 当前分享列表
        root.addView(TextView(ctx).apply {
            text = "当前分享（点开可复制分享按钮里的链接）"
            textSize = 12f
            setTextColor(Palette.FG2)
            setPadding(dp(ctx, 2), dp(ctx, 10), dp(ctx, 2), dp(ctx, 4))
        })
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val listScroll = ScrollView(ctx).apply {
            addView(list, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(listScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 170)))

        fun reloadList() {
            io.execute {
                val r = Share.list(base, sid, 20000)
                ui.post {
                    list.removeAllViews()
                    if (!r.ok) {
                        list.addView(note(ctx, "列分享失败：" + r.error))
                        return@post
                    }
                    if (r.items.isEmpty()) {
                        list.addView(note(ctx, "还没有分享链接"))
                        return@post
                    }
                    for (item in r.items) list.addView(shareRow(ctx, base, sid, item) {
                        reloadList()
                        onChanged?.invoke()
                    })
                }
            }
        }

        fun loadKids(path: String, box: LinearLayout) {
            io.execute {
                val r = Share.tree(base, sid, path, 20000)
                ui.post {
                    box.removeAllViews()
                    if (!r.ok) {
                        box.addView(note(ctx, r.error))
                        return@post
                    }
                    if (r.items.isEmpty()) {
                        box.addView(note(ctx, "（空目录）"))
                        return@post
                    }
                    for (n in r.items) box.addView(nodeRow(ctx, base, sid, n, { reloadList(); onChanged?.invoke() }) { kids, p ->
                        loadKids(p, kids)
                    })
                }
            }
        }

        // 根节点
        io.execute {
            val r = Share.tree(base, sid, "", 20000)
            ui.post {
                tree.removeAllViews()
                if (!r.ok) {
                    tree.addView(note(ctx, r.error))
                    return@post
                }
                if (r.items.isEmpty()) {
                    tree.addView(note(ctx, "没有可分享的目录"))
                    return@post
                }
                for (n in r.items) {
                    tree.addView(nodeRow(ctx, base, sid, n, { reloadList(); onChanged?.invoke() }) { kids, p ->
                        loadKids(p, kids)
                    })
                }
            }
        }
        reloadList()
        return root
    }

    /** 一个树节点行：▸ + 图标 + 名字 + 分享按钮；子节点容器点开才加载 */
    private fun nodeRow(
        ctx: Context, base: String, sid: String?, n: Share.Node,
        onShared: () -> Unit, loadKids: (LinearLayout, String) -> Unit
    ): View {
        val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 8 + 12 * nDepth(n.path)), dp(ctx, 5), dp(ctx, 8), dp(ctx, 5))
        }
        val arrow = TextView(ctx).apply {
            text = if (n.dir) "▸" else "·"
            textSize = 14f
            setTextColor(Palette.ACCENT_SOFT)
            setPadding(dp(ctx, 4), 0, dp(ctx, 6), 0)
        }
        row.addView(arrow, LinearLayout.LayoutParams(dp(ctx, 26), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(TextView(ctx).apply {
            text = (if (n.dir) "📁 " else "🎞 ") + n.name
            textSize = 13f
            setTextColor(Palette.FG)
            if (n.dir) setTypeface(typeface, Typeface.BOLD)
            setSingleLine(true)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(Button(ctx).apply {
            text = "分享"
            isAllCaps = false
            textSize = 12f
            setTextColor(Palette.ACCENT_SOFT)
            stateListAnimator = null
            backgroundTintList = null
            setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4))
            background = Palette.round(ctx, Palette.BTN, 7f)
            setOnClickListener { doShare(ctx, base, sid, n.path, n.name, onShared) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val kids = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        var loaded = false
        arrow.setOnClickListener {
            if (!n.dir) return@setOnClickListener
            if (kids.visibility == View.VISIBLE) {
                kids.visibility = View.GONE
                arrow.text = "▸"
            } else {
                kids.visibility = View.VISIBLE
                arrow.text = "▾"
                if (!loaded) {
                    loaded = true
                    loadKids(kids, n.path)
                }
            }
        }
        // 点名字也能展开（目录）
        row.setOnClickListener { if (n.dir) arrow.performClick() }

        wrap.addView(row)
        wrap.addView(kids)
        return wrap
    }

    /** 已有分享的一行：名字 + 分享按钮（ACTION_SEND）+ 撤销 */
    private fun shareRow(
        ctx: Context, base: String, sid: String?, item: Share.Item, onChanged: () -> Unit
    ): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 10), dp(ctx, 7), dp(ctx, 10), dp(ctx, 7))
            background = Palette.round(ctx, Palette.CARD, 8f, Palette.LINE, 1f)
        }
        box.addView(TextView(ctx).apply {
            text = (if (item.dir) "[目录] " else "") +
                    (if (item.name.isNotEmpty()) item.name else item.path) + "  " + item.code
            textSize = 12f
            setTextColor(Palette.FG)
            setSingleLine(true)
        })
        val acts = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 4), 0, 0)
        }
        acts.addView(Button(ctx).apply {
            text = "分享链接"; isAllCaps = false; textSize = 12f
            setTextColor(android.graphics.Color.WHITE); stateListAnimator = null; backgroundTintList = null
            background = Palette.round(ctx, Palette.ACCENT, 8f)
            setOnClickListener {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, item.url)
                }
                ctx.startActivity(Intent.createChooser(send, "分享链接"))
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(ctx, 4) })
        acts.addView(Button(ctx).apply {
            text = "撤销"; isAllCaps = false; textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#FF8A94")); stateListAnimator = null; backgroundTintList = null
            background = Palette.round(ctx, Palette.BTN, 8f)
            setOnClickListener {
                io.execute {
                    val d = Share.drop(base, sid, item.code, 20000)
                    ui.post {
                        Toast.makeText(ctx, if (d.ok && d.removed) "已撤销" else "撤销失败：" + d.error, Toast.LENGTH_SHORT).show()
                        onChanged()
                    }
                }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(ctx, 4) })
        box.addView(acts)
        return box
    }

    private fun doShare(ctx: Context, base: String, sid: String?, path: String, name: String, onShared: () -> Unit) {
        io.execute {
            val r = Share.create(base, sid, path, 20000)
            ui.post {
                if (!r.ok) {
                    Toast.makeText(ctx, "分享失败：" + r.error, Toast.LENGTH_SHORT).show()
                    return@post
                }
                Toast.makeText(ctx, "已生成 " + r.code, Toast.LENGTH_SHORT).show()
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, r.url)
                }
                ctx.startActivity(Intent.createChooser(send, "分享 $name"))
                onShared()
            }
        }
    }

    private fun note(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        setTextColor(Palette.FG2)
        setPadding(dp(ctx, 8), dp(ctx, 8), dp(ctx, 8), dp(ctx, 8))
    }

    /** 用路径深度当缩进层级：根目录浅、子目录深，最多 6 级防止挤爆 */
    private fun nDepth(path: String): Int = minOf(6, path.count { it == '/' } - 1).coerceAtLeast(0)

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
}
