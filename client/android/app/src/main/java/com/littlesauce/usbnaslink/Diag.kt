package com.littlesauce.usbnaslink

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Android 专用的极简自检：给 UI 上的「诊断」用，不参与主扫描流程。
 *
 * 之所以单独写一份而不复用 Probe：Probe 已经把指纹判定逻辑钉死了，
 * 而排障时我们要看的是「连都没连上」还是「连上了但内容不对」，
 * 所以这里只做最原始的建连 + 读头，把失败原因原样吐出来。
 */
object Diag {

    data class Line(val ok: Boolean, val text: String)

    fun tcp(ip: String, port: Int, timeoutMs: Int = 1500): Line {
        val t0 = System.currentTimeMillis()
        var s: Socket? = null
        return try {
            s = Socket()
            s.connect(InetSocketAddress(ip, port), timeoutMs)
            val ms = System.currentTimeMillis() - t0
            Line(true, "TCP $ip:$port 通，$ms ms，本端=${s.localAddress.hostAddress}")
        } catch (t: Throwable) {
            Line(false, "TCP $ip:$port 失败：" + t.javaClass.simpleName +
                (t.message?.let { " ($it)" } ?: ""))
        } finally {
            try { s?.close() } catch (_: Throwable) {}
        }
    }

    fun http(ip: String, port: Int, path: String = "/", timeoutMs: Int = 2000): Line {
        var s: Socket? = null
        return try {
            s = Socket()
            s.connect(InetSocketAddress(ip, port), timeoutMs)
            s.soTimeout = timeoutMs
            val out: OutputStream = s.getOutputStream()
            out.write(("GET $path HTTP/1.0\r\nHost: $ip:$port\r\nConnection: close\r\n\r\n").toByteArray())
            out.flush()
            val inp: InputStream = s.getInputStream()
            val buf = ByteArray(512)
            val n = inp.read(buf)
            if (n <= 0) return Line(false, "HTTP $ip:$port 建连后无响应")
            val head = String(buf, 0, n, Charsets.ISO_8859_1)
            Line(true, "HTTP $path -> ${head.lineSequence().firstOrNull() ?: "?"}")
        } catch (t: Throwable) {
            Line(false, "HTTP $ip:$port 失败：" + t.javaClass.simpleName +
                (t.message?.let { " ($it)" } ?: ""))
        } finally {
            try { s?.close() } catch (_: Throwable) {}
        }
    }
}
