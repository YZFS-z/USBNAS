package com.littlesauce.usbnaslink.desktop;

import com.littlesauce.usbnaslink.Scanner;
import com.littlesauce.usbnaslink.ServiceInfo;

import java.util.List;

/**
 * 命令行扫描器：用来在没有界面的环境下验证发现/指纹链路是否正常。
 *
 *   java -cp out\classes com.littlesauce.usbnaslink.desktop.ScanCli [port] [--loose]
 *
 * 桌面客户端「扫描」按钮走的是同一套 Scanner，只是多了一个 UI 包装，
 * 所以这里跑通就等于 GUI 的扫描逻辑是通的。
 */
public final class ScanCli {

    public static void main(String[] argv) {
        Scanner.Options o = new Scanner.Options();
        boolean loose = false;
        for (String a : argv) {
            if (a == null) continue;
            if ("--loose".equals(a)) {
                loose = true;
                continue;
            }
            try {
                o.port = Integer.parseInt(a.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        o.strict = !loose;
        o.discoveryMs = 1200;
        o.maxSubnets = 2;

        System.out.println("[SCAN] port=" + o.port + " strict=" + o.strict
                + " threads=" + o.threads + " discoveryMs=" + o.discoveryMs);
        long t0 = System.currentTimeMillis();
        Scanner sc = new Scanner(o);
        List<ServiceInfo> list = sc.scan(new Scanner.Progress() {
            @Override
            public void onProgress(String phase, int done, int total, String note) {
                if ("PROBE".equals(phase) || "DISCOVER".equals(phase) || "DONE".equals(phase)) {
                    System.out.println("  [" + phase + "] " + note);
                }
            }

            @Override
            public boolean onFound(ServiceInfo si) {
                System.out.println("  + FOUND " + si.baseUrl + "  name=" + si.displayName()
                        + "  rtt=" + si.rttMs + "ms  src=" + si.source);
                return true;
            }
        });
        long dt = System.currentTimeMillis() - t0;

        System.out.println("[RESULT] " + list.size() + " 个服务，耗时 " + dt + "ms");
        for (ServiceInfo si : list) {
            System.out.println("  -> " + si.baseUrl
                    + " | " + si.displayName()
                    + (si.version.isEmpty() ? "" : " | v" + si.version)
                    + (si.disksText.isEmpty() ? "" : " | " + si.disksText));
        }
        if (list.isEmpty()) {
            System.out.println("[RESULT] 未发现 USBNAS，检查：设备热点是否开着 / 服务是否已启动 / 本机是否同一网段");
        }
    }
}
