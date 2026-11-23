package com.littlesauce.usbnas;

import java.util.ArrayList;
import java.util.List;

/**
 * 外接 USB 存储的空闲休眠控制。
 *
 * <h3>为什么需要三处一起改</h3>
 * USB 存储的电源管理是分层的，只改一处都不生效：
 * <ol>
 *   <li><b>SCSI 层</b> {@code .../scsi_disk/*&#47;manage_start_stop = 1}
 *       —— 让内核在挂起设备时下发 START STOP UNIT，磁头归位、盘真正停转。
 *       不设这个，只能做到"总线挂起"，马达照样转，噪音一秒不少。</li>
 *   <li><b>块设备层</b> {@code .../device/power/control = auto} 且
 *       {@code autosuspend_delay_ms = N*1000} —— 空闲多久算空闲。</li>
 *   <li><b>上游 USB 节点</b> {@code /sys/bus/usb/devices/X-Y/power/control = auto}
 *       —— USB 父设备默认是 on，它会一直"持住"下面的子设备，
 *       子设备再怎么配也永远进不了 suspended。</li>
 * </ol>
 *
 * <h3>对使用的影响</h3>
 * 实测唤醒是内核同步完成的：休眠中请求一个文件，HTTP 响应 25ms 完成，
 * 用户完全察觉不到。而没被访问的那块盘会继续睡 —— 两块盘是各自独立的。
 *
 * <h3>需要 root</h3>
 * 这些 sysfs 节点属 root:root 0644，app 域写不了。全程走 {@link RootShell}。
 */
public final class USBSleep {

    /** 一条 su 命令干完所有事：省得为每块盘起一次 shell */
    private static final String FN_COMMON = ""
            // 列出外接块设备：readlink 出来的路径里带 xhci/usb 的才是 USB，
            // 内部存储走 ufshc，不能误伤。
            + "usb_disks(){ for d in /sys/block/sd*; do [ -d \"$d\" ] || continue; "
            + "l=$(readlink -f \"$d\"); case \"$l\" in *xhci*|*usb*) basename \"$d\";; esac; done; }; "
            // 逐级上溯找带 idVendor 的节点，那就是 USB 设备本体
            + "usb_node(){ p=/sys/block/$1; while [ \"$p\" != \"/\" ]; do "
            + "if [ -f \"$p/idVendor\" ]; then echo \"$p\"; return 0; fi; "
            + "p=$(dirname \"$p\"); done; return 1; }; ";

    private USBSleep() {
    }

    /** 一块盘的当前状态 */
    public static final class Info {
        public String dev = "";        // sdg
        public String model = "";      // YongzhenWeiye
        public long sizeGB;
        public boolean rotational;     // true=机械
        public String control = "?";   // on / auto
        public String status = "?";    // active / suspended
        public long delayMs = -1;
        public boolean manageStartStop;

        public boolean sleeping() {
            return "suspended".equals(status);
        }

        /** 首页/对话框上的一行摘要 */
        public String label() {
            String kind = rotational ? "机械" : "固态";
            String state = sleeping() ? "已停转" : (isAuto() ? "运转中" : "常转");
            return dev + " · " + (model.isEmpty() ? "U 盘" : model) + " · " + sizeGB + "G "
                    + kind + " · " + state;
        }

        public boolean isAuto() {
            return "auto".equals(control);
        }
    }

    /** 读所有外接盘状态；没 root 或没插盘返回空列表（绝不抛异常） */
    public static List<Info> read() {
        List<Info> out = new ArrayList<>();
        String s = RootShell.get().run(FN_COMMON
                + "for n in $(usb_disks); do d=/sys/block/$n; "
                + "echo \"DEV=$n\"; "
                + "echo \"MODEL=$(cat $d/device/model 2>/dev/null | tr -d ' ')\"; "
                + "echo \"SIZE=$(cat $d/size 2>/dev/null)\"; "
                + "echo \"ROT=$(cat $d/queue/rotational 2>/dev/null)\"; "
                + "echo \"CTL=$(cat $d/device/power/control 2>/dev/null)\"; "
                + "echo \"ST=$(cat $d/device/power/runtime_status 2>/dev/null)\"; "
                + "echo \"DLY=$(cat $d/device/power/autosuspend_delay_ms 2>/dev/null)\"; "
                + "echo \"MSS=$(cat $d/device/scsi_disk/*/manage_start_stop 2>/dev/null)\"; "
                + "echo '---'; done", 6_000L);
        if (s == null) return out;

        Info cur = null;
        for (String raw : s.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("DEV=")) {
                cur = new Info();
                cur.dev = line.substring(4);
                out.add(cur);
            } else if (cur == null) {
                continue;
            } else if (line.startsWith("MODEL=")) {
                cur.model = line.substring(6);
            } else if (line.startsWith("SIZE=")) {
                try {
                    // /sys/block/X/size 单位是 512 字节扇区
                    cur.sizeGB = Long.parseLong(line.substring(5).trim()) / 2 / 1024 / 1024;
                } catch (NumberFormatException ignored) {
                }
            } else if (line.startsWith("ROT=")) {
                cur.rotational = "1".equals(line.substring(4).trim());
            } else if (line.startsWith("CTL=")) {
                cur.control = line.substring(4).trim();
            } else if (line.startsWith("ST=")) {
                cur.status = line.substring(3).trim();
            } else if (line.startsWith("DLY=")) {
                try {
                    cur.delayMs = Long.parseLong(line.substring(4).trim());
                } catch (NumberFormatException ignored) {
                }
            } else if (line.startsWith("MSS=")) {
                cur.manageStartStop = "1".equals(line.substring(4).trim());
            }
        }
        // 丢掉没解析出关键字段的鬼条目
        out.removeIf(i -> i.dev.isEmpty() || "?".equals(i.status));
        return out;
    }

    /**
     * 开/关自动休眠。
     *
     * @param on      true=空闲后挂起，false=恢复常转（复原成出厂默认）
     * @param delaySec 空闲秒数，仅 on 时用
     * @return 是否执行成功（root 不可用时 false）
     */
    public static boolean apply(boolean on, int delaySec) {
        int sec = Math.max(10, Math.min(600, delaySec));
        String body;
        if (on) {
            body = "for n in $(usb_disks); do d=/sys/block/$n/device; "
                    // ① SCSI 层：挂起时下发 START STOP UNIT，盘才真停转
                    + "for f in $d/scsi_disk/*/manage_start_stop; do "
                    + "[ -f \"$f\" ] && echo 1 > \"$f\" 2>/dev/null; done; "
                    // ② 块设备层空闲阈值要先于 control 写，否则会立刻按旧值判一次
                    + "echo " + (sec * 1000) + " > $d/power/autosuspend_delay_ms 2>/dev/null; "
                    + "echo auto > $d/power/control 2>/dev/null; "
                    // ③ 上游 USB 节点也要放手，否则父设备一直持住子设备
                    + "u=$(usb_node $n); if [ -n \"$u\" ]; then "
                    + "echo auto > $u/power/control 2>/dev/null; "
                    + "for i in $u/*:*.*; do [ -f \"$i/power/control\" ] && "
                    + "echo auto > \"$i/power/control\" 2>/dev/null; done; fi; done; echo ok";
        } else {
            body = "for n in $(usb_disks); do d=/sys/block/$n/device; "
                    + "echo on > $d/power/control 2>/dev/null; "
                    + "for f in $d/scsi_disk/*/manage_start_stop; do "
                    + "[ -f \"$f\" ] && echo 0 > \"$f\" 2>/dev/null; done; "
                    + "u=$(usb_node $n); if [ -n \"$u\" ]; then "
                    + "echo on > $u/power/control 2>/dev/null; "
                    + "for i in $u/*:*.*; do [ -f \"$i/power/control\" ] && "
                    + "echo on > \"$i/power/control\" 2>/dev/null; done; fi; done; echo ok";
        }
        String r = RootShell.get().run(FN_COMMON + body, 8_000L);
        return r != null && r.contains("ok");
    }

    /**
     * 立刻让盘停转（不等空闲计时器）。
     *
     * 实现上就是"开 auto + 阈值清零"，再等内核收敛。不直接写
     * {@code power/control=auto} 之外的挂起指令 —— 内核没有对外暴露
     * "立刻挂起"的写接口，硬写 runtime_status 是只读的。
     *
     * @return 2 秒后的停转块数（-1 表示 root 不可用）
     */
    public static int suspendNow() {
        RootShell.get().run(FN_COMMON
                + "for n in $(usb_disks); do d=/sys/block/$n/device; "
                + "for f in $d/scsi_disk/*/manage_start_stop; do "
                + "[ -f \"$f\" ] && echo 1 > \"$f\" 2>/dev/null; done; "
                + "echo 0 > $d/power/autosuspend_delay_ms 2>/dev/null; "
                + "echo auto > $d/power/control 2>/dev/null; "
                + "u=$(usb_node $n); if [ -n \"$u\" ]; then "
                + "echo auto > $u/power/control 2>/dev/null; "
                + "for i in $u/*:*.*; do [ -f \"$i/power/control\" ] && "
                + "echo auto > \"$i/power/control\" 2>/dev/null; done; fi; done", 8_000L);

        // 内核挂起是异步的，给它一点时间再数
        try {
            Thread.sleep(2_500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int n = 0;
        for (Info i : read()) {
            if (i.sleeping()) n++;
        }
        return n;
    }

    /** 首页那一行摘要：没插盘返回 null */
    public static String summary() {
        List<Info> all = read();
        if (all.isEmpty()) return null;
        int sleeping = 0;
        for (Info i : all) {
            if (i.sleeping()) sleeping++;
        }
        if (sleeping == all.size()) return all.size() + " 块 · 全部休眠";
        if (sleeping == 0) return all.size() + " 块 · 运转中";
        return all.size() + " 块 · " + sleeping + " 块休眠";
    }
}
