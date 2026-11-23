package com.littlesauce.usbnaslink.desktop;

import com.littlesauce.usbnaslink.Admin;

import javax.swing.BorderFactory;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 目录树 —— 管理设置里选目录用。
 *
 * <p>为什么是树而不是一排复选框：服务端 {@code candidateDirs()} 会把每个存储根下
 * 两层子目录全平铺出来，机械盘上那是几百上千行，一屏塞不下、也看不出层级关系。
 * 树能折叠，只展开用户真正在找的那一支。
 *
 * <p>数据来源不变：还是 {@code /_admin/api} 返回的扁平 {@code choices}（只有两层），
 * 这里按路径前缀把它们重组成层级。不做额外请求、不改服务端契约。
 *
 * <p>交互约定（踩过坑后定下的）：
 * <ul>
 *   <li><b>点小方块</b>切换本节点的勾选（连带整支子树）；</li>
 *   <li><b>点文字</b>只展开/折叠，不改勾选 —— 否则想展开看一眼就把配置改了；</li>
 *   <li>勾选状态存在 {@link Node#checked} 里，{@link #checkedPaths()} 是唯一出口，
 *       父面板再把它写进文本域；</li>
 *   <li>有子节点的「整支勾选」写成父路径一行（{@code /storage/emulated/0/Movies}），
 *       不展开成 N 行 —— 配置里一行就够服务端递归扫了。</li>
 * </ul>
 */
final class DirTree {

    /** 节点携带的数据 */
    static final class Node {
        final String path;      // 绝对路径；根节点也有
        final String label;     // 显示名
        final boolean root;     // 是否存储根
        boolean checked;        // 是否勾选
        boolean configuredOnly;// 配置里有、但服务端没列出（手工写的路径）

        Node(String path, String label, boolean root) {
            this.path = path == null ? "" : path;
            this.label = label == null || label.isEmpty() ? baseName(this.path) : label;
            this.root = root;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final JTree tree;
    private final DefaultMutableTreeNode top = new DefaultMutableTreeNode("候选目录");
    private final DefaultTreeModel model;
    /** 树叶节点的展开箭头区域宽度上限，点击落在这块宽度内视为「点方块」 */
    private static final int BOX_HIT = 18;

    /** 勾选变化回调；参数是当前所有勾中的路径（按树序去重） */
    private Runnable onChange = () -> {
    };

    DirTree() {
        model = new DefaultTreeModel(top);
        tree = new JTree(model);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(24);
        tree.setBackground(Theme.BG_CARD);
        tree.setForeground(Theme.FG_TEXT);
        tree.setCellRenderer(new Renderer());
        tree.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        tree.putClientProperty("JTree.lineStyle", "None");
        tree.setToggleClickCount(0);  // 自己处理点击，别让默认的双击展开抢走

        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!tree.isEnabled()) return;
                if (e.getButton() != MouseEvent.BUTTON1) return;
                TreePath p = tree.getPathForLocation(e.getX(), e.getY());
                if (p == null) return;
                Object last = p.getLastPathComponent();
                if (!(last instanceof DefaultMutableTreeNode)) return;
                Object uo = ((DefaultMutableTreeNode) last).getUserObject();
                if (!(uo instanceof Node)) return;

                // 点击点落在行首 BOX_HIT 像素内算「点方块」，否则算「点文字」
                java.awt.Rectangle rb = tree.getPathBounds(p);
                boolean onBox = rb != null && (e.getX() - rb.x) < BOX_HIT;
                if (!onBox) {
                    // 点文字：只展开/折叠
                    if (tree.isExpanded(p)) tree.collapsePath(p);
                    else tree.expandPath(p);
                    return;
                }
                Node n = (Node) uo;
                n.checked = !n.checked;
                applySubtree((DefaultMutableTreeNode) last, n.checked);
                model.nodeChanged((DefaultMutableTreeNode) last);
                repaintAll();
                onChange.run();
            }
        });
    }

    JTree component() {
        return tree;
    }

    void setPreferredSize(Dimension d) {
        tree.setPreferredSize(d);
        tree.setMinimumSize(d);
    }

    void setOnChange(Runnable r) {
        onChange = r == null ? () -> {
        } : r;
    }

    /**
     * 用服务端候选 + 当前配置重建整棵树。
     *
     * @param choices 扁平候选（服务端来的，只有两层）
     * @param current 当前已配置的目录（可能含手工写的、不在候选里的）
     */
    void rebuild(List<Admin.DirChoice> choices, List<String> current) {
        List<String> conf = nzList(current);
        top.setUserObject("候选目录（" + conf.size() + " 个已选）");
        top.removeAllChildren();

        Map<String, DefaultMutableTreeNode> roots = new LinkedHashMap<>();
        Map<String, DefaultMutableTreeNode> dirs = new LinkedHashMap<>();

        // 1) 按「根 → 一层 → 二层」把扁平候选组织起来
        for (Admin.DirChoice c : nzList(choices)) {
            String path = norm(c.path);
            if (path.isEmpty()) continue;
            String rootKey = rootKeyOf(path);

            DefaultMutableTreeNode rn = roots.get(rootKey);
            if (rn == null) {
                rn = new DefaultMutableTreeNode(new Node(rootKey, labelOfRoot(rootKey, c.label, roots.size()), true));
                roots.put(rootKey, rn);
                top.add(rn);
            }

            String[] seg = split(path);
            String acc = rootKey;
            DefaultMutableTreeNode parent = rn;
            for (int i = segmentsOfRoot(rootKey); i < seg.length; i++) {
                acc = acc + "/" + seg[i];
                DefaultMutableTreeNode dn = dirs.get(acc);
                if (dn == null) {
                    dn = new DefaultMutableTreeNode(new Node(acc, lastSeg(acc), false));
                    dirs.put(acc, dn);
                    parent.add(dn);
                }
                parent = dn;
            }
        }

        // 2) 配置里可能有候选里没有的路径（手写的），补成一支，方便一眼看到
        for (String c : conf) {
            String path = norm(c);
            if (path.isEmpty()) continue;
            if (find(rootLookup(rootKeyOf(path)), path) != null) continue;
            Node ph = new Node(path, lastSeg(path), false);
            ph.configuredOnly = true;
            DefaultMutableTreeNode pn = new DefaultMutableTreeNode(ph);
            DefaultMutableTreeNode rn = roots.get(rootKeyOf(path));
            if (rn != null) rn.add(pn);
            else top.add(pn);
        }

        // 3) 标勾：路径本身在配置里 → 置灰不可再单独取消（它就是个已选目录）
        markChecked(top, new LinkedHashSet<>(conf));

        model.setRoot(top);
        model.reload();
        expandAll();
        repaintAll();
    }

    /** 当前勾中的路径（按树序遍历，天然去重） */
    List<String> checkedPaths() {
        List<String> out = new ArrayList<>();
        walk(top, out);
        return out;
    }

    /** 一次性把整棵树设成勾/不勾（面板回填时用） */
    void setCheckedAll(List<String> paths) {
        markChecked(top, new LinkedHashSet<>(nzList(paths)));
        model.reload();
        expandAll();
        repaintAll();
    }

    void setEnabledDeep(boolean on) {
        tree.setEnabled(on);
        tree.repaint();
    }

    /**
     * 只展开根这一层。
     *
     * 早先是 for (i&lt;getRowCount()) expandRow(i)，等于把每一层都摊开：内部存储
     * 下面有几十上百个子目录，铺出来是十几屏，两个 /mnt/media_rw/* 的盘根被顶到
     * 很下面 —— 用户看到的就是「只能选内部存储」。根各占一行、点哪个开哪个才对。
     */
    void expandAll() {
        for (int i = 0; i < tree.getRowCount(); i++) {
            TreePath p = tree.getPathForRow(i);
            if (p == null) continue;
            Object last = p.getLastPathComponent();
            // 根自己保持展开（否则一行都不显示），其余一律折叠
            if (last == top) tree.expandPath(p);
            else tree.collapsePath(p);
        }
    }

    // ---------------------------------------------------------------- 内部

    private DefaultMutableTreeNode rootLookup(String key) {
        Object root = model.getRoot();
        if (!(root instanceof DefaultMutableTreeNode)) return null;
        return find((DefaultMutableTreeNode) root, key);
    }

    /**
     * 收集勾选路径。勾了父级就等于要整个父级，子节点不再重复列出来 ——
     * 否则勾一次「内部存储」会往配置里塞四十几行，README 和 /_music 的
     * dirs 列表都会被这些同源条目刷满。
     */
    private void walk(DefaultMutableTreeNode n, List<String> out) {
        Object uo = n.getUserObject();
        if (uo instanceof Node) {
            Node d = (Node) uo;
            if (d.checked && !d.path.isEmpty() && !out.contains(d.path)) out.add(d.path);
            if (d.checked) return;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            walk((DefaultMutableTreeNode) n.getChildAt(i), out);
        }
    }

    /**
     * 按配置集合标勾：命中就是勾上；某个已配路径的祖先只是「含已选项」，
     * 不算勾上（否则 unchecked 会造成整支一起选中，用户取消不掉）。
     */
    private void markChecked(DefaultMutableTreeNode n, Set<String> set) {
        Object uo = n.getUserObject();
        if (uo instanceof Node) {
            Node d = (Node) uo;
            d.checked = set.contains(d.path);
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            markChecked((DefaultMutableTreeNode) n.getChildAt(i), set);
        }
    }

    private void applySubtree(DefaultMutableTreeNode n, boolean on) {
        Object uo = n.getUserObject();
        if (uo instanceof Node) ((Node) uo).checked = on;
        for (int i = 0; i < n.getChildCount(); i++) {
            applySubtree((DefaultMutableTreeNode) n.getChildAt(i), on);
        }
    }

    private static DefaultMutableTreeNode find(DefaultMutableTreeNode n, String path) {
        Object uo = n.getUserObject();
        if (uo instanceof Node && ((Node) uo).path.equals(path)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            DefaultMutableTreeNode hit = find((DefaultMutableTreeNode) n.getChildAt(i), path);
            if (hit != null) return hit;
        }
        return null;
    }

    private void repaintAll() {
        tree.treeDidChange();
        tree.repaint();
    }

    private static String norm(String p) {
        if (p == null) return "";
        String s = p.trim();
        while (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String[] split(String path) {
        if (path == null || path.isEmpty() || path.equals("/")) return new String[0];
        String s = path.startsWith("/") ? path.substring(1) : path;
        return s.split("/");
    }

    /** 根键：/storage/emulated/0 三段整体当根；其余（/mnt/media_rw/XXX）也取三段更像「一个盘」 */
    private static String rootKeyOf(String path) {
        String[] s = split(path);
        if (path.startsWith("/storage/emulated/")) {
            return s.length >= 3 ? "/" + s[0] + "/" + s[1] + "/" + s[2] : path;
        }
        if (path.startsWith("/mnt/media_rw/") || path.startsWith("/mnt/")) {
            return s.length >= 3 ? "/" + s[0] + "/" + s[1] + "/" + s[2] : path;
        }
        return "/" + (s.length > 0 ? s[0] : "");
    }

    private static int segmentsOfRoot(String rootKey) {
        return split(rootKey).length;
    }

    private static String labelOfRoot(String rootKey, String serverLabel, int seq) {
        if (rootKey.startsWith("/storage/emulated/")) return "内部存储";
        if (rootKey.startsWith("/mnt/media_rw/")) {
            String[] s = split(rootKey);
            return "外部盘 " + s[s.length - 1];
        }
        return serverLabel == null || serverLabel.isEmpty() ? "存储 " + (seq + 1) : serverLabel;
    }

    private static String lastSeg(String path) {
        String[] s = split(path);
        return s.length == 0 ? path : s[s.length - 1];
    }

    private static String baseName(String path) {
        return path == null || path.isEmpty() ? "目录" : lastSeg(path);
    }

    private static <T> List<T> nzList(List<T> in) {
        return in == null ? new ArrayList<>() : in;
    }

    // ---------------------------------------------------------------- 渲染

    /** 树行渲染：小方块 + 名字 + 路径灰字 */
    private static final class Renderer extends DefaultTreeCellRenderer {

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                                                     boolean expanded, boolean leaf, int row,
                                                     boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            setBackgroundNonSelectionColor(Theme.BG_CARD);
            setBackgroundSelectionColor(Theme.BG_CARD);
            setTextNonSelectionColor(Theme.FG_TEXT);
            setTextSelectionColor(Theme.FG_TEXT);
            setBorderSelectionColor(null);
            setLeafIcon(null);
            setOpenIcon(null);
            setClosedIcon(null);
            setFont(Theme.ui(12));

            Object uo = value instanceof DefaultMutableTreeNode
                    ? ((DefaultMutableTreeNode) value).getUserObject() : null;
            if (!(uo instanceof Node)) {
                setText("<html><span style='color:" + Theme.hex(Theme.FG_DIM) + ";font-family:"
                        + Theme.FONT_STACK + "'>" + esc(String.valueOf(value)) + "</span></html>");
                return this;
            }
            Node n = (Node) uo;
            StringBuilder sb = new StringBuilder("<html><span style='font-family:");
            sb.append(Theme.FONT_STACK).append("'>");
            sb.append("<span style='color:")
              .append(Theme.hex(n.checked ? Theme.BRAND_LINK : Theme.FG_MUTE))
              .append("'>").append(n.checked ? "◼" : "◻").append("</span>&nbsp;");
            sb.append("<span style='color:")
              .append(Theme.hex(n.root ? Theme.FG_TITLE : Theme.FG_TEXT))
              .append(n.root ? ";font-weight:bold" : "")
              .append("'>").append(esc(n.label)).append("</span>");
            if (n.configuredOnly) {
                sb.append("<span style='color:").append(Theme.hex(Theme.WARN))
                  .append("'>&nbsp;· 已配置（候选里没有）</span>");
            } else if (n.root) {
                sb.append("<span style='color:").append(Theme.hex(Theme.FG_MUTE))
                  .append("'>&nbsp;&nbsp;").append(esc(n.path)).append("</span>");
            }
            sb.append("</span></html>");
            setText(sb.toString());
            return this;
        }

        private static String esc(String s) {
            if (s == null) return "";
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
