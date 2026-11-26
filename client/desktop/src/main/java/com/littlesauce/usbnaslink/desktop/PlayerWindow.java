package com.littlesauce.usbnaslink.desktop;

import com.littlesauce.usbnaslink.Music;
import com.littlesauce.usbnaslink.Track;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * 音乐播放器窗口。
 *
 * 之所以要在 EXE 里自己做播放器，而不是丢给系统播放器：
 * 曲库在手机上，系统播放器要一次一次拿 URL、拿不到播放列表，也就没有
 * 「上一首/下一首/单曲循环」。
 *
 * 解码两档回退，而且两档都留在窗口里：
 *   1. {@link VlcEngine}（libvlc）—— mp3/flac/m4a/ogg/ape 全都吃，还能 seek
 *   2. Java Sound 的 {@link javax.sound.sampled.SourceDataLine} —— 只解 WAV
 *
 * 以前还有第三档「交给系统默认播放器」，那是错的：点一首歌就跳出浏览器，
 * 曲目列表、进度、歌词全部断掉，正是用户反馈的「电脑端音乐播放跳转到浏览器」。
 * 现在两档都失败也只在窗口里写清楚原因，不往外甩。
 *
 * 播放列表、循环模式、歌词抓取都在这里驱动；歌词渲染交给 {@link LyricWindow}。
 */
final class PlayerWindow extends JFrame {

    private final Music.Playlist list = new Music.Playlist();
    /** 悬浮歌词条（独立的置顶小窗，可关） */
    private final LyricWindow lyrics = new LyricWindow();
    /** 窗口内的大字歌词，跟手机播放页一样占住右侧 */
    private final LrcView lrc = new LrcView();
    /** 唱片 */
    private final DiscPanel disc = new DiscPanel();

    private final JLabel title = new JLabel("未在播放", SwingConstants.CENTER);
    private final JLabel sub = new JLabel(" ", SwingConstants.CENTER);
    private final JProgressBar bar = new JProgressBar();
    private final JLabel timeText = new JLabel("00:00 / 00:00");
    /**
     * 三颗主控键用 control() 而不是 ghost()：
     * ghost 的底色就是窗口底色，日间主题下 #f2f4f7 底配 #57606a 字，
     * 摆在别的东西旁边像三颗灰掉的占位按钮。control 是实底卡片 + 亮描边。
     */
    private final JButton prevBtn = Theme.control("上一首");
    private final JButton playBtn = Theme.primary("播放");
    private final JButton nextBtn = Theme.control("下一首");
    private final JLabel offsetText = new JLabel("0.0s");
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
        setSize(900, 640);
        setMinimumSize(new Dimension(720, 520));
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(0, 10));
        root.setBackground(Theme.BG_DEEP);
        root.setBorder(new EmptyBorder(16, 18, 14, 18));

        // ---- 左：唱片 ----
        JPanel left = new JPanel(new BorderLayout());
        left.setOpaque(false);
        left.setPreferredSize(new Dimension(360, 0));
        left.add(disc, BorderLayout.CENTER);

        // ---- 右：标题 / 歌词 / 进度 / 按键 ----
        title.setFont(Theme.bold(21));
        title.setForeground(Theme.FG_TITLE);
        title.setHorizontalAlignment(SwingConstants.LEFT);
        sub.setFont(Theme.ui(12));
        sub.setForeground(Theme.FG_DIM);
        sub.setHorizontalAlignment(SwingConstants.LEFT);

        JPanel head = new JPanel(new BorderLayout(0, 6));
        head.setOpaque(false);
        head.add(title, BorderLayout.NORTH);
        head.add(sub, BorderLayout.SOUTH);

        Theme.progress(bar);
        bar.setStringPainted(false);
        bar.setValue(0);
        timeText.setFont(Theme.mono(11));
        timeText.setForeground(Theme.FG_SUB);
        timeText.setHorizontalAlignment(SwingConstants.LEFT);

        // 主控键做宽一点：手机上是四颗等宽大键，桌面这里用固定宽度对齐
        Dimension big = new Dimension(96, 38);
        for (JButton b : new JButton[]{prevBtn, playBtn, nextBtn}) {
            b.setPreferredSize(b == playBtn ? new Dimension(116, 38) : big);
        }

        // 工具行：桌面歌词开关 + 歌词位移（音词不同步时手动拨）
        JButton earlier = Theme.control("歌词提前");
        JButton later = Theme.control("歌词延后");
        JButton resetOff = Theme.control("归零");
        offsetText.setFont(Theme.mono(11));
        offsetText.setForeground(Theme.FG_SUB);
        offsetText.setToolTipText("歌词时间轴偏移量，±10 秒以内，重启后保留");

        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        tools.setOpaque(false);
        tools.add(lyricBox);
        tools.add(earlier);
        tools.add(offsetText);
        tools.add(later);
        tools.add(resetOff);

        // 主控键靠左、循环模式靠右。原来两样都塞进一条 FlowLayout，
        // 窗口一窄 combo 就被折到第二行 —— 而这一行的首选高度只有一行，
        // 折下去的部分直接被裁掉（探针里 combo 的 y=32 而父面板高 32）。
        JPanel ctl = new JPanel(new BorderLayout(10, 0));
        ctl.setOpaque(false);
        JPanel ctlLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        ctlLeft.setOpaque(false);
        ctlLeft.add(prevBtn);
        ctlLeft.add(playBtn);
        ctlLeft.add(nextBtn);
        ctl.add(ctlLeft, BorderLayout.CENTER);
        ctl.add(modeBox, BorderLayout.EAST);

        JPanel mid = new JPanel(new BorderLayout(0, 10));
        mid.setOpaque(false);
        mid.setBorder(new EmptyBorder(4, 16, 0, 0));
        mid.add(head, BorderLayout.NORTH);
        mid.add(lrc, BorderLayout.CENTER);

        JPanel prog = new JPanel(new BorderLayout(0, 4));
        prog.setOpaque(false);
        prog.add(bar, BorderLayout.NORTH);
        prog.add(timeText, BorderLayout.SOUTH);

        JPanel rightBottom = new JPanel(new BorderLayout(0, 10));
        rightBottom.setOpaque(false);
        rightBottom.add(prog, BorderLayout.NORTH);
        rightBottom.add(ctl, BorderLayout.CENTER);
        rightBottom.add(tools, BorderLayout.SOUTH);

        JPanel right = new JPanel(new BorderLayout(0, 10));
        right.setOpaque(false);
        right.add(mid, BorderLayout.CENTER);
        right.add(rightBottom, BorderLayout.SOUTH);

        JPanel stage = new JPanel(new BorderLayout(16, 0));
        stage.setOpaque(false);
        stage.add(left, BorderLayout.WEST);
        stage.add(right, BorderLayout.CENTER);

        // ---- 底：播放列表（高度固定，可滚）----
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
        JScrollPane qsp = new JScrollPane(qList);
        qsp.setPreferredSize(new Dimension(0, 132));
        qsp.setBorder(BorderFactory.createLineBorder(Theme.LINE, 1, true));
        qsp.getVerticalScrollBar().setUnitIncrement(18);

        root.add(stage, BorderLayout.CENTER);
        root.add(qsp, BorderLayout.SOUTH);
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
        installShortcuts();

        // 歌词位移：每次 ±0.5 秒，浮窗与窗口内歌词读的是同一份偏移
        earlier.addActionListener(e -> bumpOffset(-500));
        later.addActionListener(e -> bumpOffset(+500));
        resetOff.addActionListener(e -> {
            LrcView.setOffsetMs(0);
            lrc.refresh();
            lyrics.refresh();
            offsetText.setText(LrcView.offsetLabel());
        });
        lrc.setOnOffsetChanged(() -> offsetText.setText(LrcView.offsetLabel()));
        offsetText.setText(LrcView.offsetLabel());

        ticker = new Timer(200, e -> onTick());
        ticker.setRepeats(true);
    }

    /**
     * 键盘快捷键：空格播放/暂停，左右方向上/下一曲，媒体键同理。
     * 用 WHEN_IN_FOCUSED_WINDOW 绑在 root pane 上，焦点落在歌词表或按钮上也能生效。
     */
    private void installShortcuts() {
        JRootPane rp = getRootPane();
        bind(rp, KeyStroke.getKeyStroke("SPACE"), playBtn);
        bind(rp, KeyStroke.getKeyStroke("LEFT"), prevBtn);
        bind(rp, KeyStroke.getKeyStroke("RIGHT"), nextBtn);
        // JDK 17 的 java.awt.event.KeyEvent 没有 VK_MEDIA_* 常量，
        // 桌面端就靠空格 + 左右键，和网页/手机端的核心手势保持一致。
    }

    private void bind(JRootPane rp, KeyStroke ks, JButton btn) {
        final String key = "shortcut-" + ks.toString();
        rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ks, key);
        rp.getActionMap().put(key, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                btn.doClick();
            }
        });
    }

    /** 歌词位移：正数 = 歌词往后推（字幕变慢），负数 = 提前。 */
    private void bumpOffset(int deltaMs) {
        offsetText.setText(lrc.nudge(deltaMs));
        // 悬浮条走的是同一份静态偏移，但它的 cur 缓存要清一下才会立刻重画
        lyrics.refresh();
    }

    /**
     * 播放/暂停切换时只换字，不换 UI —— 按钮圆角底色由 FlatButtonUI 自绘。
     * 顺带把唱片的转动挂到同一个状态上：文字是「暂停」就说明正在放。
     */
    private void setPlayLabel(String text) {
        playBtn.setText(text);
        disc.setSpinning("暂停".equals(text));
    }

    /** 给自动化探针用：从当前选中项开始播 */
    void playFirst() {
        playCurrent();
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
        disc.setTip(nm);
        disc.setProgress(0, 0);
        loadCover(t);

        final String base = baseUrl;
        final String ck = cookie;
        if (base.isEmpty()) {
            sub.setText("没有连接服务器");
            return;
        }

        String audio = Music.audioUrl(base, t.path);

        // 一档：libvlc —— 唯一能让 mp3/flac/m4a/ape 在这个窗口里出声的路径
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
            // play() 返回 false 只说明这次没起来（多半是流连不上，或者服务端
            // 那边还没把这首歌扫出来）。以前这里会掉到「打开浏览器」，用户看到
            // 的就是「点一首歌就跳出浏览器」—— 现在改成留在窗口里说清楚。
            sub.setText("这首歌没打开（网络或服务端未就绪），点播放再试一次");
            loadLyrics(base, ck, t);
            return;
        }

        // 二档：Java Sound（只有 WAV）—— 本机没有 libvlc 时唯一的兜底，
        // 而且必须留在窗口里播：跳浏览器就没有曲目列表、没有进度、没有歌词。
        if (!isWav(t.name)) {
            sub.setText("本机没有可用的 VLC 运行时（vlc 目录要在程序目录下），"
                    + "内置解码只认 WAV，这首放不了");
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
                            sub.setText("声卡不支持这个格式（本机只在有 libvlc 时才能播非 WAV）"));
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
                    sub.setText("内置播放失败：" + ex.getMessage());
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

    /**
     * 拉歌词：一份正文喂两个渲染器（窗口内大字 + 桌面浮窗），
     * 两边的偏移量读的是同一份 {@link LrcView#offsetMs()}。
     */
    private void loadLyrics(String base, String ck, Track t) {
        if (!t.hasLyrics()) {
            SwingUtilities.invokeLater(() -> {
                lrc.clear();
                lyrics.clear();
            });
            return;
        }
        Thread th = new Thread(() -> {
            String raw = Music.fetchLyrics(base, ck, t.path, 8000);
            SwingUtilities.invokeLater(() -> {
                lrc.setLrc(raw);
                lyrics.setLrc(raw);
            });
        }, "usbnas-lrc");
        th.setDaemon(true);
        th.start();
    }

    /** 拉封面：内嵌封面走 /cover?f=，同目录同名图走普通 GET，都没有就保持默认贴纸 */
    private void loadCover(Track t) {
        disc.setCover(null);
        if (t == null || !t.hasCover()) return;
        final String base = baseUrl;
        final String ck = cookie;
        final String url = t.coverUrl(base);
        if (url == null || url.isEmpty()) return;
        Thread th = new Thread(() -> {
            BufferedImage img = DiscPanel.download(url, ck);
            if (img != null) SwingUtilities.invokeLater(() -> disc.setCover(img));
        }, "usbnas-cover");
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
        lrc.tick(p);
        lyrics.tick(p);
        disc.setProgress(p, total);
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