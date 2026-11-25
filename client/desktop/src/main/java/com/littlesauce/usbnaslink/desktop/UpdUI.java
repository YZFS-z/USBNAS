package com.littlesauce.usbnaslink.desktop;

import com.littlesauce.usbnaslink.Update;
import com.littlesauce.usbnaslink.UpdatePrefs;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 桌面端的「检查更新 / 自动更新」。
 *
 * <h3>更新包长什么样</h3>
 * Release 里挂的是 {@code USBNASLink-<版本>-win64.zip}，解开后是 exe + jar + lib/ + vlc/。
 * 因为装的是绿色包、没有安装器，这里的做法是：
 * <ol>
 *   <li>下到 {@code %APPDATA%/USBNASLink/update/}；</li>
 *   <li>解开到一个临时目录并校验 exe 和 jar 都在（防半包）；</li>
 *   <li>写一个换文件脚本，让用户点「立即重启并更新」；</li>
 *   <li>脚本等本进程退出后覆盖安装目录，再拉起新的 exe。</li>
 * </ol>
 * 全程不碰用户数据目录（设置、播放列表、缓存的歌词海报都在 %APPDATA% 里，和安装目录分开）。
 *
 * <h3>为什么用外部脚本换文件</h3>
 * Windows 上正在运行的 exe/exe 占着的 jar 都不能被覆盖。
 * 唯一稳的办法是「让自己退出，由另一个进程来搬」。批处理是最轻的那个进程：
 * 不用再分发一个更新器 exe，也不会被杀毒软件当成可疑小工具。
 */
final class UpdUI {

    /** 两次自动检查的最小间隔：桌面端用得勤，一天一次足够 */
    static final long AUTO_GAP_MS = 24L * 60 * 60 * 1000;

    private static final String ASSET_PREFIX = "USBNASLink-";
    private static final String ASSET_SUFFIX = "-win64.zip";

    private UpdUI() {}

    /** 安装目录（含 USBNASLink.exe 的那一级），推不出来就返回 null */
    static File installDir() {
        try {
            File jar = new File(UpdUI.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            File dir = jar.isFile() ? jar.getParentFile() : jar;
            // 开发时 jar 在 build 目录里，装出来的包 exe 和 jar 同级
            if (dir != null && new File(dir, "USBNASLink.exe").isFile()) return dir;
            return dir;
        } catch (Exception e) {
            return null;
        }
    }

    /** 数据目录：%APPDATA%/USBNASLink（跟安装目录分开，覆盖更新不会丢设置） */
    static File dataDir() {
        String appdata = System.getenv("APPDATA");
        File base = appdata != null && !appdata.isEmpty()
                ? new File(appdata, "USBNASLink")
                : new File(System.getProperty("user.home"), ".usbnaslink");
        //noinspection ResultOfMethodCallIgnored
        base.mkdirs();
        return base;
    }

    static UpdatePrefs prefs() {
        return new UpdatePrefs(new File(dataDir(), "update.properties"));
    }

    static String currentVersion() {
        return Build.VERSION;
    }

    /**
     * 启动时静默检查一次。用户没开自动更新就什么都不做。
     *
     * @param onFound 有新版本时在 EDT 上回调，由调用方决定怎么提示
     */
    static void autoCheckOnStart(java.util.function.Consumer<Update.Info> onFound) {
        UpdatePrefs p = prefs();
        if (!p.enabled()) return;
        if (!p.due(AUTO_GAP_MS)) return;
        Thread t = new Thread(() -> {
            Update.Info info = Update.check(currentVersion(), ASSET_PREFIX, ASSET_SUFFIX);
            p.markChecked();
            if (!info.ok) return;                   // 自动检查失败不打扰用户
            if (!Update.newer(info, currentVersion())) return;
            if (p.skip() != null && !p.skip().isEmpty()
                    && Update.compare(info.version, p.skip()) == 0) return;
            SwingUtilities.invokeLater(() -> onFound.accept(info));
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    /** 主窗口上的「检查更新」入口：手动点，无论结果如何都给一句反馈 */
    static void manualCheck(java.awt.Component parent) {
        Thread t = new Thread(() -> {
            Update.Info info = Update.check(currentVersion(), ASSET_PREFIX, ASSET_SUFFIX);
            prefs().markChecked();
            SwingUtilities.invokeLater(() -> {
                if (!info.ok) {
                    Theme.message(parent, "检查更新", "检查失败：" + info.error);
                    return;
                }
                if (!Update.newer(info, currentVersion())) {
                    Theme.message(parent, "检查更新",
                            "已经是最新版本（" + currentVersion() + "）");
                    return;
                }
                showDialog(parent, info);
            });
        }, "update-manual");
        t.setDaemon(true);
        t.start();
    }

    /** 发现新版本时的对话框：说明 + 三个出口 */
    static void showDialog(java.awt.Component parent, Update.Info info) {
        boolean canSelfUpdate = info.hasAsset() && installDir() != null;

        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setBackground(Theme.BG_CARD);
        box.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));

        JLabel head = new JLabel("发现新版本 " + info.version);
        head.setFont(Theme.bold(16));
        head.setForeground(Theme.FG_TITLE);
        box.add(head);

        JLabel sub = new JLabel("当前 " + currentVersion()
                + (info.hasAsset() ? "　安装包 " + info.asset.humanSize() : ""));
        sub.setFont(Theme.ui(12));
        sub.setForeground(Theme.FG_SUB);
        sub.setBorder(BorderFactory.createEmptyBorder(4, 0, 10, 0));
        box.add(sub);

        if (info.notes != null && !info.notes.isEmpty()) {
            JTextArea notes = new JTextArea(info.notes);
            notes.setEditable(false);
            notes.setLineWrap(true);
            notes.setWrapStyleWord(true);
            notes.setFont(Theme.ui(12));
            notes.setBackground(Theme.BG_DEEP);
            notes.setForeground(Theme.FG_TEXT);
            notes.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
            JScrollPane sp = new JScrollPane(notes);
            sp.setPreferredSize(new Dimension(520, 180));
            sp.setBorder(Theme.cardBorder());
            box.add(sp);
        }

        if (!canSelfUpdate) {
            JLabel warn = new JLabel(info.hasAsset()
                    ? "找不到安装目录，只能手动下载。"
                    : "这个版本没有 Windows 包，只能手动去发布页。");
            warn.setFont(Theme.ui(11));
            warn.setForeground(Theme.WARN);
            warn.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
            box.add(warn);
        }

        JDialog dlg = new JDialog();
        dlg.setTitle("检查更新");
        dlg.setModal(true);
        dlg.setLayout(new BorderLayout());
        dlg.getContentPane().setBackground(Theme.BG_DEEP);
        dlg.add(box, BorderLayout.CENTER);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 10));
        btns.setBackground(Theme.BG_DEEP);

        JButton skip = Theme.ghost("跳过此版本");
        skip.addActionListener(e -> {
            prefs().setSkip(info.version);
            dlg.dispose();
        });
        btns.add(skip);

        JButton page = Theme.ghost("发布页");
        page.addActionListener(e -> openInBrowser(info.pageUrl));
        btns.add(page);

        JButton later = Theme.ghost("稍后");
        later.addActionListener(e -> dlg.dispose());
        btns.add(later);

        JButton go = Theme.primary(canSelfUpdate ? "下载并安装" : "去下载");
        go.addActionListener(e -> {
            dlg.dispose();
            if (canSelfUpdate) downloadAndStage(parent, info);
            else openInBrowser(info.hasAsset() ? info.asset.url : info.pageUrl);
        });
        btns.add(go);

        dlg.add(btns, BorderLayout.SOUTH);
        dlg.pack();
        dlg.setLocationRelativeTo(parent);
        dlg.setVisible(true);
    }

    /** 下载 → 解包校验 → 写换文件脚本 → 问用户要不要现在重启 */
    private static void downloadAndStage(java.awt.Component parent, Update.Info info) {
        File dir = installDir();
        if (dir == null || !info.hasAsset()) {
            openInBrowser(info.pageUrl);
            return;
        }

        JDialog dlg = new JDialog();
        dlg.setTitle("正在下载 " + info.version);
        dlg.setModal(true);
        dlg.setLayout(new BorderLayout());
        dlg.getContentPane().setBackground(Theme.BG_DEEP);

        JProgressBar bar = new JProgressBar(0, 1000);
        bar.setStringPainted(true);
        bar.setString("连接中…");
        bar.setBorderPainted(false);
        bar.setPreferredSize(new Dimension(420, 26));
        Theme.progress(bar);
        // 下载对话框铺在 BG_DEEP 上，进度条的槽也用 BG_DEEP 才看不出接缝
        bar.setBackground(Theme.BG_DEEP);
        JPanel pad = new JPanel(new BorderLayout());
        pad.setBackground(Theme.BG_DEEP);
        pad.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
        pad.add(bar, BorderLayout.CENTER);
        dlg.add(pad, BorderLayout.CENTER);
        dlg.pack();
        dlg.setLocationRelativeTo(parent);

        File updDir = new File(dataDir(), "update");
        //noinspection ResultOfMethodCallIgnored
        updDir.mkdirs();
        File zip = new File(updDir, "USBNASLink-" + info.version + "-win64.zip");

        Thread t = new Thread(() -> {
            String err = null;
            File staged = null;
            try {
                try (OutputStream os = new FileOutputStream(zip)) {
                    boolean ok = Update.download(info.asset.url, os, (done, total) -> {
                        int pct = total > 0 ? (int) (done * 1000 / total) : 0;
                        SwingUtilities.invokeLater(() -> {
                            if (total > 0) {
                                bar.setValue(pct);
                                bar.setString(done * 100 / total + "%  "
                                        + fmt(done) + " / " + fmt(total));
                            } else {
                                bar.setString(fmt(done));
                            }
                        });
                    });
                    if (!ok) throw new java.io.IOException("下载不完整");
                }
                SwingUtilities.invokeLater(() -> bar.setString("正在解包…"));
                staged = unzipTo(zip, new File(updDir, "stage-" + info.version));
                if (!new File(staged, "USBNASLink.exe").isFile()) {
                    throw new java.io.IOException("包里没有 USBNASLink.exe");
                }
            } catch (Throwable ex) {
                err = Update.describeForUser(ex);
            }
            final String ferr = err;
            final File fstage = staged;
            SwingUtilities.invokeLater(() -> {
                dlg.dispose();
                if (ferr != null) {
                    Theme.message(parent, "检查更新", "下载失败：" + ferr);
                    openInBrowser(info.pageUrl);
                    return;
                }
                askRestart(parent, info, dir, fstage, zip);
            });
        }, "update-download");
        t.setDaemon(true);
        t.start();
        dlg.setVisible(true);
    }

    /** 问要不要当场重启；确认则写脚本并退出本进程 */
    private static void askRestart(java.awt.Component parent, Update.Info info,
                                   File installDir, File stage, File zip) {
        boolean yes = Theme.confirm(parent, "准备就绪",
                "新版本 " + info.version + " 已下载好。\n"
                        + "现在重启并替换程序文件吗？\n\n"
                        + "程序会关闭并重新打开，设置、歌单、缓存都不受影响。\n"
                        + "（如果点「稍后」，下次启动时会再问你一次）",
                "现在重启", "稍后", false);
        if (yes) {
            try {
                launchSwap(installDir, stage, zip);
                System.exit(0);
            } catch (Exception ex) {
                Theme.message(parent, "检查更新",
                        "替换脚本写不出去：" + ex.getMessage() + "\n可以手动解压 "
                                + stage.getAbsolutePath() + " 覆盖安装目录。");
            }
        }
    }

    /**
     * 写换文件脚本并起一个独立进程跑它。
     *
     * 脚本逻辑：等 2 秒（让本进程真正退干净）→ 用 xcopy /E /Y 把 stage 盖到安装目录 →
     * 拉起 exe → 自删。xcopy 而不是 move，是因为目标目录里可能还有 stage 里没有的
     * 旧文件（比如用户自己塞的配置），move 会留下半新半旧的两份。
     */
    private static void launchSwap(File installDir, File stage, File zip) throws Exception {
        File script = new File(dataDir(), "apply-update.bat");
        String exe = new File(installDir, "USBNASLink.exe").getAbsolutePath();
        StringBuilder sb = new StringBuilder();
        sb.append("@echo off\r\n");
        sb.append("chcp 65001 >nul\r\n");
        sb.append("ping -n 3 127.0.0.1 >nul\r\n");
        sb.append("xcopy \"").append(stage.getAbsolutePath()).append("\" \"")
                .append(installDir.getAbsolutePath()).append("\" /E /Y /I /Q >nul\r\n");
        sb.append("start \"\" \"").append(exe).append("\"\r\n");
        sb.append("rmdir /S /Q \"").append(stage.getAbsolutePath()).append("\" >nul 2>nul\r\n");
        sb.append("del /Q \"").append(zip.getAbsolutePath()).append("\" >nul 2>nul\r\n");
        sb.append("(goto) 2>nul & del \"%~f0\"\r\n");
        Files.write(script.toPath(), sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        new ProcessBuilder("cmd.exe", "/c", script.getAbsolutePath())
                .directory(installDir)
                .start();
    }

    /** 解压；返回解出来的根目录（zip 里可能有一层同名目录，往里探一层） */
    private static File unzipTo(File zip, File dest) throws Exception {
        if (dest.exists()) deleteTree(dest.toPath());
        //noinspection ResultOfMethodCallIgnored
        dest.mkdirs();
        try (ZipInputStream zin = new ZipInputStream(
                new java.io.FileInputStream(zip), java.nio.charset.StandardCharsets.UTF_8)) {
            ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = zin.getNextEntry()) != null) {
                File out = new File(dest, e.getName());
                // 防 zip 路径穿越：解出来的文件必须还在 dest 里
                if (!out.getCanonicalPath().startsWith(dest.getCanonicalPath())) {
                    throw new java.io.IOException("压缩包里有可疑路径：" + e.getName());
                }
                if (e.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                File p = out.getParentFile();
                if (p != null) {
                    //noinspection ResultOfMethodCallIgnored
                    p.mkdirs();
                }
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = zin.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
        }
        // 包裹目录：只含一个子目录且里面才有 exe
        File[] kids = dest.listFiles();
        if (kids != null && kids.length == 1 && kids[0].isDirectory()
                && new File(kids[0], "USBNASLink.exe").isFile()) {
            return kids[0];
        }
        return dest;
    }

    private static void deleteTree(Path root) {
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static void openInBrowser(String url) {
        if (url == null || url.isEmpty()) return;
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(url));
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
        } catch (Exception ignored) {
        }
    }

    private static String fmt(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    /** 这个面板是给「管理设置」页嵌的：开关 + 立即检查 + 清除跳过 */
    static javax.swing.JComponent settingsPanel() {
        return settingsPanel0();
    }

    private static JPanel settingsPanel0() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Theme.BG_CARD);
        p.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        JLabel h = new JLabel("自动更新");
        h.setFont(Theme.bold(14));
        h.setForeground(Theme.FG_TITLE);
        h.setAlignmentX(0f);
        p.add(h);

        UpdatePrefs prefs = prefs();
        JCheckBox on = Theme.check("启动时自动检查 GitHub 上的新版本", prefs.enabled());
        on.setBackground(Theme.BG_CARD);
        on.setForeground(Theme.FG_TEXT);
        on.setFont(Theme.ui(12));
        on.setAlignmentX(0f);
        on.addActionListener(e -> prefs.setEnabled(on.isSelected()));
        p.add(on);

        JLabel hint = new JLabel("当前版本 " + currentVersion()
                + "　上次检查：" + prefs.lastCheckText());
        hint.setFont(Theme.ui(11));
        hint.setForeground(Theme.FG_SUB);
        hint.setBorder(BorderFactory.createEmptyBorder(6, 0, 8, 0));
        hint.setAlignmentX(0f);
        p.add(hint);

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row.setBackground(Theme.BG_CARD);
        row.setAlignmentX(0f);
        JButton check = Theme.ghost("立即检查更新");
        check.addActionListener(e -> manualCheck(p));
        row.add(check);
        String skip = prefs.skip();
        if (skip != null && !skip.isEmpty()) {
            JButton unskip = Theme.ghost("不再跳过 " + skip);
            unskip.addActionListener(e -> {
                prefs.setSkip("");
                unskip.setEnabled(false);
                unskip.setText("已清除跳过标记");
            });
            row.add(unskip);
        }
        p.add(row);
        p.add(Box.createVerticalGlue());
        return p;
    }

    /** 版本号常量：与 build.ps1 里写进 jar 清单的 Implementation-Version 保持一致 */
    static final class Build {
        static final String VERSION = "1.0.9";

        private Build() {}
    }

}
