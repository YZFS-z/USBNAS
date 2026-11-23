package com.littlesauce.usbnas

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

class NasService : Service() {

    companion object {
        const val PORT = 8080
        const val CHANNEL_ID = "usbnas"
        const val NOTIF_ID = 1001

        const val ACTION_START = "com.littlesauce.usbnas.START"
        const val ACTION_STOP = "com.littlesauce.usbnas.STOP"

        @Volatile var isRunning = false
        @Volatile var stats: Pair<Long, Long>? = null

        /**
         * 服务真正开始监听的那一刻（墙钟毫秒）。
         *
         * 首页原来显示的是「系统开机多久」，那是 SystemClock.elapsedRealtime()，
         * 跟服务本身没关系 —— 手机开着一天没重启、服务刚起两分钟，它也显示
         * 「1 天 3 小时」，看着像服务跑了那么久。
         * 这里改成记服务自己的启动时刻，零点表示「没在跑」。
         */
        @Volatile var startedAt: Long = 0L

        /** Activity 附着时设置，用来接收实时日志 */
        @Volatile var logSink: ((String) -> Unit)? = null

        /** 最多保留 300 条历史日志，供 Activity 重入时回放 */
        val recentLogs = ArrayDeque<String>()

        fun pushLog(line: String) {
            synchronized(recentLogs) {
                recentLogs.addLast(line)
                while (recentLogs.size > 300) recentLogs.removeFirst()
            }
            logSink?.invoke(line)
        }

        /** 运行中的 server 引用，供账号/匿名开关改动后热更新，避免重启服务 */
        @Volatile private var liveConfig: HttpServer.Config? = null

        /** 运行中的 server，U 盘插拔后用来清共享目录缓存 */
        @Volatile private var liveServer: HttpServer? = null

        /** U 盘插上/拔出后调用：让下一个请求立刻重新扫卷，网页端无需重启服务 */
        fun invalidateRoots() {
            liveServer?.invalidateRoots()
        }

        /**
         * 服务是否处在「长时间没人访问」状态。
         *
         * 网页端一旦空闲，目录扫描就被冻结，此时首页不该再去对 U 盘做
         * StatFs —— 那会立刻把机械盘唤醒，白白抵消掉休眠。
         */
        fun isIdle(): Boolean = liveServer?.isRootsFrozen() ?: false

        /** 空闲了多久（毫秒），没在跑返回 0 */
        fun idleMs(): Long = liveServer?.idleMs() ?: 0L

        /**
         * 按当前设置给外接盘套上/摘掉空闲休眠。
         *
         * 服务启动、插拔 U 盘、用户改开关时都要调一次：
         * sysfs 状态不会跟着 App 存活，重启手机后一律回到常转。
         */
        fun applyUsbSleep(context: android.content.Context) {
            val on = AppPrefs.usbSleep(context.applicationContext)
            val sec = AppPrefs.usbSleepSec(context.applicationContext)
            val ok = USBSleep.apply(on, sec)
            pushLog(
                when {
                    !ok -> "😴 U 盘休眠设置失败（需要 root）"
                    on -> "😴 U 盘空闲 ${sec} 秒后自动停转"
                    else -> "😴 U 盘休眠已关闭，保持常转"
                }
            )
        }

        /** App 内改完账号后调用：立即生效，不必重启服务 */
        fun reloadAuth(context: android.content.Context) {
            val cfg = liveConfig ?: return
            cfg.reload(context.applicationContext)
            pushLog("🔐 账号已更新：${cfg.users?.byName?.size ?: 0} 个，" +
                    if (cfg.anonymousRead) "允许匿名只读" else "需登录访问")
        }

        /** App 内改完标题后调用：网页 title 与顶栏立刻换掉，不必重启服务 */
        fun reloadTitle(context: android.content.Context) {
            liveConfig?.setTitle(AppPrefs.title(context.applicationContext))
        }

        /** App 内改完音乐目录后调用：曲库缓存立刻作废，客户端下次请求拿到新列表 */
        fun reloadMusicDirs(context: android.content.Context) {
            val cfg = liveConfig ?: return
            cfg.setMusicDirs(AppPrefs.musicDirs(context.applicationContext))
            pushLog("🎵 音乐目录已更新：${cfg.musicDirs.size} 个")
        }

        /** App 内改完影视目录后调用：影视库缓存立刻作废，可同时存在多个盘 */
        fun reloadVideoDirs(context: android.content.Context) {
            val cfg = liveConfig ?: return
            cfg.setVideoDirs(AppPrefs.videoDirs(context.applicationContext))
            pushLog("🎬 影视目录已更新：${cfg.videoDirs.size} 个")
        }

        /** App 内改完图书目录后调用：书库缓存立刻作废，可同时存在多个书架根 */
        fun reloadBookDirs(context: android.content.Context) {
            val cfg = liveConfig ?: return
            cfg.setBookDirs(AppPrefs.bookDirs(context.applicationContext))
            pushLog("📚 图书目录已更新：${cfg.bookDirs.size} 个")
        }
    }

    private var server: HttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须先 startForeground 才能做任何耗时动作（Android 8+）
        ensureForeground("正在启动…")

        if (intent?.action == ACTION_STOP) {
            stopServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startServer()
        return START_STICKY
    }

    private fun startServer() {
        if (isRunning) {
            val ip = NetUtils.bestIp(this) ?: "0.0.0.0"
            updateNotification("http://$ip:$PORT/")
            return
        }

        val roots = StorageUtils.listRoots(this)
        if (roots.isEmpty()) {
            pushLog("⚠ 没有可共享目录，插上 U 盘后重启服务")
        } else {
            roots.forEach { pushLog("📁 共享: ${it.absolutePath}") }
        }

        acquireWakeLock()

        // 运行时状态采集
        val sys = SysInfo.get(this)
        sys.start()

        val cfg = HttpServer.Config().apply {
            port = PORT
            title = AppPrefs.title(this@NasService)
            detectLyrics = true
            users = Users.Snapshot.of(this@NasService)
            anonymousRead = Users.allowAnonymous(this@NasService)
            this.sys = sys
            // 读 assets 里的离线渲染库（docx-preview / SheetJS / jszip）要用
            appContext = applicationContext
            // U 盘可以随时插拔，每次都要重新扫卷，不能用启动时的快照
            rootsSupplier = HttpServer.Config.RootsSupplier {
                StorageUtils.listRoots(this@NasService)
            }
            // 曲库目录跟着服务一起起来：客户端一连上就有完整列表，不用等 App 里再点一次
            musicDirs = AppPrefs.musicDirs(this@NasService)
            // 影视目录同理，允许多个（盘0/盘1 各放一部分）
            videoDirs = AppPrefs.videoDirs(this@NasService)
            // 图书目录（多个书架根）；不配就是空书库，绝不回落去扫全盘
            bookDirs = AppPrefs.bookDirs(this@NasService)
        }
        pushLog("🔐 账号 " + cfg.users!!.byName.size + " 个，" +
                if (cfg.anonymousRead) "允许匿名只读" else "需登录访问")
        // 排查用：把每个账号的等级与目录范围打出来，登录不上时一眼能看出是没读到还是密码不对
        cfg.users!!.byName.values.forEach { u ->
            Log.i("NasService", "user=${u.name} level=${u.level} scopes=${u.scopes}")
        }
        liveConfig = cfg

        val s = HttpServer(cfg, roots) { line -> pushLog(line) }
        liveServer = s
        try {
            s.start()
            server = s
            isRunning = true
            startedAt = System.currentTimeMillis()
            cfg.startedAt = startedAt

            // 服务真起来了再动 sysfs：root 授权是异步的，太早调用会静默失败
            Thread { applyUsbSleep(this@NasService) }.apply { isDaemon = true }.start()

            val ip = NetUtils.bestIp(this) ?: "0.0.0.0"
            pushLog("✅ 服务已启动 http://$ip:$PORT/")
            updateNotification("http://$ip:$PORT/")

            Thread {
                while (isRunning) {
                    stats = Pair(s.reqCount, s.bytesOut)
                    try {
                        Thread.sleep(1000)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }.apply { isDaemon = true }.start()

        } catch (e: Exception) {
            pushLog("❌ 启动失败: ${e.message}")
            Log.e("NasService", "start failed", e)
            isRunning = false
            releaseWakeLock()
            sys.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopServer() {
        if (!isRunning) return
        pushLog("正在停止…")
        try {
            server?.stop()
        } catch (e: Exception) {
            Log.w("NasService", "stop", e)
        }
        server = null
        isRunning = false
        startedAt = 0L
        stats = null
        releaseWakeLock()
        SysInfo.get(this).stop()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    // ---------- WakeLock ----------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "USBNAS::server").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
            pushLog("已持有 WakeLock，防休眠断流")
        } catch (e: Exception) {
            Log.w("NasService", "wakelock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            Log.w("NasService", "release", e)
        }
        wakeLock = null
    }

    // ---------- 通知 ----------

    private fun ensureForeground(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "USBNAS 服务", NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "文件共享服务运行状态"
                        setShowBadge(false)
                    })
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, buildNotification(text),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)

        return b.setContentTitle("USBNAS")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.w("NasService", "notify", e)
        }
    }
}
