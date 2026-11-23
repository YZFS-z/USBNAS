package com.littlesauce.usbnas;

import android.util.Log;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 常驻 root shell 会话。
 *
 * 为什么要这个：MIUI 的 SELinux 把 /proc/stat、/proc/net/dev 和 /proc 目录遍历
 * 对 app 域全关了，只能走 root。但每次 fork 一个 su 都要经过 Magisk 的授权流程，
 * Magisk 会发一条「已被授予超级用户权限」的 toast —— 5 秒一次采样就是
 * 每分钟十几次，系统最后按 toast 配额直接丢弃（above allowed toast quota）。
 *
 * 做法：启动时 fork 一次 su，开一条长驻的 sh 管道，之后所有命令都从这条管道发。
 * Magisk 只在建管道这一次弹提示，后续命令不再经过授权。
 *
 * 协议：发一条命令，再发一行随机 marker；读到 marker 就说明命令结束。
 * 用 marker 而不是等 EOF，因为管道是常驻的，永远不会 EOF。
 *
 * 重要：这里刻意不用 run() 来探活。探活必须走低层的 rawExec，
 * 否则 run() → open() → run() 递归，每次递归还 fork 一个 su，
 * 直接把自己和 Magisk 一起拖死。
 */
public final class RootShell {

    private static final String TAG = "USBNAS-Root";
    private static final long DEFAULT_TIMEOUT_MS = 3000L;
    private static final long PROBE_TIMEOUT_MS = 5000L;

    private static volatile RootShell instance;

    public static RootShell get() {
        RootShell s = instance;
        if (s == null) {
            synchronized (RootShell.class) {
                s = instance;
                if (s == null) {
                    s = new RootShell();
                    instance = s;
                }
            }
        }
        return s;
    }

    private Process proc;
    private BufferedWriter stdin;
    private BufferedReader stdout;
    private final AtomicLong seq = new AtomicLong(0);
    /** 会话是否可用。只由 open()/close() 改，run() 只读。 */
    private volatile boolean usable = false;
    /** 上次尝试建立会话的时间，防止 su 不可用时每个采样周期都重试 */
    private volatile long lastOpenFailAt = 0;
    private static final long OPEN_RETRY_MS = 60_000L;

    private RootShell() { }

    /** 会话是否可用于发命令 */
    public boolean isReady() {
        Process p = proc;
        return usable && p != null && p.isAlive();
    }

    /**
     * 执行一条命令，返回它的全部 stdout。
     * 会话不可用或命令失败返回 null，调用方保持旧值。
     */
    public String run(String cmd) {
        return run(cmd, DEFAULT_TIMEOUT_MS);
    }

    public synchronized String run(String cmd, long timeoutMs) {
        if (!isReady() && !open()) {
            return null;
        }
        return pipe(cmd, timeoutMs);
    }

    /**
     * 建立会话。已有健康会话直接返回 true。
     * 失败后 60 秒内不再重试，避免没 root 时每个采样周期都 fork 一次 su。
     */
    public synchronized boolean open() {
        if (isReady()) return true;
        long now = System.currentTimeMillis();
        if (now - lastOpenFailAt < OPEN_RETRY_MS) return false;

        close();
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", "sh").redirectErrorStream(true).start();
            BufferedWriter w = new BufferedWriter(
                    new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));

            // 探活：直接在这条新管道上发一条,不走 run()，避免递归
            String probe = pipeOn(p, w, r, "id -u", PROBE_TIMEOUT_MS);
            if (probe == null || !probe.trim().equals("0")) {
                Log.w(TAG, "su 没给到 root，会话不可用：" + probe);
                destroyQuietly(p, w, r);
                lastOpenFailAt = now;
                return false;
            }
            proc = p;
            stdin = w;
            stdout = r;
            usable = true;
            lastOpenFailAt = 0;
            Log.i(TAG, "root 会话已建立（后续命令不再触发授权提示）");
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "建会话失败: " + e);
            destroyQuietly(p, null, null);
            lastOpenFailAt = now;
            return false;
        }
    }

    /** 在当前会话上跑命令 */
    private String pipe(String cmd, long timeoutMs) {
        String out = pipeOn(proc, stdin, stdout, cmd, timeoutMs);
        if (out == null) {
            // 管道断了，标记不可用，下次 run() 会重建
            close();
        }
        return out;
    }

    /**
     * 在指定管道上跑一条命令并读到 marker。
     * 这一层不碰任何成员状态，open() 和 run() 都复用它，所以不会有递归。
     */
    private String pipeOn(Process p, BufferedWriter w, BufferedReader r,
                          String cmd, long timeoutMs) {
        if (p == null || w == null || r == null) return null;
        String marker = "__USBNAS_EOF_" + seq.incrementAndGet() + "__";
        try {
            // 包在 { } 里，保证命令自己的换行不会把后面的 echo 吞掉
            w.write("{ " + cmd + "\n} 2>&1\n");
            w.write("echo " + marker + "\n");
            w.flush();
        } catch (Throwable e) {
            Log.w(TAG, "写管道失败: " + e);
            return null;
        }

        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            while (System.currentTimeMillis() <= deadline) {
                if (!r.ready()) {
                    if (!p.isAlive()) {
                        Log.w(TAG, "root 进程已退出");
                        return null;
                    }
                    Thread.sleep(4);
                    continue;
                }
                String line = r.readLine();
                if (line == null) {
                    Log.w(TAG, "管道 EOF");
                    return null;
                }
                if (line.equals(marker)) return sb.toString();
                sb.append(line).append('\n');
            }
            Log.w(TAG, "命令超时: " + cmd);
            return null;
        } catch (Throwable e) {
            Log.w(TAG, "读管道失败: " + e);
            return null;
        }
    }

    public synchronized void close() {
        usable = false;
        BufferedWriter w = stdin;
        BufferedReader r = stdout;
        Process p = proc;
        stdin = null;
        stdout = null;
        proc = null;
        destroyQuietly(p, w, r);
    }

    private static void destroyQuietly(Process p, BufferedWriter w, BufferedReader r) {
        if (w != null) try { w.close(); } catch (Throwable ignored) { }
        if (r != null) try { r.close(); } catch (Throwable ignored) { }
        if (p != null) try { p.destroy(); } catch (Throwable ignored) { }
    }

    /** 未使用的 import 占位，避免 IDE 警告 */
    @SuppressWarnings("unused")
    private static void unusedImports(InputStream a, IOException b, InputStreamReader c) { }
}
