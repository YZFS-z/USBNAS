package com.littlesauce.usbnaslink.desktop;

import com.littlesauce.usbnaslink.Book;
import com.littlesauce.usbnaslink.BookLib;
import com.littlesauce.usbnaslink.Music;
import com.littlesauce.usbnaslink.Photo;
import com.littlesauce.usbnaslink.PhotoLib;
import com.littlesauce.usbnaslink.Session;
import com.littlesauce.usbnaslink.Track;
import com.littlesauce.usbnaslink.Video;
import com.littlesauce.usbnaslink.VideoLib;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 音乐 / 影视库面板。
 *
 * 设计取舍：
 * - 影视按「目录一块」纵向排（对应服务端预设的多个影视目录），每块里是海报卡片流；
 *   海报尺寸差异大，统一裁成 2:3 再画，列表才不会一列高一列低。
 * - 刮削信息不预取：上千条 nfo 一次拉会让面板打开就转好几秒，
 *   这里按卡片懒加载，取回来缓存在内存里（video → meta、url → 图片各一份）。
 * - 播放交给桌面默认播放器（Desktop.open），不自造解码器。
 */
final class LibraryPanel extends JPanel {

    /** 面板把点击事件抛给外部：播放/打开网页由主窗口决定 */
    interface Handler {
        void onPlayVideo(Video v, Video.Meta meta);

        void onPlayMusic(List<Track> album, Track t);

        void onOpenBook(Book b);

        void onOpenPage(String url);
    }

    private final Handler h;
    private final JPanel content = new JPanel();
    private final JPanel musicContent = new JPanel();
    private final JPanel bookContent = new JPanel();
    private final JPanel photoContent = new JPanel();
    private final JLabel hint = new JLabel(" ");
    private final JTextField filter = new JTextField();
    /**
     * 走 Theme.tabs() 而不是裸 new JTabbedPane()：裸构造会落到系统 LAF 的
     * MetalTabbedPaneUI，页签是圆角亮块 + 深灰字，跟窗口其它自绘控件两个世界。
     */
    private final JTabbedPane tabs = Theme.tabs();
    private final JPanel videoHost = new JPanel(new BorderLayout());
    private final JPanel musicHost = new JPanel(new BorderLayout());
    private final JPanel bookHost = new JPanel(new BorderLayout());
    private final JPanel photoHost = new JPanel(new BorderLayout());

    private volatile String baseUrl = "";
    private volatile String cookie = null;

    private volatile VideoLib.Lib vlib;
    private volatile Music.Lib mlib;
    private volatile BookLib.Lib blib;
    private volatile PhotoLib.Lib plib;
    /** 音乐页签内部的三种看法：0=平铺，1=按歌手，2=按专辑 */
    private int musicView = 0;
    /** 已展开的歌手/专辑桶名；换页签或换服务器时清空 */
    private final Set<String> openedBuckets = new HashSet<>();
    private final Map<String, Video.Meta> metaCache = new HashMap<>();
    private final Map<String, ImageIcon> posterCache = new HashMap<>();
    /** 相册缩略图缓存，键是 "t:" + 路径。原图不进这里（一张 10MB 会把堆吃穿）。 */
    private final Map<String, ImageIcon> photoCache = new HashMap<>();

    private javax.swing.Timer rebuildTimer;

    LibraryPanel(Handler h) {
        this.h = h;
        setLayout(new BorderLayout(0, 6));
        setBorder(new EmptyBorder(8, 10, 8, 10));

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        JLabel t = new JLabel("媒体库");
        t.setFont(Theme.bold(15));
        t.setForeground(Theme.FG_TITLE);
        top.add(t, BorderLayout.WEST);

        filter.setToolTipText("按片名 / 歌手 / 目录过滤");
        Theme.styleInput(filter);
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                rebuildSoon();
            }

            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                rebuildSoon();
            }

            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                rebuildSoon();
            }
        });
        JPanel fr = new JPanel(new BorderLayout());
        fr.add(filter, BorderLayout.CENTER);
        fr.setPreferredSize(new Dimension(220, 26));
        top.add(fr, BorderLayout.EAST);

        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Theme.BG_DEEP);
        JScrollPane sp = new JScrollPane(content);
        sp.getVerticalScrollBar().setUnitIncrement(24);
        sp.setBorder(BorderFactory.createEmptyBorder());
        videoHost.add(sp, BorderLayout.CENTER);

        musicContent.setLayout(new BoxLayout(musicContent, BoxLayout.Y_AXIS));
        musicContent.setBackground(Theme.BG_DEEP);
        JScrollPane spm = new JScrollPane(musicContent);
        spm.getVerticalScrollBar().setUnitIncrement(24);
        spm.setBorder(BorderFactory.createEmptyBorder());
        // 工具栏固定在滚动区上方：曲库上千首时，拉到底还能一键切回「按歌手」
        musicHost.add(musicBar(), BorderLayout.NORTH);
        musicHost.add(spm, BorderLayout.CENTER);

        bookContent.setLayout(new BoxLayout(bookContent, BoxLayout.Y_AXIS));
        bookContent.setBackground(Theme.BG_DEEP);
        JScrollPane spb = new JScrollPane(bookContent);
        spb.getVerticalScrollBar().setUnitIncrement(24);
        spb.setBorder(BorderFactory.createEmptyBorder());
        bookHost.add(spb, BorderLayout.CENTER);

        photoContent.setLayout(new BoxLayout(photoContent, BoxLayout.Y_AXIS));
        photoContent.setBackground(Theme.BG_DEEP);
        JScrollPane spp = new JScrollPane(photoContent);
        spp.getVerticalScrollBar().setUnitIncrement(24);
        spp.setBorder(BorderFactory.createEmptyBorder());
        photoHost.add(spp, BorderLayout.CENTER);

        tabs.addTab("影视", videoHost);
        tabs.addTab("音乐", musicHost);
        tabs.addTab("图书", bookHost);
        tabs.addTab("相册", photoHost);
        tabs.addChangeListener(e -> rebuild());

        hint.setForeground(Theme.FG_DIM);
        hint.setFont(Theme.ui(11));
        hint.setBorder(new EmptyBorder(2, 2, 0, 2));

        add(top, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        add(hint, BorderLayout.SOUTH);

        content.add(msg("选中左侧服务器后，这里会显示它的影视、音乐、图书与相册。"));
    }

    /**
     * 音乐页签的视图切换栏（平铺 / 按歌手 / 按专辑）+ 一个「全部收起」。
     *
     * 分类只在客户端做，不新增服务端端点：曲库清单本来就一次全给，
     * 用 Music.group() 在本地分组，切视图零延迟，也不用服务端跟着改。
     */
    private JComponent musicBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.setOpaque(true);
        bar.setBackground(Theme.BG_DEEP);
        bar.setBorder(new EmptyBorder(2, 6, 2, 6));

        String[] names = {"平铺", "按歌手", "按专辑"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            // 三档都用 ghost：只用字重和颜色表示选中，比三块实心蓝按钮安静得多
            final JButton b = Theme.ghost(names[i]);
            b.setFont(Theme.ui(12));
            b.addActionListener(e -> {
                musicView = idx;
                openedBuckets.clear();
                rebuild();
            });
            bar.add(b);
        }
        JButton fold = Theme.ghost("全部收起");
            fold.setFont(Theme.ui(12));
        fold.setToolTipText("把展开的歌手/专辑都折回去，回到只剩名字的一屏");
        fold.addActionListener(e -> {
            openedBuckets.clear();
            rebuild();
        });
        bar.add(fold);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        bar.setAlignmentX(LEFT_ALIGNMENT);
        return bar;
    }

    /** 视图按钮要能看出当前是哪一档：重建时把选中项换成实心按钮 */
    private void paintMusicBar() {
        for (Component c : ((JPanel) musicHost.getComponent(0)).getComponents()) {
            if (!(c instanceof JButton)) continue;
            JButton b = (JButton) c;
            String t = b.getText();
            int idx = "平铺".equals(t) ? 0 : "按歌手".equals(t) ? 1 : "按专辑".equals(t) ? 2 : -1;
            if (idx < 0) continue;   // 「全部收起」不是档位
            boolean on = idx == musicView;
            b.setFont(on ? Theme.bold(12) : Theme.ui(12));
            b.setForeground(on ? Color.WHITE : Theme.FG_SUB);
        }
    }

    /** 切换目标服务器：地址或会话变了才重新拉 */
    void setTarget(String base, String sid) {
        boolean same = base != null && base.equals(this.baseUrl) && eq(sid, this.cookie);
        this.baseUrl = base == null ? "" : base;
        this.cookie = sid;
        if (same && (vlib != null || mlib != null || blib != null || plib != null)) return;
        metaCache.clear();
        posterCache.clear();
        vlib = null;
        mlib = null;
        blib = null;
        plib = null;
        rebuild();
        reload();
    }

    void reload() {
        final String base = baseUrl;
        final String ck = cookie;
        if (base.isEmpty()) {
            hint.setText("先选中一台服务器");
            return;
        }
        hint.setText("正在拉取媒体库…");
        Thread t = new Thread(() -> {
            VideoLib.Lib vl = VideoLib.fetch(base, ck, null, 30000);
            Music.Lib ml = Music.fetch(base, ck, 30000);
            BookLib.Lib bl = BookLib.fetch(base, ck, null, 30000);
            PhotoLib.Lib pl = PhotoLib.fetch(base, ck, null, 30000);
            SwingUtilities.invokeLater(() -> {
                vlib = vl;
                mlib = ml;
                blib = bl;
                plib = pl;
                rebuild();
                String e1 = vl.ok ? "" : ("影视库 " + vl.error);
                String e2 = ml.ok ? "" : ("曲库 " + ml.error);
                String e3 = bl.ok ? "" : ("书库 " + bl.error);
                String e4 = pl.ok ? "" : ("相册 " + pl.error);
                String err = (e1 + "  " + e2 + "  " + e3 + "  " + e4).trim();
                hint.setText(err.isEmpty()
                        ? "影视 " + vl.videos.size() + " 部 · 音乐 " + ml.tracks.size()
                          + " 首 · 图书 " + bl.books.size() + " 本 · 相册 " + pl.photos.size() + " 张"
                        : err);
            });
        }, "usbnas-lib");
        t.setDaemon(true);
        t.start();
    }

    private void rebuildSoon() {
        if (rebuildTimer != null) rebuildTimer.stop();
        rebuildTimer = new javax.swing.Timer(220, e -> rebuild());
        rebuildTimer.setRepeats(false);
        rebuildTimer.start();
    }

    private String q() {
        return filter.getText() == null ? "" : filter.getText().trim();
    }

    private void rebuild() {
        content.removeAll();
        int idx = tabs.getSelectedIndex();
        // 四个页签各用一份独立宿主面板，切页时只搬内容，不重建滚动条位置
        if (idx == 1) {
            musicContent.removeAll();
            buildMusic();
            musicContent.revalidate();
            musicContent.repaint();
        } else if (idx == 2) {
            bookContent.removeAll();
            buildBooks();
            bookContent.revalidate();
            bookContent.repaint();
        } else if (idx == 3) {
            photoContent.removeAll();
            buildPhotos();
            photoContent.revalidate();
            photoContent.repaint();
        } else {
            buildVideos();
            content.revalidate();
            content.repaint();
        }
    }

    // ------------------------------------------------------------------ 影视

    private void buildVideos() {
        if (vlib == null) {
            content.add(msg("正在拉取影视库…"));
            return;
        }
        if (!vlib.ok) {
            content.add(msg("影视库拉取失败：" + vlib.error));
            return;
        }
        if (vlib.videos.isEmpty()) {
            content.add(msg("服务端还没设影视目录。用管理员账号在 /_admin 里设一个即可。"));
            return;
        }
        String k = q();

        // 按预设根分块：这就是「电影板块可以同时有盘0、盘1」的落地
        List<VideoLib.Group> blocks = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        for (VideoLib.Group g : vlib.roots) {
            List<Video> in = new ArrayList<>();
            for (Video v : vlib.videos) {
                if (!taken.contains(v.path) && under(v.path, g.path)) in.add(v);
            }
            if (in.isEmpty()) continue;
            blocks.add(g);
            for (Video v : in) taken.add(v.path);
        }
        List<Video> rest = new ArrayList<>();
        for (Video v : vlib.videos) if (!taken.contains(v.path)) rest.add(v);
        if (!rest.isEmpty()) {
            VideoLib.Group g = new VideoLib.Group();
            g.path = "";
            g.label = "其他";
            g.count = rest.size();
            blocks.add(g);
        }

        int shown = 0;
        for (VideoLib.Group g : blocks) {
            boolean isRest = g.path == null || g.path.isEmpty();
            List<Video> in = new ArrayList<>();
            for (Video v : vlib.videos) {
                boolean match = isRest ? rest.contains(v) : under(v.path, g.path);
                if (!match) continue;
                if (!k.isEmpty() && !v.matches(k)) continue;
                in.add(v);
            }
            if (in.isEmpty()) continue;
            shown += in.size();

            JPanel head = new JPanel(new BorderLayout());
            head.setOpaque(true);
            head.setBackground(Theme.BG_HOVER);
            head.setBorder(new EmptyBorder(9, 12, 9, 12));
            JLabel hl = new JLabel(g.label);
        hl.setFont(Theme.bold(13));
            hl.setForeground(Theme.FG_TITLE);
            head.add(hl, BorderLayout.WEST);
            JLabel hr = new JLabel(in.size() + " 部   " + g.path);
        hr.setFont(Theme.ui(11));
            hr.setForeground(Theme.FG_MUTE);
            head.add(hr, BorderLayout.EAST);
            head.setAlignmentX(LEFT_ALIGNMENT);
            head.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            content.add(head);
            content.add(cards(in));
            content.add(Box.createVerticalStrut(10));
        }
        if (shown == 0) {
            content.add(msg(k.isEmpty() ? "库里没有视频" : "没有匹配「" + k + "」的条目"));
        }
    }

    private static boolean under(String path, String root) {
        if (root == null || root.isEmpty()) return false;
        return path.equals(root) || path.startsWith(root.endsWith("/") ? root : root + "/");
    }

    private JComponent cards(List<Video> in) {
        JPanel wrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        wrap.setOpaque(false);
        wrap.setAlignmentX(LEFT_ALIGNMENT);
        for (Video v : in) wrap.add(new VideoCard(v));
        JPanel outer = new JPanel(new BorderLayout());
        outer.setOpaque(false);
        outer.setAlignmentX(LEFT_ALIGNMENT);
        outer.add(wrap, BorderLayout.WEST);
        return outer;
    }

    /** 单张海报卡片 */
    private final class VideoCard extends JPanel {
        private final Video v;
        private final JLabel img = new JLabel("", SwingConstants.CENTER);

        VideoCard(Video v) {
            this.v = v;
            setLayout(new BorderLayout(0, 3));
            setOpaque(true);
            setBackground(Theme.BG_CARD);
            setBorder(BorderFactory.createLineBorder(Theme.LINE));
            setPreferredSize(new Dimension(128, 220));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            img.setPreferredSize(new Dimension(126, 168));
            img.setOpaque(true);
            img.setBackground(Theme.BG_DEEP);
            img.setForeground(Theme.FG_MUTE);
            img.setText("…");
            add(img, BorderLayout.CENTER);

            String title = v.title == null || v.title.isEmpty() ? trimExt(v.name) : v.title;
            JLabel nm = new JLabel(shorten(title, 13));
        nm.setFont(Theme.bold(11));
            nm.setForeground(Theme.FG_TEXT);
            nm.setBorder(new EmptyBorder(0, 5, 0, 5));
            nm.setToolTipText(v.path);

            String tg = v.isTv() ? ("TV " + v.tag()) : trimExt(v.name);
            JLabel tag = new JLabel(shorten(tg, 16));
        tag.setFont(Theme.ui(10));
            tag.setForeground(Theme.FG_MUTE);
            tag.setBorder(new EmptyBorder(0, 5, 3, 5));

            JPanel bottom = new JPanel();
            bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
            bottom.setOpaque(false);
            nm.setAlignmentX(LEFT_ALIGNMENT);
            tag.setAlignmentX(LEFT_ALIGNMENT);
            bottom.add(nm);
            bottom.add(tag);
            add(bottom, BorderLayout.SOUTH);

            MouseAdapter ma = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    openDetail();
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    setBackground(Theme.BG_SEL);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    setBackground(Theme.BG_CARD);
                }
            };
            addMouseListener(ma);
            for (Component c : getComponents()) c.addMouseListener(ma);

            loadPoster();
        }

        private void loadPoster() {
            final String base = baseUrl;
            final String ck = cookie;
            if (base.isEmpty()) return;
            Thread t = new Thread(() -> {
                Video.Meta m = metaCache.get(v.path);
                if (m == null) {
                    m = VideoLib.fetchMeta(base, ck, v.path, 8000);
                    if (m.ok) metaCache.put(v.path, m);
                }
                ImageIcon ic = null;
                if (m.ok) {
                    String url = VideoLib.artUrl(base, m.posterUrl);
                    if (url != null) {
                        synchronized (posterCache) {
                            ic = posterCache.get(url);
                        }
                        if (ic == null) {
                            ic = fetchImage(url, ck);
                            if (ic != null) {
                                synchronized (posterCache) {
                                    posterCache.put(url, ic);
                                }
                            }
                        }
                    }
                }
                final ImageIcon f = ic;
                SwingUtilities.invokeLater(() -> {
                    if (f != null) {
                        img.setIcon(f);
                        img.setText("");
                    } else {
                        img.setText("▦");
                    }
                });
            }, "usbnas-poster");
            t.setDaemon(true);
            t.start();
        }

        private void openDetail() {
            Video.Meta m = metaCache.get(v.path);
            if (m != null) {
                showDetail(v, m);
                return;
            }
            hint.setText("正在读取刮削信息…");
            final String base = baseUrl;
            final String ck = cookie;
            Thread t = new Thread(() -> {
                Video.Meta got = VideoLib.fetchMeta(base, ck, v.path, 10000);
                if (got.ok) metaCache.put(v.path, got);
                SwingUtilities.invokeLater(() -> {
                    hint.setText(" ");
                    showDetail(v, got);
                });
            }, "usbnas-meta");
            t.setDaemon(true);
            t.start();
        }
    }

    /** 详情窗：海报 + 刮削信息 + 播放 */
    private void showDetail(Video v, Video.Meta m) {
        JPanel box = new JPanel(new BorderLayout(14, 0));
        box.setBackground(Theme.BG_DEEP);
        box.setBorder(new EmptyBorder(14, 14, 14, 14));

        JLabel art = new JLabel();
        art.setPreferredSize(new Dimension(200, 300));
        art.setOpaque(true);
        art.setBackground(Theme.BG_DEEP);
        art.setHorizontalAlignment(SwingConstants.CENTER);
        art.setForeground(Theme.FG_MUTE);
        String purl = m.ok ? VideoLib.artUrl(baseUrl, m.posterUrl) : null;
        if (purl != null) {
            ImageIcon raw;
            synchronized (posterCache) {
                raw = posterCache.get(purl);
            }
            if (raw == null) raw = fetchImage(purl, cookie);
            if (raw != null) art.setIcon(raw);
        }
        if (art.getIcon() == null) art.setText("无海报");
        box.add(art, BorderLayout.WEST);

        StringBuilder sb = new StringBuilder();
        sb.append("<html><body style='width:420px;color:").append(Theme.hex(Theme.FG_SUB))
          .append(";font-family:").append(Theme.FONT_STACK).append("'>");
        String tt = m.title == null || m.title.isEmpty() ? trimExt(v.name) : m.title;
        sb.append("<div style='font-size:17px;font-weight:bold;color:")
          .append(Theme.hex(Theme.FG_TITLE)).append("'>")
          .append(escapeInline(tt)).append("</div>");
        if (!m.originalTitle.isEmpty() && !m.originalTitle.equals(tt)) {
            sb.append("<div style='font-size:12px;color:")
              .append(Theme.hex(Theme.FG_DIM)).append(";margin-top:2px'>")
              .append(escapeInline(m.originalTitle)).append("</div>");
        }
        String line = m.metaLine();
        if (!line.isEmpty()) {
            sb.append("<div style='font-size:12px;color:" + Theme.hex(Theme.BRAND_LINK) + ";margin-top:6px'>")
              .append(escapeInline(line)).append("</div>");
        }
        if (!m.director.isEmpty() || !m.actors.isEmpty()) {
            sb.append("<div style='font-size:12px;color:" + Theme.hex(Theme.FG_SUB) + ";margin-top:8px'>");
            if (!m.director.isEmpty()) {
                sb.append("导演：").append(escapeInline(m.director)).append("<br>");
            }
            if (!m.actors.isEmpty()) {
                sb.append("演员：").append(escapeInline(joinList(m.actors, 5)));
            }
            sb.append("</div>");
        }
        if (!m.plot.isEmpty()) {
            String plot = m.plot.length() > 240 ? m.plot.substring(0, 240) + "…" : m.plot;
            sb.append("<div style='font-size:12px;color:" + Theme.hex(Theme.FG_TEXT) + ";margin-top:10px;line-height:1.6'>")
              .append(escapeInline(plot)).append("</div>");
        }
        sb.append("<div style='font-size:11px;color:" + Theme.hex(Theme.FG_MUTE) + ";margin-top:10px'>")
          .append("文件：").append(escapeInline(v.name)).append("<br>")
          .append("目录：").append(escapeInline(v.dir)).append("<br>")
          .append("大小：").append(Music.humanSize(v.size));
        if (!m.nfoFile.isEmpty()) sb.append("<br>刮削：").append(escapeInline(m.nfoFile));
        if (!m.ok && !m.error.isEmpty()) {
            sb.append("<br><span style='color:" + Theme.hex(Theme.WARN) + "'>").append(escapeInline(m.error)).append("</span>");
        }
        sb.append("</div></body></html>");

        JLabel info = new JLabel(sb.toString());
        info.setVerticalAlignment(SwingConstants.TOP);
        JScrollPane isp = new JScrollPane(info);
        isp.setBorder(BorderFactory.createEmptyBorder());
        isp.getViewport().setBackground(Theme.BG_DEEP);
        box.add(isp, BorderLayout.CENTER);

        Window owner = SwingUtilities.getWindowAncestor(this);
        Integer r = Theme.options(owner, "影片信息", null, box,
                new String[]{"播放", "在网页打开", "关闭"});
        if (r != null && r == 0) {
            h.onPlayVideo(v, m);
        } else if (r != null && r == 1) {
            h.onOpenPage(baseUrl + "/play/video?f=" + pct(v.path));
        }
    }

    // ------------------------------------------------------------------ 音乐

    private void buildMusic() {
        if (mlib == null) {
            musicContent.add(msg("正在拉取曲库…"));
            return;
        }
        if (!mlib.ok) {
            musicContent.add(msg("曲库拉取失败：" + mlib.error));
            return;
        }
        if (mlib.tracks.isEmpty()) {
            musicContent.add(msg("服务端还没设音乐目录。用管理员账号在 /_admin 里设一个即可。"));
            return;
        }
        paintMusicBar();
        String k = q();
        List<Track> hits = Music.filter(mlib.tracks, k);
        if (hits.isEmpty()) {
            musicContent.add(msg("没有匹配「" + k + "」的曲目"));
            return;
        }
        if (musicView == 0) {
            buildMusicFlat(hits);
        } else {
            buildMusicGrouped(hits, musicView == 1 ? Music.GroupBy.ARTIST : Music.GroupBy.ALBUM);
        }
    }

    /** 平铺：按目录分块，就是原来的样子（曲库只有一层目录时它其实等于按专辑） */
    private void buildMusicFlat(List<Track> hits) {
        Map<String, List<Track>> byDir = new LinkedHashMap<>();
        for (Track t : hits) {
            List<Track> l = byDir.get(t.dir);
            if (l == null) {
                l = new ArrayList<>();
                byDir.put(t.dir, l);
            }
            l.add(t);
        }
        for (Map.Entry<String, List<Track>> en : byDir.entrySet()) {
            musicContent.add(bucketHead(folderName(en.getKey()), en.getValue().size() + " 首", null));
            final List<Track> album = en.getValue();
            for (final Track t : album) {
                musicContent.add(trackRow(t, false, () -> h.onPlayMusic(album, t)));
                musicContent.add(Box.createVerticalStrut(1));
            }
            musicContent.add(Box.createVerticalStrut(8));
        }
    }

    /**
     * 按歌手 / 按专辑分组。
     *
     * 上千首歌铺成一条列表根本翻不动，所以这里做「折叠桶」：
     * 每个桶一行（名字 + 几首 + 总大小），点开才列出曲目。
     * 默认只展开第一桶，免得一次性塞进几千个 JLabel 把窗口卡住。
     */
    private void buildMusicGrouped(List<Track> hits, Music.GroupBy by) {
        List<Music.Bucket> buckets = Music.group(hits, by);
        boolean expandedAny = false;
        for (Music.Bucket b : buckets) {
            final String key = by.name() + ":" + b.name;
            final boolean opened = openedBuckets.contains(key);
            if (!opened && !expandedAny) {
                openedBuckets.add(key);
                expandedAny = true;
            }
            final boolean isOpen = openedBuckets.contains(key);

            JPanel head = bucketHead(b.name, b.summary(), isOpen ? "▾" : "▸");
            head.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            head.setToolTipText(isOpen ? "点击收起" : "点击展开 " + b.size() + " 首");
            MouseAdapter ma = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (!openedBuckets.remove(key)) openedBuckets.add(key);
                    rebuild();
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    head.setBackground(Theme.BG_SEL);
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    head.setBackground(Theme.BG_HOVER);
                }
            };
            head.addMouseListener(ma);
            for (Component c : head.getComponents()) c.addMouseListener(ma);
            musicContent.add(head);

            if (isOpen) {
                final List<Track> album = b.tracks;
                for (final Track t : album) {
                    String sub = by == Music.GroupBy.ARTIST ? t.album() : t.artist();
                    musicContent.add(trackRow(t, true, () -> h.onPlayMusic(album, t)));
                    if (sub != null && !sub.isEmpty()) {
                        // 副标题用 tooltip 就够，行高固定 30px，塞第二行会挤掉曲名
                        Component last = musicContent.getComponent(musicContent.getComponentCount() - 1);
                        if (last instanceof JComponent) ((JComponent) last).setToolTipText(t.path + "    ·    " + sub);
                    }
                    musicContent.add(Box.createVerticalStrut(1));
                }
            }
            musicContent.add(Box.createVerticalStrut(6));
        }
        musicContent.add(msg("共 " + buckets.size() + " 个「" + by.label + "」，点名字展开"));
    }

    /** 分块标题条：左侧名字、右侧统计，可选一个展开箭头 */
    private JPanel bucketHead(String name, String right, String arrow) {
        JPanel head = new JPanel(new BorderLayout(8, 0));
        head.setOpaque(true);
        head.setBackground(Theme.BG_HOVER);
        head.setBorder(new EmptyBorder(8, 12, 8, 12));
        JLabel hl = new JLabel((arrow == null ? "" : arrow + "  ") + name);
        hl.setFont(Theme.bold(13));
        hl.setForeground(arrow == null ? Theme.FG_TITLE : Theme.BRAND_LINK);
        head.add(hl, BorderLayout.WEST);
        JLabel hr = new JLabel(right == null ? "" : right);
        hr.setFont(Theme.ui(11));
        hr.setForeground(Theme.FG_MUTE);
        head.add(hr, BorderLayout.EAST);
        head.setAlignmentX(LEFT_ALIGNMENT);
        head.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        return head;
    }

    // ------------------------------------------------------------------ 排障钩子
    //
    // Swing 的状态全在 EDT 上，脚本没法截图也没法断言，所以留两个只读入口：
    // 一个直接点「按歌手/按专辑」按钮，一个把音乐页的标签文字吐出来。
    // 包内可见，不影响公开 API。

    JComponent debugMusicContent() {
        return musicContent;
    }

    /** 排障：直接按页签索引重建一次（0 影视 / 1 音乐 / 2 图书） */
    void debugSelectTab(int idx) {
        tabs.setSelectedIndex(idx);
    }

    /** 排障：当前曲库拉到了几首（null 表示还没拉到） */
    int debugMusicCount() {
        Music.Lib m = mlib;
        return m == null ? -1 : m.tracks.size();
    }

    /** 排障：当前页签与视图档位 */
    String debugState() {
        return "tab=" + tabs.getSelectedIndex() + " view=" + musicView + " buckets=" + openedBuckets.size();
    }

    /** 按下音乐工具栏的第 idx 个视图按钮（0 平铺 / 1 按歌手 / 2 按专辑） */
    void debugClickBar(int idx) {
        Component bar = musicHost.getComponent(0);
        if (!(bar instanceof JPanel)) return;
        List<JButton> btns = new ArrayList<>();
        for (Component c : ((JPanel) bar).getComponents()) {
            if (c instanceof JButton) btns.add((JButton) c);
        }
        if (idx >= 0 && idx < btns.size()) btns.get(idx).doClick();
    }

    /** 一首歌的一行；indent=true 时左侧多缩进，表示它挂在某个歌手/专辑下面 */
    private JPanel trackRow(final Track t, boolean indent, final Runnable onPlay) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(true);
        row.setBackground(Theme.BG_CARD);
        row.setBorder(new EmptyBorder(6, indent ? 26 : 14, 6, 12));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        row.setAlignmentX(LEFT_ALIGNMENT);

        JLabel n = new JLabel("♪  " + t.displayTitle());
        n.setFont(Theme.ui(12));
        n.setForeground(Theme.FG_TEXT);
        n.setToolTipText(t.path);
        row.add(n, BorderLayout.CENTER);

        // 右侧副标题：平铺/按歌手看时给专辑名（找歌时它就是那张唱片的身份），
        // 按专辑看时专辑名已经在上面的分组标题里了，只留大小
        String sub = Music.humanSize(t.size) + (t.hasLyrics() ? "   有歌词" : "");
        String al = t.guessAlbum();
        if (musicView != 2 && !al.isEmpty() && !al.equals(t.displayTitle())) {
            sub = shorten(al, 18) + "    " + sub;
        }
        JLabel sz = new JLabel(sub);
        sz.setFont(Theme.ui(10));
        sz.setForeground(Theme.FG_MUTE);
        row.add(sz, BorderLayout.EAST);

        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                onPlay.run();
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                row.setBackground(Theme.BG_SEL);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                row.setBackground(Theme.BG_CARD);
            }
        };
        row.addMouseListener(ma);
        n.addMouseListener(ma);
        sz.addMouseListener(ma);
        return row;
    }

    // ------------------------------------------------------------------ 图书

    /**
     * 书库：按目录分层架，每条一行，双击/单击都能打开。
     *
     * 为什么不画封面网格：书不像电影有固定的 2:3 海报，多数 ePub 的封面藏在
     * 包里（要解压才拿到），服务端也不给。硬画一排占位方块只会让列表变慢还不好看，
     * 所以这里老老实实做「书名 + 格式 + 大小」的列表 —— 翻书要的是找得到，不是好看。
     */
    private void buildBooks() {
        if (blib == null) {
            bookContent.add(msg("正在拉取书库…"));
            return;
        }
        if (!blib.ok) {
            bookContent.add(msg("书库拉取失败：" + blib.error));
            return;
        }
        if (blib.notConfigured()) {
            bookContent.add(msg("服务端还没设图书目录。用管理员账号在网页端 /_admin 或桌面端「管理设置」里设一个即可。"));
            return;
        }
        String k = q();
        Map<String, List<Book>> byDir = new LinkedHashMap<>();
        for (Book b : blib.books) {
            if (!k.isEmpty() && !b.matches(k)) continue;
            List<Book> l = byDir.get(b.dir);
            if (l == null) {
                l = new ArrayList<>();
                byDir.put(b.dir, l);
            }
            l.add(b);
        }
        if (byDir.isEmpty()) {
            bookContent.add(msg(k.isEmpty() ? "书库里还没有书" : "没有匹配「" + k + "」的书"));
            return;
        }
        for (Map.Entry<String, List<Book>> en : byDir.entrySet()) {
            JPanel head = new JPanel(new BorderLayout());
            head.setOpaque(true);
            head.setBackground(Theme.BG_HOVER);
            head.setBorder(new EmptyBorder(8, 12, 8, 12));
            JLabel hl = new JLabel(folderName(en.getKey()));
        hl.setFont(Theme.bold(13));
            hl.setForeground(Theme.FG_TITLE);
            head.add(hl, BorderLayout.WEST);
            JLabel hr = new JLabel(en.getValue().size() + " 本");
        hr.setFont(Theme.ui(11));
            hr.setForeground(Theme.FG_MUTE);
            head.add(hr, BorderLayout.EAST);
            head.setAlignmentX(LEFT_ALIGNMENT);
            head.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
            bookContent.add(head);

            for (final Book b : en.getValue()) {
                JPanel row = new JPanel(new BorderLayout(8, 0));
                row.setOpaque(true);
                row.setBackground(Theme.BG_CARD);
                row.setBorder(new EmptyBorder(6, 14, 6, 12));
                row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
                row.setAlignmentX(LEFT_ALIGNMENT);

                String label = b.title == null || b.title.isEmpty() ? trimExt(b.name) : b.title;
                JLabel n = new JLabel("📖  " + label);
        n.setFont(Theme.ui(12));
                n.setForeground(Theme.FG_TEXT);
                n.setToolTipText(b.path);
                row.add(n, BorderLayout.CENTER);

                JLabel meta = new JLabel(b.tag() + "    " + Music.humanSize(b.size));
        meta.setFont(Theme.ui(10));
                meta.setForeground(Theme.FG_MUTE);
                row.add(meta, BorderLayout.EAST);

                row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                MouseAdapter ma = new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        h.onOpenBook(b);
                    }

                    @Override
                    public void mouseEntered(MouseEvent e) {
                        row.setBackground(Theme.BG_SEL);
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        row.setBackground(Theme.BG_CARD);
                    }
                };
                row.addMouseListener(ma);
                n.addMouseListener(ma);
                meta.addMouseListener(ma);

                bookContent.add(row);
                bookContent.add(Box.createVerticalStrut(1));
            }
            bookContent.add(Box.createVerticalStrut(8));
        }
    }

    // ------------------------------------------------------------------ 相册

    /**
     * 相册页：按目录分成若干「卷」，每卷里是一格一格的方形缩略图。
     *
     * 缩略图走服务端 {@code /_photos/thumb?f=&w=}，不要直接拉原图 —— 手机拍的
     * 照片动辄 10MB/4000×3000，一屏 20 张就是 200MB 流量加 20×48MB 的 ARGB 解码，
     * 桌面端这边会直接卡住几十秒。服务端用 inSampleSize 解到 320px 再压 JPEG80，
     * 单张约 30KB。
     */
    private void buildPhotos() {
        if (plib == null) {
            photoContent.add(msg("正在拉取相册…"));
            return;
        }
        if (!plib.ok) {
            photoContent.add(msg("相册拉取失败：" + plib.error));
            return;
        }
        if (plib.notConfigured()) {
            photoContent.add(msg("服务端还没设相册目录。用管理员账号在网页端 /_admin 里指一个目录就会出来。"));
            return;
        }
        String k = q();
        Map<String, List<Photo>> byDir = new LinkedHashMap<>();
        for (Photo p : plib.photos) {
            String dir = p.dir == null ? "" : p.dir;
            if (!k.isEmpty()) {
                String hay = (p.name == null ? "" : p.name) + " " + dir;
                if (!hay.toLowerCase().contains(k.toLowerCase())) continue;
            }
            byDir.computeIfAbsent(dir, x -> new ArrayList<>()).add(p);
        }
        if (byDir.isEmpty()) {
            photoContent.add(msg(k.isEmpty() ? "相册里还没有照片" : "没有匹配「" + k + "」的照片"));
            return;
        }
        for (Map.Entry<String, List<Photo>> en : byDir.entrySet()) {
            List<Photo> list = en.getValue();
            JPanel head = new JPanel(new BorderLayout());
            head.setOpaque(true);
            head.setBackground(Theme.BG_HOVER);
            head.setBorder(new EmptyBorder(8, 12, 8, 12));
            JLabel hl = new JLabel(folderName(en.getKey()));
            hl.setFont(Theme.bold(13));
            hl.setForeground(Theme.FG_TITLE);
            head.add(hl, BorderLayout.WEST);
            JLabel hr = new JLabel(list.size() + " 张");
            hr.setFont(Theme.ui(11));
            hr.setForeground(Theme.FG_MUTE);
            head.add(hr, BorderLayout.EAST);
            head.setAlignmentX(LEFT_ALIGNMENT);
            head.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
            photoContent.add(head);

            JPanel grid = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
            grid.setOpaque(true);
            grid.setBackground(Theme.BG_DEEP);
            grid.setAlignmentX(LEFT_ALIGNMENT);
            for (Photo p : list) grid.add(photoCell(p));
            // FlowLayout 只会按自己被分配的宽度折行，而 BoxLayout(Y) 会给子组件
            // 一个「多宽都行」的最大宽度 —— 于是 12 张就排成一条 1872px 的长龙，
            // 超出窗口的部分直接被裁掉。这里自己算列数并把尺寸钉死，
            // 折行结果才和外面预留的高度对得上。
            int cell = 148 + 8;
            int cols = Math.max(1, (photoGridWidth() + 8) / cell);
            int rows = (list.size() + cols - 1) / cols;
            int gridH = rows * cell + 8;
            grid.setPreferredSize(new Dimension(Math.min(cols, list.size()) * cell + 8, gridH));
            JPanel wrap = new JPanel(new BorderLayout());
            wrap.setOpaque(false);
            wrap.add(grid, BorderLayout.WEST);
            wrap.setAlignmentX(LEFT_ALIGNMENT);
            wrap.setMaximumSize(new Dimension(Integer.MAX_VALUE, gridH));
            photoContent.add(wrap);
            photoContent.add(Box.createVerticalStrut(8));
        }
    }

    /**
     * 相册网格能用的宽度。窗口还没显示出来时 viewport 宽是 0，
     * 退回到一个保守值（4 列），总比排成一条长龙强。
     */
    private int photoGridWidth() {
        int w = photoHost.getWidth();
        if (w <= 0 && photoHost.getComponentCount() > 0
                && photoHost.getComponent(0) instanceof JScrollPane) {
            w = ((JScrollPane) photoHost.getComponent(0)).getViewport().getWidth();
        }
        if (w <= 0) w = 4 * 156;
        return Math.max(156, w - 24);
    }

    /**
     * 一格缩略图。尺寸固定 148×148（贴方形），文件名单独一行 —— 照片名是
     * IMG_20251026_223556.jpg 这种，撑不开也认不出，所以只在悬停提示里给全名。
     */
    private JComponent photoCell(Photo p) {
        JLabel cell = new JLabel();
        cell.setPreferredSize(new Dimension(148, 148));
        cell.setOpaque(true);
        cell.setBackground(Theme.BG_CARD);
        cell.setBorder(new LineBorder(Theme.LINE));
        cell.setHorizontalAlignment(SwingConstants.CENTER);
        cell.setVerticalAlignment(SwingConstants.CENTER);
        cell.setForeground(Theme.FG_MUTE);
        cell.setText("…");
        cell.setToolTipText(p.name + (p.hasSize() ? "    " + p.width + "×" + p.height : ""));
        cell.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        String key = "t:" + p.path;
        ImageIcon hit;
        synchronized (photoCache) {
            hit = photoCache.get(key);
        }
        if (hit != null) {
            cell.setText(null);
            cell.setIcon(hit);
        } else {
            final String base = baseUrl, ck = cookie;
            Thread t = new Thread(() -> {
                ImageIcon ic = fetchSquare(PhotoLib.thumbUrl(base, p.path, 320), ck, 148);
                SwingUtilities.invokeLater(() -> {
                    if (ic == null) {
                        cell.setText("图裂了");
                        return;
                    }
                    synchronized (photoCache) {
                        photoCache.put(key, ic);
                    }
                    cell.setText(null);
                    cell.setIcon(ic);
                });
            }, "usbnas-photo");
            t.setDaemon(true);
            t.start();
        }

        cell.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                showPhoto(p);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                cell.setBackground(Theme.BG_SEL);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                cell.setBackground(Theme.BG_CARD);
            }
        });
        return cell;
    }

    /**
     * 点开一张照片：无边框弹窗 + 纯深色底，图按窗口比例缩放到能放下为止。
     * 这里才拉原图（{@code /_photos/image} 那条路径），而且只在用户真的要看的
     * 时候拉一次，看完就随窗口一起丢掉，不进 {@link #photoCache}。
     */
    private void showPhoto(Photo p) {
        final JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(this), Dialog.ModalityType.APPLICATION_MODAL);
        dlg.setUndecorated(true);
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.BG_DEEP);
        root.setBorder(new LineBorder(Theme.LINE));

        JLabel img = new JLabel("正在取原图…", SwingConstants.CENTER);
        img.setFont(Theme.ui(13));
        img.setForeground(Theme.FG_DIM);
        img.setPreferredSize(new Dimension(900, 620));
        root.add(img, BorderLayout.CENTER);

        JLabel cap = new JLabel("  " + p.name + "    "
                + (p.hasSize() ? p.width + "×" + p.height + "    " : "")
                + Music.humanSize(p.size) + "    点任意处关闭");
        cap.setFont(Theme.ui(11));
        cap.setForeground(Theme.FG_SUB);
        cap.setBorder(new EmptyBorder(6, 8, 8, 8));
        root.add(cap, BorderLayout.SOUTH);

        final String base = baseUrl, ck = cookie;
        final int vw = 900, vh = 620;
        Thread t = new Thread(() -> {
            BufferedImage bi = fetchRaw(PhotoLib.imageUrl(base, p.path), ck);
            SwingUtilities.invokeLater(() -> {
                if (bi == null) {
                    img.setText("原图取不到（可能已删除，或者登录已过期）");
                    img.setForeground(Theme.ERR);
                } else {
                    img.setText(null);
                    img.setIcon(new ImageIcon(fit(bi, vw, vh)));
                }
                dlg.pack();
                dlg.setLocationRelativeTo(LibraryPanel.this);
            });
        }, "usbnas-photo-full");
        t.setDaemon(true);
        t.start();

        MouseAdapter close = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                dlg.dispose();
            }
        };
        root.addMouseListener(close);
        img.addMouseListener(close);
        cap.addMouseListener(close);
        dlg.getRootPane().registerKeyboardAction(e -> dlg.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        dlg.setContentPane(root);
        dlg.pack();
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);
    }

    private JComponent msg(String text) {
        JLabel l = new JLabel("<html><div style='width:560px;color:" + Theme.hex(Theme.FG_SUB) + ";font-size:13px'>"
                + escapeInline(text) + "</div></html>");
        l.setBorder(new EmptyBorder(16, 14, 16, 14));
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(l, BorderLayout.WEST);
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 90));
        return p;
    }

    // ------------------------------------------------------------------ 工具

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static ImageIcon fetchImage(String url, String cookie) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(3000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "USBNASLink/1.0");
            if (cookie != null && !cookie.isEmpty()) {
                c.setRequestProperty("Cookie", Session.COOKIE + "=" + cookie);
            }
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            BufferedImage bi = ImageIO.read(in);
            in.close();
            if (bi == null) return null;
            return new ImageIcon(scale2to3(bi, 126, 168));
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 统一裁成 2:3 再缩放，卡片才对得齐 */
    private static BufferedImage scale2to3(BufferedImage src, int w, int h) {
        double want = 2.0 / 3.0;
        int sw = src.getWidth(), sh = src.getHeight();
        if (sw <= 0 || sh <= 0) return src;
        double cur = (double) sw / sh;
        int cx, cy, cw, ch;
        if (cur > want) {
            ch = sh;
            cw = (int) Math.round(sh * want);
            cx = (sw - cw) / 2;
            cy = 0;
        } else {
            cw = sw;
            ch = (int) Math.round(sw / want);
            cx = 0;
            cy = (sh - ch) / 2;
        }
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, cx, cy, cx + cw, cy + ch, null);
        g.dispose();
        return out;
    }

    /**
     * 相册缩略图：服务端已经按 w 参数解好、压成 JPEG 了，这里只需要拉回来缩到
     * {@code side×side} 的方格里。和 {@link #fetchImage} 分开是因为海报要 2:3 裁剪，
     * 照片裁成 2:3 会切掉两边，看着不像照片。
     */
    private static ImageIcon fetchSquare(String url, String cookie, int side) {
        BufferedImage bi = fetchRaw(url, cookie, 8000);
        if (bi == null) return null;
        return new ImageIcon(square(bi, side));
    }

    /** 拉一张原图/原尺寸图，不做任何缩放。用于相册全屏查看。 */
    private static BufferedImage fetchRaw(String url, String cookie) {
        return fetchRaw(url, cookie, 20000);
    }

    private static BufferedImage fetchRaw(String url, String cookie, int readTimeout) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(readTimeout);
            c.setRequestProperty("User-Agent", "USBNASLink/1.0");
            if (cookie != null && !cookie.isEmpty()) {
                c.setRequestProperty("Cookie", Session.COOKIE + "=" + cookie);
            }
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            BufferedImage bi = ImageIO.read(in);
            in.close();
            return bi;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 居中裁成正方形再缩放，缩略图网格才对得齐。 */
    private static BufferedImage square(BufferedImage src, int side) {
        int sw = src.getWidth(), sh = src.getHeight();
        if (sw <= 0 || sh <= 0) return src;
        int s = Math.min(sw, sh);
        int cx = (sw - s) / 2, cy = (sh - s) / 2;
        BufferedImage out = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, side, side, cx, cy, cx + s, cy + s, null);
        g.dispose();
        return out;
    }

    /** 等比缩到能塞进 maxW×maxH，不放大。 */
    private static BufferedImage fit(BufferedImage src, int maxW, int maxH) {
        int sw = src.getWidth(), sh = src.getHeight();
        if (sw <= 0 || sh <= 0) return src;
        double k = Math.min(1.0, Math.min((double) maxW / sw, (double) maxH / sh));
        int w = Math.max(1, (int) Math.round(sw * k));
        int h = Math.max(1, (int) Math.round(sh * k));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static String folderName(String dir) {
        if (dir == null || dir.isEmpty()) return "曲目";
        int i = dir.lastIndexOf('/');
        return i < 0 || i == dir.length() - 1 ? dir : dir.substring(i + 1);
    }

    private static String trimExt(String n) {
        if (n == null) return "";
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static String joinList(List<String> l, int max) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String s : l) {
            if (n++ >= max) {
                sb.append(" 等");
                break;
            }
            if (sb.length() > 0) sb.append("、");
            sb.append(s);
        }
        return sb.toString();
    }

    static String pct(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    private static String escapeInline(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", " ");
    }
}