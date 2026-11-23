package com.littlesauce.usbnaslink.desktop;

import com.littlesauce.usbnaslink.Book;
import com.littlesauce.usbnaslink.BookLib;
import com.littlesauce.usbnaslink.Scanner;
import com.littlesauce.usbnaslink.ServiceInfo;
import com.littlesauce.usbnaslink.Session;
import com.littlesauce.usbnaslink.Store;
import com.littlesauce.usbnaslink.Track;
import com.littlesauce.usbnaslink.Video;
import com.littlesauce.usbnaslink.VideoLib;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.html.HTMLEditorKit;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * USBNASLink 桌面客户端。
 *
 * 设计取向（想清楚再改）：
 *   - **打开即扫描**：构造完窗口就自动跑一次 {@link Scanner#scan}，用户不需要点任何
 *     东西。这条路径必须够快 —— 扫描走后台线程，UI 立刻可见。
 *   - **不做原生浏览器外壳**：Java 侧没有可靠的 Chromium 可嵌入（JCEF 体积 100MB+，
 *     还要单独分发），而 USBNAS 网页本身就是深色/浅色/水墨三主题自适应的响应式页面。
 *     所以主界面用「服务列表 + 内置轻量 HTML 预览 + 一键在系统浏览器打开」。
 *   - **登录态不重复造轮子**：登录走服务端表单 POST（{@link Session}），拿到的
 *     {@code usbnas_sid} 直接塞进系统浏览器的 cookie 是不可能跨进程的，所以这里
 *     选择「登录用内嵌面板验证凭据 + 把 URL 交给浏览器」分工：验证凭据在有 cookie
 *     的同一进程里做，浏览交给浏览器。
 *
 * 线程约定：所有 Swing 组件只在 EDT 上碰；扫描结果通过 {@link SwingUtilities#invokeLater}
 * 回投。扫描用独立线程，长任务不会卡 UI。
 */
public final class Main {

    private static final int DEFAULT_PORT = 8080;
    private static final String NL = "\n";

    private final JFrame frame = new JFrame("USBNASLink — 局域网 NAS 连接器");
    private final DefaultListModel<Svc> model = new DefaultListModel<>();
    private final JList<Svc> list = new JList<>(model);
    private final JLabel status = Theme.caption("准备中…");
    private final JProgressBar bar = new JProgressBar();
    private final JButton scanBtn = Theme.ghost("重新扫描");
    private final JButton openBtn = Theme.primary("在浏览器打开");
    private final JButton loginBtn = Theme.ghost("登录…");
    /** 顶栏右侧的更新入口：图标用文字代替，省得再带一套位图资源 */
    private final JButton updBtn = Theme.ghost("⟳ 更新");
    private final JTextField portField = Theme.field(5);
    private final JCheckBox looseBox = Theme.check("显示未确认的开放端口", false);
    private final JEditorPane preview = new JEditorPane();
    private final JTextArea log = new JTextArea(6, 40);
    private final Store store;
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private volatile Scanner current;

    // --- 媒体库与播放器（登录成功后才有地址和 sid） ---
    private LibraryPanel library;
    private PlayerWindow player;
    private AdminPanel adminPanel;
    /** 内嵌阅读页：第一次点书时才建，之后复用同一个 Tab 换内容 */
    private BookReader bookReader;
    /** 内嵌视频窗口：第一次点片时才建 */
    private VideoWindow videoWindow;
    /** 建 Tab 时要用到，所以提到字段里 */
    private JTabbedPane tabs;
    private volatile String connBase = "";
    private volatile String connSid = null;

    /** 列表项包装，让 JList 用自定义渲染器 */
    static final class Svc {
        final ServiceInfo info;

        Svc(ServiceInfo i) {
            this.info = i;
        }

        @Override
        public String toString() {
            return info.baseUrl;
        }
    }

    Main() {
        store = new Store(new File(appDir(), "known.txt"));
        buildUi();
        seedFromHistory();
        SwingUtilities.invokeLater(this::startScan);
    }

    // ------------------------------------------------------------------ 界面

    /**
     * 界面结构与网页一一对位：
     *
     * <pre>
     *   网页                         桌面端
     *   header + h1 + .sub           顶部品牌行（站点名 + 一句话说明）
     *   .wrap 1080px 居中            内容区 1080 宽居中（窗口再宽也不拉散）
     *   .roots 两列卡片              左侧服务列表
     *   .row 行                      列表行（悬停/选中都用网页那两档底色）
     *   .crumbs 面包屑               页签 + 状态行
     * </pre>
     */
    private void buildUi() {
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(880, 600));
        frame.setSize(1060, 720);
        frame.setLocationRelativeTo(null);
        frame.getContentPane().setBackground(Theme.BG_DEEP);

        // --- 顶部品牌行 ---
        JPanel brand = new JPanel(new BorderLayout(12, 0));
        brand.setOpaque(true);
        brand.setBackground(Theme.BG_DEEP);
        brand.setBorder(Theme.padding(14, 18, 8, 18));

        JPanel brandLeft = new JPanel();
        brandLeft.setOpaque(false);
        brandLeft.setLayout(new BoxLayout(brandLeft, BoxLayout.X_AXIS));
        JLabel logo = new JLabel("肥鼠");
        logo.setFont(Theme.bold(11));
        logo.setForeground(Color.WHITE);
        logo.setOpaque(true);
        logo.setBackground(Theme.BRAND);
        logo.setBorder(Theme.padding(3, 7, 3, 7));
        brandLeft.add(logo);
        brandLeft.add(javax.swing.Box.createHorizontalStrut(10));
        JLabel t1 = new JLabel("USBNASLink");
        t1.setFont(Theme.bold(20));
        t1.setForeground(Theme.FG_TITLE);
        brandLeft.add(t1);
        brandLeft.add(javax.swing.Box.createHorizontalStrut(10));
        JLabel t2 = new JLabel("局域网 NAS 连接器");
        t2.setFont(Theme.ui(13));
        t2.setForeground(Theme.FG_DIM);
        brandLeft.add(t2);
        brand.add(brandLeft, BorderLayout.WEST);

        JPanel brandRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        brandRight.setOpaque(false);
        brandRight.add(scanBtn);
        brandRight.add(updBtn);
        brandRight.add(portField);
        brandRight.add(looseBox);
        brand.add(brandRight, BorderLayout.EAST);

        // --- 服务列表 ---
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(-1);
        list.setCellRenderer(new SvcRenderer());
        Theme.applyTo(list);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) onSelect();
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) openInBrowser(selected());
            }
        });

        // --- 右侧预览 ---
        preview.setEditable(false);
        preview.setContentType("text/html");
        preview.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        preview.setEditorKit(new HTMLEditorKit());
        preview.setBackground(Theme.BG_DEEP);
        preview.setBorder(Theme.padding(10, 14, 10, 14));
        preview.addHyperlinkListener(ev -> {
            if (ev.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                Svc s = selected();
                String url = ev.getURL() == null ? null : ev.getURL().toString();
                if (url != null) {
                    // 站内链接就地展示，站外链接交浏览器
                    Svc cur = s;
                    if (cur != null && url.startsWith(cur.info.baseUrl)) {
                        loadPreview(url);
                    } else {
                        openUrl(url);
                    }
                }
            }
        });

        JPanel right = Theme.pane();
        right.setLayout(new BorderLayout());
        JScrollPane pv = Theme.scroll(preview);
        right.add(pv, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        actions.setOpaque(true);
        actions.setBackground(Theme.BG_DEEP);
        actions.setBorder(Theme.padding(0, 6, 0, 6));
        actions.add(openBtn);
        actions.add(loginBtn);
        JButton davBtn = Theme.ghost("复制 WebDAV 地址");
        davBtn.addActionListener(e -> {
            Svc s = selected();
            if (s != null) {
                copyToClipboard(s.info.baseUrl + "/dav");
                setStatus("已复制 WebDAV 地址：" + s.info.baseUrl + "/dav");
            }
        });
        actions.add(davBtn);
        // 「复制登录链接」：和「在浏览器打开」同一个票据机制，只是不立刻打开，
        // 方便把链接贴到另一台机器的浏览器、或者微信里发给自己手机。
        JButton linkBtn = Theme.ghost("复制登录链接");
        linkBtn.addActionListener(e -> {
            Svc s = selected();
            if (s == null) {
                setStatus("先选一台服务器");
                return;
            }
            final String base = s.info.baseUrl;
            linkBtn.setEnabled(false);
            Thread t = new Thread(() -> {
                String url = handoffUrl(base, "/");
                SwingUtilities.invokeLater(() -> {
                    linkBtn.setEnabled(true);
                    copyToClipboard(url);
                    setStatus(url.indexOf("/_login/sso") > 0
                            ? "已复制免登录链接（120 秒内有效，只能用一次）"
                            : "已复制普通地址（没登录，或服务端不支持免登录票）");
                });
            }, "usbnas-handoff");
            t.setDaemon(true);
            t.start();
        });
        actions.add(linkBtn);
        JButton mediaBtn = Theme.ghost("影视 · 音乐");
        mediaBtn.addActionListener(e -> {
            Svc s = selected();
            if (s == null) {
                setStatus("先选一台服务器");
                return;
            }
            connectMedia(s.info.baseUrl);
        });
        actions.add(mediaBtn);
        right.add(actions, BorderLayout.SOUTH);

        JScrollPane listScroll = Theme.scroll(list);
        listScroll.setPreferredSize(new Dimension(320, 100));
        JPanel leftBox = Theme.pane();
        leftBox.setLayout(new BorderLayout());
        leftBox.setBorder(Theme.padding(0, 0, 0, 6));
        JLabel listHead = Theme.caption("  同一热点下的服务");
        listHead.setBorder(Theme.padding(4, 8, 6, 8));
        leftBox.add(listHead, BorderLayout.NORTH);
        leftBox.add(listScroll, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftBox, right);
        split.setDividerLocation(330);
        split.setResizeWeight(0.32);
        split.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        split.setOpaque(true);
        split.setBackground(Theme.BG_DEEP);

        // --- 底部状态 + 日志 ---
        Theme.progress(bar);
        bar.setStringPainted(false);
        bar.setVisible(false);
        log.setEditable(false);
        log.setFont(Theme.mono(11));
        log.setBackground(Theme.BG_CARD);
        log.setForeground(Theme.FG_SUB);
        log.setCaretColor(Theme.FG_TITLE);
        log.setBorder(Theme.padding(8, 10, 8, 10));
        JScrollPane logScroll = Theme.scroll(log);
        logScroll.setBorder(Theme.cardBorder());
        logScroll.setPreferredSize(new Dimension(100, 104));

        status.setFont(Theme.caption("").getFont());
        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        bottom.setOpaque(true);
        bottom.setBackground(Theme.BG_DEEP);
        bottom.setBorder(Theme.padding(6, 18, 12, 18));
        bottom.add(status, BorderLayout.NORTH);
        bottom.add(bar, BorderLayout.CENTER);
        bottom.add(logScroll, BorderLayout.SOUTH);

        // --- 媒体库页：与「服务器」页并列成一个 Tab，不挤占预览区 ---
        library = new LibraryPanel(new LibraryPanel.Handler() {
            @Override
            public void onPlayVideo(Video v, Video.Meta meta) {
                // 内嵌播放：mkv/hevc/字幕都由 libvlc 解，不甩给系统播放器。
                // 只有 libvlc 不可用时，VideoWindow 自己会给「用系统播放器」的兜底按钮。
                if (videoWindow == null) {
                    videoWindow = new VideoWindow();
                }
                videoWindow.play(v, VideoLib.videoUrl(connBase, v.path), connSid);
                setStatus("正在播放：" + v.name);
            }

            @Override
            public void onPlayMusic(java.util.List<Track> album, Track t) {
                ensurePlayer().setPlaylist(album, t);
                player.setVisible(true);
                player.toFront();
            }

            @Override
            public void onOpenBook(Book b) {
                // 内嵌阅读：服务端 /_books/text 先把 docx/doc/epub/txt 抽成纯文本，
                // 由 BookReader 自己排版，不跳浏览器。只有 PDF 例外——客户端没有
                // PDF 渲染器，BookReader 里会给「用浏览器打开」的按钮。
                if (bookReader == null) {
                    bookReader = new BookReader();
                }
                bookReader.setTarget(connBase, connSid);
                tabs.addTab(b.title == null || b.title.isEmpty() ? b.name : b.title, bookReader);
                tabs.setSelectedComponent(bookReader);
                bookReader.open(b);
                setStatus("正在打开：" + b.name);
            }

            @Override
            public void onOpenPage(String url) {
                openUrl(url);
            }
        });

        JTabbedPane tabs = Theme.tabs();
        this.tabs = tabs;
        tabs.addTab("服务器", split);
        tabs.addTab("影视 · 音乐 · 图书", library);
        // 管理页：只有管理员能用，权限由服务端 /_admin/api 判定，非管理员进去是锁定态
        adminPanel = new AdminPanel();
        tabs.addTab("管理设置", adminPanel);
        tabs.addChangeListener(e -> {
            if (tabs.getSelectedComponent() == adminPanel) {
                adminPanel.setTarget(connBase, connSid);
            }
        });

        // 自动更新：启动时静默查一次（用户关掉就一个请求都不发），
        // 找到新版本才弹窗。发现按钮放在品牌栏右侧，找起来比翻设置快。
        updBtn.addActionListener(e -> UpdUI.manualCheck(frame));
        UpdUI.autoCheckOnStart(info -> UpdUI.showDialog(frame, info));

        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(brand, BorderLayout.NORTH);
        frame.getContentPane().add(tabs, BorderLayout.CENTER);
        frame.getContentPane().add(bottom, BorderLayout.SOUTH);

        scanBtn.addActionListener(e -> startScan());
        openBtn.addActionListener(e -> openInBrowser(selected()));
        loginBtn.addActionListener(e -> doLogin(selected()));

        portField.setText(String.valueOf(DEFAULT_PORT));
        portField.setPreferredSize(new Dimension(70, 32));

        showPlaceholder();
    }

    private void showPlaceholder() {
        preview.setText("<html><body style='font-family:" + Theme.FONT_STACK + ";"
                + "background:" + Theme.hex(Theme.BG_DEEP) + ";color:" + Theme.hex(Theme.FG_SUB) + ";padding:28px'>"
                + "<h2 style='color:" + Theme.hex(Theme.FG_TITLE) + ";margin:0 0 8px'>USBNASLink</h2>"
                + "<p style='color:" + Theme.hex(Theme.FG_SUB) + "'>选择左侧扫描到的服务器，双击或用下方按钮在浏览器中打开。</p>"
                + "<p style='color:" + Theme.hex(Theme.FG_MUTE) + ";font-size:12px'>"
                + "扫描会先用 UDP 广播探测，再对 <code>本机所在 /24</code> 并行做 TCP 连接，"
                + "最后用 <code>/_status</code> 与 <code>Server</code> 头确认是不是 USBNAS。</p>"
                + "</body></html>");
    }

    // ---------------------------------------------------------------- 媒体库

    /**
     * 把媒体库指向某台服务器。
     *
     * 曲库/影视库端点走的是「匿名可读就看，否则要 sid」的规则，所以登录成功后
     * 会把新的 sid 再灌一次进来 —— 不登录也能浏览，只是私有目录会空。
     */
    private void connectMedia(String base) {
        connBase = base;
        library.setTarget(base, connSid);
        if (player != null) player.setTarget(base, connSid);
        if (adminPanel != null) adminPanel.setTarget(base, connSid);
        setStatus("媒体库已连接 " + base);
    }

    private PlayerWindow ensurePlayer() {
        if (player == null) {
            player = new PlayerWindow();
            player.setTarget(connBase, connSid);
        }
        return player;
    }

    // ------------------------------------------------------------------ 扫描

    /** 先把手写历史塞进列表，让用户一眼看到「上次那台」，扫描完再合并 */
    private void seedFromHistory() {
        for (ServiceInfo si : store.load()) {
            model.addElement(new Svc(si));
        }
        int n = model.size();
        setStatus(n == 0 ? "正在扫描…" : "载入 " + n + " 个历史地址，正在扫描…");
    }

    private void startScan() {
        if (!scanning.compareAndSet(false, true)) return;
        for (int i = model.size() - 1; i >= 0; i--) {
            if (!"HISTORY".equals(model.get(i).info.source)) model.remove(i);
        }
        scanBtn.setEnabled(false);
        bar.setVisible(true);
        bar.setIndeterminate(true);
        bar.setStringPainted(true);
        bar.setString("广播探测…");
        log.setText("");
        final int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            appendLog("端口不合法，回退到 " + DEFAULT_PORT);
            portField.setText(String.valueOf(DEFAULT_PORT));
            SwingUtilities.invokeLater(this::startScan);
            scanning.set(false);
            return;
        }

        Scanner.Options o = new Scanner.Options();
        o.port = port;
        o.strict = !looseBox.isSelected();
        current = new Scanner(o);

        Thread t = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                current.scan(new Scanner.Progress() {
                    @Override
                    public void onProgress(String phase, int done, int total, String note) {
                        SwingUtilities.invokeLater(() -> {
                            bar.setString(note);
                            if ("PORT".equals(phase) || "PROBE".equals(phase)) {
                                bar.setIndeterminate(false);
                                bar.setMaximum(Math.max(1, total));
                                bar.setValue(Math.min(done, total));
                            }
                            setStatus(note);
                        });
                    }

                    @Override
                    public boolean onFound(ServiceInfo si) {
                        SwingUtilities.invokeLater(() -> mergeInto(si));
                        return true;
                    }
                });
            } catch (Throwable ex) {
                SwingUtilities.invokeLater(() -> appendLog("扫描异常：" + ex));
            } finally {
                long dt = System.currentTimeMillis() - t0;
                SwingUtilities.invokeLater(() -> {
                    bar.setIndeterminate(false);
                    bar.setValue(bar.getMaximum());
                    bar.setString("完成");
                    bar.setVisible(false);
                    scanBtn.setEnabled(true);
                    scanning.set(false);
                    int n = model.size();
                    setStatus(n == 0
                            ? "没扫到服务（耗时 " + dt + "ms）。确认手机热点已开、服务已启动、端口为 " + port
                            : "发现 " + n + " 个服务（耗时 " + dt + "ms）");
                    if (n > 0 && list.getSelectedIndex() < 0) {
                        list.setSelectedIndex(0);
                    }
                    appendLog("扫描完成，用时 " + dt + "ms，命中 " + n + " 个");
                });
            }
        }, "usbnas-scan");
        t.setDaemon(true);
        t.start();
    }

    /** 合并进列表：同 ip:port 更新条目，否则新增 */
    private void mergeInto(ServiceInfo si) {
        for (int i = 0; i < model.size(); i++) {
            Svc s = model.get(i);
            if (s.info.key().equals(si.key())) {
                s.info.title = si.displayName();
                s.info.disksText = si.disksText;
                s.info.version = si.version;
                s.info.source = "SCAN";
                s.info.seenAt = System.currentTimeMillis();
                model.set(i, s);
                store.remember(si);
                appendLog("确认 " + si.baseUrl + "  " + si.displayName()
                        + (si.disksText.isEmpty() ? "" : "  " + si.disksText));
                return;
            }
        }
        model.addElement(new Svc(si));
        store.remember(si);
        appendLog("发现 " + si.baseUrl + "  " + si.displayName()
                + (si.disksText.isEmpty() ? "" : "  " + si.disksText));
    }

    // ------------------------------------------------------------------ 交互

    private Svc selected() {
        return list.getSelectedValue();
    }

    private void onSelect() {
        Svc s = selected();
        if (s == null) {
            showPlaceholder();
            return;
        }
        // 用轻量 HTML 卡片预览，而不是真的渲染整站：
        // 站内页面依赖 cookie + 大量内联 SVG，渲染进来反而看不清，交给浏览器。
        //
        // 磁盘这一行只有两种状态，别把「查不到」说成「登录后可看」：
        //   - 有值：扫到 /_status 了，直接显示；
        //   - 空：这台服务没报出可用存储（可能所有根都没挂载），与登录无关。
        // 原本这里写的是「（未取到，登录后可看实时状态）」，但 /_status 本来就匿名可取，
        // 登录后也不会变——那句提示是把「提取失败」误当成「权限不足」的错话。
        String disks = s.info.disksText != null && !s.info.disksText.isEmpty()
                ? escape(s.info.disksText)
                : "（这台服务没有报出可用存储）";
        String html = "<html><body style='font-family:" + Theme.FONT_STACK + ";"
                + "background:" + Theme.hex(Theme.BG_DEEP) + ";color:" + Theme.hex(Theme.FG_SUB) + ";padding:28px'>"
                + "<div style='font-size:12px;color:" + Theme.hex(Theme.FG_MUTE) + ";letter-spacing:.5px'>当前服务器</div>"
                + "<h2 style='color:" + Theme.hex(Theme.FG_TITLE) + ";margin:6px 0 2px'>" + escape(s.info.displayName()) + "</h2>"
                + "<div style='font-family:Consolas,monospace;color:" + Theme.hex(Theme.BRAND_LINK) + ";font-size:14px'>"
                + escape(s.info.baseUrl) + "</div>"
                + "<table style='margin-top:18px;font-size:13px;color:" + Theme.hex(Theme.FG_SUB) + "'>"
                + row("来源", describeSource(s.info.source))
                + row("响应", s.info.rttMs + " ms")
                + row("版本", s.info.version == null || s.info.version.isEmpty() ? "—" : s.info.version)
                + row("登录", s.info.needLogin ? "需要登录" : "可直接浏览")
                + row("磁盘", disks)
                + "</table>"
                + "<p style='color:" + Theme.hex(Theme.FG_MUTE) + ";font-size:12px;margin-top:20px'>"
                + "双击左侧条目或点「在浏览器打开」进入完整站点。</p>"
                + "</body></html>";
        preview.setText(html);
        preview.setCaretPosition(0);
    }

    private String row(String k, String v) {
        return "<tr><td style='color:" + Theme.hex(Theme.FG_MUTE) + ";padding:3px 14px 3px 0'>" + k
                + "</td><td style='padding:3px 0'>" + v + "</td></tr>";
    }

    private void onSelectLoadPreview() {
        Svc s = selected();
        if (s != null) loadPreview(s.info.baseUrl + "/");
    }

    private void loadPreview(String url) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(3000);
            c.setRequestProperty("User-Agent", "USBNASLink/1.0");
            if (c.getResponseCode() == 401) {
                appendLog("该页面需要登录：" + url);
            }
            preview.setPage(url);
        } catch (IOException e) {
            appendLog("预览失败：" + e.getMessage());
        }
    }

    private void openInBrowser(Svc s) {
        if (s == null) {
            setStatus("先选一台服务器");
            return;
        }
        openUrl(handoffUrl(s.info.baseUrl, "/"));
    }

    /**
     * 拼一个「带一次性票据」的地址，让浏览器打开就是登录态。
     *
     * 直接把自己的 sid 塞进 URL 是行不通的做法：sid 是长期 Cookie，进了地址栏就会
     * 留在浏览器历史里、被 Referer 带给外站、被截图、被聊天软件存一份，泄一次能用到
     * 24 小时后。所以这里向服务端要一张一次性票（{@code /_login/handoff}），票只能换
     * 一次 Cookie、120 秒过期，浏览器的 {@code /_login/sso} 换完就作废。
     *
     * 没登录、服务端是老版本、网络不通，一律退回普通地址 —— 用户顶多看到登录页，
     * 不至于因为拿不到票就打不开。
     */
    private String handoffUrl(String baseUrl, String back) {
        String sid = connSid;
        if (sid == null || sid.isEmpty()) return baseUrl + back;
        Session sess = new Session(baseUrl);
        sess.setSid(sid);
        String ticket = sess.handoff();
        if (ticket == null || ticket.isEmpty()) {
            appendLog("免登录票没拿到（服务端版本或网络原因），退回普通地址");
            return baseUrl + back;
        }
        return baseUrl + "/_login/sso?t=" + ticket + "&back=" + encodePath(back);
    }

    private static String encodePath(String p) {
        StringBuilder sb = new StringBuilder();
        for (byte b : p.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int c = b & 255;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '/' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
            }
        }
        return sb.toString();
    }

    private void openUrl(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                setStatus("已在浏览器打开 " + url);
            } else {
                copyToClipboard(url);
                setStatus("当前系统不支持直接打开浏览器，已复制地址：" + url);
            }
        } catch (Throwable t) {
            copyToClipboard(url);
            setStatus("打开失败，已复制地址：" + url);
        }
    }

    /**
     * 登录：弹一个小面板收帐号密码，走 {@link Session#login} 真正 POST 一次。
     * 失败提示会原样带出服务端的错误（HTTP 401 = 用户名或密码不对）。
     * 成功后把 cookie 写进系统浏览器会话不现实，所以这里至少做到「验证凭据可用」，
     * 并在浏览器里打开登录页让用户自己在那边登（浏览器自己存 cookie）。
     */
    private void doLogin(Svc s) {
        if (s == null) {
            setStatus("先选一台服务器");
            return;
        }
        JPanel p = Theme.pane();
        p.setLayout(new GridLayout(0, 1, 0, 6));
        p.setBorder(Theme.padding(8, 8, 0, 8));
        JTextField u = Theme.field(16);
        u.setText(System.getProperty("user.name", ""));
        JPasswordField pw = Theme.password(16);
        p.add(Theme.label("用户名"));
        p.add(u);
        p.add(Theme.label("密码"));
        p.add(pw);
        p.add(Theme.caption("登录后点「在浏览器打开」会用一张一次性票自动登录浏览器。"));
        int r = JOptionPane.showConfirmDialog(frame, p, "登录 " + s.info.hostPort(),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;

        loginBtn.setEnabled(false);
        setStatus("正在登录…");
        final String user = u.getText().trim();
        final String pass = new String(pw.getPassword());
        Thread t = new Thread(() -> {
            Session sess = new Session(s.info.baseUrl);
            String err = sess.login(user, pass);
            SwingUtilities.invokeLater(() -> {
                loginBtn.setEnabled(true);
                if (err == null) {
                    s.info.needLogin = false;
                    s.info.user = user;
                    setStatus("登录成功：" + user + "（浏览器免登录链接已就绪）");
                    appendLog("登录成功 " + s.info.baseUrl + " as " + user
                            + " sid=" + (sess.sid().length() > 8 ? sess.sid().substring(0, 8) + "…" : sess.sid()));
                    previewSvc = s;
                    connSid = sess.sid();
                    connectMedia(s.info.baseUrl);
                    onSelect();
                } else {
                    setStatus("登录失败：" + err);
                    appendLog("登录失败：" + err);
                }
            });
        }, "usbnas-login");
        t.setDaemon(true);
        t.start();
    }

    private volatile Svc previewSvc;

    // ------------------------------------------------------------------ 小工具

    private void setStatus(String s) {
        status.setText(s);
    }

    private void appendLog(String s) {
        log.append(s + NL);
        log.setCaretPosition(log.getDocument().getLength());
    }

    private static void copyToClipboard(String s) {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(s), null);
    }

    private static String describeSource(String src) {
        switch (src == null ? "" : src.toUpperCase(Locale.ROOT)) {
            case "DISCOVERY":
                return "UDP 广播应答";
            case "HISTORY":
                return "历史记录（未验证）";
            default:
                return "网段扫描 + 指纹确认";
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 应用数据目录：优先 %APPDATA%\USBNASLink，失败就退到当前目录 */
    private static File appDir() {
        String appdata = System.getenv("APPDATA");
        File d = appdata != null && !appdata.isEmpty()
                ? new File(appdata, "USBNASLink")
                : new File(System.getProperty("user.home", "."), ".usbnaslink");
        if (!d.isDirectory() && !d.mkdirs()) {
            d = new File(".").getAbsoluteFile();
        }
        return d;
    }

    /** 列表渲染器：两行，主行是名字 + 地址，副行是磁盘与来源 */
    private static final class SvcRenderer extends JPanel implements ListCellRenderer<Svc> {
        private final JLabel title = new JLabel();
        private final JLabel sub = new JLabel();
        private boolean hover;

        SvcRenderer() {
            setLayout(new BorderLayout(0, 2));
            setBorder(Theme.padding(7, 10, 7, 10));
            title.setFont(Theme.bold(13));
            sub.setFont(Theme.ui(11));
            add(title, BorderLayout.NORTH);
            add(sub, BorderLayout.SOUTH);
            // 网页的 .row:hover 是「悬停换底色」，Swing 默认没有这个反馈，补上
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    hover = true;
                }
            });
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                }
            });
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Svc> list, Svc v,
                                                      int idx, boolean isSel, boolean focus) {
            ServiceInfo i = v.info;
            title.setText(i.displayName());
            String url = i.baseUrl + (i.needLogin ? "  🔒" : "");
            String d = i.disksText == null ? "" : i.disksText;
            sub.setText("<html><span style='color:" + Theme.hex(Theme.BRAND_LINK) + "'>" + escape(url) + "</span>"
                    + (d.isEmpty() ? "" : " <span style='color:" + Theme.hex(Theme.FG_DIM) + "'>· " + escape(d) + "</span>")
                    + "</html>");
            // 选中/悬停/普通三档，与网页 rowAt 的取值一致
            setBackground(Theme.rowBg(isSel, isSel ? false : hover));
            title.setForeground(isSel ? Theme.FG_TITLE : Theme.FG_TEXT);
            sub.setForeground(Theme.FG_SUB);
            setOpaque(true);
            return this;
        }
    }

    // ------------------------------------------------------------------ 入口

    public static void main(String[] argv) {
        // 高 DPI 友好：不设的话 jpackage 出来的 exe 在 200% 缩放下会糊
        System.setProperty("sun.java2d.uiScale", "1.0");
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Throwable ignored) {
        }
        // 必须在 setLookAndFeel 之后：Theme.install() 灌的是 UIDefaults 覆盖值，
        // 反过来会被系统 LAF 再覆盖掉，界面就退回 Windows 灰。
        Theme.install();
        SwingUtilities.invokeLater(() -> {
            Main m = new Main();
            m.frame.setVisible(true);
        });
    }
}
