package com.littlesauce.usbnaslink;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UDP 发现。
 *
 * 协议（我方定义，服务端需要在 HttpServer / NasService 里加一个监听线程配合，
 * 不加也能跑 —— 会退化成纯 TCP 扫描，只是慢几秒）：
 *
 *   请求（任意端口发出）：
 *     USBNAS?1
 *   应答（发回请求源端口）：
 *     USBNAS!1 port=8080 title=一只肥鼠的NAS ver=20261122 login=0
 *
 * 为什么用「明文一问一答」而不是 SSDP/mDNS：
 *   - 安卓上 mDNS 需要 MulticastLock，热点场景下经常被系统掐掉；
 *   - SSDP 的 XML 解析在纯 Java / 安卓两套环境里要各写一份，没必要；
 *   - 一行文本，抓包就能看懂，出问题好排查。
 *
 * 广播地址的取法：每条网卡算一次定向广播（如 172.23.255.255），再加 255.255.255.255。
 * 安卓的 WifiManager 可能限制广播，所以同一个包发三遍：定向、全局、组播兜底。
 */
public final class Discovery {

    /** 同一网段内所有客户端用同一个端口，服务端只需要开一个 UDP socket */
    public static final int PORT = 8081;
    public static final String MAGIC_REQ = "USBNAS?1";
    public static final String MAGIC_RESP = "USBNAS!1";

    public interface Listener {
        /** 收到一个应答；返回 false 表示上层要求停止继续收 */
        boolean onFound(ServiceInfo si);
    }

    /**
     * 在 {@code listenMs} 内同步收应答。调用方自己放后台线程。
     *
     * @return 收到的应答（按 ip 去重，保留最先到达的）
     */
    public static List<ServiceInfo> listen(int listenMs) {
        List<ServiceInfo> out = new ArrayList<>();
        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket(null);
            sock.setReuseAddress(true);
            sock.bind(new InetSocketAddress(PORT));
            sock.setBroadcast(true);
            long deadline = System.currentTimeMillis() + listenMs;
            byte[] buf = new byte[1024];
            Map<String, Boolean> seen = new LinkedHashMap<>();
            while (System.currentTimeMillis() < deadline) {
                int left = (int) (deadline - System.currentTimeMillis());
                if (left <= 0) break;
                sock.setSoTimeout(Math.max(1, Math.min(left, 200)));
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    sock.receive(p);
                } catch (SocketTimeoutException te) {
                    continue;
                } catch (IOException io) {
                    break;
                }
                String msg = new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8).trim();
                if (!msg.startsWith(MAGIC_RESP)) continue;
                String fromIp = p.getAddress().getHostAddress();
                ServiceInfo si = parse(msg, fromIp);
                if (si == null) continue;
                if (seen.containsKey(si.key())) continue;
                seen.put(si.key(), Boolean.TRUE);
                out.add(si);
            }
        } catch (IOException ignored) {
        } finally {
            if (sock != null) {
                try {
                    sock.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return out;
    }

    /** 解析一行应答；坏包返回 null（永远不要相信网络上的东西） */
    public static ServiceInfo parse(String msg, String fromIp) {
        ServiceInfo si = new ServiceInfo();
        si.ip = fromIp;
        si.port = 8080;
        si.source = "DISCOVERY";
        for (String part : msg.substring(MAGIC_RESP.length()).trim().split("\\s+")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq);
            String v = part.substring(eq + 1);
            switch (k) {
                case "port":
                    try {
                        si.port = Integer.parseInt(v);
                    } catch (NumberFormatException ignored) {
                    }
                    break;
                case "title":
                    si.title = v;
                    break;
                case "ver":
                    si.version = v;
                    break;
                case "login":
                    si.needLogin = "1".equals(v);
                    break;
                default:
                    break;
            }
        }
        si.baseUrl = "http://" + si.ip + ":" + si.port;
        return si;
    }

    /**
     * 向所有网卡广播探测包。要发两轮（有的安卓热点第一包会被 ARP 消化掉）。
     */
    public static void broadcast() {
        List<Host.Nic> nics = Host.nics();
        byte[] payload = MAGIC_REQ.getBytes(StandardCharsets.UTF_8);

        // 1) 定向广播（每个网段的广播地址）
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setBroadcast(true);
            for (Host.Nic n : nics) {
                if (n.isLoopback()) continue;
                try {
                    InetAddress bc = InetAddress.getByAddress(n.broadcast());
                    s.send(new DatagramPacket(payload, payload.length, bc, PORT));
                } catch (Throwable ignored) {
                }
            }
            // 2) 全局广播
            try {
                s.send(new DatagramPacket(payload, payload.length,
                        InetAddress.getByName("255.255.255.255"), PORT));
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        } finally {
            if (s != null) s.close();
        }

        // 3) 组播兜底：某些路由/热点只放组播不放广播
        MulticastSocket ms = null;
        try {
            ms = new MulticastSocket();
            ms.setTimeToLive(2);
            InetAddress grp = InetAddress.getByName("239.255.42.99");
            for (int i = 0; i < 2; i++) {
                try {
                    ms.send(new DatagramPacket(payload, payload.length, grp, PORT));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (ms != null) ms.close();
        }
    }
}
