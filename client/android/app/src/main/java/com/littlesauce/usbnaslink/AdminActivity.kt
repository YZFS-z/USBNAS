package com.littlesauce.usbnaslink

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * 管理设置页（APK）。
 *
 * 站点名称、音乐目录、影视目录都是「设一次、所有客户端共用」的全局配置，
 * 存在服务端；这里的界面只是它们的一个编辑入口。谁能改由服务端
 * `/_admin/api` 的 `admin` 字段说了算 —— 客户端只读不判，避免两份校验错位。
 *
 * 目录用树来选（[DirTree]）：服务端给的是「每个存储根下两层」的扁平候选，
 * 机械盘上那能有几百上千行，平铺一屏根本扫不完。树能折叠，一层层点下去。
 */
class AdminActivity : android.app.Activity() {

    companion object {
        const val EXTRA_URL = "base_url"
        const val EXTRA_SID = "sid"

        fun intent(ctx: Context, baseUrl: String, sid: String?): android.content.Intent =
            android.content.Intent(ctx, AdminActivity::class.java)
                .putExtra(EXTRA_URL, baseUrl)
                .putExtra(EXTRA_SID, sid)
    }

    private lateinit var stateText: TextView
    private lateinit var spinner: View
    private lateinit var body: LinearLayout
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "usbnas-admin").apply { isDaemon = true }
    }

    private var baseUrl: String = ""
    private var sid: String? = null

    private var titleBox: EditText? = null
    private var musicTree: DirTree? = null
    private var videoTree: DirTree? = null
    private var bookTree: DirTree? = null
    private var saveTitle: Button? = null
    private var saveMusic: Button? = null
    private var saveVideo: Button? = null
    private var saveBook: Button? = null
    private var last: Admin.State? = null
    private var admin = false

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        baseUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        sid = intent.getStringExtra(EXTRA_SID)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E1116"))
        }

        // 顶栏
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(8), dp(8), dp(8))
        }
        bar.addView(TextView(this).apply {
            text = "‹"
            textSize = 26f
            setTextColor(Color.parseColor("#9AA4B2"))
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { finish() }
        })
        bar.addView(TextView(this).apply {
            text = "管理设置"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#E6E9EF"))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(this).apply {
            text = "刷新"
            textSize = 13f
            setTextColor(Color.parseColor("#5AA9FF"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { load() }
        })
        root.addView(bar)

        root.addView(TextView(this).apply {
            text = baseUrl
            textSize = 11f
            setTextColor(Color.parseColor("#6B7480"))
            setPadding(dp(16), 0, dp(16), dp(6))
        })

        stateText = TextView(this).apply {
            text = "正在读取…"
            textSize = 12f
            setTextColor(Color.parseColor("#9AA4B2"))
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        root.addView(stateText)

        spinner = android.widget.ProgressBar(this).apply {
            visibility = View.GONE
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        root.addView(spinner)

        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        root.addView(
            android.widget.ScrollView(this).apply {
                // 关键：ScrollView 的子视图必须是「高度 = WRAP_CONTENT」。
                // addView(body) 不传 LayoutParams 时走 generateDefaultLayoutParams()，
                // 对 ScrollView 来说是 FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)，
                // body 就只拿到滚动视口那么高，多出来的内容被裁掉且滚不动 ——
                // 表现就是「设置项只显示到音乐目录说明文字为止」，目录树永远看不见。
                addView(
                    body,
                    android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        setContentView(root)
        // 状态栏/手势栏透明，看到的永远是 root 的 #0E1116（详见 Immersive.tintBars）
        Immersive.tintBars(this, Color.parseColor("#0E1116"))
        load()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 读取

    private fun load() {
        if (baseUrl.isEmpty()) {
            stateText.text = "没有服务器地址"
            return
        }
        spinner.visibility = View.VISIBLE
        val base = baseUrl
        val ck = sid
        io.execute {
            val st = try {
                Admin.fetch(base, ck, 9000)
            } catch (t: Throwable) {
                Admin.State().apply { ok = false; error = "${t.message}" }
            }
            runOnUiThread {
                spinner.visibility = View.GONE
                apply(st)
            }
        }
    }

    private fun apply(st: Admin.State) {
        last = st
        body.removeAllViews()
        titleBox = null
        musicTree = null
        videoTree = null
        bookTree = null

        if (!st.ok) {
            stateText.text = "读取失败：${st.error ?: "没有响应"}"
            body.addView(note("连不上服务端，或者它还不支持 /_admin/api。", "#FF8A94"))
            return
        }
        admin = st.admin
        if (!st.admin) {
            stateText.text = if (st.logged)
                "当前账号「${st.who}」是${st.levelName}，改设置需要管理员"
            else
                "需要管理员账号"
            body.addView(note(
                "站点名称、音乐目录、影视目录是全局配置，只有管理员能改。" +
                    "先用管理员账号在网页端登录，再回到这里就会自动带上登录状态。",
                "#9AA4B2"
            ))
            val b = Button(this).apply {
                text = if (st.logged) "换管理员账号" else "去网页端登录"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#2F6FED"))
                setOnClickListener {
                    startActivity(BrowseActivity.intent(this@AdminActivity, baseUrl, null))
                }
            }
            body.addView(b, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
            ).apply { topMargin = dp(18) })
            return
        }

        stateText.text = "管理员：${st.who}（${st.levelName}）"

        // ---- 站点名称
        titleBox = EditText(this).apply {
            setText(st.title ?: "")
            setTextColor(Color.parseColor("#E6E9EF"))
            setHintTextColor(Color.parseColor("#5E6A7A"))
            hint = "显示在浏览器标题栏和响应头里"
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 15f
            setSingleLine()
            setBackgroundColor(Color.parseColor("#171B22"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        section(
            "站点名称",
            "最多 24 个字符；留空会用默认的「一只肥鼠的NAS」",
            titleBox!!, null, { save("title", "站点名称", titleBox?.text?.toString() ?: "") }
        ).let { body.addView(it.first); saveTitle = it.second }

        // ---- 音乐目录
        musicTree = DirTree(this).apply { setPaths(st.musicDirs ?: emptyList()) }
        section(
            "音乐目录",
            "客户端播放器只在这些目录里找歌；可以勾多个（盘0/音乐 + 盘1/音乐 各一部分）。点方框勾选，点文字展开。",
            null, musicTree, { save("music", "音乐目录", musicTree?.checkedPaths()?.joinToString("\n") ?: "") }
        ).let { body.addView(it.first); saveMusic = it.second }
        musicTree!!.rebuild(st.choices ?: emptyList(), st.musicDirs ?: emptyList())

        // ---- 影视目录
        videoTree = DirTree(this).apply { setPaths(st.videoDirs ?: emptyList()) }
        section(
            "影视目录",
            "电影和电视剧可以分在不同盘里，客户端会按目录分块显示；海报和 .nfo 从视频同目录读。",
            null, videoTree, { save("video", "影视目录", videoTree?.checkedPaths()?.joinToString("\n") ?: "") }
        ).let { body.addView(it.first); saveVideo = it.second }
        videoTree!!.rebuild(st.choices ?: emptyList(), st.videoDirs ?: emptyList())

        // ---- 图书目录
        bookTree = DirTree(this).apply { setPaths(st.bookDirs ?: emptyList()) }
        section(
            "图书目录",
            "客户端「图书」页只显示这里的书。认 epub/pdf/mobi/azw3/fb2/djvu/cbz/cbr/txt/md；不配就是空书库（不会去扫全盘找书）。",
            null, bookTree, { save("book", "图书目录", bookTree?.checkedPaths()?.joinToString("\n") ?: "") }
        ).let { body.addView(it.first); saveBook = it.second }
        bookTree!!.rebuild(st.choices ?: emptyList(), st.bookDirs ?: emptyList())

        for (b in listOfNotNull(saveTitle, saveMusic, saveVideo, saveBook)) b.isEnabled = admin
    }

    // ---------------------------------------------------------------- 保存

    private fun save(action: String, label: String, value: String) {
        if (!admin) {
            Toast.makeText(this, "当前会话不是管理员", Toast.LENGTH_SHORT).show()
            return
        }
        spinner.visibility = View.VISIBLE
        val base = baseUrl
        val ck = sid
        val dirs = lines(value)
        io.execute {
            val r = try {
                when (action) {
                    "title" -> Admin.saveTitle(base, ck, value, 9000)
                    "music" -> Admin.saveMusicDirs(base, ck, dirs, 9000)
                    "book" -> Admin.saveBookDirs(base, ck, dirs, 9000)
                    else -> Admin.saveVideoDirs(base, ck, dirs, 9000)
                }
            } catch (t: Throwable) {
                Admin.Saved().apply { ok = false; error = "${t.message}" }
            }
            runOnUiThread {
                spinner.visibility = View.GONE
                Toast.makeText(
                    this@AdminActivity,
                    if (r.ok) "$label 已保存（${r.msg ?: "已生效"}）" else "$label 没保存：${r.error ?: "未知错误"}",
                    if (r.ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()
                if (r.ok) load()
            }
        }
    }

    // ---------------------------------------------------------------- 小节

    /** 标题 + 说明 + （输入框 或 目录树）+ 保存按钮；返回「整块 + 保存按钮」 */
    private fun section(
        title: String,
        hintText: String,
        editor: View?,
        tree: DirTree?,
        onSave: () -> Unit
    ): Pair<LinearLayout, Button> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, dp(10))
        }
        box.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#E6E9EF"))
        })
        box.addView(TextView(this).apply {
            text = hintText
            textSize = 11f
            setTextColor(Color.parseColor("#7D8590"))
            setPadding(0, dp(4), 0, dp(8))
        })
        if (editor != null) {
            box.addView(editor, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        if (tree != null) {
            // 不要把树塞进内层 ScrollView：外面已经有一层 ScrollView，
            // 嵌套的同向滚动容器在测量时拿不到高度，树会渲染成 0 高度（连
            // 「目录树 · 已选 N 个」这行都看不见）。树自带「只显示已选」的
            // 折叠，铺开也就十几行，直接放进外层滚动容器即可。
            val tv = tree.component()
            box.addView(
                tv,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            )
            box.addView(TextView(this).apply {
                text = "已选路径（保存的就是这里的每一行，也可以直接手改）"
                textSize = 11f
                setTextColor(Color.parseColor("#7D8590"))
                setPadding(0, dp(8), 0, dp(4))
            })
            box.addView(tree.editor(), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        val btn = Button(this).apply {
            text = "保存$title"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2F6FED"))
            setOnClickListener { onSave() }
        }
        box.addView(btn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
        ).apply { topMargin = dp(12) })
        return box to btn
    }

    // ---------------------------------------------------------------- 小工具

    private fun note(text: String, color: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(Color.parseColor(color))
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 多行文本 → 路径列表，空行和重复项都吃掉 */
    private fun lines(raw: String): List<String> =
        raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
