package com.littlesauce.usbnas;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.List;
import java.util.Locale;

/**
 * 手机运行状态采集器。
 *
 * 数据来源全部是本地虚拟文件与系统 API，不申请额外权限。
 * 各指标读取位置：CPU 占用 proc/stat 两次采样差，CPU 频率 cpuN/cpufreq/scaling_cur_freq，
 * GPU 负载 kgsl-3d0/gpubusy，温度 thermal/thermal_zoneN，内存 proc/meminfo，
 * 存储 StatFs，电量 电池广播，网速 proc/net/dev 差分（全局网卡速率）。
 *
 * 单例 + 后台线程，每 SAMPLE_MS 采一次，网页接口读快照。
 * 其中 CPU 占用、网速、进程数在 MIUI 上都只有 root 能读，
 * 统一走 RootShell 的常驻会话，避免每个采样周期 fork 一次 su。
 */
public final class SysInfo {

    public static final long SAMPLE_MS = 5000L;

    private static volatile SysInfo instance;

    public static SysInfo get(Context ctx) {
        SysInfo s = instance;
        if (s == null) {
            synchronized (SysInfo.class) {
                s = instance;
                if (s == null) {
                    s = new SysInfo(ctx.getApplicationContext());
                    instance = s;
                }
            }
        }
        return s;
    }

    private final Context ctx;
    private Thread worker;
    private volatile boolean alive = false;

    // ---- CPU ----
    private long[] prevCpu = null;               // {total, idle}
    private volatile float cpuUsage = 0f;
    private volatile int cpuCores = 0;
    private volatile long cpuFreqMax = 0;        // kHz，所有核心最大
    private volatile long cpuFreqCur = 0;        // kHz，各核当前最大值

    // ---- GPU ----
    private volatile float gpuLoad = -1f;        // -1 表示读不到
    private volatile long gpuFreq = -1;          // Hz
    private volatile long gpuFreqMax = -1;
    private long gpuFreqAt = 0;                  // 上次取 GPU 频率的时间，60 秒一次

    // ---- 温度 ----
    private volatile float tempCpu = 0f;
    private volatile float tempGpu = 0f;
    private volatile float tempBattery = 0f;

    // ---- 内存 ----
    private volatile long memTotal = 0;
    private volatile long memAvail = 0;
    private volatile long swapTotal = 0;
    private volatile long swapFree = 0;

    // ---- 网络速率（字节/秒）----
    private long prevRx = -1;
    private long prevTx = -1;
    private long prevNetAt = 0;
    private volatile long netRxBps = 0;
    private volatile long netTxBps = 0;

    // ---- 电池 ----
    private volatile int batteryPct = -1;
    private volatile float batteryTemp = 0f;
    private volatile boolean charging = false;

    // ---- uptime ----
    private volatile long uptimeMs = 0;

    // ---- 进程数 ----
    private volatile String procCountCache = null;
    private volatile long procCountAt = 0;

    private SysInfo(Context c) {
        this.ctx = c;
        readStatic();
        sample();
    }

    public synchronized void start() {
        if (alive) return;
        alive = true;
        worker = new Thread(() -> {
            while (alive) {
                try {
                    Thread.sleep(SAMPLE_MS);
                } catch (InterruptedException e) {
                    break;
                }
                if (!alive) break;
                try {
                    sample();
                } catch (Throwable ignored) {
                    // 采样失败不影响下一轮
                }
            }
        }, "usbnas-sysinfo");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        alive = false;
        Thread w = worker;
        worker = null;
        if (w != null) w.interrupt();
    }

    // ==================== 采样主流程 ====================

    private void sample() {
        uptimeMs = SystemClock.elapsedRealtime();
        readCpu();
        readCpuFreq();
        readGpu();
        readGpuFreqViaRoot();
        readRootBundle();
        readThermal();
        readMem();
        readBattery();
    }

    /** 只跑一次的东西 */
    private void readStatic() {
        cpuCores = Runtime.getRuntime().availableProcessors();
        uptimeMs = SystemClock.elapsedRealtime();
        readCpuFreq();
        readMem();
        readBattery();
    }

    // ==================== CPU ====================

    private void readCpu() {
        // MIUI 的 SELinux 把 /proc/stat 对 app 域设成不可读，
        // 真实数据只能由 readRootBundle() 通过 root 会话取回。
        // 这里只在能直读时算一次差；读不到就保持旧值，绝不归零。
        String line = readProcStatLine();
        if (line == null) return;
        applyCpuStat(line);
    }

    private String readProcStatLine() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/stat"), 512)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("cpu ")) return line;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void applyCpuStat(String line) {
        long[] sums = parseCpuStat(line);
        if (sums == null) return;
        long total = sums[0];
        long idle = sums[1];
        if (prevCpu != null) {
            long dt = total - prevCpu[0];
            long di = idle - prevCpu[1];
            if (dt > 0) {
                float pct = (dt - di) * 100f / dt;
                if (pct < 0) pct = 0;
                if (pct > 100) pct = 100;
                cpuUsage = pct;
            }
        }
        prevCpu = new long[]{total, idle};
    }

    private static long[] parseCpuStat(String line) {
        if (line == null) return null;
        String[] p = line.trim().split("\\s+");
        if (p.length < 5) return null;
        long total = 0;
        long vals = 0;
        for (int i = 1; i < p.length; i++) {
            long v;
            try {
                v = Long.parseLong(p[i]);
            } catch (Exception e) {
                continue;
            }
            total += v;
            vals++;
        }
        if (vals == 0 || total <= 0) return null;
        long idle;
        try {
            idle = Long.parseLong(p[4]);
            if (p.length > 5) idle += Long.parseLong(p[5]);
        } catch (Exception e) {
            return null;
        }
        return new long[]{total, idle};
    }

    private void readCpuFreq() {
        long mx = 0;
        long cur = 0;
        for (int i = 0; i < 16; i++) {
            File base = new File("/sys/devices/system/cpu/cpu" + i + "/cpufreq");
            if (!base.isDirectory()) continue;
            long c = readLong(new File(base, "scaling_cur_freq").getAbsolutePath());
            long m = readLong(new File(base, "cpuinfo_max_freq").getAbsolutePath());
            if (c > cur) cur = c;
            if (m > mx) mx = m;
        }
        if (mx > 0) cpuFreqMax = mx;
        if (cur > 0) cpuFreqCur = cur;
    }

    // ==================== GPU ====================

    private void readGpu() {
        String s = readText("/sys/class/kgsl/kgsl-3d0/gpubusy");
        if (s == null) return;
        String[] p = s.trim().split("\\s+");
        if (p.length < 2) return;
        long busy = parseLongSafe(p[0]);
        long total = parseLongSafe(p[1]);
        if (busy < 0 || total <= 0) return;
        gpuLoad = busy * 100f / total;
    }

    private void readGpuFreqViaRoot() {
        // 频率 60 秒取一次就够，省得频繁发命令
        if (gpuFreq > 0 && gpuFreqMax > 0
                && SystemClock.elapsedRealtime() - gpuFreqAt < 60_000) {
            return;
        }
        gpuFreqAt = SystemClock.elapsedRealtime();
        String out = RootShell.get().run(
                "cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq; "
                        + "cat /sys/class/kgsl/kgsl-3d0/devfreq/max_freq");
        if (out == null) return;
        String[] lines = out.split("\n");
        if (lines.length >= 1) {
            long cur = parseLongSafe(lines[0]);
            if (cur > 0) gpuFreq = cur;
        }
        if (lines.length >= 2) {
            long mx = parseLongSafe(lines[1]);
            if (mx > 0) gpuFreqMax = mx;
        }
    }

    // ==================== 温度 ====================

    private void readThermal() {
        File dir = new File("/sys/class/thermal");
        File[] zones = dir.listFiles((d, n) -> n.startsWith("thermal_zone"));
        if (zones == null) return;
        float bestCpu = 0;
        float bestGpu = 0;
        for (File z : zones) {
            String type = readText(new File(z, "type").getAbsolutePath());
            if (type == null) continue;
            String t = type.trim().toLowerCase(Locale.ROOT);
            long raw = readLong(new File(z, "temp").getAbsolutePath());
            if (raw <= 0) continue;
            float c = raw > 1000 ? raw / 1000f : raw;
            if (c < 5 || c > 130) continue;
            if (t.contains("gpu")) {
                if (c > bestGpu) bestGpu = c;
            } else if (t.contains("cpu") || t.contains("soc") || t.contains("ap")
                    || t.contains("tsens") || t.contains("cluster")) {
                if (c > bestCpu) bestCpu = c;
            } else if (t.contains("batt") || t.contains("charger")) {
                if (c > tempBattery) tempBattery = c;
            }
        }
        if (bestCpu > 0) tempCpu = bestCpu;
        if (bestGpu > 0) tempGpu = bestGpu;
    }

    // ==================== 内存 ====================

    private void readMem() {
        long total = 0;
        long avail = 0;
        long swapT = 0;
        long swapF = 0;
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"), 4096)) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 2) continue;
                long kb;
                try {
                    kb = Long.parseLong(p[1]);
                } catch (Exception e) {
                    continue;
                }
                switch (p[0]) {
                    case "MemTotal:":
                        total = kb * 1024;
                        break;
                    case "MemAvailable:":
                        avail = kb * 1024;
                        break;
                    case "SwapTotal:":
                        swapT = kb * 1024;
                        break;
                    case "SwapFree:":
                        swapF = kb * 1024;
                        break;
                    default:
                        break;
                }
            }
        } catch (Throwable ignored) {
        }
        if (total > 0) {
            memTotal = total;
            memAvail = avail;
            swapTotal = swapT;
            swapFree = swapF;
        }
    }

    // ==================== 电池 ====================

    private void readBattery() {
        try {
            Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return;
            int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) batteryPct = level * 100 / scale;
            int t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            if (t > 0) batteryTemp = t / 10f;
            int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            charging = st == BatteryManager.BATTERY_STATUS_CHARGING
                    || st == BatteryManager.BATTERY_STATUS_FULL;
        } catch (Throwable ignored) {
        }
    }

    // ==================== 批量 root 采样 ====================

    /**
     * 一条命令把 app 域读不到的几项全抓回来。
     *
     * MIUI 的 SELinux 挡了 /proc/stat、/proc/net/dev 和 /proc 遍历，
     * 每项单独走一次 root 的话，5 秒一次的采样周期里要发 3 条命令。
     * 现在走 RootShell 的常驻会话，一条命令全取回，Magisk 也不会再弹授权提示。
     *
     * 输出格式（用 marker 分段，纯文本好解析）：
     * ===STAT===
     * cpu  123 456 ...
     * ===NET===
     * eth0: ...
     * ===PROC===
     * 789
     */
    private void readRootBundle() {
        String cmd = "S=/proc/stat; N=/proc/net/dev; "
                + "echo '===STAT==='; head -1 $S; "
                + "echo '===NET==='; cat $N; "
                + "echo '===PROC==='; ls -1d /proc/[0-9]* 2>/dev/null | wc -l";

        String bundle = RootShell.get().run(cmd);
        if (bundle == null || bundle.isEmpty()) {
            // 没 root 或被拒，各项保持旧值
            return;
        }

        String stat = null;
        String procs = null;
        int section = 0;
        StringBuilder netSb = new StringBuilder();
        for (String line : bundle.split("\n")) {
            if (line.equals("===STAT===")) {
                section = 1;
                continue;
            }
            if (line.equals("===NET===")) {
                section = 2;
                continue;
            }
            if (line.equals("===PROC===")) {
                section = 3;
                continue;
            }
            switch (section) {
                case 1:
                    if (stat == null && line.startsWith("cpu ")) stat = line;
                    break;
                case 2:
                    netSb.append(line).append('\n');
                    break;
                case 3:
                    for (String tok : line.trim().split("\\s+")) {
                        if (isPid(tok)) {
                            procs = tok;
                            break;
                        }
                    }
                    break;
                default:
                    break;
            }
        }

        if (stat != null) applyCpuStat(stat);
        if (netSb.length() > 0) applyNet(netSb.toString());
        if (procs != null) {
            procCountCache = procs;
            procCountAt = SystemClock.elapsedRealtime();
        }
    }

    private static boolean isPid(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    /** /proc/net/dev 全文 → 各网卡收发汇总差分 */
    private void applyNet(String text) {
        long rx = 0;
        long tx = 0;
        for (String line : text.split("\n")) {
            int c = line.indexOf(':');
            if (c < 0) continue;
            String name = line.substring(0, c).trim();
            if (name.equals("lo") || name.startsWith("dummy")) continue;
            String[] p = line.substring(c + 1).trim().split("\\s+");
            if (p.length < 9) continue;
            long r = parseLongSafe(p[0]);
            long t = parseLongSafe(p[8]);
            if (r > 0) rx += r;
            if (t > 0) tx += t;
        }
        long now = SystemClock.elapsedRealtime();
        if (prevRx >= 0 && prevNetAt > 0) {
            long dt = now - prevNetAt;
            if (dt > 0) {
                long dr = rx - prevRx;
                long dt2 = tx - prevTx;
                if (dr < 0) dr = 0;
                if (dt2 < 0) dt2 = 0;
                netRxBps = dr * 1000 / dt;
                netTxBps = dt2 * 1000 / dt;
            }
        }
        prevRx = rx;
        prevTx = tx;
        prevNetAt = now;
    }

    // ==================== 工具 ====================

    private static String readText(String path) {
        File f = new File(path);
        if (!f.isFile() || !f.canRead()) return null;
        try (BufferedReader br = new BufferedReader(new FileReader(f), 512)) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    private static long readLong(String path) {
        String s = readText(path);
        if (s == null) return -1;
        return parseLongSafe(s.trim());
    }

    private static long parseLongSafe(String s) {
        if (s == null) return -1;
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    // ==================== 对外读取 ====================

    public long uptimeMs() {
        return uptimeMs;
    }

    public String uptimeText() {
        long s = uptimeMs / 1000;
        long d = s / 86400;
        long h = s % 86400 / 3600;
        long m = s % 3600 / 60;
        if (d > 0) return d + " 天 " + h + " 小时";
        if (h > 0) return h + " 小时 " + m + " 分";
        return m + " 分";
    }

    /**
     * 服务已经跑了多久。
     *
     * 和 uptimeText() 是两个完全不同的量：那个是开机时长，
     * 这个是服务自身从开始监听算起的时长。首页要的是后者 ——
     * 用户想知道「NAS 开了多久」，不是「手机开机多久」。
     *
     * 服务没在跑时返回「—」，别把 0 秒显示成「0 分」让人以为刚起来。
     */
    public static String serviceUptimeText(long startedAtMs) {
        if (startedAtMs <= 0) return "—";
        long s = (System.currentTimeMillis() - startedAtMs) / 1000;
        if (s < 0) s = 0;                    // 系统时间被调回去也别显示负数
        long d = s / 86400;
        long h = s % 86400 / 3600;
        long m = s % 3600 / 60;
        if (d > 0) return d + " 天 " + h + " 小时";
        if (h > 0) return h + " 小时 " + m + " 分";
        if (m > 0) return m + " 分";
        return "刚刚";
    }

    public float cpuUsage() {
        return cpuUsage;
    }

    public int cpuCores() {
        return cpuCores;
    }

    public long cpuFreqCur() {
        return cpuFreqCur;
    }

    public long cpuFreqMax() {
        return cpuFreqMax;
    }

    public float gpuLoad() {
        return gpuLoad;
    }

    public long gpuFreq() {
        return gpuFreq;
    }

    public long gpuFreqMax() {
        return gpuFreqMax;
    }

    public float tempCpu() {
        return tempCpu;
    }

    public float tempGpu() {
        return tempGpu;
    }

    public float tempBattery() {
        return batteryTemp;
    }

    public long memTotal() {
        return memTotal;
    }

    public long memAvail() {
        return memAvail;
    }

    public long swapTotal() {
        return swapTotal;
    }

    public long swapFree() {
        return swapFree;
    }

    public long netRxBps() {
        return netRxBps;
    }

    public long netTxBps() {
        return netTxBps;
    }

    public int batteryPct() {
        return batteryPct;
    }

    public boolean charging() {
        return charging;
    }

    public String procCount() {
        if (procCountCache != null
                && SystemClock.elapsedRealtime() - procCountAt < 15000) {
            return procCountCache;
        }
        return procCountCache;
    }

    /** 磁盘用量：{total, free} */
    public static long[] disk(String path) {
        try {
            StatFs s = new StatFs(path);
            long total = s.getBlockCountLong() * s.getBlockSizeLong();
            long free = s.getAvailableBlocksLong() * s.getBlockSizeLong();
            return new long[]{total, free};
        } catch (Throwable e) {
            return new long[]{0, 0};
        }
    }

    // ==================== 展示格式化 ====================

    public static String size(long bytes) {
        if (bytes < 0) return "—";
        double b = bytes;
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (b >= 1024 && i < u.length - 1) {
            b /= 1024;
            i++;
        }
        return String.format(Locale.US, i <= 1 ? "%.0f %s" : "%.1f %s", b, u[i]);
    }

    public String cpuUsageText() {
        return String.format(Locale.US, "%.0f%%", cpuUsage);
    }

    public String cpuFreqText() {
        if (cpuFreqCur <= 0 || cpuFreqMax <= 0) return "—";
        return String.format(Locale.US, "%.2f/%.2f GHz",
                cpuFreqCur / 1_000_000.0, cpuFreqMax / 1_000_000.0);
    }

    public String gpuLoadText() {
        if (gpuLoad < 0) return "—";
        return String.format(Locale.US, "%.0f%%", gpuLoad);
    }

    public String gpuFreqText() {
        if (gpuFreq <= 0) return "—";
        // devfreq 的 cur_freq 单位是 Hz，max_gpuclk 有的机型是 MHz，
        // 超过 10 万一定是 Hz，先统一折成 MHz 再显示，免得出现 315.00 GHz
        double curMhz = gpuFreq > 100_000 ? gpuFreq / 1_000_000.0 : gpuFreq;
        if (gpuFreqMax <= 0) return String.format(Locale.US, "%.2f GHz", curMhz / 1000.0);
        double maxMhz = gpuFreqMax > 100_000 ? gpuFreqMax / 1_000_000.0 : gpuFreqMax;
        return String.format(Locale.US, "%.2f/%.2f GHz", curMhz / 1000.0, maxMhz / 1000.0);
    }

    public String memUsedText() {
        if (memTotal <= 0) return "—";
        return size(memTotal - memAvail);
    }

    public String memTotalText() {
        if (memTotal <= 0) return "—";
        return "共 " + size(memTotal) + " · 可用 " + size(memAvail);
    }

    /** 首个共享盘的已用量，给 App 状态卡副标题用 */
    public static String diskUsedText(List<File> roots) {
        long[] d = primaryDisk(roots);
        if (d == null || d[0] <= 0) return "—";
        return size(d[0] - d[1]);
    }

    /** 指定盘（内部存储 / U 盘）的已用量；拿不到容量就返回 — */
    public static String diskUsedText(File dir) {
        if (dir == null) return "—";
        long[] d = disk(dir.getAbsolutePath());
        if (d[0] <= 0) return "—";
        return size(d[0] - d[1]);
    }

    /** 指定盘的总量与剩余 */
    public static String diskTotalText(File dir) {
        if (dir == null) return "—";
        long[] d = disk(dir.getAbsolutePath());
        if (d[0] <= 0) return "—";
        return "共 " + size(d[0]) + " · 剩余 " + size(d[1]);
    }

    /** 首个共享盘的总量与剩余 */
    public static String diskTotalText(List<File> roots) {
        long[] d = primaryDisk(roots);
        if (d == null || d[0] <= 0) return "—";
        return "共 " + size(d[0]) + " · 剩余 " + size(d[1]);
    }

    private static long[] primaryDisk(List<File> roots) {
        if (roots == null || roots.isEmpty()) return null;
        for (File r : roots) {
            if (r == null || !r.exists()) continue;
            long[] d = disk(r.getAbsolutePath());
            if (d[0] > 0) return d;
        }
        return null;
    }

    /** 只报 CPU 温度；GPU 温度照旧采集，但不在界面上单独再说一遍 */
    public String tempText() {
        return tempCpu > 0 ? String.format(Locale.US, "%.0f℃", tempCpu) : "—";
    }

    public String batteryText() {
        StringBuilder sb = new StringBuilder();
        if (batteryPct >= 0) sb.append(batteryPct).append('%');
        if (charging) sb.append(" ⚡");
        return sb.length() == 0 ? "—" : sb.toString();
    }

    public String netText() {
        return "↓ " + size(netRxBps) + "/s · ↑ " + size(netTxBps) + "/s";
    }

    // ==================== 存储卷命名 ====================

    static String volumeName(File f) {
        return volumeName(f, -1);
    }

    /**
     * @param removableIndex 可移除卷的序号；-1 表示未知，此时退回 UUID
     */
    static String volumeName(File f, int removableIndex) {
        if (f == null) return "存储";
        String p = f.getAbsolutePath();
        if (p.startsWith("/storage/emulated") || p.equals("/data")) return "内部存储";
        if (removableIndex >= 0) return "盘" + removableIndex;
        String n = f.getName();
        if (n.isEmpty()) return "存储";
        return n;
    }

    /** 判断是否内部存储（给可移除卷编号时跳过它） */
    static boolean isInternal(File f) {
        if (f == null) return false;
        String p = f.getAbsolutePath();
        return p.startsWith("/storage/emulated") || p.equals("/data");
    }

    // ==================== JSON ====================

    public String toJson(List<File> roots, long srvReq, long srvBytes,
                         long srvBps, long srvRxBps) {
        StringBuilder sb = new StringBuilder(768);
        sb.append('{');
        sb.append("\"uptime\":").append(uptimeMs).append(',');
        sb.append("\"uptimeText\":\"").append(jsonEsc(uptimeText())).append("\",");
        // 网页端「已运行」用的是这一对：服务自己的开始时刻，不是开机时刻。
        // 由 HttpServer 在拼 JSON 时按 NasService.startedAt 覆写。
        sb.append("\"startedAt\":0,");
        sb.append("\"serviceUptimeText\":\"—\",");
        sb.append("\"cpu\":").append(String.format(Locale.US, "%.1f", cpuUsage)).append(',');
        sb.append("\"cpuCores\":").append(cpuCores).append(',');
        sb.append("\"cpuFreq\":").append(Math.max(0, cpuFreqCur) / 1000).append(',');
        sb.append("\"cpuFreqMax\":").append(Math.max(0, cpuFreqMax) / 1000).append(',');
        sb.append("\"cpuTemp\":").append(String.format(Locale.US, "%.1f", tempCpu)).append(',');
        // 和 cpuFreq 一样统一用 MHz：内核给的是 Hz，这里 /1000 得 kHz，再 /1000 得 MHz
        sb.append("\"gpu\":").append(gpuLoad < 0 ? "-1"
                : String.format(Locale.US, "%.1f", gpuLoad)).append(',');
        // Hz → MHz；超过 10 万的值才当 Hz 处理，防止某些节点直接给 MHz
        sb.append("\"gpuFreq\":").append(Math.round(gpuFreq > 100_000
                ? gpuFreq / 1_000_000.0 : Math.max(0, gpuFreq))).append(',');
        sb.append("\"gpuFreqMax\":").append(Math.round(gpuFreqMax > 100_000
                ? gpuFreqMax / 1_000_000.0 : Math.max(0, gpuFreqMax))).append(',');
        sb.append("\"gpuTemp\":").append(String.format(Locale.US, "%.1f", tempGpu)).append(',');
        sb.append("\"memTotal\":").append(memTotal).append(',');
        sb.append("\"memAvail\":").append(memAvail).append(',');
        sb.append("\"swapTotal\":").append(swapTotal).append(',');
        sb.append("\"swapFree\":").append(swapFree).append(',');
        sb.append("\"battery\":").append(batteryPct).append(',');
        sb.append("\"batteryTemp\":").append(String.format(Locale.US, "%.1f", batteryTemp)).append(',');
        sb.append("\"charging\":").append(charging).append(',');
        sb.append("\"netRx\":").append(netRxBps).append(',');
        sb.append("\"netTx\":").append(netTxBps).append(',');
        sb.append("\"procCount\":").append(procCount() == null ? 0 : parseLongSafe(procCount())).append(',');
        sb.append("\"srvReq\":").append(srvReq).append(',');
        sb.append("\"srvBytes\":").append(srvBytes).append(',');
        sb.append("\"srvBps\":").append(srvBps).append(',');
        sb.append("\"srvRxBps\":").append(srvRxBps).append(',');
        sb.append("\"disks\":[");
        if (roots != null) {
            boolean hasEmulated = false;
            int removableIndex = 0;
            boolean first = true;
            for (File r : roots) {
                if (r == null || !r.exists()) continue;
                if (isInternal(r)) hasEmulated = true;
                int idx = isInternal(r) ? -1 : removableIndex++;
                if (!first) sb.append(',');
                first = false;
                long[] d = disk(r.getAbsolutePath());
                sb.append("{\"name\":\"").append(jsonEsc(volumeName(r, idx))).append("\",");
                sb.append("\"path\":\"").append(jsonEsc(r.getAbsolutePath())).append("\",");
                sb.append("\"total\":").append(d[0]).append(',');
                sb.append("\"free\":").append(d[1]).append('}');
            }
            if (!hasEmulated && new File("/data").exists()) {
                if (!first) sb.append(',');
                long[] d = disk("/data");
                sb.append("{\"name\":\"内部存储\",\"path\":\"/data\",\"total\":")
                        .append(d[0]).append(",\"free\":").append(d[1]).append('}');
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String jsonEsc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }
}
