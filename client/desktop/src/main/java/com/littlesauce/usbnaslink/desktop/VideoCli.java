package com.littlesauce.usbnaslink.desktop;

import com.littlesauce.usbnaslink.Probe;
import com.littlesauce.usbnaslink.ServiceInfo;
import com.littlesauce.usbnaslink.Session;
import com.littlesauce.usbnaslink.Video;
import com.littlesauce.usbnaslink.VideoLib;

import java.util.List;

/**
 * 影视库命令行验证器。
 *
 * 桌面端 GUI 出问题的时候，先用这个确认 「到底是对端没数据，还是自己解析坏了」——
 * 上一轮音乐库就是靠同类工具把「服务端 OK / 客户端 JSON 解析错」区分开的。
 */
public final class VideoCli {

    public static void main(String[] argv) throws Exception {
        String host = argv.length > 0 ? argv[0] : "192.168.1.10";
        int port = argv.length > 1 ? Integer.parseInt(argv[1]) : 8080;
        String dir = argv.length > 2 ? argv[2] : "";

        ServiceInfo si = Probe.classify(host, port);
        if (si == null) {
            System.out.println("[FAIL] " + host + ":" + port + " 不像 USBNAS");
            return;
        }
        String base = "http://" + host + ":" + port;
        System.out.println("[OK] " + base + "  " + si.displayName());

        String cookie = null;
        if (si.needLogin) {
            System.out.println("[WARN] 需要登录，这里不做交互，直接匿名试试");
        }

        long t0 = System.currentTimeMillis();
        VideoLib.Lib lib = VideoLib.fetch(base, cookie, dir, 15000);
        long cost = System.currentTimeMillis() - t0;
        System.out.println("[LIB] ok=" + lib.ok + " total=" + lib.videos.size()
                + " roots=" + lib.roots.size() + " groups=" + lib.groups.size()
                + " cost=" + cost + "ms" + (lib.error.isEmpty() ? "" : " error=" + lib.error));

        for (VideoLib.Group g : lib.roots) {
            System.out.println("  ROOT " + g.label + "  count=" + g.count + "  " + g.path);
        }
        for (VideoLib.Group g : lib.groups) {
            System.out.println("  GROUP " + g.label + "  count=" + g.count + "  " + g.path);
        }

        int n = 0;
        for (Video v : lib.videos) {
            if (n++ >= 12) {
                System.out.println("  ... 还有 " + (lib.videos.size() - 12) + " 条");
                break;
            }
            System.out.println("  " + (v.isTv() ? "[TV " + v.tag() + "]" : "[MOV]")
                    + " " + v.title + "   dir=" + v.folder() + "  size=" + v.size);
        }

        // 拿第一条做一次刮削信息实测
        List<Video> all = lib.videos;
        if (!all.isEmpty()) {
            Video v = all.get(0);
            long t1 = System.currentTimeMillis();
            Video.Meta m = VideoLib.fetchMeta(base, cookie, v.path, 15000);
            long cost2 = System.currentTimeMillis() - t1;
            System.out.println("[META] ok=" + m.ok + " cost=" + cost2 + "ms"
                    + (m.error.isEmpty() ? "" : " error=" + m.error));
            System.out.println("  nfoFile=" + m.nfoFile);
            System.out.println("  title=" + m.title + " year=" + m.year + " line=" + m.metaLine());
            System.out.println("  genres=" + m.genres + " countries=" + m.countries);
            System.out.println("  director=" + m.director + " actors=" + m.actors);
            System.out.println("  poster=" + VideoLib.artUrl(base, m.posterUrl));
            System.out.println("  plot=" + (m.plot.length() > 60 ? m.plot.substring(0, 60) + "..." : m.plot));
        }

        System.out.println("[SESSION] cookie=" + (Session.COOKIE));
    }
}
