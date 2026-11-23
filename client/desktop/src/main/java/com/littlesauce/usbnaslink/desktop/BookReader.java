package com.littlesauce.usbnaslink.desktop;

import com.littlesauce.usbnaslink.Book;
import com.littlesauce.usbnaslink.BookLib;
import com.littlesauce.usbnaslink.BookText;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * 内嵌阅读面板：不跳浏览器，直接在客户端窗口里把书排版出来。
 *
 * <p>正文来源是 {@link BookText}（服务端把 docx/doc/epub/txt 都抽成纯文本），
 * 所以这里只有一件事要做——排版。三种呈现：
 * <ul>
 *   <li>纯文本正文：用一个不可编辑的 JTextPane 铺满，字号可调，行距放宽；</li>
 *   <li>PDF：客户端没有 PDF 渲染器，给一个「用浏览器打开」的按钮并把地址
 *       显示出来（这是唯一还会跳外部应用的地方，因为自绘 PDF 需要引
 *       PDFBox/PDFRenderer，与「不引第三方库」的约定冲突）；</li>
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

        stack.add(hint, "hint");
        stack.add(content, "content");
        stack.add(busy, "busy");
        add(stack, BorderLayout.CENTER);
        cards.show(stack, "hint");

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
        cards.show(stack, "busy");
        busy.setVisible(true);

        final String base = baseUrl;
        final String ck = cookie;
        new Thread(() -> {
            BookText bt = BookText.fetch(base, ck, b.path, 20000);
            SwingUtilities.invokeLater(() -> show(bt));
        }, "usbnas-read").start();
    }

    private void show(BookText bt) {
        busy.setVisible(false);
        if (!bt.ok) {
            // PDF 是预期内的「打不开」，措辞要给出路而不是报错
            String msg = bt.pdf
                    ? "这本书是 PDF。客户端没有内嵌 PDF 渲染器（引 PDFBox 会让安装包大一倍），点右上「用浏览器打开」可以看。"
                    : (bt.error == null ? "打开失败" : bt.error);
            JPanel p = new JPanel(new BorderLayout(0, 10));
            p.setBackground(Theme.BG_DEEP);
            JLabel l = Theme.sub("<html><body style='width:520px'>" + escape(msg) + "</body></html>");
            l.setBorder(new EmptyBorder(28, 26, 10, 26));
            p.add(l, BorderLayout.NORTH);
            if (bt.pdf) {
                JButton b = Theme.primary("在浏览器里打开 PDF");
                b.addActionListener(e -> {
                    if (current != null) PlayerWindow.openExternal(BookLib.readUrl(baseUrl, current.path));
                });
                JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 26, 0));
                row.setOpaque(false);
                row.add(b);
                p.add(row, BorderLayout.CENTER);
            }
            stack.add(p, "err");
            ((JComponent) p).putClientProperty("card", "err");
            cards.show(stack, "err");
            // 每次错误都 add 会积累，旧的要清掉
            for (Component c : stack.getComponents()) {
                if (c != p && "err".equals(nameOf(c))) stack.remove(c);
            }
            return;
        }
        metaLabel.setText(bt.summary() + "    ·    " + (current == null ? "" : current.folder()));
        body.setFont(Theme.ui(fontSize));
        body.setText(bt.text);
        body.setCaretPosition(0);
        buildToc(bt.text);
        cards.show(stack, "content");
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
