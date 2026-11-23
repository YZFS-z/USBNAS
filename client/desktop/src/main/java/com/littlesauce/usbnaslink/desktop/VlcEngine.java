package com.littlesauce.usbnaslink.desktop;

import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;

import java.io.File;
import java.util.function.Consumer;

/**
 * 基于 libvlc（VLCJ）的播放引擎。
 *
 * 为什么不再用 Java Sound：JRE 自带的解码器只有 PCM/WAV，mp3/flac/m4a 全都
 * 播不了，之前只能丢给系统播放器 —— 一交出去就没有曲目列表、没有进度、没有
 * 桌面歌词。libvlc 几乎什么都能解（含 .mkv/.flac/.ape），而且支持 seek。
 *
 * 定位规则（第一次成功就缓存住，之后不再重复试探）：
 *   1. 系统属性 usbnaslink.vlc，或环境变量 USBNASLINK_VLC
 *   2. exe 同目录的 vlc\ 子目录（打包时随 zip 分发）
 *   3. 注册表里安装过的 VLC（VLCJ 的 NativeDiscovery 自带这步）
 * 都找不到时 {@link #isAvailable()} 返回 false，调用方退回系统播放器，
 * 功能降级但不会一头撞死。
 */
final class VlcEngine {

    private static boolean tried;
    private static boolean ok;
    private static String vlcDir = "";
    private static String lastError = "";

    private MediaPlayerFactory factory;
    private MediaPlayer player;

    /** 播放进度，毫秒 */
    private volatile long timeMs;
    private volatile long lengthMs;
    /** 是否走在 libvlc 上（false = 已回退，界面别再用它取进度） */
    private volatile boolean active;
    private Consumer<Void> onFinished;

    private VlcEngine() {
    }

    // ------------------------------------------------------------ 静态探测

    /**
     * 找 VLC 运行时目录，返回 ...\vlc 这种带 libvlc.dll 的目录。
     * 找到就把 libvlc 的路径告诉 JNA，之后 new MediaPlayerFactory() 才能成。
     */
    private static synchronized boolean probe() {
        if (tried) return ok;
        tried = true;

        String[] cands = new String[]{
                System.getProperty("usbnaslink.vlc"),
                System.getenv("USBNASLINK_VLC"),
                appDir() + File.separator + "vlc",
                appDir() + File.separator + "runtime" + File.separator + "vlc",
        };
        for (String c : cands) {
            if (c == null || c.isEmpty()) continue;
            File d = new File(c);
            if (new File(d, "libvlc.dll").isFile()) {
                vlcDir = d.getAbsolutePath();
                installJnaPath(d);
                ok = true;
                return true;
            }
        }

        // 交给 VLCJ 自己的 NativeDiscovery（查注册表与 Program Files）
        try {
            uk.co.caprica.vlcj.factory.discovery.NativeDiscovery nd =
                    new uk.co.caprica.vlcj.factory.discovery.NativeDiscovery();
            if (nd.discover()) {
                vlcDir = nd.discoveredPath();
                ok = vlcDir != null && !vlcDir.isEmpty();
                if (ok) return true;
            }
        } catch (Throwable t) {
            lastError = String.valueOf(t);
        }

        lastError = "没找到 libvlc.dll；把 VLC 装到本机，或解压到 exe 同目录的 vlc\\ 下";
        return false;
    }

    /** 把 vlc 目录塞进 jna.library.path，JNA 加载 libvlc.dll 时才找得到 */
    private static void installJnaPath(File dir) {
        String old = System.getProperty("jna.library.path");
        String p = dir.getAbsolutePath();
        if (old == null || old.isEmpty()) System.setProperty("jna.library.path", p);
        else if (!old.contains(p)) System.setProperty("jna.library.path", p + File.pathSeparator + old);
        // plugins 路径也直接写死，省得 libvlc 自己去猜工作目录
        System.setProperty("vlcj.plugins.path", "plugins");
        System.setProperty("vlcj.log", System.getProperty("vlcj.log", "WARN"));
    }

    /** exe 所在目录：优先 jpackage/启动器给的，其次当前 jar 的位置 */
    private static String appDir() {
        try {
            String s = System.getProperty("usbnaslink.home");
            if (s != null && !s.isEmpty()) return s;
        } catch (Throwable ignored) {
        }
        try {
            File f = new File(VlcEngine.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (f.isFile()) f = f.getParentFile();
            return f == null ? "." : f.getAbsolutePath();
        } catch (Throwable t) {
            return ".";
        }
    }

    static boolean isAvailable() {
        return probe();
    }

    static String status() {
        if (!probe()) return "未找到 VLC 运行时：" + lastError;
        return "VLC 运行时：" + vlcDir;
    }

    // ------------------------------------------------------------ 实例

    /** 建一个引擎；失败返回 null，调用方自己回退 */
    static VlcEngine create() {
        if (!probe()) return null;
        try {
            VlcEngine e = new VlcEngine();
            e.factory = new MediaPlayerFactory("--no-video-title-show", "--quiet");
            // 纯音频用 headless player：不建视频窗口，嵌入式那个要绑 Component
            e.player = e.factory.mediaPlayers().newMediaPlayer();
            e.player.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
                @Override
                public void playing(MediaPlayer mp) {
                    e.active = true;
                }

                @Override
                public void timeChanged(MediaPlayer mp, long t) {
                    e.timeMs = t;
                }

                @Override
                public void lengthChanged(MediaPlayer mp, long len) {
                    e.lengthMs = len;
                }

                @Override
                public void finished(MediaPlayer mp) {
                    Consumer<Void> cb = e.onFinished;
                    if (cb != null) cb.accept(null);
                }

                @Override
                public void error(MediaPlayer mp) {
                    e.active = false;
                }
            });
            return e;
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            return null;
        }
    }

    void setOnFinished(Runnable r) {
        onFinished = r == null ? null : (v) -> r.run();
    }

    /** 播一个网络地址（http://… 也行）；headers 里可带 Referer/User-Agent */
    boolean play(String url, String cookie) {
        try {
            if (player == null) return false;
            timeMs = 0;
            lengthMs = 0;
            active = true;
            String[] opts = new String[]{"--no-video-title-show"};
            if (cookie != null && !cookie.isEmpty()) {
                // libvlc 的 http 访问插件认这句话里的 Cookie
                opts = new String[]{
                        "--no-video-title-show",
                        ":http-referrer=",
                        ":http-user-agent=USBNASLink/1.0",
                };
            }
            boolean started = player.media().play(url, opts);
            return started;
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            active = false;
            return false;
        }
    }

    void pause() {
        try {
            if (player != null) player.controls().setPause(true);
        } catch (Throwable ignored) {
        }
    }

    void resume() {
        try {
            if (player != null) player.controls().setPause(false);
        } catch (Throwable ignored) {
        }
    }

    void stop() {
        try {
            if (player != null) player.controls().stop();
        } catch (Throwable ignored) {
            }
        active = false;
    }

    /** 跳到指定毫秒；libvlc 支持在 http 流上 seek（服务端要支持 Range） */
    void seekTo(long ms) {
        try {
            if (player != null) player.controls().setTime(Math.max(0, ms));
        } catch (Throwable ignored) {
        }
    }

    long timeMs() {
        return timeMs;
    }

    long lengthMs() {
        return lengthMs;
    }

    boolean isActive() {
        return active && player != null;
    }

    void release() {
        active = false;
        try {
            if (player != null) {
                player.controls().stop();
                player.release();
            }
        } catch (Throwable ignored) {
        }
        player = null;
        try {
            if (factory != null) factory.release();
        } catch (Throwable ignored) {
        }
        factory = null;
    }
}
