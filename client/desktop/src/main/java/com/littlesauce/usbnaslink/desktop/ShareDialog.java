package com.littlesauce.usbnaslink.desktop;

import com.littlesauce.usbnaslink.Share;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * 分享管理对话框（桌面端）。
 *
 * <p>把服务端的 /_share/new|drop|list 接到桌面：上半区输入服务端绝对路径新建短链，
 * 下半区列出已有分享、撤销、复制链接。网络调用全放后台线程，EDT 只刷 UI。
 */
final class ShareDialog extends JDialog {

    private final String base;
    private final String cookie;
    private final JTextField pathField = Theme.field(26);
    private final DefaultListModel<Share.Item> model = new DefaultListModel<>();
    private final JList<Share.Item> list = new JList<>(model);
    private final JLabel status = new JLabel(" ");
    private final JButton createBtn = Theme.primary("生成分享链接");
    private final JButton copyBtn = Theme.control("复制链接");
    private final JButton dropBtn = Theme.ghost("撤销");

    ShareDialog(java.awt.Window parent, String base, String cookie) {
        super(parent, "分享管理", ModalityType.APPLICATION_MODAL);
        this.base = base;
        this.cookie = cookie;

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(Theme.BG_DEEP);
        root.setBorder(Theme.padding(18, 20, 16, 20));
        setContentPane(root);

        JLabel title = new JLabel("分享管理");
        title.setFont(Theme.bold(16));
        title.setForeground(Theme.FG_TITLE);
        root.add(title, BorderLayout.NORTH);

        // ---- 上：新建 ----
        JPanel make = new JPanel(new BorderLayout(0, 8));
        make.setOpaque(false);
        JLabel hint = new JLabel("输入服务端上的路径（文件可分享单文件，目录会开放整个子树）：");
        hint.setFont(Theme.ui(12));
        hint.setForeground(Theme.FG_DIM);
        pathField.setToolTipText("例如 /mnt/media_rw/xxxx/电影/山村老尸.mp4");
        make.add(hint, BorderLayout.NORTH);
        JPanel mkRow = new JPanel(new BorderLayout(8, 0));
        mkRow.setOpaque(false);
        mkRow.add(pathField, BorderLayout.CENTER);
        mkRow.add(createBtn, BorderLayout.EAST);
        make.add(mkRow, BorderLayout.SOUTH);
        root.add(make, BorderLayout.CENTER);

        // ---- 中：列表 ----
        list.setBackground(Theme.BG_CARD);
        list.setForeground(Theme.FG_TEXT);
        list.setFont(Theme.ui(12));
        list.setSelectionBackground(Theme.BG_SEL);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> l, Object v, int i,
                                                          boolean sel, boolean focus) {
                JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, sel, focus);
                Share.Item it = (Share.Item) v;
                c.setText(it == null ? "" : it.line());
                c.setBorder(Theme.padding(5, 8, 5, 8));
                c.setBackground(sel ? Theme.BG_SEL : Theme.BG_CARD);
                return c;
            }
        });
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(640, 240));
        sp.setBorder(BorderFactory.createLineBorder(Theme.LINE, 1, true));
        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.setOpaque(false);
        JLabel listTitle = new JLabel("当前分享（服务重启会失效）：");
        listTitle.setFont(Theme.ui(12));
        listTitle.setForeground(Theme.FG_DIM);
        center.add(listTitle, BorderLayout.NORTH);
        center.add(sp, BorderLayout.CENTER);

        // ---- 下：操作 + 状态 ----
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        row.setOpaque(false);
        row.add(copyBtn);
        row.add(dropBtn);
        JButton refreshBtn = Theme.ghost("刷新列表");
        row.add(refreshBtn);
        status.setFont(Theme.ui(11));
        status.setForeground(Theme.FG_DIM);

        JPanel south = new JPanel(new BorderLayout(0, 8));
        south.setOpaque(false);
        south.add(center, BorderLayout.CENTER);
        south.add(status, BorderLayout.WEST);
        south.add(row, BorderLayout.SOUTH);
        root.add(south, BorderLayout.SOUTH);

        // ---- 事件 ----
        createBtn.addActionListener(e -> {
            final String p = pathField.getText().trim();
            if (p.isEmpty()) { setStatus("先填路径"); return; }
            busy(true, "正在创建…");
            new Thread(() -> {
                final Share.Created r = Share.create(base, cookie, p, 20000);
                SwingUtilities.invokeLater(() -> {
                    busy(false, "");
                    if (r.ok) {
                        setStatus("已生成：" + r.code);
                        pathField.setText("");
                        refresh();
                    } else {
                        setStatus("创建失败：" + r.error);
                    }
                });
            }, "share-create").start();
        });
        copyBtn.addActionListener(e -> {
            Share.Item it = list.getSelectedValue();
            if (it == null) { setStatus("先选一条分享"); return; }
            copy(it.url);
            setStatus("链接已复制");
        });
        dropBtn.addActionListener(e -> {
            Share.Item it = list.getSelectedValue();
            if (it == null) { setStatus("先选一条分享"); return; }
            busy(true, "正在撤销…");
            new Thread(() -> {
                final Share.Dropped r = Share.drop(base, cookie, it.code, 20000);
                SwingUtilities.invokeLater(() -> {
                    busy(false, "");
                    setStatus(r.ok && r.removed ? "已撤销" : "撤销失败：" + r.error);
                    refresh();
                });
            }, "share-drop").start();
        });
        refreshBtn.addActionListener(e -> refresh());

        pack();
        setLocationRelativeTo(parent);
        refresh();
    }

    private void refresh() {
        busy(true, "正在加载分享列表…");
        new Thread(() -> {
            final Share.ListResult r = Share.list(base, cookie, 20000);
            SwingUtilities.invokeLater(() -> {
                busy(false, "");
                model.clear();
                if (r.ok) {
                    for (Share.Item it : r.items) model.addElement(it);
                    setStatus("共 " + r.items.size() + " 条分享");
                } else {
                    setStatus("列分享失败：" + r.error);
                }
            });
        }, "share-list").start();
    }

    private void busy(boolean on, String text) {
        createBtn.setEnabled(!on);
        copyBtn.setEnabled(!on);
        dropBtn.setEnabled(!on);
        if (!text.isEmpty()) setStatus(text);
    }

    private void setStatus(String s) {
        status.setText(s);
    }

    private void copy(String s) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new java.awt.datatransfer.StringSelection(s), null);
        } catch (Throwable ignored) {
        }
    }
}
