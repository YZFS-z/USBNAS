package com.littlesauce.usbnaslink.desktop;

import com.littlesauce.usbnaslink.Music;
import com.littlesauce.usbnaslink.Track;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * 音乐播放器窗口。
 *
 * 之所以要在 EXE 里自己做播放器，而不是丢给系统播放器：
 * 曲库在手机上，系统播放器要一次一次拿 URL、拿不到播放列表，也就没有
 * 「上一首/下一首/单曲循环」。
 *
 * 解码三档回退：
 *   1. {@link VlcEngine}（libvlc）—— mp3/flac/m4a/ogg/ape 全都吃，还能 seek
 *   2. Java Sound 的 {@link javax.sound.sampled.SourceDataLine} —— 只解 WAV
 *   3. 系统默认播放器 —— 上面两档都不行时的兜底
 *
 * 播放列表、循环模式、歌词抓取都在这里驱动；歌词渲染交给 {@link LyricWindow}。
 */
final class PlayerWindow extends JFrame {

    private final Music.Playlist list = new Music.Playlist();
    private final LyricWindow lyrics = new LyricWindow();

    private final JLabel title = new JLabel("未在播放", SwingConstants.CENTER);
    private final JLabel sub = new JLabel(" ", SwingConstants.CENTER);
    private final JProgressBar bar = new JProgressBar();
    private final JLabel timeText = new JLabel("00:00 / 00:00");
    private final JButton prevBtn = Theme.ghost("上一首");
    private final JButton playBtn = Theme.primary("播放");
    private final JButton nextBtn = Theme.ghost("下一首");
    private final JComboBox<Music.Mode> modeBox = Theme.combo(Music.Mode.values());
    private final JCheckBox lyricBox = Theme.check("桌面歌词", false);
    private final DefaultListModel<Track> model = new DefaultListModel<>();
    private final JList<Track> qList = new JList<>(model);

    private volatile String baseUrl = "";
    private volatile String cookie = null;

    private ClipHolder clip; // 见文件末尾的流式播放包装（WAV 回退用）
    private volatile VlcEngine vlc; // null = 本机没有 libvlc，或还没建
    private volatile boolean vlcTried;
    private Timer ticker;
    private long playStartMs;
    private long pauseAtMs;
    private boolean playing;

    PlayerWindow() {
        super("USBNASLink — 正在播放");
        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        setSize(560, 460);
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(Theme.BG_DEEP);
        root.setBorder(new EmptyBorder(16, 18, 14, 18));

        title.setFont(Theme.bold(17));
        title.setForeground(Theme.FG_TITLE);
        sub.setFont(Theme.ui(12));
        sub.setForeground(Theme.FG_DIM);

        JPanel head = new JPanel(new BorderLayout(0, 4));
        head.setOpaque(false);
        head.add(title, BorderLayout.NORTH);
        head.add(sub, BorderLayout.SOUTH);

        Theme.progress(bar);
        bar.setStringPainted(false);
        bar.setValue(0);
        timeText.setFont(Theme.mono(11));
        timeText.setForeground(Theme.FG_SUB);
        timeText.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel center = new JPanel(new BorderLayout(0, 4));
        center.setOpaque(false);
        center.add(bar, BorderLayout.NORTH);
        center.add(timeText, BorderLayout.SOUTH);
        center.add(qList, BorderLayout.CENTER);

        qList.setBackground(Theme.BG_CARD);
        qList.setForeground(Theme.FG_TEXT);
        qList.setSelectionBackground(Theme.BG_SEL);
        qList.setFont(Theme.ui(12));
        qList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i,
                                                          boolean sel, boolean focus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, sel, focus);
                Track t = (Track) v;
                String nm = t.title == null || t.title.isEmpty() ? t.name : t.title;
                c.setText((i + 1) + ".  " + nm);
                c.setBorder(new EmptyBorder(5, 8, 5, 8));
                c.setBackground(sel ? Theme.BG_SEL : Theme.BG_CARD);
                return c;
            }
        });
        qList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int i = qList.getSelectedIndex();
                    if (i >= 0) {
                        list.select(i);
                        playCurrent();
                    }
                }
            }
        });

        JPanel ctl = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        ctl.setOpaque(false);
        ctl.add(prevBtn);
        ctl.add(playBtn);
        ctl.add(nextBtn);
        ctl.add(new JLabel(" "));
        ctl.add(modeBox);
        ctl.add(lyricBox);

        root.add(head, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        root.add(ctl, BorderLayout.SOUTH);
        setContentPane(root);

        prevBtn.addActionListener(e -> {
            Track t = list.prev();
            if (t != null) playCurrent();
        });
        nextBtn.addActionListener(e -> {
            Track t = list.next();
            if (t != null) playCurrent();
        });
        playBtn.addActionListener(e -> toggle());
        modeBox.addActionListener(e -> {
            Music.Mode m = (Music.Mode) modeBox.getSelectedItem();
            if (m != null) list.mode = m;
        });
        lyricBox.addActionListener(e -> {
            if (lyricBox.isSelected()) lyrics.setVisible(true);
            else lyrics.setVisible(false);
        });

        ticker = new Timer(200, e -> onTick());
        ticker.setRepeats(true);
    }

    /** 播放/暂停切换时只换字，不换 UI —— 按钮圆角底色由 FlatButtonUI 自绘 */
    private void setPlayLabel(String text) {
        playBtn.setText(text);
    }

    void setTarget(String base, String sid) {
        this.baseUrl = base == null ? "" : base;
        this.cookie = sid;
    }

    void setPlaylist(List<Track> album, Track start) {
        list.setTracks(album);
        model.clear();
        for (Track t : album) model.addElement(t);
        if (start != null) list.select(list.indexOf(start));
        modeBox.setSelectedItem(list.mode);
    }

    /** 直接播系统播放器能开的格式（mp4/mkv 之类） */
    static void openExternal(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(java.net.URI.create(url));
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 播放

    private void toggle() {
        if (playing) pause();
        else resume();
    }

    private void pause() {
        if (vlc != null && vlc.isActive()) vlc.pause();
        if (clip != null) {
            pauseAtMs = pos();
            clip.stop();
        }
        playing = false;
        setPlayLabel("播放");
        if (ticker != null) ticker.stop();
    }

    private void resume() {
        if (vlc != null && vlc.isActive()) {
            vlc.resume();
            playing = true;
            setPlayLabel("暂停");
            if (ticker != null) ticker.start();
            return;
        }
        if (clip == null) {
            playCurrent();
            return;
        }
        clip.setMicrosecondPosition(pauseAtMs * 1000L);
        clip.start();
        playStartMs = System.currentTimeMillis() - pauseAtMs;
        playing = true;
        setPlayLabel("暂停");
        if (ticker != null) ticker.start();
    }

    private long pos() {
        if (vlc != null && vlc.isActive()) return vlc.timeMs();
        return clip == null ? 0 : clip.getMicrosecondPosition() / 1000L;
    }

    /** 懒建 libvlc 引擎；本机没有 VLC 运行时返回 null，之后不再重试 */
    private VlcEngine engine() {
        if (vlc != null) return vlc;
        if (vlcTried) return null;
        vlcTried = true;
        VlcEngine e = VlcEngine.create();
        if (e != null) {
            e.setOnFinished(this::onEnded);
            vlc = e;
        }
        return e;
    }

    private void playCurrent() {
        Track t = list.current();
        if (t == null) return;
        closeClip();
        playing = false;
        setPlayLabel("播放");

        String nm = t.title == null || t.title.isEmpty() ? t.name : t.title;
        title.setText(nm);
        sub.setText(t.dir);
        qList.setSelectedIndex(list.index);
        bar.setValue(0);
        timeText.setText("00:00 / 00:00");

        final String base = baseUrl;
        final String ck = cookie;
        if (base.isEmpty()) {
            sub.setText("没有连接服务器");
            return;
        }

        String audio = Music.audioUrl(base, t.path);

        // 一档：libvlc
        VlcEngine e = engine();
        if (e != null) {
            if (e.play(audio, ck)) {
                sub.setText(t.dir + "    ·    libvlc");
                playing = true;
                setPlayLabel("暂停");
                ticker.start();
                loadLyrics(base, ck, t);
                return;
            }
            sub.setText("libvlc 打不开，改用内置解码");
        }

        // 二档：Java Sound（只有 WAV）
        if (!isWav(t.name)) {
            sub.setText("已交给系统播放器打开（本机没有可用的 libvlc，内置解码只认 WAV）");
            openExternal(audio);
            loadLyrics(base, ck, t);
            return;
        }

        Thread th = new Thread(() -> {
            try {
                java.net.URL u = new java.net.URL(audio);
                javax.sound.sampled.AudioInputStream ais =
                        javax.sound.sampled.AudioSystem.getAudioInputStream(u);
                javax.sound.sampled.DataLine.Info info =
                        new javax.sound.sampled.DataLine.Info(
                                javax.sound.sampled.SourceDataLine.class, ais.getFormat());
                if (!javax.sound.sampled.AudioSystem.isLineSupported(info)) {
                    SwingUtilities.invokeLater(() ->
                            sub.setText("声卡不支持这个格式，已交给系统播放器"));
                    openExternal(audio);
                    loadLyrics(base, ck, t);
                    return;
                }
                javax.sound.sampled.SourceDataLine line =
                        (javax.sound.sampled.SourceDataLine) javax.sound.sampled.AudioSystem.getLine(info);
                line.open(ais.getFormat());
                line.start();
                long frames = ais.getFrameLength();
                float rate = ais.getFormat().getFrameRate();
                // 帧数可能是 NOT_SPECIFIED(-1)，那就按码率估一个时长，估不出来就退化成 0（进度条不显示百分比）
                long totalMs = 0;
                if (frames > 0 && rate > 0) {
                    totalMs = (long) (frames * 1000.0 / rate);
                } else if (t.size > 0) {
                    int bps = ais.getFormat().getSampleSizeInBits()
                            * Math.max(1, ais.getFormat().getChannels())
                            * Math.max(8000, (int) rate);
                    if (bps > 0) totalMs = (long) (t.size * 8000.0 / bps);
                }
                final long totalMsF = totalMs;
                SwingUtilities.invokeLater(() -> {
                    clip = new ClipHolder(line, ais, totalMsF);
                    playing = true;
                    setPlayLabel("暂停");
                    playStartMs = System.currentTimeMillis();
                    ticker.start();
                });
                loadLyrics(base, ck, t);

                byte[] buf = new byte[8192];
                int n;
                while ((n = ais.read(buf)) > 0) {
                    line.write(buf, 0, n);
                }
                line.drain();
                line.stop();
                line.close();
                ais.close();
                SwingUtilities.invokeLater(this::onEnded);
            } catch (Throwable ex) {
                SwingUtilities.invokeLater(() -> {
                    sub.setText("内置播放失败：" + ex.getMessage() + "（已交给系统播放器）");
                    openExternal(audio);
                });
            }
        }, "usbnas-play");
        th.setDaemon(true);
        th.start();
    }

    private void onEnded() {
        playing = false;
        setPlayLabel("播放");
        if (ticker != null) ticker.stop();
        bar.setValue(100);
        Track t = list.onEnded();
        if (t != null && t == list.current()) {
            // 单曲循环：同一个对象，重播
            playCurrent();
            return;
        }
        if (t != null) playCurrent();
    }

    private void loadLyrics(String base, String ck, Track t) {
        if (!t.hasLyrics()) {
            SwingUtilities.invokeLater(() -> lyrics.clear());
            return;
        }
        Thread th = new Thread(() -> {
            String lrc = Music.fetchLyrics(base, ck, t.path, 8000);
            SwingUtilities.invokeLater(() -> lyrics.setLrc(lrc));
        }, "usbnas-lrc");
        th.setDaemon(true);
        th.start();
    }

    private void onTick() {
        long p;
        long total;
        if (vlc != null && vlc.isActive()) {
            p = vlc.timeMs();
            total = vlc.lengthMs();
        } else if (clip != null) {
            p = clip.getMicrosecondPosition() / 1000L;
            total = clip.totalMs;
        } else {
            return;
        }
        bar.setMaximum(1000);
        bar.setValue(total > 0 ? (int) Math.min(1000, p * 1000 / total) : 0);
        timeText.setText(fmt(p) + " / " + fmt(total));
        lyrics.tick(p);
    }

    private void closeClip() {
        if (clip != null) {
            clip.close();
            clip = null;
        }
        if (vlc != null && vlc.isActive()) vlc.stop();
        if (ticker != null) ticker.stop();
        playing = false;
    }

    private static boolean isWav(String name) {
        return name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".wav");
    }

    private static String fmt(long ms) {
        long s = ms / 1000;
        return String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    /**
     * SourceDataLine 的薄包装。
     *
     * 原来想用 Clip，但 Clip 要么整段 UnsupportedAudioFile，要么得先把
     * 整个文件读进内存 —— 一首 FLAC 转出来的 WAV 就几十兆，手机上还得再传
     * 一遍。SourceDataLine 是流式的，边读边放，切歌时直接 close 掉就行。
     */
    private static final class ClipHolder {
        final javax.sound.sampled.SourceDataLine line;
        final javax.sound.sampled.AudioInputStream ais;
        final long totalMs;
        private long startedAt;

        ClipHolder(javax.sound.sampled.SourceDataLine line,
                   javax.sound.sampled.AudioInputStream ais, long totalMs) {
            this.line = line;
            this.ais = ais;
            this.totalMs = totalMs;
            this.startedAt = System.currentTimeMillis() * 1000L;
        }

        void start() {
            line.start();
            startedAt = line.getMicrosecondPosition();
        }

        void stop() {
            line.stop();
        }

        long getMicrosecondPosition() {
            return line.getMicrosecondPosition();
        }

        void setMicrosecondPosition(long us) {
            // SourceDataLine 不支持 seek；这里只能把计时基准挪过去，
            // 音频本身继续顺放 —— 「暂停后续播」在流式播放里就是这个语义。
            startedAt = line.getMicrosecondPosition() - us;
        }

        void close() {
            try {
                line.stop();
                line.close();
            } catch (Throwable ignored) {
            }
            try {
                ais.close();
            } catch (Throwable ignored) {
            }
        }
    }
}