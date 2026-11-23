package com.littlesauce.usbnaslink;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 已发现的服务器历史（小手写文件，不引 JSON 库）。
 *
 * 为什么要它：热点一断、adb 一重连、手机一休眠，服务地址常常没变，
 * 但重新扫一次要 2~5 秒。启动时先读历史直接连上，扫后台再刷新体验最好。
 *
 * 文件格式（一行一条，UTF-8）：
 *   ip:port \t 站点名 \t 最后一次成功时间戳
 */
public final class Store {

    private final File file;

    public Store(File file) {
        this.file = file;
    }

    public File file() {
        return file;
    }

    public List<ServiceInfo> load() {
        List<ServiceInfo> out = new ArrayList<>();
        if (file == null || !file.isFile()) return out;
        Set<String> seen = new LinkedHashSet<>();
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) Math.min(file.length(), 64 * 1024)];
            int n = in.read(buf);
            if (n <= 0) return out;
            String all = new String(buf, 0, n, StandardCharsets.UTF_8);
            for (String line : all.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\t");
                if (p.length < 1) continue;
                int c = p[0].lastIndexOf(':');
                if (c <= 0) continue;
                ServiceInfo si = new ServiceInfo();
                si.ip = p[0].substring(0, c);
                try {
                    si.port = Integer.parseInt(p[0].substring(c + 1).trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                si.title = p.length > 1 ? p[1] : "";
                if (p.length > 2) {
                    try {
                        si.seenAt = Long.parseLong(p[2].trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
                si.baseUrl = "http://" + si.ip + ":" + si.port;
                si.source = "HISTORY";
                if (seen.add(si.key())) out.add(si);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 整份覆盖写（条目本来就是个位数，不需要增量） */
    public void save(List<ServiceInfo> services) {
        if (file == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("# USBNASLink 已知服务器  ip:port<TAB>名称<TAB>时间戳\n");
        Set<String> seen = new LinkedHashSet<>();
        for (ServiceInfo si : services) {
            if (!seen.add(si.key())) continue;
            sb.append(si.key()).append('\t')
                    .append(si.displayName().replace('\t', ' ').replace('\n', ' ')).append('\t')
                    .append(si.seenAt).append('\n');
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        try (OutputStream o = new FileOutputStream(file)) {
            o.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    /** 记住一台；已存在则更新时间戳 */
    public void remember(ServiceInfo si) {
        List<ServiceInfo> all = load();
        boolean hit = false;
        for (ServiceInfo o : all) {
            if (o.key().equals(si.key())) {
                o.title = si.displayName();
                o.seenAt = System.currentTimeMillis();
                hit = true;
                break;
            }
        }
        if (!hit) {
            ServiceInfo n = new ServiceInfo();
            n.ip = si.ip;
            n.port = si.port;
            n.baseUrl = si.baseUrl;
            n.title = si.displayName();
            n.seenAt = System.currentTimeMillis();
            all.add(0, n);
        }
        save(all);
    }
}
