package com.littlesauce.usbnaslink.desktop;

import com.littlesauce.usbnaslink.Admin;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理设置面板（桌面端）。
 *
 * 这些设置（站点名称、音乐目录、影视目录）是「设一次、所有客户端共用」的全局
 * 配置，所以不放在手机 App 的控制界面上，而是由管理员在这里改 —— 服务端
 * 通过 {@code /_admin/api} 落盘到 SharedPreferences。
 *
 * 权限判定只有一份，在服务端：客户端只管把 {@code admin} 字段读出来，
 * 是 false 就把面板锁死。不做本地二次校验，两边错位比不做更麻烦。
 *
 * 目录编辑是「树 + 文本域」双轨：
 * <ul>
 *   <li>{@link DirTree} 把服务端返回的扁平候选重组成可折叠的层级，点一下就勾选；</li>
 *   <li>文本域是**唯一数据源**，树上勾选会并进文本域，文本域改了会回标到树；</li>
 *   <li>文本域还留着「候选里没有、但我知道路径」这一手，也方便整段粘贴。</li>
 * </ul>
 * 早先是一大片平铺复选框（每个存储根下两层全摊开，机械盘上几百上千行），
 * 一屏塞不下也看不出层级 —— 所以换成了树。
 */
final class AdminPanel extends JPanel {

    private final JLabel stateLabel = new JLabel("未连接");
    private final JTextField titleField = new JTextField(24);
    private final JTextArea musicArea = new JTextArea(3, 34);
    private final JTextArea videoArea = new JTextArea(3, 34);
    private final JTextArea bookArea = new JTextArea(3, 34);
    private final DirTree musicTree = new DirTree();
    private final DirTree videoTree = new DirTree();
    private final DirTree bookTree = new DirTree();
    private final JButton reloadBtn = Theme.ghost("重新读取");
    private final JButton saveTitleBtn = Theme.primary("保存站点名称");
    private final JButton saveMusicBtn = Theme.primary("保存音乐目录");
    private final JButton saveVideoBtn = Theme.primary("保存影视目录");
    private final JButton saveBookBtn = Theme.primary("保存图书目录");
    private final JLabel musicCount = new JLabel("当前 0 个");
    private final JLabel videoCount = new JLabel("当前 0 个");
    private final JLabel bookCount = new JLabel("当前 0 个");

    private volatile String baseUrl = "";
    private volatile String cookie = null;
    /** 服务端认定当前会话是管理员；false 时所有控件禁用 */
    private volatile boolean admin;
    private volatile Admin.State last;
    /** 回填时抑制「文本域 → 树」的再入，避免一次赋值触发两轮重建 */
    private boolean filling;

    AdminPanel() {
        super(new BorderLayout(0, 8));
        setBorder(Theme.padding(14, 16, 14, 16));
        setBackground(Theme.BG_DEEP);

        stateLabel.setForeground(Theme.FG_SUB);
        stateLabel.setFont(Theme.ui(12));

        JPanel head = new JPanel(new BorderLayout(8, 0));
        head.setOpaque(false);
        JLabel t = Theme.title("管理设置");
        t.setFont(Theme.bold(16));
        head.add(t, BorderLayout.WEST);
        head.add(stateLabel, BorderLayout.CENTER);
        head.add(reloadBtn, BorderLayout.EAST);
        add(head, BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));

        titleField.setBackground(Theme.BG_CARD);
        titleField.setForeground(Theme.FG_TEXT);
        titleField.setCaretColor(Theme.FG_TITLE);
        titleField.setFont(Theme.ui(13));
        titleField.setBorder(BorderFactory.createCompoundBorder(
                Theme.cardBorder(), Theme.padding(6, 9, 6, 9)));

        body.add(section("站点名称",
                "浏览器标签页与 Server 响应头里显示的名字。",
                titleField, null, saveTitleBtn));

        musicTree.setPreferredSize(new Dimension(620, 190));
        body.add(section("音乐目录",
                "客户端播放器只在这里找歌；可以勾多个（比如盘0/音乐 + 盘1/音乐 各一部分）。点前面的方块勾选（连带整支），点文字只展开。",
                textArea(musicArea), musicTree.component(), saveMusicBtn, musicCount));

        videoTree.setPreferredSize(new Dimension(620, 190));
        body.add(section("影视目录",
                "电影、电视剧可以放在不同目录，客户端会按目录分块显示。同样是点方块勾选、点文字展开。",
                textArea(videoArea), videoTree.component(), saveVideoBtn, videoCount));

        bookTree.setPreferredSize(new Dimension(620, 190));
        body.add(section("图书目录",
                "客户端「图书」页只显示这里的书。认 epub/pdf/mobi/azw3/fb2/djvu/cbz/cbr/txt/md；"
                        + "不配就是空书库（不会去扫全盘找书，否则日志和配置文件都会被当成书列出来）。",
                textArea(bookArea), bookTree.component(), saveBookBtn, bookCount));

        JScrollPane sp = new JScrollPane(body);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getViewport().setBackground(Theme.BG_DEEP);
        sp.getVerticalScrollBar().setUnitIncrement(18);
        add(sp, BorderLayout.CENTER);

        reloadBtn.addActionListener(e -> reload());
        saveTitleBtn.addActionListener(e -> {
            String v = titleField.getText().trim();
            if (v.isEmpty()) {
                warn("站点名称不能为空");
                return;
            }
            save(saveTitleBtn, "正在保存…", () -> Admin.saveTitle(baseUrl, cookie, v, 8000),
                    () -> "站点名称已保存：" + v);
        });
        saveMusicBtn.addActionListener(e -> {
            List<String> dirs = lines(musicArea.getText());
            save(saveMusicBtn, "正在保存…", () -> Admin.saveMusicDirs(baseUrl, cookie, dirs, 8000),
                    () -> "音乐目录已保存（" + dirs.size() + " 个）");
        });
        saveVideoBtn.addActionListener(e -> {
            List<String> dirs = lines(videoArea.getText());
            save(saveVideoBtn, "正在保存…", () -> Admin.saveVideoDirs(baseUrl, cookie, dirs, 8000),
                    () -> "影视目录已保存（" + dirs.size() + " 个）");
        });
        saveBookBtn.addActionListener(e -> {
            List<String> dirs = lines(bookArea.getText());
            save(saveBookBtn, "正在保存…", () -> Admin.saveBookDirs(baseUrl, cookie, dirs, 8000),
                    () -> "图书目录已保存（" + dirs.size() + " 个）");
        });

        // 树 → 文本域：勾选变化直接把结果写回唯一数据源
        musicTree.setOnChange(() -> {
            if (!filling) musicArea.setText(join(musicTree.checkedPaths()));
        });
        videoTree.setOnChange(() -> {
            if (!filling) videoArea.setText(join(videoTree.checkedPaths()));
        });
        bookTree.setOnChange(() -> {
            if (!filling) bookArea.setText(join(bookTree.checkedPaths()));
        });
        // 文本域 → 树：手动改了路径或粘贴一整段，重新标勾
        musicArea.getDocument().addDocumentListener(new SimpleDoc(() -> remark(musicTree, musicArea)));
        videoArea.getDocument().addDocumentListener(new SimpleDoc(() -> remark(videoTree, videoArea)));
        bookArea.getDocument().addDocumentListener(new SimpleDoc(() -> remark(bookTree, bookArea)));

        lockAll(true);
    }

    // ---------------------------------------------------------------- 构建

    /** 一块设置：标题 + 说明 + 编辑区 +（可选）候选树 + 保存按钮 +（可选）计数 */
    private JPanel section(String name, String hint, JComponent editor,
                           JComponent cands, JButton save, JComponent... extra) {
        JPanel box = new JPanel(new BorderLayout(0, 6));
        box.setOpaque(false);
        box.setBorder(Theme.padding(12, 0, 12, 0));

        JLabel n = new JLabel(name);
        n.setForeground(Theme.FG_TEXT);
        n.setFont(Theme.bold(13));
        JLabel h = new JLabel("<html><span style='color:" + Theme.hex(Theme.FG_DIM) + "'>"
                + escape(hint) + "</span></html>");
        h.setFont(Theme.ui(11));

        JPanel top = new JPanel(new BorderLayout(0, 2));
        top.setOpaque(false);
        top.add(n, BorderLayout.NORTH);
        top.add(h, BorderLayout.SOUTH);
        box.add(top, BorderLayout.NORTH);

        JPanel mid = new JPanel(new BorderLayout(0, 6));
        mid.setOpaque(false);
        mid.add(editor, BorderLayout.NORTH);
        if (cands != null) mid.add(cands, BorderLayout.CENTER);
        box.add(mid, BorderLayout.CENTER);

        JPanel act = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        act.setOpaque(false);
        save.setFocusable(false);
        act.add(save);
        for (JComponent c : extra) act.add(c);
        box.add(act, BorderLayout.SOUTH);

        for (JComponent c : extra) {
            if (c instanceof JLabel) ((JLabel) c).setForeground(Theme.FG_SUB);
        }
        return box;
    }

    private JComponent textArea(JTextArea a) {
        a.setBackground(Theme.BG_CARD);
        a.setForeground(Theme.FG_TEXT);
        a.setCaretColor(Theme.FG_TITLE);
        a.setFont(Theme.mono(12));
        a.setLineWrap(false);
        a.setBorder(Theme.padding(6, 8, 6, 8));
        JScrollPane sp = new JScrollPane(a);
        sp.setPreferredSize(new Dimension(620, 74));
        sp.setBorder(Theme.cardBorder());
        sp.getViewport().setBackground(Theme.BG_CARD);
        return sp;
    }

    // ---------------------------------------------------------------- 数据

    void setTarget(String base, String sid) {
        boolean changed = base == null ? baseUrl != null : !base.equals(baseUrl);
        this.baseUrl = base == null ? "" : base;
        this.cookie = sid;
        if (changed || last == null) reload();
    }

    /** 读一次服务端状态；未登录/非管理员也会正常返回，只是 admin=false */
    void reload() {
        final String base = baseUrl;
        if (base.isEmpty()) {
            stateLabel.setText("未连接服务器");
            lockAll(true);
            return;
        }
        stateLabel.setText("正在读取…");
        Thread th = new Thread(() -> {
            Admin.State st = Admin.fetch(base, cookie, 8000);
            SwingUtilities.invokeLater(() -> apply(st));
        }, "usbnas-admin");
        th.setDaemon(true);
        th.start();
    }

    private void apply(Admin.State st) {
        last = st;
        if (st == null || !st.ok) {
            stateLabel.setText("读取失败：" + (st == null ? "无响应" : st.error));
            lockAll(true);
            return;
        }
        admin = st.admin;
        if (!st.admin) {
            stateLabel.setText(st.logged
                    ? "当前账号「" + nz(st.who) + "」是" + nz(st.levelName) + "，改设置需要管理员"
                    : "需要管理员账号：请先用「登录…」登录");
            lockAll(true);
            return;
        }

        stateLabel.setText("管理员「" + nz(st.who) + "」· " + nz(st.levelName)
                + " · " + (st.choices == null ? 0 : st.choices.size()) + " 个候选目录");
        lockAll(false);

        filling = true;
        try {
            titleField.setText(nz(st.title));
            musicArea.setText(join(st.musicDirs));
            videoArea.setText(join(st.videoDirs));
            bookArea.setText(join(st.bookDirs));
        } finally {
            filling = false;
        }
        musicTree.rebuild(st.choices, st.musicDirs);
        videoTree.rebuild(st.choices, st.videoDirs);
        bookTree.rebuild(st.choices, st.bookDirs);
        musicCount.setText("当前 " + (st.musicDirs == null ? 0 : st.musicDirs.size()) + " 个");
        videoCount.setText("当前 " + (st.videoDirs == null ? 0 : st.videoDirs.size()) + " 个");
        bookCount.setText("当前 " + (st.bookDirs == null ? 0 : st.bookDirs.size()) + " 个");
    }

    /** 文本域被手动改过：按新内容重标树上的勾，但不重建结构（会丢展开状态） */
    private void remark(DirTree tree, JTextArea area) {
        if (filling) return;
        filling = true;
        try {
            tree.rebuild(last == null ? null : last.choices, lines(area.getText()));
        } finally {
            filling = false;
        }
    }

    // ---------------------------------------------------------------- 保存

    private interface Action {
        Admin.Saved run();
    }

    private interface Msg {
        String text();
    }

    private void save(JButton btn, String busy, Action action, Msg okMsg) {
        if (!admin) {
            warn("当前会话不是管理员");
            return;
        }
        btn.setEnabled(false);
        stateLabel.setText(busy);
        Thread th = new Thread(() -> {
            Admin.Saved r = action.run();
            SwingUtilities.invokeLater(() -> {
                btn.setEnabled(true);
                if (r != null && r.ok) {
                    stateLabel.setText(okMsg.text());
                    reload();
                } else {
                    stateLabel.setText("保存失败：" + (r == null ? "无响应" : nz(r.error)));
                }
            });
        }, "usbnas-admin-save");
        th.setDaemon(true);
        th.start();
    }

    private void lockAll(boolean lock) {
        titleField.setEnabled(!lock);
        musicArea.setEnabled(!lock);
        videoArea.setEnabled(!lock);
        bookArea.setEnabled(!lock);
        saveTitleBtn.setEnabled(!lock);
        saveMusicBtn.setEnabled(!lock);
        saveVideoBtn.setEnabled(!lock);
        saveBookBtn.setEnabled(!lock);
        musicTree.setEnabledDeep(!lock);
        videoTree.setEnabledDeep(!lock);
        bookTree.setEnabledDeep(!lock);
    }

    private void warn(String msg) {
        Theme.message(this, "管理设置", msg);
    }

    // ---------------------------------------------------------------- 小工具

    /** 文档变化监听的最小适配器（只要「变了」这一件事） */
    private static final class SimpleDoc implements javax.swing.event.DocumentListener {
        private final Runnable r;

        SimpleDoc(Runnable r) {
            this.r = r;
        }

        @Override
        public void insertUpdate(javax.swing.event.DocumentEvent e) {
            r.run();
        }

        @Override
        public void removeUpdate(javax.swing.event.DocumentEvent e) {
            r.run();
        }

        @Override
        public void changedUpdate(javax.swing.event.DocumentEvent e) {
            r.run();
        }
    }

    private static List<String> lines(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String l : raw.split("\r?\n")) {
            String s = l.trim();
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        }
        return out;
    }

    private static String join(List<String> arr) {
        if (arr == null || arr.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : arr) {
            if (s == null || s.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(s.trim());
        }
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null || s.isEmpty() ? "—" : s;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
