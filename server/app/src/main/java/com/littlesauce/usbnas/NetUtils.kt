package com.littlesauce.usbnas

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetUtils {

    /**
     * 选一个最合适的本机 IPv4：
     *   1. 热点接口（ap0 / softap0 / wlan1 / swlan0）
     *   2. wlan0（连了局域网 Wi-Fi）
     *   3. 任意非回环、非 p2p、非蜂窝的地址
     */
    fun bestIp(@Suppress("UNUSED_PARAMETER") ctx: Context?): String? {
        val candidates = ArrayList<Pair<String, String>>() // iface -> ip
        try {
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name ?: continue
                if (name.startsWith("p2p") || name.startsWith("dummy")
                    || name.startsWith("rmnet") || name.startsWith("tun")
                    || name.startsWith("ccmni")) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        candidates.add(name to (addr.hostAddress ?: continue))
                        break
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
        if (candidates.isEmpty()) return null

        val apNames = listOf("ap0", "softap0", "wlan1", "swlan0")
        for (n in apNames) {
            candidates.firstOrNull { it.first == n }?.let { return it.second }
        }
        candidates.firstOrNull { it.first == "wlan0" }?.let { return it.second }
        return candidates.first().second
    }

    /** 是否正在作为热点发射 */
    fun isHotspotOn(): Boolean = try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .any {
                it.isUp && (it.name == "ap0" || it.name == "softap0" || it.name == "swlan0")
            }
    } catch (e: Exception) {
        false
    }

    /** 蜂窝数据是否在用（仅用于提示） */
    fun hasCellular(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_MOBILE
    } catch (e: Exception) {
        false
    }
// ==================== 热点凭据（给二维码用） ====================

    /**
     * 读本机热点名称。
     *
     * MIUI 上 WifiManager.getSoftApConfiguration() 对普通 App 直接 SecurityException，
     * 所以主路是 RootShell 走 su 读 WifiConfigStoreSoftAp.xml —— 那里 <string name="WifiSsid">
     * 完整存着 SSID 和 Passphrase。拿不到再退回公开 API。
     */
    private const val SOFTAP_XML = "/data/misc/apexdata/com.android.wifi/WifiConfigStoreSoftAp.xml"

    /** su 读 XML，用正则抠出标签内容；整个文件一次读完，两个字段共用 */
    private fun softApXmlField(ctx: Context?, tag: String): String? {
        val body = try {
            RootShell.get().run("cat $SOFTAP_XML")
        } catch (e: Throwable) {
            return null
        }
        if (body.isBlank()) return null
        val m = Regex("<string name=\"$tag\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .find(body) ?: return null
        // XML 里存的是 &quot;MyPhone-Hotspot&quot; 这种形式，去掉引号实体和反斜杠
        return m.groupValues[1]
            .replace("&quot;", "")
            .replace("\\", "")
            .trim()
            .ifBlank { null }
    }

    fun hotspotSsid(ctx: Context?): String? {
        if (ctx == null) return null
        softApXmlField(ctx, "WifiSsid")?.let { return it }
        val cfg = softApConfig(ctx) ?: return null
        return try {
            val ssid = cfg.javaClass.getMethod("getSsid").invoke(cfg) as? String
            if (ssid.isNullOrBlank()) null else ssid
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 拿 SoftApConfiguration 对象。
     * 直接用 Kotlin 的 wm.softApConfiguration 在 compileSdk 35 上会被裁成
     * 「Unresolved reference」，所以统一走反射；拿不到就返回 null。
     */
    private fun softApConfig(ctx: Context): Any? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        return try {
            val wm = ctx.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                ?: return null
            wm.javaClass.getMethod("getSoftApConfiguration").invoke(wm)
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * 读本机热点密码。
     * getSoftApConfiguration() 只在 API 33+ 才开放 passphrase 读取，
     * 拿不到就返回 null —— 二维码会退化成「无密码」形式，不会画错。
     */
    fun hotspotPassword(ctx: Context?): String? {
        if (ctx == null) return null
        softApXmlField(ctx, "Passphrase")?.let { return it }
        if (android.os.Build.VERSION.SDK_INT < 33) return null
        val cfg = softApConfig(ctx) ?: return null
        return try {
            val pass = cfg.javaClass.getMethod("getPassphrase").invoke(cfg) as? String
            if (pass.isNullOrBlank()) null else pass
        } catch (e: Throwable) {
            null
        }
    }
}