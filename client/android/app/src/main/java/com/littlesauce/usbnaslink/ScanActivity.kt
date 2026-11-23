package com.littlesauce.usbnaslink

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 启动页：进来立刻扫局域网，扫到唯一一台就直接进；扫到多台就列出让用户挑。
 *
 * 为什么先读 Prefs.lastUrl 再去扫：
 *   扫描一轮 2~5 秒。如果上次刚连过同一台 NAS，它的 IP 大概率没变
 *   （家庭/热点环境里 DHCP 租期远长于人的耐心），所以先并发探一下历史地址，
 *   探通就直接跳，后台再扫一遍补全列表。用户感知是「秒开」。
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var adapter: ServiceAdapter
    private lateinit var list: RecyclerView
    private lateinit var spinner: View
    private lateinit var subtitle: TextView
    private lateinit var empty: TextView

    private val io = Executors.newCachedThreadPool { r ->
        Thread(r, "usbnas-scan").apply { isDaemon = true }
    }
    private val scanning = AtomicBoolean(false)
    private var scanner: Scanner? = null
    private var autoOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)
        // 扫描页现在是深色底 #0F141A（同 Palette.BG）：系统栏透明后看到的永远是它，
        // tintBars 会按亮度自动选浅色图标。
        Immersive.tintBars(this, Palette.BG)
        prefs = Prefs(this)

        list = findViewById(R.id.list)
        spinner = findViewById(R.id.spinner)
        subtitle = findViewById(R.id.subtitle)
        empty = findViewById(R.id.empty)

        adapter = ServiceAdapter(
            onPick = { si -> connectTo(si) },
            connectedKey = { prefs.lastUrl?.removePrefix("http://") }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<View>(R.id.btnRescan).setOnClickListener { startScan(true) }
        findViewById<View>(R.id.btnManual).setOnClickListener { askManual() }
        // 自动更新放在首页底部：连不上 NAS 的时候最需要它（服务端升级后客户端
        // 不跟着升，握手就对不上了），而这种时候进不去媒体库，藏在设置页里的
        // 入口等于没有。manual=true 表示是用户主动点的，查不到新版本也要给回执。
        findViewById<View>(R.id.btnUpdate).setOnClickListener {
            ApkUpdater.checkOnStart(this, true)
        }
        // 「媒体库」与「管理设置」两个按钮已删：点列表就是进媒体库，
        // 管理设置在媒体库的第四个页签里（站点名称/音乐/影视/图书目录 + App 自身设置），
        // 两处各留一份入口只会让同一个东西看起来像两个东西。

        askPermissionsThenStart()
    }

    // ---------------------------------------------------------------- 权限

    /**
     * 安卓 13 起读 Wi-Fi / 邻居设备要 NEARBY_WIFI_DEVICES。不给也能扫
     * （TCP 连接不受限制），只是拿不到 SSID 之类的提示信息，所以拒绝了照样往下走，
     * 绝不因为权限把用户拦在门口。
     */
    private fun askPermissionsThenStart() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            startScan(true)
            return
        }
        val perm = Manifest.permission.NEARBY_WIFI_DEVICES
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            startScan(true)
            return
        }
        requestPermissions(arrayOf(perm), 1001)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) startScan(true)
    }

    // ---------------------------------------------------------------- 扫描

    private fun startScan(showProgress: Boolean) {
        if (!scanning.compareAndSet(false, true)) return
        android.util.Log.i("USBNASLink", "startScan begin")
        if (showProgress) {
            spinner.visibility = View.VISIBLE
            subtitle.text = getString(R.string.scan_hint)
        }
        autoOpened = false

        io.execute {
            // 网卡是本项目最容易出问题的地方（多 Wi-Fi、AP 隔离、/17 大网段），
            // 所以开扫前把实际枚举结果打出来，装机排障全靠这几行。
            try {
                val nics = Host.nics()
                for (n in nics) {
                    android.util.Log.i("USBNASLink",
                        "nic ${n.name} ${n.cidr()} bc=${Host.addr(n.broadcast())} " +
                            "targets=${Host.expand(n, 2).size}")
                }
                android.util.Log.i("USBNASLink", "nic count=${nics.size}")
                // 顺手对每个网卡所在 /24 的 x.1 与自己的 IP 做一次建连自检：
                // 安卓上 UID 缺少 INTERNET 权限、或系统对该 App 掐了网络，
                // 表现就是「全 0 命中」，日志里这两行能立刻区分是网络问题还是代码问题。
                for (n in nics) {
                    val self = Host.addr(n.ip)
                    android.util.Log.i("USBNASLink", "diag self " + Diag.tcp(self, 8080).text)
                    android.util.Log.i("USBNASLink", "diag http " + Diag.http(self, 8080).text)
                }
                // 关键回归点：expand() 必须把自己那一格 /24 里除自己以外的地址都列出来。
                // 之前在安卓上出现过「760 个地址全 0 命中」，就是这里该有却没有。
                val w1 = nics.firstOrNull { it.name == "wlan1" }
                if (w1 != null) {
                    val ex = Host.expand(w1, 2)
                    val hasSelf = ex.any { it.ipText == Host.addr(w1.ip) }
                    android.util.Log.i("USBNASLink",
                        "expand wlan1 size=${ex.size} containsSelf=$hasSelf " +
                            "first=${ex.firstOrNull()?.ipText} hash=${ex.hashCode()}")
                    val probe = Probe.isOpen(Host.addr(w1.ip), 8080, 1500)
                    android.util.Log.i("USBNASLink", "Probe.isOpen(self)=$probe")
                    // 用工线上同一个线程池跑一次「同地址 connect」，验证线程池路径本身
                    val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
                    val futs = (1..8).map {
                        pool.submit(java.util.concurrent.Callable {
                            Probe.isOpen(Host.addr(w1.ip), 8080, 1500)
                        })
                    }
                    val got = futs.map {
                        try { it.get(5, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Throwable) { "EX" }
                    }
                    pool.shutdownNow()
                    android.util.Log.i("USBNASLink", "pool isOpen x8 = $got")
                    // 打头 3 个 + 尾 3 个实际地址，以及它们各自 connect 的结果。
                    // 「1.2 秒扫完 256 个」= ARP 全被拒，说明连的不是本网段或全网段没人。
                    val sample = (ex.take(3) + ex.takeLast(3)).distinct()
                    for (h in sample) {
                        val ok = Probe.isOpen(h.ipText, 8080, 400)
                        android.util.Log.i("USBNASLink", "sample ${h.ipText} open=$ok")
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("USBNASLink", "nics() failed", t)
            }
            // 先探历史地址：通了就直接进，感知上等于秒开
            if (showProgress) quickHistoryProbe()

            val opt = Scanner.Options().apply {
                port = 8080
                strict = true
                maxSubnets = 2
                discoveryMs = 1100
                threads = 64   // 手机别开太大，96 条线程在小内存机上会抖
            }
            scanner = Scanner(opt)
            scanner!!.scanDebug = java.util.function.Consumer { m ->
                android.util.Log.i("USBNASLink", m)
            }
            val found = scanner!!.scan(object : Scanner.Progress {
                override fun onProgress(phase: String, done: Int, total: Int, note: String) {
                    android.util.Log.i("USBNASLink", "$phase $done/$total $note")
                    runOnUiThread {
                        if (phase == "DONE") return@runOnUiThread
                        subtitle.text = note
                    }
                }

                override fun onFound(si: ServiceInfo): Boolean {
                    android.util.Log.i("USBNASLink", "FOUND ${si.baseUrl} ${si.displayName()}")
                    runOnUiThread {
                        adapter.add(si)
                        empty.visibility = View.GONE
                        maybeAutoOpen()
                    }
                    return true
                }
            })
            android.util.Log.i("USBNASLink", "scan done, found=${found.size}")

            runOnUiThread {
                scanning.set(false)
                spinner.visibility = View.GONE
                subtitle.text = if (found.isEmpty()) {
                    getString(R.string.scan_hint)
                } else {
                    "找到 ${found.size} 台"
                }
                empty.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
                maybeAutoOpen()
            }
        }
    }

    /** 探一次上次成功连接的地址；通了就跳转，不阻塞扫描 */
    private fun quickHistoryProbe() {
        val url = prefs.lastUrl ?: return
        val hp = url.removePrefix("http://")
        val c = hp.lastIndexOf(':')
        if (c <= 0) return
        val ip = hp.substring(0, c)
        val port = hp.substring(c + 1).toIntOrNull() ?: return
        val si = Probe.classify(ip, port) ?: return
        runOnUiThread {
            adapter.add(si)
            empty.visibility = View.GONE
            maybeAutoOpen()
        }
    }

    /**
     * 扫到的服务「唯一且不是刚退回来的那台」时自动进。
     *
     * 加 autoOpened 与 lastUrl 比对是为了防止：用户从浏览页按返回键回到扫描页，
     * 扫描回调又把用户弹回去 —— 那会变成退不出去的死循环。
     */
    private fun maybeAutoOpen() {
        if (autoOpened) return
        val all = adapter.all()
        if (all.size != 1) return
        val only = all[0]
        val last = prefs.lastUrl
        if (last != null && last == only.baseUrl) {
            // 上次就是这个，说明用户是主动返回的，别自作主张
            autoOpened = true
            return
        }
        autoOpened = true
        connectTo(only)
    }

    /**
     * 连上之后进的是**媒体库**，不是网页。
     *
     * 手机上影视/音乐/图书这三块原生页面比 WebView 好用得多（播放器、歌词、
     * PDF 都在原生侧），所以卡片点下去直接进媒体库；网页版没有丢，媒体库
     * 顶栏那个「网页」按钮就是它（BrowseActivity），只是从主入口降成副入口。
     *
     * 之前这里跳的是 BrowseActivity，而底部那个「媒体库」按钮被删掉了 ——
     * 结果媒体库在 UI 上没有任何入口，只能 am start 硬拉。这里一并修掉。
     */
    private fun connectTo(si: ServiceInfo) {
        prefs.lastUrl = si.baseUrl
        prefs.lastName = si.displayName()
        val i = Intent(this, LibraryActivity::class.java)
        i.putExtra(LibraryActivity.EXTRA_URL, si.baseUrl)
        i.putExtra(LibraryActivity.EXTRA_NAME, si.displayName())
        i.putExtra(LibraryActivity.EXTRA_SID, prefs.sid)
        startActivity(i)
    }

    /** 直接进媒体库：connectTo() 已改走这条路，保留给「手动输入成功后重进」用 */
    private fun openLibrary(url: String, name: String?) {
        val i = Intent(this, LibraryActivity::class.java)
        i.putExtra(LibraryActivity.EXTRA_URL, url)
        i.putExtra(LibraryActivity.EXTRA_NAME, name)
        i.putExtra(LibraryActivity.EXTRA_SID, prefs.sid)
        startActivity(i)
    }

    /** 手动输入 ip[:port] —— 有些路由器开了 AP 隔离，广播和扫描都会被掐，只能手填 */
    private fun askManual() {
        val pad = (48 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this)
        box.setPadding(pad, pad / 2, pad, 0)
        val input = EditText(this).apply {
            hint = "192.168.1.10 或 192.168.1.10:8080"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            prefs.lastUrl?.let { setText(it.removePrefix("http://")) }
        }
        box.addView(input)

        AlertDialog.Builder(this)
            .setTitle(R.string.manual)
            .setView(box)
            .setNegativeButton(R.string.login_cancel, null)
            .setPositiveButton(R.string.connect) { _, _ ->
                val raw = input.text.toString().trim()
                    .removePrefix("http://").removeSuffix("/")
                if (raw.isEmpty()) return@setPositiveButton
                val hp = if (raw.contains(':')) raw else "$raw:8080"
                val c = hp.lastIndexOf(':')
                val ip = hp.substring(0, c)
                val port = hp.substring(c + 1).toIntOrNull() ?: 8080
                io.execute {
                    val si = Probe.classify(ip, port)
                    runOnUiThread {
                        if (si != null) {
                            adapter.add(si)
                            connectTo(si)
                        } else {
                            Toast.makeText(this, "连不上 $hp", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }

    override fun onDestroy() {
        scanner?.cancel()
        io.shutdownNow()
        super.onDestroy()
    }
}
