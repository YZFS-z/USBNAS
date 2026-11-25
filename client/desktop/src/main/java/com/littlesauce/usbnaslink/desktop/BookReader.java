package com.littlesauce.usbnaslink.desktop;

import com.littlesauce.usbnaslink.Book;
import com.littlesauce.usbnaslink.BookLib;
import com.littlesauce.usbnaslink.BookPdf;
import com.littlesauce.usbnaslink.BookText;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内嵌阅读面板：不跳浏览器，直接在客户端窗口里把书排版出来。
 *
 * <p>正文来源是 {@link BookText}（服务端把 docx/doc/epub/txt 都抽成纯文本），
 * 所以这里只有一件事要做——排版。三种呈现：
 * <ul>
 *   <li>纯文本正文：用一个不可编辑的 JTextPane 铺满，字号可调，行距放宽；</li>
 *   <li>PDF：服务端用 Android 自带的 {@code PdfRenderer} 把每一页渲成 JPEG，
 *       这里当一个图片阅读器翻页（{@link BookPdf}）。客户端仍然不带 PDF 引擎，
 *       安装包不增加一行依赖；</li>
 *   <li>失败：把服务端原话显示出来，不要自己编一句「打开失败」。</li>
 * </ul>
 *
 * <p>翻页不做分页，用滚动条直接看——纯文本分页在窗口大小变化时全是麻烦，
 * 而滚动是所有人都会用的东西。章节跳转靠左侧目录（按空行分段猜的），
 * 猜得不准也不影响上下滚。
 */
final class BookReader extends JPanel {

    private final JLabel titleLabel = new JLabel("没有打开的书");
    private final JLabel metaLabel = new JLabel("");
    private final JTextPane body = new JTextPane();
    private final JScrollPane bodyScroll;
    private final JButton zoomIn = Theme.ghost("A+");
    private final JButton zoomOut = Theme.ghost("A-");
    private final JButton openOuter = Theme.ghost("用浏览器打开");
    private final JProgressBar busy = new JProgressBar();
    private final CardLayout cards = new CardLayout();
    private final JPanel stack = new JPanel(cards);
    private final JPanel tocHost = new JPanel();

    private String baseUrl = "";
    private String cookie;
    private Book current;

    /** 字号，A+/A- 调的是这个 */
    private int fontSize = 16;

    // ---------------------------------------------------------------- PDF 翻页
    /** 当前这本书的总页数，0 表示不是 PDF 或还没问到 */
    private int pdfPages;
    /** 正在看第几页（0 基） */
    private int pdfPage;
    /** 页面图缓存。按 30 页限，一页 1240 宽约 200–400KB，30 页 ≈ 10MB */
    private final LinkedHashMap<Integer, BufferedImage> pdfCache =
            new LinkedHashMap<Integer, BufferedImage>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, BufferedImage> e) {
                    return size() > 30;
                }
            };
    private final JLabel pdfLabel = new JLabel("", SwingConstants.CENTER);
    private final JPanel pdfCard = new JPanel(new BorderLayout());
    private final JScrollPane pdfScroll;
    private final JButton pdfPrev = Theme.ghost("上一页");
    private final JButton pdfNext = Theme.ghost("下一页");
    private final JButton pdfJump = Theme.ghost("跳转");
    private final JLabel pdfInfo = Theme.caption("");
    private final JProgressBar pdfBusy = new JProgressBar();

    BookReader() {
        super(new BorderLayout());
        setBackground(Theme.BG_DEEP);

        titleLabel.setFont(Theme.bold(16));
        titleLabel.setForeground(Theme.FG_TITLE);
        metaLabel.setFont(Theme.ui(12));
        metaLabel.setForeground(Theme.FG_DIM);

        JPanel head = new JPanel(new BorderLayout(8, 2));
        head.setOpaque(true);
        head.setBackground(Theme.BG_CARD);
        head.setBorder(new EmptyBorder(8, 14, 8, 14));
        JPanel headText = new JPanel(new GridLayout(2, 1));
        headText.setOpaque(false);
        headText.add(titleLabel);
        headText.add(metaLabel);
        head.add(headText, BorderLayout.CENTER);

        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        tools.setOpaque(false);
        tools.add(zoomOut);
        tools.add(zoomIn);
        tools.add(openOuter);
        head.add(tools, BorderLayout.EAST);
        add(head, BorderLayout.NORTH);

        body.setEditable(false);
        body.setBackground(Theme.BG_DEEP);
        body.setForeground(Theme.FG_TEXT);
        body.setFont(Theme.ui(fontSize));
        body.setBorder(new EmptyBorder(18, 26, 28, 26));
        body.setCaretColor(Theme.FG_TEXT);
        // 正文永远左对齐、不禁断词：中文长段自动换行靠 JTextPane 的 wrap
        bodyScroll = Theme.scroll(body);
        bodyScroll.getVerticalScrollBar().setUnitIncrement(fontSize + 4);

        // 左侧目录：按行首形如「第X章」「Chapter N」「1.」的短行猜章节
        tocHost.setLayout(new BoxLayout(tocHost, BoxLayout.Y_AXIS));
        tocHost.setBackground(Theme.BG_CARD);
        tocHost.setBorder(new EmptyBorder(8, 6, 8, 6));
        JScrollPane tocScroll = Theme.scroll(tocHost);
        tocScroll.setPreferredSize(new Dimension(190, 100));

        JPanel content = new JPanel(new BorderLayout());
        content.setOpaque(false);
        content.add(tocScroll, BorderLayout.WEST);
        content.add(bodyScroll, BorderLayout.CENTER);

        JPanel hint = new JPanel(new BorderLayout());
        hint.setBackground(Theme.BG_DEEP);
        JLabel h = Theme.sub("选一本书，就会在这里直接排版出来（不跳浏览器）。");
        h.setBorder(new EmptyBorder(28, 26, 28, 26));
        hint.add(h, BorderLayout.NORTH);

        busy.setIndeterminate(true);
        busy.setVisible(false);
        busy.setBorderPainted(false);
        busy.setPreferredSize(new Dimension(100, 3));
        // 走 Theme 那一套（底色 BG_CARD、进度 BRAND），和播放页/主界面同一条蓝
        Theme.progress(busy);
        busy.setStringPainted(false);

        stack.add(hint, "hint");
        stack.add(content, "content");
        stack.add(busy, "busy");
        stack.add(pdfCard, "pdf");
        add(stack, BorderLayout.CENTER);
        cards.show(stack, "hint");

        // ------------------------------------------------------------ PDF 卡片
        // 服务端把每页渲成 JPEG，这里只当图片翻。翻页条固定在底部，
        // 页面本身放进可滚动的居中容器 —— A4 竖版在 900px 高的窗口里
        // 一屏放不下，硬缩到一屏会让字糊掉，宁可让用户滚。
        JPanel pdfHolder = new JPanel(new GridBagLayout());
        pdfHolder.setBackground(Theme.BG_CARD);
        pdfHolder.add(pdfLabel);
        pdfScroll = Theme.scroll(pdfHolder);
        pdfScroll.getVerticalScrollBar().setUnitIncrement(40);
        pdfCard.setBackground(Theme.BG_DEEP);
        pdfCard.add(pdfScroll, BorderLayout.CENTER);

        pdfBusy.setIndeterminate(true);
        pdfBusy.setVisible(false);
        pdfBusy.setBorderPainted(false);
        pdfBusy.setPreferredSize(new Dimension(100, 3));
        Theme.progress(pdfBusy);
        pdfBusy.setStringPainted(false);

        JPanel pdfBar = new JPanel(new BorderLayout(8, 0));
        pdfBar.setOpaque(true);
        pdfBar.setBackground(Theme.BG_CARD);
        pdfBar.setBorder(new EmptyBorder(6, 12, 6, 12));
        JPanel pdfBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        pdfBtns.setOpaque(false);
        pdfBtns.add(pdfPrev);
        pdfBtns.add(pdfNext);
        pdfBtns.add(pdfJump);
        pdfBtns.add(pdfBusy);
        pdfBar.add(pdfBtns, BorderLayout.WEST);
        pdfBar.add(pdfInfo, BorderLayout.EAST);
        pdfCard.add(pdfBar, BorderLayout.SOUTH);

        pdfPrev.addActionListener(e -> showPdfPage(pdfPage - 1));
        pdfNext.addActionListener(e -> showPdfPage(pdfPage + 1));
        pdfJump.addActionListener(e -> askJumpPage());
        // 键盘上的左右方向键也能翻，翻页器都这样
        InputMap pim = pdfCard.getInputMap(WHEN_IN_FOCUSED_WINDOW);
        pim.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "pdfPrev");
        pim.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "pdfNext");
        pdfCard.getActionMap().put("pdfPrev", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { showPdfPage(pdfPage - 1); }
        });
        pdfCard.getActionMap().put("pdfNext", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { showPdfPage(pdfPage + 1); }
        });

        zoomIn.addActionListener(e -> setFontSize(fontSize + 2));
        zoomOut.addActionListener(e -> setFontSize(fontSize - 2));
        openOuter.addActionListener(e -> {
            if (current != null) {
                PlayerWindow.openExternal(BookLib.readUrl(baseUrl, current.path));
            }
        });

        // Ctrl +/- 调字号，和浏览器习惯一致
        InputMap im = getInputMap(WHEN_IN_FOCUSED_WINDOW);
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "zoomIn");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "zoomOut");
        getActionMap().put("zoomIn", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { setFontSize(fontSize + 2); }
        });
        getActionMap().put("zoomOut", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { setFontSize(fontSize - 2); }
        });
    }

    void setTarget(String base, String sid) {
        this.baseUrl = base == null ? "" : base;
        this.cookie = sid;
    }

    /** 打开一本书。UI 线程调用即可，内部自己开线程读。 */
    void open(Book b) {
        if (b == null) return;
        current = b;
        titleLabel.setText(b.title == null || b.title.isEmpty() ? b.name : b.title);
        metaLabel.setText(b.folder() + "    ·    " + b.tag() + "    ·    " + com.littlesauce.usbnaslink.Music.humanSize(b.size));
        openOuter.setEnabled(true);
        pdfPages = 0;
        pdfPage = 0;
        pdfCache.clear();
        cards.show(stack, "busy");
        busy.setVisible(true);

        final String base = baseUrl;
        final String ck = cookie;
        final String path = b.path;

        // 扩展名就能判断的事就先判断：PDF 走图片翻页，不必先去问 /_books/text
        // 再让服务端回一句「这个请用 PDF 视图打开」——多一个来回，还会让
        // 用户白等一次 spinner。
        if (isPdf(b)) {
            new Thread(() -> {
                BookPdf meta = BookPdf.meta(base, ck, path, 20000);
                SwingUtilities.invokeLater(() -> {
                    busy.setVisible(false);
                    if (meta.error != null) {
                        showError("这本书打不开：" + meta.error, false);
                        return;
                    }
                    pdfPages = meta.pages;
                    metaLabel.setText("PDF    ·    " + pdfPages + " 页    ·    " + b.folder());
                    cards.show(stack, "pdf");
                    showPdfPage(0);
                });
            }, "usbnas-pdfmeta").start();
            return;
        }

        new Thread(() -> {
            BookText bt = BookText.fetch(base, ck, path, 20000);
            SwingUtilities.invokeLater(() -> show(bt));
        }, "usbnas-read").start();
    }

    private static boolean isPdf(Book b) {
        if (b == null) return false;
        String n = b.name != null ? b.name : b.path;
        return n != null && n.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf");
    }

    /**
     * 渲（或从缓存取）第 n 页并显示。越界自动钳住，翻到头/尾只是按钮变灰。
     * 每次都新开线程：服务端渲一页 100–300ms，放 EDT 上会卡住整个窗口。
     */
    private void showPdfPage(int n) {
        if (pdfPages <= 0) return;
        if (n < 0) n = 0;
        if (n >= pdfPages) n = pdfPages - 1;
        pdfPage = n;
        pdfInfo.setText((n + 1) + " / " + pdfPages);
        pdfPrev.setEnabled(n > 0);
        pdfNext.setEnabled(n < pdfPages - 1);

        BufferedImage cached = pdfCache.get(n);
        if (cached != null) {
            pdfLabel.setIcon(new ImageIcon(cached));
            return;
        }

        final int page = n;
        final String base = baseUrl;
        final String ck = cookie;
        final Book book = current;
        if (book == null) return;
        pdfBusy.setVisible(true);
        pdfLabel.setIcon(null);
        pdfLabel.setText("正在渲染第 " + (page + 1) + " 页…");
        pdfLabel.setForeground(Theme.FG_DIM);
        new Thread(() -> {
            // 宽度按窗口给：窗口越宽画得越清楚，但服务端钳到 1600 上限。
            int w = Math.max(600, Math.min(1600, getWidth() - 120));
            byte[] data = BookPdf.fetchPage(base, ck, book.path, page, w, 30000);
            final String err = BookPdf.lastError();
            BufferedImage img = null;
            if (data != null) {
                try {
                    img = ImageIO.read(new ByteArrayInputStream(data));
                } catch (Throwable ignored) {
                }
            }
            final BufferedImage got = img;
            SwingUtilities.invokeLater(() -> {
                // 用户可能已经翻走了，慢回来的旧页不要盖上去
                if (pdfPage != page) return;
                pdfBusy.setVisible(false);
                if (got == null) {
                    pdfLabel.setIcon(null);
                    pdfLabel.setText("<html><div style='width:420px;text-align:center'>这一页没取到："
                            + escape(err == null || err.isEmpty() ? "没有数据" : err)
                            + "<br>可以点右下「用浏览器打开」先看着。</div></html>");
                    pdfLabel.setForeground(Theme.ERR);
                    return;
                }
                pdfCache.put(page, got);
                pdfLabel.setText("");
                pdfLabel.setIcon(new ImageIcon(got));
                pdfScroll.getVerticalScrollBar().setValue(0);
            });
        }, "usbnas-pdfpage").start();
    }

    /** 跳页：问一个页码（1 基），超出范围就钳到两端 */
    private void askJumpPage() {
        if (pdfPages <= 0) return;
        JTextField f = Theme.field(6);
        f.setText(String.valueOf(pdfPage + 1));
        JPanel p = Theme.pane();
        p.setLayout(new GridLayout(0, 1, 0, 6));
        p.setBorder(Theme.padding(8, 8, 0, 8));
        p.add(Theme.label("跳到第几页（共 " + pdfPages + " 页）"));
        p.add(f);
        if (!Theme.confirm(SwingUtilities.getWindowAncestor(this), "跳转", null, p, "跳转", "取消", false)) return;
        int n;
        try {
            n = Integer.parseInt(f.getText().trim());
        } catch (NumberFormatException e) {
            return;
        }
        showPdfPage(n - 1);
    }

    private void show(BookText bt) {
        busy.setVisible(false);
        if (!bt.ok) {
            // PDF 正常路径已经在 open() 里被截走了（走图片翻页），
            // 能走到这里只说明服务端版本老、没有 /_books/pdf。
            if (bt.pdf) {
                showError("这本书是 PDF，但服务端没有 PDF 渲染端点（把服务端升级到最新版就能在这里直接翻页）。"
                        + "现在点右上的「用浏览器打开」也可以看。", true);
                return;
            }
            showError(bt.error == null ? "打开失败" : bt.error, false);
            return;
        }
        metaLabel.setText(bt.summary() + "    ·    " + (current == null ? "" : current.folder()));
        body.setFont(Theme.ui(fontSize));
        body.setText(bt.text);
        body.setCaretPosition(0);
        buildToc(bt.text);
        cards.show(stack, "content");
    }

    /** 统一的失败页：一句人话 + （PDF）一个「用浏览器打开」的出路 */
    private void showError(String msg, boolean allowOuter) {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBackground(Theme.BG_DEEP);
        JLabel l = Theme.sub("<html><body style='width:520px'>" + escape(msg) + "</body></html>");
        l.setBorder(new EmptyBorder(28, 26, 10, 26));
        p.add(l, BorderLayout.NORTH);
        if (allowOuter) {
            JButton b = Theme.primary("在浏览器里打开 PDF");
            b.addActionListener(e -> {
                if (current != null) PlayerWindow.openExternal(BookLib.readUrl(baseUrl, current.path));
            });
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 26, 0));
            row.setOpaque(false);
            row.add(b);
            p.add(row, BorderLayout.CENTER);
        }
        // 每次错误都 add 会积累，旧的要清掉
        for (Component c : stack.getComponents()) {
            if (c != p && "err".equals(nameOf(c))) stack.remove(c);
        }
        p.putClientProperty("card", "err");
        stack.add(p, "err");
        cards.show(stack, "err");
    }

    private static String nameOf(Component c) {
        // CardLayout 的名字没开放读接口，用 client property 记一下
        Object v = (c instanceof JComponent) ? ((JComponent) c).getClientProperty("card") : null;
        return v == null ? "" : String.valueOf(v);
    }
    private void setFontSize(int px) {
        fontSize = Math.max(11, Math.min(32, px));
        body.setFont(Theme.ui(fontSize));
        bodyScroll.getVerticalScrollBar().setUnitIncrement(fontSize + 4);
    }

    /**
     * 猜目录。
     *
     * 规则很土但够用：找长度 &lt; 40、且以「第..章/节」「Chapter」「数字.」开头的行。
     * 猜不到就不显示目录（返回空，整个左栏留白），比列出一堆乱七八糟的强。
     */
    private void buildToc(String text) {
        tocHost.removeAll();
        if (text == null || text.isEmpty()) {
            tocHost.revalidate();
            return;
        }
        JLabel head = Theme.caption("目录");
        head.setBorder(new EmptyBorder(0, 6, 6, 6));
        tocHost.add(head);

        int added = 0;
        int pos = 0;
        int n = text.length();
        while (pos < n && added < 400) {
            int nl = text.indexOf('\n', pos);
            if (nl < 0) nl = n;
            String line = text.substring(pos, nl).trim();
            if (line.length() > 0 && line.length() < 40 && looksLikeHeading(line)) {
                final int caret = pos;
                JLabel item = new JLabel(line);
                item.setFont(Theme.ui(12));
                item.setForeground(Theme.FG_SUB);
                item.setBorder(new EmptyBorder(3, 6, 3, 6));
                item.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                item.addMouseListener(new MouseAdapter() {
                    public void mouseClicked(MouseEvent e) {
                        body.setCaretPosition(Math.min(caret, body.getDocument().getLength()));
                        body.requestFocusInWindow();
                    }
                    public void mouseEntered(MouseEvent e) { item.setForeground(Theme.BRAND_LINK); }
                    public void mouseExited(MouseEvent e) { item.setForeground(Theme.FG_SUB); }
                });
                tocHost.add(item);
                added++;
            }
            pos = nl + 1;
        }
        if (added == 0) {
            JLabel none = Theme.caption("（这本书没有明显的章节标记）");
            none.setBorder(new EmptyBorder(0, 6, 0, 6));
            tocHost.add(none);
        }
        tocHost.add(Box.createVerticalGlue());
        tocHost.revalidate();
        tocHost.repaint();
    }

    private static boolean looksLikeHeading(String s) {
        if (s.startsWith("第") && (s.contains("章") || s.contains("节") || s.contains("卷"))) return true;
        if (s.length() > 8 && s.regionMatches(true, 0, "chapter", 0, 7)) return true;
        // 「1. 标题」「12、标题」这种，要求后面还有点内容
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (i > 0 && i < s.length() - 2) {
            char c = s.charAt(i);
            if (c == '.' || c == '、' || c == '．') return true;
        }
        return false;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 当前打开的书，给外部判断「要不要新开窗口」用 */
    Book currentBook() {
        return current;
    }
}
