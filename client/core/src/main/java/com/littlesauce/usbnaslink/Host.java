package com.littlesauce.usbnaslink;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个候选 IPv4 主机地址。
 *
 * 为什么把「网段里的 /24」也算成主机：发现服务靠 UDP 广播，而热点（尤其安卓热点）
 * 经常在 AP 隔离、组播过滤上出幺蛾子，广播包收不到是常态。所以发现流程分两条腿：
 *   1) UDP 广播/组播探测（快，一个包就能问到全广播域）
 *   2) /24 并行 TCP 扫描（慢一点但一定可靠）
 * 两条腿的候选都由 Host 表示，扫描结果按 ip 去重。
 */
public final class Host {

    public final byte[] ip;
    public final String ipText;
    /** 网卡名（wlan0 / eth0 / 以太网），用于展示和排序 */
    public final String ifaceName;
    /** 发起广播时用的本地地址 */
    public final String localAddr;

    public Host(byte[] ip, String ifaceName, String localAddr) {
        this.ip = ip;
        this.ipText = addr(ip);
        this.ifaceName = ifaceName;
        this.localAddr = localAddr;
    }

    public static String addr(byte[] ip) {
        return (ip[0] & 255) + "." + (ip[1] & 255) + "." + (ip[2] & 255) + "." + (ip[3] & 255);
    }

    public static byte[] parse(String s) {
        String[] p = s.trim().split("\\.");
        if (p.length != 4) return null;
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) {
            int v;
            try {
                v = Integer.parseInt(p[i]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (v < 0 || v > 255) return null;
            b[i] = (byte) v;
        }
        return b;
    }

    @Override
    public String toString() {
        return ipText + " (" + ifaceName + ")";
    }

    // ------------------------------------------------------------------ 网卡枚举

    /** 一条网卡上的 IPv4 配置 */
    public static final class Nic {
        public final String name;
        public final byte[] ip;
        public final int prefix;

        Nic(String name, byte[] ip, int prefix) {
            this.name = name;
            this.ip = ip;
            this.prefix = prefix;
        }

        /** 该网段里可能的最大前缀。热点常见 /24，但安卓热点偶有 /17，逐个 /24 展开 */
        public byte[] broadcast() {
            int shift = 32 - prefix;
            int self = ((ip[0] & 255) << 24) | ((ip[1] & 255) << 16) | ((ip[2] & 255) << 8) | (ip[3] & 255);
            int mask = prefix == 0 ? 0 : (0xFFFFFFFF << shift);
            int bc = self | ~mask;
            return new byte[]{(byte) (bc >>> 24), (byte) (bc >>> 16), (byte) (bc >>> 8), (byte) bc};
        }

        public boolean isLoopback() {
            return (ip[0] & 255) == 127;
        }

        /** 169.254.x.x 链路本地：没 DHCP 时的兜底网段，通常扫不出东西，排在最后 */
        public boolean isLinkLocal() {
            return (ip[0] & 255) == 169 && (ip[1] & 255) == 254;
        }

        /** 安卓热点默认网关就是 192.168.43.1 之类，但不要写死，只看前缀 */
        public String cidr() {
            return addr(ip) + "/" + prefix;
        }
    }

    /**
     * 枚举所有可用 IPv4 网卡。
     *
     * 为什么不用 {@code InetAddress.isReachable} 之类的上层 API：桌面上会拿到
     * 一堆虚拟网卡（VMware / Hyper-V / WSL / TAP），安卓上只有 wlan0 有用，
     * 所以这里全部列出，由上层按「名字 + 网段」排序挑重点，而不是在这里猜。
     */
    public static List<Nic> nics() {
        List<Nic> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                int prefix = 24;
                List<Inet4Address> v4 = new ArrayList<>();
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) v4.add((Inet4Address) a);
                }
                if (v4.isEmpty()) continue;
                for (Inet4Address a : v4) {
                    // 前缀从 InterfaceAddress 拿；拿不到就按 /24 处理（热点最常见）
                    int p = 24;
                    try {
                        for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                            if (ia.getAddress() instanceof Inet4Address
                                    && ia.getAddress().equals(a)) {
                                p = ia.getNetworkPrefixLength();
                                break;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    out.add(new Nic(ni.getName(), a.getAddress(), p));
                }
            }
        } catch (Throwable ignored) {
        }
        out.sort((x, y) -> {
            int sx = score(x), sy = score(y);
            if (sx != sy) return sx - sy;
            return x.cidr().compareTo(y.cidr());
        });
        return out;
    }

    /**
     * 排序权重：小者优先。
     * 192.168.0.0/16 与 10.0.0.0/8 是热点/路由器最常用的私网段，优先扫；
     * 172.16/12 次之（本机热点网段 172.23.x.x 就在这里）；链路本地最后。
     */
    private static int score(Nic n) {
        int a = n.ip[0] & 255, b = n.ip[1] & 255;
        if (n.isLoopback()) return 99;
        if (n.isLinkLocal()) return 90;
        if (a == 192 && b == 168) return 0;
        if (a == 10) return 1;
        if (a == 172 && b >= 16 && b <= 31) return 2;
        return 5;
    }

    /**
     * 把一条网卡展开成候选主机列表（跳过网络地址、广播地址和自己）。
     *
     * 大网段（/16）展开会有 6 万多个地址，所以这里限制：每个 /24 子网逐个展开，
     * 最多 {@code maxSubnets} 个，优先本地所在的那个 /24。热点就算给了 /17，
     * 邻居 /24 里也基本没有设备，优先扫自己那一格最划算。
     */
    public static List<Host> expand(Nic n, int maxSubnets) {
        List<Host> out = new ArrayList<>();
        int shift = 32 - n.prefix;
        if (n.prefix >= 24) {
            // /24 或更小：就扫这一格
            addBlock(out, n, new byte[]{(byte) (n.ip[0] & 255), (byte) (n.ip[1] & 255), (byte) (n.ip[2] & 255)});
            return out;
        }
        int selfFocused = 0;
        // 自己的 /24
        byte[] sub = new byte[]{(byte) (n.ip[0] & 255), (byte) (n.ip[1] & 255), (byte) (n.ip[2] & 255)};
        addBlock(out, n, sub);
        selfFocused++;

        // 相邻 /24：先向下（.log 值小的），再向上
        int baseInt = ((n.ip[0] & 255) << 24) | ((n.ip[1] & 255) << 16) | ((n.ip[2] & 255) << 8);
        int netInt = baseInt & (shift >= 32 ? 0 : (0xFFFFFFFF << shift));
        int lowThird = netInt >>> 8;
        int highThird = (netInt | ~(0xFFFFFFFF << shift)) >>> 8;
        int curThird = baseInt >>> 8;
        int added = selfFocused;
        for (int d = 1; added < maxSubnets && d < 256; d++) {
            for (int sign = -1; sign <= 1 && added < maxSubnets; sign += 2) {
                int t = curThird + sign * d;
                if (t < lowThird || t > highThird) continue;
                addBlock(out, n, new byte[]{(byte) (t >>> 16), (byte) (t >>> 8), (byte) t});
                added++;
            }
        }
        return out;
    }

    private static void addBlock(List<Host> out, Nic n, byte[] prefix3) {
        int selfLast = n.ip[3] & 255;
        for (int last = 1; last <= 254; last++) {
            if (prefix3[0] == n.ip[0] && prefix3[1] == n.ip[1] && prefix3[2] == n.ip[2]
                    && last == selfLast) {
                continue; // 自己
            }
            out.add(new Host(new byte[]{prefix3[0], prefix3[1], prefix3[2], (byte) last},
                    n.name, addr(n.ip)));
        }
    }
}
