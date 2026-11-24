package com.littlesauce.usbnaslink

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/**
 * 目录树（APK）。
 *
 * 服务端 `/_admin/api` 给的候选是「每个存储根下两层」的扁平列表，机械盘上
 * 那是几百上千行；平铺出来一屏扫不完，也看不出谁是谁的子目录。这里按路径
 * 前缀重组成可折叠的树，点方框勾选、点文字展开/收起。
 *
 * 树和文本域双向同步，文本域是唯一数据源：树上勾的会写进文本域，文本域被
 * 手改过会把勾状态重新标一遍。这样「手写一个候选里没有的路径」也不会被覆盖。
 */
class DirTree(private val ctx: Context) {

    private class Node(
        val path: String,
        var label: String,
        val root: Boolean,
        var checked: Boolean = false,
        var configuredOnly: Boolean = false
    )

    private class Row(val node: Node) {
        val children = ArrayList<Row>()
        var expanded = true
        var view: LinearLayout? = null
    }

    private val host = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val area = EditText(ctx).apply {
        setTextColor(Color.parseColor("#C9D4E2"))
        setHintTextColor(Color.parseColor("#5E6A7A"))
        hint = ctx.getString(R.string.dirtree_hint)
        setTypeface(Typeface.MONOSPACE)
        textSize = 11f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        setSingleLine(false)
        minLines = 2
        gravity = Gravity.TOP or Gravity.START
        setBackgroundColor(Color.parseColor("#12181F"))
        setPadding(dp(10), dp(8), dp(10), dp(8))
        // 软键盘会压住大半个屏幕；设置页不需要富文本，给它普通文本语义就够，
        // 但这行真正的用处是：让系统知道这是可编辑文本域，输入法才会正常接管
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private var top: Row = Row(Node("", "", true))
    private var marking = false
    /** 默认只显示「已选」那一支；点「显示全部目录」才铺开 */
    private var showAll = false

    init {
        // 文本域被手改：按新内容重标勾，不动结构（保住展开状态）
        area.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (marking) return
                mark(top, LinkedHashSet(lines(s?.toString())))
                render()
                // 边打字边重建会把光标顶到行首，把光标按原偏移放回末尾附近
                val at = area.text?.length ?: 0
                if (area.selectionStart != at) area.setSelection(at)
            }
        })
    }

    fun component(): View = host

    fun editor(): View = area

    fun checkedPaths(): List<String> = lines(area.text.toString())

    fun setPaths(paths: List<String>) {
        marking = true
        try {
            area.setText(paths.joinToString("\n"))
        } finally {
            marking = false
        }
    }

    /** 用服务端候选 + 当前配置重建整棵树 */
    fun rebuild(choices: List<Admin.DirChoice>, current: List<String>) {
        val conf = LinkedHashSet(current.map { norm(it) }.filter { it.isNotEmpty() })
        top = Row(Node("", ctx.getString(R.string.dirtree_candidates), true))

        val roots = LinkedHashMap<String, Row>()
        val dirs = LinkedHashMap<String, Row>()

        for (c in choices) {
            val path = norm(c.path)
            if (path.isEmpty()) continue
            val rootKey = rootKeyOf(path)
            val rn = roots.getOrPut(rootKey) {
                Row(Node(rootKey, labelOfRoot(rootKey, c.label, roots.size), true)).also {
                    top.children.add(it)
                }
            }
            val seg = split(path)
            var acc = rootKey
            var parent = rn
            for (i in split(rootKey).size until seg.size) {
                acc = "$acc/${seg[i]}"
                val dn = dirs.getOrPut(acc) {
                    Row(Node(acc, seg[i], false)).also { parent.children.add(it) }
                }
                parent = dn
            }
        }

        // 配置里有、候选里没有的路径，补成一支，标「已配置」
        for (c in conf) {
            if (c.isEmpty()) continue
            if (find(top, c) != null) continue
            val ph = Row(Node(c, lastSeg(c), false, checked = true, configuredOnly = true))
            val rn = roots[rootKeyOf(c)]
            (rn ?: top).children.add(ph)
        }

        mark(top, conf)
        // 树是「勾选状态」的唯一来源，文本框只是它的镜像。这里不把 conf 回灌
        // 进 area，就会出现「树里明明勾着 mwork、框里却是空的」，一点保存就
        // 把服务端配置清成空串。所以 mark() 之后必须立刻 syncArea()。
        syncArea()
        // 焦点先落在「已经配了什么」上：已选的那一支展开，其余折叠。
        // 一个 /storage/emulated/0 下就有几十上百个子目录，全展开光滚动就够呛。
        focus(top, conf)
        // 一个都没选时，「只显示已选」等于整棵树全空，用户会以为没得选（这就是
        // 「为什么只能选内部存储」的来源）。这种情况直接给全量视图。
        //
        // 但「全量视图」只展开到根这一层，绝不能用 expandAll(top)：内部存储下
        // 有几十上百个子目录，整棵铺开会有十几屏，U 盘那两个根被顶到很下面，
        // 看上去就像「只有内部存储可选」。根节点各占一行、点一下才展开，才是
        // 用户想看到的「盘 0 / 盘 1 / 内部存储」三个入口。
        if (conf.isEmpty()) {
            showAll = true
            // 三个根平铺、全部折叠，点哪个展开哪个。绝不预展开任何一支。
            for (r in top.children) r.expanded = false
        }
        render()
    }

    /** 含已选路径的节点展开，其它折叠 */
    private fun focus(r: Row, conf: Set<String>) {
        val holds = holdsChecked(r, conf)
        r.expanded = holds && r.children.isNotEmpty() && r.node.path.isNotEmpty()
        for (c in r.children) focus(c, conf)
    }

    /** r 这一支里有没有被选中的（含 r 自己） */
    private fun holdsChecked(r: Row): Boolean {
        if (r.node.path.isNotEmpty() && r.node.checked) return true
        for (c in r.children) if (holdsChecked(c)) return true
        return false
    }

    private fun holdsChecked(r: Row, conf: Set<String>): Boolean {
        if (r.node.path.isNotEmpty() && conf.contains(r.node.path)) return true
        for (c in r.children) if (holdsChecked(c, conf)) return true
        return false
    }

    // ---------------------------------------------------------------- 渲染

    private fun render() {
        host.removeAllViews()
        host.addView(header())
        var any = false
        for (r in top.children) {
            if (!showAll && !holdsChecked(r)) continue
            any = true
            host.addView(rowView(r, 0))
        }
        if (!any) {
            val none = top.children.isEmpty()
            host.addView(TextView(ctx).apply {
                text = if (none)
                    ctx.getString(R.string.dirtree_no_candidates)
                else
                    ctx.getString(R.string.dirtree_nothing_picked)
                textSize = 11f
                setTextColor(Color.parseColor("#7D8590"))
                setPadding(dp(4), dp(8), dp(4), dp(8))
            })
        }
    }

    /** 「已选 / 全部」两档：默认只看已选，避免一屏几十行把别的设置挤没 */
    private fun header(): View {
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(2))
        }
        val n = countChecked(top)
        bar.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.dirtree_count, n) + if (showAll) ctx.getString(R.string.dirtree_showing_all) else ctx.getString(R.string.dirtree_showing_picked)
            textSize = 11f
            setTextColor(Color.parseColor("#9AA4B2"))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(ctx).apply {
            text = if (showAll) ctx.getString(R.string.dirtree_show_picked) else ctx.getString(R.string.dirtree_show_all)
            textSize = 11f
            setTextColor(Color.parseColor("#5AA9FF"))
            setPadding(dp(10), dp(4), dp(4), dp(4))
            setOnClickListener {
                showAll = !showAll
                // 切到「显示全部」时也只展开到根这一层。整棵铺开的话，内部存储
                // 那一支就有十几屏，两个 U 盘根被顶到很下面，看上去就像「只有
                // 内部存储可选」——这正是用户最初报的问题。
                if (showAll) for (r in this@DirTree.top.children) r.expanded = false
                render()
            }
        })
        return bar
    }

    private fun countChecked(r: Row): Int {
        var n = if (r.node.path.isNotEmpty() && r.node.checked) 1 else 0
        for (c in r.children) n += countChecked(c)
        return n
    }

    private fun expandAll(r: Row) {
        r.expanded = r.children.isNotEmpty()
        for (c in r.children) expandAll(c)
    }

    private fun rowView(r: Row, depth: Int): View {
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4 + depth * 16), dp(5), dp(4), dp(5))
        }

        // 展开/收起的三角（只有有子节点时才画）
        val caret = TextView(ctx).apply {
            text = if (r.children.isEmpty()) "  " else if (r.expanded) "▾" else "▸"
            textSize = 11f
            setTextColor(Color.parseColor("#6B7480"))
            setPadding(0, 0, dp(4), 0)
            if (r.children.isNotEmpty()) {
                setOnClickListener {
                    r.expanded = !r.expanded
                    render()
                }
            }
        }
        line.addView(caret)

        // 方框：点它就是勾选/取消（连带整支）
        val box = TextView(ctx).apply {
            text = if (r.node.checked) "◼" else "◻"
            textSize = 13f
            setTextColor(Color.parseColor(if (r.node.checked) "#5AA9FF" else "#6B7480"))
            setPadding(0, 0, dp(6), 0)
            setOnClickListener {
                r.node.checked = !r.node.checked
                applySubtree(r, r.node.checked)
                render()
                syncArea()
            }
        }
        line.addView(box)

        // 名字 + 路径
        val name = TextView(ctx).apply {
            text = buildString {
                append(r.node.label)
                if (r.node.configuredOnly) {
                    append(ctx.getString(R.string.dirtree_configured_missing))
                } else if (r.node.root) {
                    append("  ")
                    append(r.node.path)
                }
            }
            textSize = 13f
            setTypeface(typeface, if (r.node.root) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(Color.parseColor(
                when {
                    r.node.configuredOnly -> "#FFC96B"
                    r.node.root -> "#E6E9EF"
                    else -> "#C9D4E2"
                }
            ))
            setOnClickListener {
                if (r.children.isNotEmpty()) {
                    r.expanded = !r.expanded
                    render()
                }
            }
        }
        line.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.view = line

        if (r.children.isEmpty() || !r.expanded) return line

        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(line)
            for (c in r.children) addView(rowView(c, depth + 1))
        }
        return wrap
    }

    // ---------------------------------------------------------------- 内部

    private fun syncArea() {
        marking = true
        try {
            area.setText(collect(top).joinToString("\n"))
        } finally {
            marking = false
        }
    }

    private fun applySubtree(r: Row, on: Boolean) {
        r.node.checked = on
        for (c in r.children) applySubtree(c, on)
    }

    private fun mark(r: Row, set: Set<String>) {
        r.node.checked = set.contains(r.node.path) && r.node.path.isNotEmpty()
        for (c in r.children) mark(c, set)
    }

    /**
     * 勾父级就等于要整个父级，所以子节点不再重复输出。
     * 之前的 collect 会把「内部存储」展开成 45 行，存到服务端后不仅难读，
     * 还会让 /_music 的 dirs 列表显示 45 个同名块。
     */
    private fun collect(r: Row): List<String> {
        if (r.node.path.isNotEmpty() && r.node.checked) return listOf(r.node.path)
        val out = ArrayList<String>()
        for (c in r.children) out.addAll(collect(c))
        return out
    }

    private fun find(r: Row, path: String): Row? {
        if (r.node.path == path) return r
        for (c in r.children) find(c, path)?.let { return it }
        return null
    }

    private fun norm(p: String?): String {
        var s = (p ?: "").trim()
        while (s.length > 1 && s.endsWith("/")) s = s.substring(0, s.length - 1)
        return s
    }

    private fun split(path: String): List<String> =
        path.trim('/').split('/').filter { it.isNotEmpty() }

    /** 根键：/storage/emulated/0、/mnt/media_rw/XXXX 这类三段路径整体当根 */
    private fun rootKeyOf(path: String): String {
        val s = split(path)
        return when {
            path.startsWith("/storage/emulated/") && s.size >= 3 -> "/${s[0]}/${s[1]}/${s[2]}"
            path.startsWith("/mnt/") && s.size >= 3 -> "/${s[0]}/${s[1]}/${s[2]}"
            s.isNotEmpty() -> "/${s[0]}"
            else -> path
        }
    }

    private fun labelOfRoot(key: String, serverLabel: String?, seq: Int): String = when {
        key.startsWith("/storage/emulated/") -> ctx.getString(R.string.dirtree_internal)
        key.startsWith("/mnt/media_rw/") -> ctx.getString(R.string.dirtree_external, split(key).last())
        serverLabel.isNullOrEmpty() -> ctx.getString(R.string.dirtree_storage_n, seq + 1)
        else -> serverLabel
    }

    private fun lastSeg(path: String): String = split(path).lastOrNull() ?: path

    private fun lines(raw: String?): List<String> =
        (raw ?: "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
}
