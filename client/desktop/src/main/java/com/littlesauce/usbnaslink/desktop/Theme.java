package com.littlesauce.usbnaslink.desktop;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JProgressBar;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.plaf.ColorUIResource;
import java.awt.Color;
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

    static final Color BG_DEEP = new Color(0x0e, 0x11, 0x16);
    static final Color BG_CARD = new Color(0x17, 0x1b, 0x22);
    static final Color BG_HOVER = new Color(0x1b, 0x22, 0x2c);
    static final Color BG_SEL = new Color(0x1d, 0x2c, 0x3d);
    static final Color LINE = new Color(0x23, 0x2a, 0x34);
    static final Color LINE_SOFT = new Color(0x2a, 0x32, 0x3d);

    static final Color FG_TITLE = new Color(0xe6, 0xe9, 0xef);
    static final Color FG_TEXT = new Color(0xe6, 0xe9, 0xef);
    static final Color FG_SUB = new Color(0x9a, 0xa4, 0xb2);
    static final Color FG_DIM = new Color(0x7d, 0x85, 0x90);
    static final Color FG_MUTE = new Color(0x6b, 0x74, 0x80);

    static final Color BRAND = new Color(0x2f, 0x80, 0xed);
    static final Color BRAND_DIM = new Color(0x24, 0x63, 0xb8);
    static final Color BRAND_LINK = new Color(0x5a, 0xa9, 0xff);
    static final Color LINK_SOFT = new Color(0x8e, 0xc5, 0xff);

    static final Color OK = new Color(0x8f, 0xe0, 0xac);
    static final Color OK_BG = new Color(0x12, 0x30, 0x1f);
    static final Color OK_LINE = new Color(0x1f, 0x52, 0x36);
    static final Color ERR = new Color(0xff, 0x8a, 0x94);
    static final Color WARN = new Color(0xff, 0xc9, 0x6b);

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
}
