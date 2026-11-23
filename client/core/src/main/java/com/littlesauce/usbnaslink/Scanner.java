package com.littlesauce.usbnaslink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全流程扫描编排：发现 → 端口扫 → 指纹确认 → 合并。
 *
 * 线程模型（重要）：
 *   第一阶段用「固定线程池 + 非阻塞 connect」的批量投递，而不是一地址一线程。
 *   /24 段 254 个地址，256 线程的池子在安卓上会直接 OOM（每线程 1MB 栈），
 *   所以池子固定 {@code threads} 条，任务按地址顺序投，天然限流。
 *
 * 代价：如果目标网段里有防火墙丢了 SYN，每个地址要等满 300ms 才放弃。
 * 254 个地址 / 64 线程 ≈ 4 批 × 300ms ≈ 1.2s，可接受。
 */
public final class Scanner {

    public interface Progress {
        /**
         * @param phase  DISCOVER / PORT / PROBE / DONE
         * @param done   已完成数
         * @param total  总数
         * @param note   一行人类可读状态
         */
        void onProgress(String phase, int done, int total, String note);

        /** 发现一个服务；返回 false 表示上层取消 */
        boolean onFound(ServiceInfo si);
    }

    /** 扫描参数。全部有合理默认值，界面上只暴露前三个 */
    public static final class Options {
        public int port = 8080;
        /** 并发线程数 */
        public int threads = 96;
        /** 单地址 TCP connect 超时（毫秒） */
        public int connectTimeoutMs = 300;
        /** UDP 发现等待时长 */
        public int discoveryMs = 1200;
        /** 每条网段最多展开多少个 /24（大网段防爆炸） */
        public int maxSubnets = 2;
        /** 扫完整个网段还不够，是否对 UDP 应答里报过的主机做补充指纹（默认 true） */
        public boolean probeDiscovered = true;
        /** 是否只接受指纹确认的 USBNAS；false 则开放 8080 的主机也列出来 */
        public boolean strict = true;
        /** 额外候选（历史地址），格式 ip 或 ip:port，会绕过网段推导直接试一次 */
        public java.util.List<String> extraTargets = new java.util.ArrayList<>();
    }

    private final Options opt;
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    /** 排障钩子：非 null 时把每批扫描的统计吐出去（桌面端/安卓端各自接日志） */
    public java.util.function.Consumer<String> scanDebug;

    public Scanner(Options opt) {
        this.opt = opt == null ? new Options() : opt;
    }

    public void cancel() {
        cancel.set(true);
    }

    /**
     * 同步执行一次完整扫描。调用方负责放后台线程。
     *
     * @return 去重合并后的服务列表；已取消时返回已找到的部分
     */
    public List<ServiceInfo> scan(Progress p) {
        Map<String, ServiceInfo> found = new LinkedHashMap<>();
        cancel.set(false);

        // ---------- 1) UDP 发现 ----------
        if (!cancel.get()) {
            report(p, "DISCOVER", 0, 1, "正在广播探测包…");
            Thread t = new Thread(Discovery::broadcast, "usbnas-bcast");
            t.setDaemon(true);
            t.start();
            List<ServiceInfo> hit = Discovery.listen(opt.discoveryMs);
            for (ServiceInfo si : hit) {
                merge(found, si);
                if (p != null && !p.onFound(si)) cancel.set(true);
            }
            report(p, "DISCOVER", 1, 1, "广播应答 " + hit.size() + " 个");
        }

        // ---------- 2) 网段 TCP 扫描 ----------
        if (!cancel.get()) {
            List<Host.Nic> nics = Host.nics();
            List<Host> targets = new ArrayList<>();
            for (Host.Nic n : nics) {
                targets.addAll(Host.expand(n, opt.maxSubnets));
                // 手机自己就是 NAS 宿主时，expand() 会把自己排掉（containsSelf=false），
                // 那样「手机上的客户端连手机上的 NAS」永远扫不出来。这里补上自身地址：
                // 对端就是本机 loopback 快捷路径，成本约 0ms，不补才是漏。
                targets.add(new Host(new byte[]{n.ip[0], n.ip[1], n.ip[2], n.ip[3]},
                        n.name, Host.addr(n.ip)));
            }
            // 顺手把历史连接过的地址塞进来：可能不在任何直连网段（比如换了 Wi-Fi），
            // 但用户明确用过，值得重试一次。
            for (String k : opt.extraTargets) {
                String ip = k.contains(":") ? k.substring(0, k.lastIndexOf(':')) : k;
                byte[] b = Host.parse(ip);
                if (b != null) targets.add(new Host(b, "历史", ip));
            }
            // 已经通过 UDP 确认的主机不必再 connect 一次
            targets.removeIf(h -> found.containsKey(h.ipText + ":" + opt.port));

            int total = targets.size();
            report(p, "PORT", 0, total, "扫描 " + nics.size() + " 条网卡 / " + total + " 个地址（端口 " + opt.port + "）");

            List<Host> openHosts = portScan(targets, p, found);
            report(p, "PORT", total, total, "开放 " + openHosts.size() + " 个");

            // ---------- 3) 指纹确认 ----------
            report(p, "PROBE", 0, openHosts.size(), "核对 USBNAS 指纹…");
            probeAll(openHosts, p, found);
            report(p, "PROBE", openHosts.size(), openHosts.size(), "确认 " + found.size() + " 个服务");
        }

        report(p, "DONE", 1, 1, "扫描结束，共 " + found.size() + " 个");
        return new ArrayList<>(found.values());
    }

    /** 并行 connect 一遍，收集开放的地址；同时按需触发指纹 */
    private List<Host> portScan(List<Host> targets, Progress p, Map<String, ServiceInfo> found) {
        List<Host> open = new ArrayList<>();
        if (targets.isEmpty()) return open;

        int n = Math.max(8, Math.min(256, opt.threads));
        ExecutorService pool = Executors.newFixedThreadPool(n, r -> {
            Thread th = new Thread(r, "usbnas-port");
            th.setDaemon(true);
            return th;
        });
        int done = 0;
        try {
            int chunk = n * 4;
            for (int i = 0; i < targets.size() && !cancel.get(); i += chunk) {
                int end = Math.min(targets.size(), i + chunk);
                List<Callable<Host>> batch = new ArrayList<>(end - i);
                for (int j = i; j < end; j++) {
                    Host h = targets.get(j);
                    batch.add(() -> Probe.isOpen(h.ipText, opt.port, opt.connectTimeoutMs) ? h : null);
                }
                List<Future<Host>> fs = pool.invokeAll(batch, opt.connectTimeoutMs + 2000L, TimeUnit.MILLISECONDS);
                int openHere = 0;
                Throwable firstErr = null;
                for (Future<Host> f : fs) {
                    try {
                        // 这里必须用 get() 而不是 get(0, ...)：invokeAll 已经把整批都跑完
                        // （或超时取消）才返回，此时每个 Future 都已完成。
                        // 早先写成 f.get(0, TimeUnit.MILLISECONDS) 是 0ms 超时，
                        // 结果每个 Future 都抛 TimeoutException 被吞掉 —— 表现就是
                        // 「扫了 760 个地址全部 0 命中」，而在 PC 上因为时序巧合偶尔能中。
                        Host h = f.get();
                        if (h != null) {
                            open.add(h);
                            openHere++;
                        }
                    } catch (java.util.concurrent.CancellationException
                             | java.util.concurrent.ExecutionException e) {
                        // 超时被取消 / 单个 connect 抛异常：当成不开放
                        if (firstErr == null) firstErr = e;
                    }
                }
                if (scanDebug != null) {
                    scanDebug.accept("batch " + i + ".." + (end - 1) + " open=" + openHere
                            + (firstErr == null ? "" : " firstErr=" + firstErr.getClass().getSimpleName()));
                }
                done = end;
                report(p, "PORT", done, targets.size(), "已扫 " + done + "/" + targets.size()
                        + "，开放 " + open.size());
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        return open;
    }

    /** 指纹确认；并发的，通常只有个位数地址，直接一线程一地址 */
    private void probeAll(List<Host> hosts, Progress p, Map<String, ServiceInfo> found) {
        if (hosts.isEmpty()) return;
        int n = Math.min(16, Math.max(1, hosts.size()));
        ExecutorService pool = Executors.newFixedThreadPool(n, r -> {
            Thread th = new Thread(r, "usbnas-probe");
            th.setDaemon(true);
            return th;
        });
        List<Future<?>> fs = new ArrayList<>();
        final int[] cnt = {0};
        for (Host h : hosts) {
            fs.add(pool.submit(() -> {
                if (cancel.get()) return;
                ServiceInfo si = Probe.classify(h.ipText, opt.port);
                synchronized (cnt) {
                    cnt[0]++;
                }
                if (si != null) {
                    merge(found, si);
                    if (p != null) p.onFound(si);
                } else if (!opt.strict) {
                    // 非严格模式：连得上但不像 USBNAS 的也列出来，用户可能改了站点名
                    ServiceInfo loose = new ServiceInfo();
                    loose.ip = h.ipText;
                    loose.port = opt.port;
                    loose.baseUrl = "http://" + h.ipText + ":" + opt.port;
                    loose.title = "未确认（端口开放）";
                    loose.source = "SCAN";
                    merge(found, loose);
                    if (p != null) p.onFound(loose);
                }
                synchronized (cnt) {
                    report(p, "PROBE", cnt[0], hosts.size(), "已核对 " + cnt[0] + "/" + hosts.size());
                }
            }));
        }
        for (Future<?> f : fs) {
            try {
                f.get(Probe.READ_TIMEOUT * 3L, TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {
            }
        }
        pool.shutdownNow();
    }

    /**
     * 合并同 ip:port 的两条结果。规则：
     *   - 谁的信息更全谁赢（disks > title > pageTitle）
     *   - 指纹来源（SCAN）比广播来源（DISCOVERY）更权威，因为它真读过内容
     */
    private static void merge(Map<String, ServiceInfo> found, ServiceInfo si) {
        ServiceInfo old = found.get(si.key());
        if (old == null) {
            found.put(si.key(), si);
            return;
        }
        if (si.title != null && !si.title.isEmpty() && (old.title == null || old.title.isEmpty())) {
            old.title = si.title;
        }
        if (si.pageTitle != null && !si.pageTitle.isEmpty()
                && (old.pageTitle == null || old.pageTitle.isEmpty())) {
            old.pageTitle = si.pageTitle;
        }
        if (si.disksText != null && !si.disksText.isEmpty() && (old.disksText == null || old.disksText.isEmpty())) {
            old.disksText = si.disksText;
        }
        if (si.version != null && !si.version.isEmpty() && (old.version == null || old.version.isEmpty())) {
            old.version = si.version;
        }
        if ("DISCOVERY".equals(old.source) && !"DISCOVERY".equals(si.source)) {
            old.source = si.source;
        }
        old.needLogin = old.needLogin || si.needLogin;
        if (si.rttMs > 0 && (old.rttMs == 0 || si.rttMs < old.rttMs)) old.rttMs = si.rttMs;
        old.seenAt = System.currentTimeMillis();
    }

    private static void report(Progress p, String phase, int done, int total, String note) {
        if (p != null) {
            try {
                p.onProgress(phase, done, total, note);
            } catch (Throwable ignored) {
            }
        }
    }
}
