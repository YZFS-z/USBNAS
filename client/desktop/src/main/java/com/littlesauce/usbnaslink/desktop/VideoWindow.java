package com.littlesauce.usbnaslink.desktop;

import com.littlesauce.usbnaslink.Video;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * 内嵌视频窗口：电影/剧集在客户端窗口里直接播，不甩给系统播放器。
 *
 * <p>用 vlcj 的 {@code EmbeddedMediaPlayerComponent}，它内部已经处理好
 * factory、视频 surface、以及「窗口尺寸变化时把 surface 重新挂到新 HWND」
 * 这些麻烦事。所以这里只剩播放控制条。
 *
 * <p>为什么这个部件要单独一个窗口而不是塞进主窗口的 Tab：
 * <ul>
 *   <li>看剧通常要最大化甚至全屏，Tab 里做不到；</li>
 *   <li>vlcj 的 surface 是一个原生 HWND 子窗口，放进 JTabbedPane 后
 *       切换页签时会被 Swing 的重绘盖掉，出现「黑屏但声音还在」。
 *       独立窗口避开这一类问题。</li>
 * </ul>
 *
 * <p>VLC 不可用时（没打包 libvlc 或用户机器上没装），按钮全部禁用并说明
 * 原因，同时保留「用系统播放器打开」的兜底入口。
 */
final class VideoWindow extends JFrame {

    private final JLabel titleLabel = new JLabel("没有在放的视频");
    private final JLabel timeLabel = new JLabel("00:00 / 00:00");
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final JButton playBtn = Theme.primary("暂停");
    private final JButton backBtn = Theme.ghost("后退 15s");
    private final JButton fwdBtn = Theme.ghost("前进 15s");
    private final JButton fullBtn = Theme.ghost("全屏");
    private final JButton extBtn = Theme.ghost("用系统播放器");
    private final JButton trackBtn = Theme.ghost("字幕/音轨");
    private final JLabel note = Theme.caption("");

    private uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent comp;
    private boolean started;
    private String currentUrl;
    private String currentCookie;
    private boolean dragging;
    private final Timer ticker;

    VideoWindow() {
        super("USBNASLink 播放器");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getContentPane().setBackground(Theme.BG_DEEP);

        titleLabel.setFont(Theme.bold(14));
        titleLabel.setForeground(Theme.FG_TITLE);

        JPanel head = new JPanel(new BorderLayout(10, 0));
        head.setOpaque(true);
        head.setBackground(Theme.BG_CARD);
        head.setBorder(new EmptyBorder(8, 14, 8, 14));
        head.add(titleLabel, BorderLayout.CENTER);
        head.add(note, BorderLayout.EAST);

        // 视频区域：先放一块占位，VLC 起来之后再换上去
        JPanel stage = new JPanel(new BorderLayout());
        stage.setBackground(Color.BLACK);
        JLabel placeholder = new JLabel("准备中…", SwingConstants.CENTER);
        placeholder.setForeground(Theme.FG_MUTE);
        stage.add(placeholder, BorderLayout.CENTER);

        bar.setBackground(Theme.BG_CARD);
        bar.setForeground(Theme.BRAND);
        bar.setBorderPainted(false);
        bar.setPreferredSize(new Dimension(100, 4));
        bar.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) { dragging = true; }
            public void mouseReleased(MouseEvent e) {
                dragging = false;
                seekTo(bar.getValue());
            }
        });

        timeLabel.setFont(Theme.mono(11));
        timeLabel.setForeground(Theme.FG_SUB);

        JPanel ctl = new JPanel(new BorderLayout(10, 0));
        ctl.setOpaque(true);
        ctl.setBackground(Theme.BG_CARD);
        ctl.setBorder(new EmptyBorder(6, 12, 8, 12));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(backBtn);
        buttons.add(playBtn);
        buttons.add(fwdBtn);
        buttons.add(trackBtn);
        buttons.add(fullBtn);
        buttons.add(extBtn);
        ctl.add(buttons, BorderLayout.WEST);
        ctl.add(timeLabel, BorderLayout.EAST);

        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(bar, BorderLayout.NORTH);
        south.add(ctl, BorderLayout.CENTER);

        add(head, BorderLayout.NORTH);
        add(stage, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        setSize(960, 620);
        setLocationRelativeTo(null);

        playBtn.addActionListener(e -> toggle());
        backBtn.addActionListener(e -> skip(-15000));
        fwdBtn.addActionListener(e -> skip(15000));
        fullBtn.addActionListener(e -> toggleFull());
        extBtn.addActionListener(e -> {
            if (currentUrl != null) PlayerWindow.openExternal(currentUrl);
        });
        trackBtn.addActionListener(e -> cycleTrack());

        ticker = new Timer(250, e -> onTick());
        ticker.setInitialDelay(0);

        // 初始就探测一次，别等用户点了播放才说「没有 libvlc」
        if (!VlcEngine.isAvailable()) {
            note.setText("没找到 libvlc");
            note.setForeground(Theme.WARN);
            playBtn.setEnabled(false);
            backBtn.setEnabled(false);
            fwdBtn.setEnabled(false);
            trackBtn.setEnabled(false);
            fullBtn.setEnabled(false);
            placeholder.setText("<html><div style='width:420px;text-align:center'>"
                    + "客户端没找到 VLC 运行时（libvlc.dll）。<br>把 VLC 装到本机，"
                    + "或解压到程序目录的 vlc\\ 下，就能在这里直接播。<br>"
                    + "现在可以点右下「用系统播放器」先看着。</div></html>");
            placeholder.setForeground(Theme.FG_SUB);
        }
    }

    /** 如果当前视频不是这一部，就换片；同一部重复点只是前置窗口 */
    void play(Video v, String url, String cookie) {
        titleLabel.setText(v == null ? "视频" : (v.title == null || v.title.isEmpty() ? v.name : v.title));
        if (v != null) {
            String sub = v.folder() + (v.isTv() ? "    ·    " + v.tag() : "");
            note.setText(sub);
            note.setForeground(Theme.FG_DIM);
        }
        currentUrl = url;
        currentCookie = cookie;
        if (url != null && url.equals(startedUrl())) {
            toFront();
            return;
        }
        setVisible(true);
        toFront();
        playUrl(url, cookie);
    }

    private String lastUrl;

    private String startedUrl() {
        return lastUrl;
    }

    private void playUrl(String url, String cookie) {
        ensureEngine();
        if (comp == null) return;
        lastUrl = url;
        try {
            uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer mp = comp.mediaPlayer();
            // 认证靠 Cookie：服务端有账号时 /play/video 与文件流都要 sid。
            // VLC 没有「设置请求头」的官方参数，用 :http-cookie 传 Cookie 头内容。
            String[] opts = cookie == null || cookie.isEmpty()
                    ? new String[]{"--no-video-title-show"}
                    : new String[]{"--no-video-title-show",
                        ":http-cookie=" + com.littlesauce.usbnaslink.Session.COOKIE + "=" + cookie};
            boolean ok = mp.media().play(url, opts);
            if (!ok) {
                note.setText("这部片打不开（编码或网络问题）");
                note.setForeground(Theme.ERR);
                playBtn.setEnabled(false);
                return;
            }
            playBtn.setEnabled(true);
            playBtn.setText("暂停");
            started = true;
            ticker.start();
        } catch (Throwable t) {
            note.setText("libvlc 报错：" + t);
            note.setForeground(Theme.ERR);
        }
    }

    /**
     * 懒加载 EmbeddedMediaPlayerComponent。
     *
     * 这个构造器会自己 build 一个 MediaPlayerFactory，所以不用走 VlcEngine 的
     * 静态探测结果之外的东西；只要 jna.library.path 指对，它就能起来。
     * 失败一次就不再试（重试会在用户点第二次时再弹一遍同样的错）。
     */
    private boolean engineTried;

    private void ensureEngine() {
        if (comp != null || engineTried) return;
        engineTried = true;
        if (!VlcEngine.isAvailable()) return;
        try {
            comp = new uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent(
                    "--no-video-title-show", "--quiet");
            JPanel stage = (JPanel) getContentPane().getComponent(1);
            stage.removeAll();
            stage.add(comp, BorderLayout.CENTER);
            stage.revalidate();
            stage.repaint();
        } catch (Throwable t) {
            comp = null;
            note.setText("初始化 libvlc 失败：" + t.getMessage());
            note.setForeground(Theme.ERR);
        }
    }

    private void toggle() {
        if (comp == null) return;
        uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer mp = comp.mediaPlayer();
        if (mp.status().isPlaying()) {
            mp.controls().setPause(true);
            playBtn.setText("播放");
        } else {
            mp.controls().setPause(false);
            playBtn.setText("暂停");
        }
    }

    private void skip(long ms) {
        if (comp == null) return;
        comp.mediaPlayer().controls().skipTime(ms);
    }

    private void seekTo(int value) {
        if (comp == null) return;
        comp.mediaPlayer().controls().setPosition(value / 1000f);
    }

    private void toggleFull() {
        GraphicsDevice gd = getGraphicsConfiguration().getDevice();
        boolean fs = gd.getFullScreenWindow() == this;
        gd.setFullScreenWindow(fs ? null : this);
        fullBtn.setText(fs ? "全屏" : "退出全屏");
    }

    /** 字幕/音轨循环切换：VLC 默认第一条轨，很多双语音轨的片子需要手动换 */
    private void cycleTrack() {
        if (comp == null) return;
        uk.co.caprica.vlcj.player.base.MediaPlayer mp = comp.mediaPlayer();
        try {
            int a = mp.audio().track();
            int count = mp.audio().trackCount();
            if (count > 1) mp.audio().setTrack(a + 1 >= count ? 0 : a + 1);
            note.setText("音轨 " + (a + 1) + "/" + Math.max(count, 1));
            note.setForeground(Theme.FG_DIM);
        } catch (Throwable ignored) {}
    }

    private void onTick() {
        if (comp == null) return;
        try {
            uk.co.caprica.vlcj.player.base.MediaPlayer mp = comp.mediaPlayer();
            long t = mp.status().time();
            long len = mp.status().length();
            // 刚开播那几秒 length() 常是错的（VLC 只读了开头一小段、
            // 甚至拿到的是首帧位置）。等信息齐了用 media.info().duration()，
            // 它才是容器里记的总时长；两者都拿不到就别刷进度条，免得
            // 「总长 1:24:44、进度条却早在 5 秒就走到 1/4」这种怪象。
            if (len <= 0) {
                try {
                    long d = mp.media().info().duration();
                    if (d > 0) len = d;
                } catch (Throwable ignored) {}
            } else {
                try {
                    long d = mp.media().info().duration();
                    if (d > 0 && d > len) len = d;
                } catch (Throwable ignored) {}
            }
            if (!dragging && len > 0) {
                bar.setValue((int) Math.max(0, Math.min(1000, t * 1000 / len)));
            }
            timeLabel.setText(fmt(t) + " / " + fmt(len));
        } catch (Throwable ignored) {}
    }

    private static String fmt(long ms) {
        if (ms <= 0) return "00:00";
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        s = s % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%02d:%02d", m, s);
    }

    /**
     * 给探针/自检用的一行状态。正式 UI 里不显示，避免多一块没人看的文字。
     *
     * <p>为什么需要它：vlcj 的播放状态全在 native 线程里，Swing 这边只能靠
     * 轮询 status() 判断「到底播起来了没有」。headless 环境下没法截图，所以
     * 把 comp/time/length/playing/画面尺寸 拼成字符串，脚本断言这一行就够了。
     */
    String debugState() {
        long t = -1;
        long len = -1;
        boolean playing = false;
        int vw = -1;
        int vh = -1;
        try {
            if (comp != null) {
                uk.co.caprica.vlcj.player.base.MediaPlayer mp = comp.mediaPlayer();
                t = mp.status().time();
                len = mp.status().length();
                playing = mp.status().isPlaying();
                java.awt.Dimension d = mp.video().videoDimension();
                if (d != null) { vw = d.width; vh = d.height; }
            }
        } catch (Throwable ignored) {}
        return "comp=" + (comp != null) + " playing=" + playing + " t=" + t + " len=" + len
                + " video=" + vw + "x" + vh + " bar=" + bar.getValue()
                + " time=" + timeLabel.getText() + " note=" + note.getText()
                + " title=" + titleLabel.getText();
    }

    @Override
    public void dispose() {
        ticker.stop();
        if (comp != null) {
            try { comp.mediaPlayer().controls().stop(); } catch (Throwable ignored) {}
            try { comp.release(); } catch (Throwable ignored) {}
            comp = null;
        }
        GraphicsDevice gd = getGraphicsConfiguration() == null ? null : getGraphicsConfiguration().getDevice();
        if (gd != null && gd.getFullScreenWindow() == this) gd.setFullScreenWindow(null);
        super.dispose();
    }
}
