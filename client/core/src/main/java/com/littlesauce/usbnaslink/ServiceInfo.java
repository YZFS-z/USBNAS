package com.littlesauce.usbnaslink;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 扫到的服务。
 *
 * 字段来自两处：
 *   A. UDP 发现应答 —— 拿到 ip/port/title/version（服务端自己报的，最可靠）
 *   B. HTTP 指纹     —— 拿到 ip/port/Server 头 + 首页 <title>（服务没跑发现端口时的兜底）
 * 两条腿都可能命中同一个服务，最终按 ip:port 去重合并。
 */
public final class ServiceInfo {

    public String ip;
    public int port;
    /** 探测来源：DISCOVERY / SCAN / MANUAL / HISTORY */
    public String source = "SCAN";
    /** 应用层能用的地址，例如 http://192.168.1.10:8080 */
    public String baseUrl;
    /** 站点名称，来自 Server 头（USBNAS 把自定义标题写进 Server: 行） */
    public String title = "";
    /** 网页首页 <title>，通常和 title 一样，但被用户改过名时会更新 */
    public String pageTitle = "";
    /** 响应里能看到的版本号（如 20261122），没有就是空 */
    public String version = "";
    /** /_status 里拿到的磁盘信息，展示用；拿不到为 null */
    public String disksText = "";
    /** 是否需要登录（匿名关闭 + 有账号） */
    public boolean needLogin;
    /** 客户端已登录的用户名（内存态，不落盘） */
    public String user = "";
    /** 探测耗时，毫秒 */
    public long rttMs;
    /** 最后一次成功探测的时间戳 */
    public long seenAt = System.currentTimeMillis();

    public String key() {
        return ip + ":" + port;
    }

    public String hostPort() {
        return ip + ":" + port;
    }

    public String displayName() {
        if (title != null && !title.isEmpty()) return title;
        if (pageTitle != null && !pageTitle.isEmpty()) return pageTitle;
        return "USBNAS";
    }

    public Map<String, String> toMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ip", ip);
        m.put("port", String.valueOf(port));
        m.put("url", baseUrl);
        m.put("name", displayName());
        m.put("disks", disksText == null ? "" : disksText);
        return m;
    }

    @Override
    public String toString() {
        return displayName() + " @ " + baseUrl;
    }
}
