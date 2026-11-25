package com.littlesauce.usbnaslink.desktop;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.plaf.ColorUIResource;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;

/**
 * 桌面端视觉基线 —— 唯一的调色板来源。
 *
 * <p>取值**逐一照抄**服务端网页的 {@code HttpServer.CSS}（深色主题那一套），
 * 这样客户端和网页放在一起看是同一个产品的两面，而不是两套皮肤。
 * 改这里之前先去 {@code USBNAS/app/src/main/java/com/littlesauce/usbnas/HttpServer.java}
 * 的 {@code static final String CSS =} 段落确认目标色值，别凭感觉发明新颜色。
 *
 * <pre>
 *   网页                          桌面端                     用途
 *   html/body  background #0e1116  BG_DEEP    窗口底、WebView 外围
 *   .search input / .root #171b22  BG_CARD    卡片、输入框、列表行底色
 *   .row:hover            #1b222c  BG_HOVER   悬停
 *   .crumbs / .adm-sect   #232a34  LINE       1px 描边
 *   header  h1             #e6e9ef  FG_TITLE   标题、强调正文
 *   body                   #e6e9ef  FG_TEXT    正文
 *   .search button/.root  #9aa4b2  FG_SUB     次要文字
 *   .sub / .stats          #7d8590  FG_DIM     说明文字
 *   .rp / .count           #6b7480  FG_MUTE    路径、计数这类最弱的字
 *   #2f80ed                        BRAND      主按钮、进度条、选中
 *   #5aa9ff                        BRAND_LINK 链接、图标高亮
 *   .adm-note              #8fe0ac  OK         成功提示
 *   #ff8a94                        ERR        错误提示
 * </pre>
 *
 * <p>用法：入口处先 {@link #install()}，然后尽量用 {@link #button}、{@link #card}
 * 这些工厂方法建控件 —— 不要在各窗口里零散写 {@code new Color(0x..)}，
 * 那样过两周又会冒出第四套配色。
 */
final class Theme {

    private Theme() {
    }

    // ---------------------------------------------------------------- 调色板

    /**
     * 当前是不是日间。**唯一开关**，改它就等于改整套色板。
     *
     * <p>为什么不直接换 LookAndFeel 或做两套 Jar：界面全是手搭的 Swing 组件，
     * 换 LAF 只能改到原生控件那一半，另一半（自绘按钮、卡片、行背景）会留在旧色上。
     * 这里的做法是「同名静态字段 + setDay() 重新赋值」，因为所有调用点都写成
     * {@code Theme.BG_CARD}，取的是**当前**字段值 —— 所以调用点一行都不用改。
     *
     * <p>注意取值时机：Swing 组件在 {@code new} 的那一刻就把颜色拷走了，
     * 所以切主题之后必须重建窗口（{@link #applyTo} 只负责列表/文本区这类
     * 「颜色留在渲染器里」的控件）。这正是网页端 {@code recreate()} 的同一思路。
     */
    private static volatile boolean day = false;

    static boolean isDay() {
        return day;
    }

    // 网页端三套主题的色值：dark / light / ink。桌面只做 dark 和 light 两套，
    // ink（水墨屏）是手机专用的低对比灰阶，桌面屏幕上看不出效果，不做。
    static Color BG_DEEP;
    static Color BG_CARD;
    static Color BG_HOVER;
    static Color BG_SEL;
    static Color LINE;
    static Color LINE_SOFT;

    static Color FG_TITLE;
    static Color FG_TEXT;
    static Color FG_SUB;
    static Color FG_DIM;
    static Color FG_MUTE;

    static Color BRAND;
    static Color BRAND_DIM;
    static Color BRAND_LINK;
    static Color LINK_SOFT;

    static Color OK;
    static Color OK_BG;
    static Color OK_LINE;
    static Color ERR;
    static Color WARN;

    static {
        applyPalette(false);
    }

    /**
     * 把调色板刷成指定模式。只改静态字段，不碰任何已存在的组件 ——
     * 调用方负责重建界面，并重新 {@link #install()} 一次让 UIManager 跟上。
     */
    static void applyPalette(boolean dayMode) {
        day = dayMode;
        if (!dayMode) {
            BG_DEEP = new Color(0x0e, 0x11, 0x16);
            BG_CARD = new Color(0x17, 0x1b, 0x22);
            BG_HOVER = new Color(0x1b, 0x22, 0x2c);
            BG_SEL = new Color(0x1d, 0x2c, 0x3d);
            LINE = new Color(0x23, 0x2a, 0x34);
            LINE_SOFT = new Color(0x2a, 0x32, 0x3d);

            FG_TITLE = new Color(0xe6, 0xe9, 0xef);
            FG_TEXT = new Color(0xe6, 0xe9, 0xef);
            FG_SUB = new Color(0x9a, 0xa4, 0xb2);
            FG_DIM = new Color(0x7d, 0x85, 0x90);
            FG_MUTE = new Color(0x6b, 0x74, 0x80);

            BRAND = new Color(0x2f, 0x80, 0xed);
            BRAND_DIM = new Color(0x24, 0x63, 0xb8);
            BRAND_LINK = new Color(0x5a, 0xa9, 0xff);
            LINK_SOFT = new Color(0x8e, 0xc5, 0xff);

            OK = new Color(0x8f, 0xe0, 0xac);
            OK_BG = new Color(0x12, 0x30, 0x1f);
            OK_LINE = new Color(0x1f, 0x52, 0x36);
            ERR = new Color(0xff, 0x8a, 0x94);
            WARN = new Color(0xff, 0xc9, 0x6b);
        } else {
            // 日间取网页 body.light 那一套：底 #f2f4f7 / 卡 #ffffff / 线 #e3e7ec /
            // 主字 #1b1f26 / 次字 #57606a。OK/ERR/WARN 要压暗，
            // 深色主题下用的浅绿浅粉在白底上会看不清。
            BG_DEEP = new Color(0xf2, 0xf4, 0xf7);
            BG_CARD = new Color(0xff, 0xff, 0xff);
            BG_HOVER = new Color(0xf6, 0xf8, 0xfa);
            BG_SEL = new Color(0xe8, 0xf0, 0xfd);
            LINE = new Color(0xe3, 0xe7, 0xec);
            LINE_SOFT = new Color(0xd5, 0xdb, 0xe3);

            FG_TITLE = new Color(0x1b, 0x1f, 0x26);
            FG_TEXT = new Color(0x1b, 0x1f, 0x26);
            FG_SUB = new Color(0x57, 0x60, 0x6a);
            FG_DIM = new Color(0x6b, 0x74, 0x80);
            FG_MUTE = new Color(0x8b, 0x94, 0x9f);

            BRAND = new Color(0x2f, 0x80, 0xed);
            BRAND_DIM = new Color(0x1f, 0x66, 0xc8);
            BRAND_LINK = new Color(0x16, 0x57, 0xb8);
            LINK_SOFT = new Color(0x2f, 0x80, 0xed);

            OK = new Color(0x1c, 0x7a, 0x48);
            OK_BG = new Color(0xe8, 0xf6, 0xee);
            OK_LINE = new Color(0xc3, 0xe6, 0xd2);
            ERR = new Color(0xc0, 0x39, 0x2b);
            WARN = new Color(0xa8, 0x6a, 0x00);
        }
    }

    // ---------------------------------------------------------------- 字体

    /**
     * 网页的字体栈是 {@code -apple-system,'Segoe UI',Roboto,...,'PingFang SC','Microsoft YaHei'}，
     * 浏览器会**逐字回退** —— 拉丁字母用 Segoe UI，汉字自动落到微软雅黑。
     *
     * <p>**Swing 不会做这件事**：`new Font("Segoe UI", ...)` 里没有汉字字形，
     * 界面上的中文就全变成方框或乱码。所以这里挑一个「本身就含汉字」的族：
     * {@code Microsoft YaHei UI}（Win7+ 自带，中文和拉丁都好看），
     * 老系统退回 {@code Microsoft YaHei}，再退 {@code Dialog}（逻辑字体，必有汉字）。
     *
     * <p>{@link #FONT_STACK} 仍然给内联 HTML（{@code JEditorPane} / {@code JLabel} 的
     * {@code <html>}）用 —— 那条路径走的是 CSS 引擎，能正确逐字回退。
     */
    static final String FONT_STACK = "'Microsoft YaHei UI','Microsoft YaHei',Segoe UI,sans-serif";

    private static final String UI_FAMILY = pickFamily(
            "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", "Noto Sans CJK SC", "Dialog");

    /** 第一个「族名真的存在」的候选；全都没有就用逻辑字体 Dialog */
    private static String pickFamily(String... candidates) {
        java.util.Set<String> have = new java.util.HashSet<>();
        for (String n : java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames()) {
            have.add(n);
        }
        for (String n : candidates) {
            if (have.contains(n)) return n;
        }
        return Font.DIALOG;
    }

    /** 当前实际用于界面的族名（排障用：`Theme.ui(13).getFamily()` 应等于它） */
    static String uiFamily() {
        return UI_FAMILY;
    }

    static Font font(int size, int style) {
        return new Font(UI_FAMILY, style, size);
    }

    static Font ui(int size) {
        return font(size, Font.PLAIN);
    }

    static Font bold(int size) {
        return font(size, Font.BOLD);
    }

    static Font mono(int size) {
        return new Font(Font.MONOSPACED, Font.PLAIN, size);
    }

    // ---------------------------------------------------------------- 边框

    static Border padding(int top, int left, int bottom, int right) {
        return BorderFactory.createEmptyBorder(top, left, bottom, right);
    }

    /** 给 JEditorPane 里的内联 HTML 用：{@code Theme.hex(Theme.FG_SUB)} → {@code #9aa4b2} */
    static String hex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** 网页里 {@code border:1px solid #232a34} 的等价物 */
    static Border cardBorder() {
        return BorderFactory.createLineBorder(LINE, 1, true);
    }

    static Border cardBorder(int thickness) {
        return BorderFactory.createLineBorder(LINE, thickness, true);
    }

    // ---------------------------------------------------------------- 控件工厂

    /** 主按钮：网页 {@code .search button} 那枚蓝底白字 */
    static JButton primary(String text) {
        return FlatButtonUI.primary(text);
    }

    /** 次按钮：网页里描边那一类 */
    static JButton ghost(String text) {
        return FlatButtonUI.ghost(text);
    }

    /** 实体控件按钮：卡片实底 + 亮描边 + 主文字色，播放器那排键用它 */
    static JButton control(String text) {
        return FlatButtonUI.control(text);
    }

    static JTextField field(int cols) {
        JTextField f = new JTextField(cols);
        styleInput(f);
        return f;
    }

    static JPasswordField password(int cols) {
        JPasswordField f = new JPasswordField(cols);
        styleInput(f);
        return f;
    }

    static void styleInput(JTextField f) {
        f.setBackground(BG_CARD);
        f.setForeground(FG_TEXT);
        f.setCaretColor(FG_TITLE);
        f.setSelectionColor(BRAND);
        f.setSelectedTextColor(Color.WHITE);
        f.setBorder(BorderFactory.createCompoundBorder(
                cardBorder(), padding(6, 9, 6, 9)));
        f.setFont(ui(13));
    }

    static <T> JComboBox<T> combo(T[] items) {
        JComboBox<T> c = new JComboBox<>(items);
        c.setBackground(BG_CARD);
        c.setForeground(FG_TEXT);
        c.setFont(ui(13));
        c.setFocusable(false);
        c.setBorder(cardBorder());
        return c;
    }

    static JCheckBox check(String text, boolean on) {
        return FlatCheckUI.make(text, on);
    }

    /** 网页风格的页签（底部蓝条指示，没有凸起卡片） */
    static JTabbedPane tabs() {
        JTabbedPane t = new JTabbedPane();
        t.setUI(new FlatTabbedPaneUI());
        t.setBackground(BG_DEEP);
        t.setForeground(FG_SUB);
        t.setFont(ui(13));
        t.setOpaque(true);
        return t;
    }

    static JLabel title(String text) {
        JLabel l = new JLabel(text);
        l.setFont(bold(22));
        l.setForeground(FG_TITLE);
        return l;
    }

    static JLabel sub(String text) {
        JLabel l = new JLabel(text);
        l.setFont(ui(13));
        l.setForeground(FG_DIM);
        return l;
    }

    static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(ui(13));
        l.setForeground(FG_SUB);
        return l;
    }

    static JLabel caption(String text) {
        JLabel l = new JLabel(text);
        l.setFont(ui(12));
        l.setForeground(FG_MUTE);
        return l;
    }

    /** 卡片容器：网页 {@code .root} / {@code .adm-sect} 那种 12~14px 圆角描边块 */
    static JPanel card() {
        JPanel p = new JPanel();
        p.setOpaque(true);
        p.setBackground(BG_CARD);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE, 1, true), padding(12, 14, 12, 14)));
        return p;
    }

    static JPanel pane() {
        JPanel p = new JPanel();
        p.setOpaque(true);
        p.setBackground(BG_DEEP);
        return p;
    }

    static JScrollPane scroll(JComponent inner) {
        JScrollPane sp = new JScrollPane(inner);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getViewport().setBackground(BG_DEEP);
        sp.setBackground(BG_DEEP);
        JScrollBar vs = sp.getVerticalScrollBar();
        vs.setBackground(BG_DEEP);
        vs.setUnitIncrement(18);
        return sp;
    }

    static void progress(JProgressBar bar) {
        bar.setBackground(BG_CARD);
        bar.setForeground(BRAND);
        bar.setBorderPainted(false);
        bar.setFont(ui(11));
    }

    // ---------------------------------------------------------------- 全局装填

    /**
     * 一次性把 UIManager 的默认值改成深色基线。
     *
     * <p>**必须由入口在创建任何窗口之前调用**，而且要在
     * {@code UIManager.setLookAndFeel(...)} **之后** —— 先设 LAF 会把这里的值全覆盖掉。
     *
     * <p>只覆盖默认值、不整体换 LAF：自绘的 {@code FlatButtonUI} /
     * {@code FlatCheckUI} 负责按钮和勾选框，剩下的原生控件（滚动条、表格、下拉箭头）
     * 靠这里的 UIResource 统一上色。这种做法在 Windows / macOS / Linux 三种 LAF
     * 下都不会出现「一半深一半浅」。
     */
    static void install() {
        Font f = ui(13);

        // 深浅切换会重跑 install()：色彩键全清一遍，否则上一次留下的浅灰描边、
        // 选中色会粘在原生控件上（UIManager 只覆盖、不会自己失效）。
        for (Object k : new java.util.ArrayList<>(UIManager.getDefaults().keySet())) {
            if (!(k instanceof String)) continue;
            String key = (String) k;
            if (!key.endsWith(".background") && !key.endsWith(".foreground")
                    && !key.endsWith("Color") && !key.equals("control")
                    && !key.equals("info") && !key.equals("text")
                    && !key.equals("menuText") && !key.equals("nimbusBase")
                    && !key.equals("activeCaption") && !key.equals("activeCaptionText")) {
                continue;
            }
            Object v = UIManager.getDefaults().get(key);
            if (v instanceof ColorUIResource) UIManager.getDefaults().remove(key);
        }

        UIManager.put("Panel.background", new ColorUIResource(BG_DEEP));
        UIManager.put("Panel.foreground", new ColorUIResource(FG_TEXT));

        UIManager.put("Label.foreground", new ColorUIResource(FG_TEXT));
        UIManager.put("Label.font", f);
        UIManager.put("Label.disabledForeground", new ColorUIResource(FG_MUTE));

        UIManager.put("OptionPane.background", new ColorUIResource(BG_CARD));
        UIManager.put("OptionPane.messageForeground", new ColorUIResource(FG_TEXT));
        UIManager.put("OptionPane.messageFont", f);
        UIManager.put("OptionPane.buttonFont", f);

        UIManager.put("TextField.background", new ColorUIResource(BG_CARD));
        UIManager.put("TextField.foreground", new ColorUIResource(FG_TEXT));
        UIManager.put("TextField.caretForeground", new ColorUIResource(FG_TITLE));
        UIManager.put("TextField.selectionBackground", new ColorUIResource(BRAND));
        UIManager.put("TextField.selectionForeground", new ColorUIResource(Color.WHITE));
        UIManager.put("TextField.inactiveBackground", new ColorUIResource(BG_CARD));

        UIManager.put("PasswordField.background", new ColorUIResource(BG_CARD));
        UIManager.put("PasswordField.foreground", new ColorUIResource(FG_TEXT));
        UIManager.put("PasswordField.caretForeground", new ColorUIResource(FG_TITLE));

        UIManager.put("TextArea.background", new ColorUIResource(BG_CARD));
        UIManager.put("TextArea.foreground", new ColorUIResource(FG_TEXT));
        UIManager.put("TextArea.caretForeground", new ColorUIResource(FG_TITLE));
        UIManager.put("TextArea.selectionBackground", new ColorUIResource(BRAND));

        UIManager.put("EditorPane.background", new ColorUIResource(BG_DEEP));
        UIManager.put("EditorPane.foreground", new ColorUIResource(FG_TEXT));

        UIManager.put("List.background", new ColorUIResource(BG_DEEP));
        UIManager.put("List.foreground", new ColorUIResource(FG_TEXT));
        UIManager.put("List.selectionBackground", new ColorUIResource(BG_SEL));
        UIManager.put("List.selectionForeground", new ColorUIResource(FG_TITLE));
        UIManager.put("List.focusCellHighlightBorder", BorderFactory.createEmptyBorder());

        UIManager.put("ComboBox.background", new ColorUIResource(BG_CARD));
        UIManager.put("ComboBox.foreground", new ColorUIResource(FG_TEXT));
        UIManager.put("ComboBox.selectionBackground", new ColorUIResource(BG_SEL));
        UIManager.put("ComboBox.selectionForeground", new ColorUIResource(FG_TITLE));
        UIManager.put("ComboBox.buttonBackground", new ColorUIResource(BG_CARD));
        UIManager.put("ComboBox.buttonDarkShadow", new ColorUIResource(LINE));
        UIManager.put("ComboBox.buttonHighlight", new ColorUIResource(LINE_SOFT));

        UIManager.put("ScrollPane.background", new ColorUIResource(BG_DEEP));
        UIManager.put("Viewport.background", new ColorUIResource(BG_DEEP));
        UIManager.put("ScrollBar.background", new ColorUIResource(BG_DEEP));
        UIManager.put("ScrollBar.thumb", new ColorUIResource(LINE_SOFT));
        UIManager.put("ScrollBar.thumbDarkShadow", new ColorUIResource(BG_DEEP));
        UIManager.put("ScrollBar.thumbHighlight", new ColorUIResource(LINE_SOFT));
        UIManager.put("ScrollBar.thumbShadow", new ColorUIResource(LINE));
        UIManager.put("ScrollBar.track", new ColorUIResource(BG_DEEP));
        UIManager.put("ScrollBar.trackHighlight", new ColorUIResource(BG_DEEP));

        UIManager.put("ProgressBar.background", new ColorUIResource(BG_CARD));
        UIManager.put("ProgressBar.foreground", new ColorUIResource(BRAND));
        UIManager.put("ProgressBar.selectionBackground", new ColorUIResource(FG_TEXT));
        UIManager.put("ProgressBar.selectionForeground", new ColorUIResource(BG_DEEP));

        UIManager.put("TabbedPane.background", new ColorUIResource(BG_DEEP));
        UIManager.put("TabbedPane.foreground", new ColorUIResource(FG_SUB));
        UIManager.put("TabbedPane.selected", new ColorUIResource(BG_CARD));
        UIManager.put("TabbedPane.selectedForeground", new ColorUIResource(FG_TITLE));
        UIManager.put("TabbedPane.contentAreaColor", new ColorUIResource(BG_DEEP));
        UIManager.put("TabbedPane.font", ui(13));
        UIManager.put("TabbedPane.tabAreaBackground", new ColorUIResource(BG_DEEP));
        UIManager.put("TabbedPane.highlight", new ColorUIResource(LINE));
        UIManager.put("TabbedPane.darkShadow", new ColorUIResource(LINE));
        UIManager.put("TabbedPane.light", new ColorUIResource(LINE));
        UIManager.put("TabbedPane.focus", new ColorUIResource(BG_DEEP));
        UIManager.put("TabbedPane.borderHightlightColor", new ColorUIResource(LINE));

        UIManager.put("SplitPane.background", new ColorUIResource(BG_DEEP));
        UIManager.put("SplitPane.dividerSize", 6);
        UIManager.put("SplitPaneDivider.draggingColor", new ColorUIResource(BRAND));

        UIManager.put("ToolTip.background", new ColorUIResource(BG_CARD));
        UIManager.put("ToolTip.foreground", new ColorUIResource(FG_TEXT));
        UIManager.put("ToolTip.font", ui(12));

        UIManager.put("Table.background", new ColorUIResource(BG_DEEP));
        UIManager.put("Table.foreground", new ColorUIResource(FG_TEXT));
        UIManager.put("Table.selectionBackground", new ColorUIResource(BG_SEL));
        UIManager.put("Table.selectionForeground", new ColorUIResource(FG_TITLE));
        UIManager.put("Table.gridColor", new ColorUIResource(LINE));

        UIManager.put("Separator.foreground", new ColorUIResource(LINE));

        // 列表/下拉这些原生渲染器要用「控件当前背景色」来算对比，这里给个底
        UIManager.put("control", new ColorUIResource(BG_CARD));
        UIManager.put("info", new ColorUIResource(BG_CARD));
        UIManager.put("nimbusBase", new ColorUIResource(BG_CARD));
        UIManager.put("text", new ColorUIResource(FG_TEXT));
        UIManager.put("menuText", new ColorUIResource(FG_TEXT));
        UIManager.put("activeCaption", new ColorUIResource(BG_CARD));
        UIManager.put("activeCaptionText", new ColorUIResource(FG_TITLE));
    }

    /**
     * 切到指定模式并重装 UIManager。**只改色、不改已存在的窗口** ——
     * 调用方负责重建界面（{@code new Main()} 或 {@code SwingUtilities.updateComponentTreeUI}）。
     */
    static void setDay(boolean dayMode) {
        applyPalette(dayMode);
        install();
    }

    /** 当前列表行是否被选中时用的底色，供各处自定义渲染器复用 */
    static Color rowBg(boolean selected, boolean hover) {
        if (selected) return BG_SEL;
        return hover ? BG_HOVER : BG_DEEP;
    }

    static void applyTo(JList<?> l) {
        l.setBackground(BG_DEEP);
        l.setForeground(FG_TEXT);
        l.setSelectionBackground(BG_SEL);
        l.setSelectionForeground(FG_TITLE);
        l.setBorder(BorderFactory.createEmptyBorder());
    }

    static void applyTo(JScrollPane sp) {
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getViewport().setBackground(BG_DEEP);
        sp.setBackground(BG_DEEP);
    }

    static void applyTo(JTabbedPane t) {
        t.setBackground(BG_DEEP);
        t.setForeground(FG_SUB);
        t.setFont(ui(13));
    }

    /**
     * 不重建窗口的前提下，尽量把颜色灌回已存在的组件。
     *
     * <p>能刷的只有「颜色是存在组件属性里、不是在构造时算死」的那些：
     * 列表、文本区、滚动面板、下拉、标签、面板底色。自绘按钮
     * （{@link FlatButtonUI}）每次 {@code paint} 都现读 {@code Theme} 字段，
     * 所以它们连 {@code repaint()} 都不用，重建树即可。
     *
     * <p>真正做不到的是「布局代码里写死 {@code new Color(...)} 或
     * {@code setBackground(当前色)} 的那部分」—— 那些只能靠重建窗口。
     */
    static void applyTo(java.awt.Component root) {
        if (root == null) return;
        if (root instanceof JComponent) {
            ((JComponent) root).putClientProperty("usbnas.theme", day ? "day" : "night");
        }
        if (root instanceof JList) applyTo((JList<?>) root);
        else if (root instanceof JScrollPane) applyTo((JScrollPane) root);
        else if (root instanceof JTabbedPane) applyTo((JTabbedPane) root);
        else if (root instanceof javax.swing.JTextArea) {
            javax.swing.JTextArea ta = (javax.swing.JTextArea) root;
            ta.setBackground(BG_DEEP);
            ta.setForeground(FG_TEXT);
            ta.setCaretColor(FG_TITLE);
            ta.setSelectionColor(BRAND);
            ta.setSelectedTextColor(Color.WHITE);
        } else if (root instanceof javax.swing.text.JTextComponent
                && !(root instanceof JTextField)) {
            javax.swing.text.JTextComponent tc = (javax.swing.text.JTextComponent) root;
            tc.setBackground(BG_CARD);
            tc.setForeground(FG_TEXT);
            tc.setCaretColor(FG_TITLE);
            tc.setSelectionColor(BRAND);
            tc.setSelectedTextColor(Color.WHITE);
        } else if (root instanceof javax.swing.AbstractButton) {
            javax.swing.AbstractButton b = (javax.swing.AbstractButton) root;
            // 自绘按钮的颜色在 paint 里现取，这里只补原生按钮
            if (!(b.getUI() instanceof FlatButtonUI)) b.setForeground(FG_TEXT);
        } else if (root instanceof javax.swing.JLabel) {
            ((javax.swing.JLabel) root).setForeground(FG_TEXT);
        }
        if (root instanceof java.awt.Container) {
            java.awt.Container c = (java.awt.Container) root;
            if (c.isOpaque() && !(c instanceof javax.swing.JList)
                    && !(c instanceof JScrollPane) && !(c instanceof JTabbedPane)) {
                c.setBackground(BG_DEEP);
            }
            for (java.awt.Component ch : c.getComponents()) applyTo(ch);
        }
        if (root instanceof JComponent) SwingUtilities.updateComponentTreeUI(root);
        root.repaint();
    }

    // ---------------------------------------------------------------- 提示对话框

    /**
     * 统一替代 {@code JOptionPane.showMessageDialog / showConfirmDialog / showOptionDialog}。
     *
     * <p>为什么不用 JOptionPane：它把按钮交给当前 LAF 画。{@link #install()} 只改
     * {@code UIManager} 的默认值，Windows LAF 的 {@code WindowsButtonUI} 会无视
     * {@code Button.background} 自己画渐变 —— 于是深色窗口里弹出的一块浅灰面板配三枚
     * 系统灰按钮，跟自绘的 {@code FlatButtonUI} 完全不是一套。
     *
     * <p>这里用自绘按钮 + 同一套底色重写一遍：
     * {@code message(...)} 只有一个「好」，{@code confirm(...)} 给「取消 / 确定」，
     * {@code options(...)} 返回被点中那一项的下标（关掉窗口返回 -1）。
     */
    static void message(java.awt.Component parent, String title, String text) {
        options(parent, title, text, (String[]) null, new String[]{"好"});
    }

    /** @return true = 点了「确定」；取消/关窗 = false */
    static boolean confirm(java.awt.Component parent, String title, String text,
                           String okLabel, String cancelLabel, boolean danger) {
        Integer r = options(parent, title, text, null, new String[]{cancelLabel, okLabel}, 1, danger);
        return r != null && r == 1;
    }

    /**
     * 正文是一整块自定义控件（登录框那种要收输入的场景）。
     *
     * @return true = 点了 {@code okLabel} 那个按钮
     */
    static boolean confirm(java.awt.Component parent, String title, String unusedText,
                           JComponent body, String okLabel, String cancelLabel, boolean danger) {
        final boolean[] ok = {false};
        JPanel card = new JPanel(new BorderLayout(0, 14));
        card.setBackground(BG_CARD);
        card.setBorder(padding(18, 20, 16, 20));

        JLabel head = new JLabel(title == null ? "" : title);
        head.setFont(bold(15));
        head.setForeground(FG_TITLE);
        card.add(head, BorderLayout.NORTH);
        card.add(body, BorderLayout.CENTER);

        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        row.setOpaque(false);

        JDialog dlg = new JDialog(
                parent == null ? null
                        : (parent instanceof java.awt.Window ? (java.awt.Window) parent
                           : SwingUtilities.getWindowAncestor(parent)),
                title == null ? "USBNASLink" : title, java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        dlg.setUndecorated(true);
        dlg.setContentPane(card);
        dlg.getRootPane().setBorder(BorderFactory.createLineBorder(LINE, 1, true));

        JButton cancel = ghost(cancelLabel);
        cancel.addActionListener(e -> dlg.dispose());
        JButton commit = primary(okLabel);
        if (danger) commit.setForeground(ERR);
        commit.addActionListener(e -> {
            ok[0] = true;
            dlg.dispose();
        });
        row.add(cancel);
        row.add(commit);
        card.add(row, BorderLayout.SOUTH);

        dlg.getRootPane().registerKeyboardAction(e -> dlg.dispose(),
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        dlg.getRootPane().setDefaultButton(commit);
        dlg.pack();
        dlg.setLocationRelativeTo(parent);
        dlg.setVisible(true);
        return ok[0];
    }

    static Integer options(java.awt.Component parent, String title, String text,
                           String[] bodyArgs, String[] buttons) {
        return options(parent, title, text, bodyArgs, buttons, 0, false);
    }

    /**
     * 正文是一整块自定义控件（影片信息那种塞了滚动面板的场景）。
     * 与 {@link #options(java.awt.Component, String, String, String[], String[])} 的区别
     * 只在第四参：那里是 {@code %s} 的替换值，这里是现成的控件。
     *
     * @return 被点中那一项的下标；关窗返回 null
     */
    static Integer options(java.awt.Component parent, String title, String text,
                           JComponent body, String[] buttons) {
        return options(parent, title, text, null, body, buttons, 0, false);
    }

    static Integer options(java.awt.Component parent, String title, String text,
                           String[] bodyArgs, JComponent body,
                           String[] buttons, int def, boolean danger) {
        final int[] picked = {-1};

        JPanel card = new JPanel(new BorderLayout(0, 14));
        card.setBackground(BG_CARD);
        card.setBorder(padding(18, 20, 16, 20));

        JLabel head = new JLabel(title == null ? "" : title);
        head.setFont(bold(15));
        head.setForeground(FG_TITLE);
        card.add(head, BorderLayout.NORTH);

        JComponent center;
        if (body != null) {
            center = body;
        } else {
            String t = text == null ? "" : text;
            if (bodyArgs != null && bodyArgs.length > 0) {
                try {
                    t = String.format(t, (Object[]) bodyArgs);
                } catch (RuntimeException ignore) {
                    // 格式串和参数对不上就原样显示——提示框不该二次抛错
                }
            }
            JLabel msg = new JLabel("<html><body style='width:330px;font-size:12px;"
                    + "color:" + hex(FG_SUB) + ";line-height:1.65'>"
                    + t.replace("\n", "<br>") + "</body></html>");
            msg.setFont(ui(12));
            msg.setVerticalAlignment(SwingConstants.TOP);
            center = msg;
        }
        card.add(center, BorderLayout.CENTER);

        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        row.setOpaque(false);

        JDialog dlg = new JDialog(
                parent == null ? null
                        : (parent instanceof java.awt.Window ? (java.awt.Window) parent
                           : SwingUtilities.getWindowAncestor(parent)),
                title == null ? "USBNASLink" : title, java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        dlg.setUndecorated(true);
        dlg.setContentPane(card);
        dlg.getRootPane().setBorder(BorderFactory.createLineBorder(LINE, 1, true));

        JButton defaultBtn = null;
        for (int i = 0; i < buttons.length; i++) {
            boolean last = i == buttons.length - 1;
            JButton b = (last && buttons.length > 1) ? primary(buttons[i]) : ghost(buttons[i]);
            if (last && danger && buttons.length > 1) b.setForeground(ERR);
            final int idx = i;
            b.addActionListener(e -> {
                picked[0] = idx;
                dlg.dispose();
            });
            if (i == def) defaultBtn = b;
            row.add(b);
        }
        card.add(row, BorderLayout.SOUTH);

        dlg.getRootPane().registerKeyboardAction(e -> dlg.dispose(),
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        if (defaultBtn != null) {
            dlg.getRootPane().setDefaultButton(defaultBtn);
            final JButton fb = defaultBtn;
            SwingUtilities.invokeLater(fb::requestFocusInWindow);
        }
        dlg.pack();
        dlg.setLocationRelativeTo(parent);
        dlg.setVisible(true);
        return picked[0] < 0 ? null : picked[0];
    }

    /**
     * @param bodyArgs 正文里 {@code %s} 的替换值（可空）
     * @param def     按钮默认高亮项下标（可空）
     * @param danger  最后一个按钮是否画成危险动作（红字描边）
     */
    static Integer options(java.awt.Component parent, String title, String text,
                           String[] bodyArgs, String[] buttons, int def, boolean danger) {
        final int[] picked = {-1};

        JPanel card = new JPanel(new BorderLayout(0, 14));
        card.setBackground(BG_CARD);
        card.setBorder(padding(18, 20, 16, 20));

        JLabel head = new JLabel(title == null ? "" : title);
        head.setFont(bold(15));
        head.setForeground(FG_TITLE);
        card.add(head, BorderLayout.NORTH);

        String body = text == null ? "" : text;
        if (bodyArgs != null && bodyArgs.length > 0) {
            try {
                body = String.format(body, (Object[]) bodyArgs);
            } catch (RuntimeException ignore) {
                // 格式串和参数对不上就原样显示——提示框不该二次抛错
            }
        }
        JLabel msg = new JLabel("<html><body style='width:330px;font-size:12px;"
                + "color:" + hex(FG_SUB) + ";line-height:1.65'>"
                + body.replace("\n", "<br>") + "</body></html>");
        msg.setFont(ui(12));
        msg.setVerticalAlignment(SwingConstants.TOP);
        card.add(msg, BorderLayout.CENTER);

        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        row.setOpaque(false);

        final JDialog[] holder = new JDialog[1];
        java.util.List<JButton> made = new java.util.ArrayList<>();
        for (int i = 0; i < buttons.length; i++) {
            boolean last = i == buttons.length - 1;
            JButton b = (last && buttons.length > 1) ? primary(buttons[i]) : ghost(buttons[i]);
            if (last && danger && buttons.length > 1) b.setForeground(ERR);
            final int idx = i;
            b.addActionListener(e -> {
                picked[0] = idx;
                if (holder[0] != null) holder[0].dispose();
            });
            made.add(b);
            row.add(b);
        }
        card.add(row, BorderLayout.SOUTH);

        java.awt.Window owner = parent == null ? null
                : (parent instanceof java.awt.Window ? (java.awt.Window) parent
                   : SwingUtilities.getWindowAncestor(parent));
        JDialog dlg = new JDialog(owner, title == null ? "USBNASLink" : title,
                java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        holder[0] = dlg;
        dlg.setUndecorated(true);
        dlg.setContentPane(card);
        dlg.getRootPane().setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LINE, 1, true), padding(0, 0, 0, 0)));
        // Esc / 回车都要有反应，键盘用户不该被卡住
        dlg.getRootPane().registerKeyboardAction(e -> dlg.dispose(),
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        if (def >= 0 && def < made.size()) {
            dlg.getRootPane().setDefaultButton(made.get(def));
            SwingUtilities.invokeLater(made.get(def)::requestFocusInWindow);
        }
        dlg.pack();
        dlg.setLocationRelativeTo(parent);
        dlg.setVisible(true);

        return picked[0] < 0 ? null : picked[0];
    }
}
