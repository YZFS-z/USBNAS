package com.littlesauce.usbnaslink;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * 自动更新的用户偏好：开不开、跳过哪个版本、上次检查时间。
 *
 * 桌面端写在 {@code %APPDATA%/USBNASLink/update.properties}，
 * 安卓端写在应用私有目录下的同名文件 —— 两端都不需要额外的存储依赖，
 * 而且和 {@link Store} 一样是「小文件 + 手写」，出问题用记事本就能看。
 *
 * <h3>默认值</h3>
 * {@link #enabled} 默认 <b>true</b>：用户装了客户端就是想要新功能，
 * 默认帮他看一眼；但他随时能在界面上关掉，关掉之后连一次请求都不发。
 */
public final class UpdatePrefs {

    public static final String KEY_ENABLED = "update.enabled";
    public static final String KEY_SKIP = "update.skip";
    public static final String KEY_LAST_CHECK = "update.last_check";
    public static final String KEY_LAST_SEEN = "update.last_seen";

    private final File file;
    private final Properties props = new Properties();

    public UpdatePrefs(File file) {
        this.file = file;
        load();
    }

    public File file() {
        return file;
    }

    private void load() {
        if (file == null || !file.isFile()) return;
        try (InputStream in = new FileInputStream(file)) {
            props.load(in);
        } catch (Throwable ignored) {
            // 文件坏了就当没配过：更新检查失败不该影响主功能
        }
    }

    /** 写盘。失败也不抛 —— 偏好存不下最多是下次多问一遍 */
    public boolean save() {
        if (file == null) return false;
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            try (OutputStream out = new FileOutputStream(file)) {
                props.store(out, "USBNASLink auto-update preferences");
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 自动更新总开关，默认开 */
    public boolean enabled() {
        String v = props.getProperty(KEY_ENABLED);
        if (v == null) return true;
        return "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim());
    }

    public void setEnabled(boolean on) {
        props.setProperty(KEY_ENABLED, on ? "true" : "false");
        save();
    }

    /** 用户点过「跳过这个版本」的版本号；空串表示没跳过 */
    public String skip() {
        return props.getProperty(KEY_SKIP, "");
    }

    public void setSkip(String version) {
        props.setProperty(KEY_SKIP, version == null ? "" : version);
        save();
    }

    /**
     * 该不该在「现在」发起一次检查。
     *
     * @param minGapMs 两次检查之间的最小间隔。桌面端给 24 小时，
     *                 安卓端给 6 小时（手机带着走，换网络的机会更多）。
     *                 传 0 表示不限，用于用户手动点「检查更新」。
     */
    public boolean due(long minGapMs) {
        if (!enabled()) return false;
        if (minGapMs <= 0) return true;
        long last = lastCheck();
        if (last <= 0) return true;
        return System.currentTimeMillis() - last >= minGapMs;
    }

    public long lastCheck() {
        try {
            return Long.parseLong(props.getProperty(KEY_LAST_CHECK, "0"));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public void markChecked() {
        props.setProperty(KEY_LAST_CHECK, String.valueOf(System.currentTimeMillis()));
        save();
    }

    /** 上次「已经提示过」的版本，用来避免同一个版本每次启动都弹 */
    public String lastSeen() {
        return props.getProperty(KEY_LAST_SEEN, "");
    }

    public void setLastSeen(String version) {
        props.setProperty(KEY_LAST_SEEN, version == null ? "" : version);
        save();
    }

    /** 上次检查时间的人类可读形式，给「上次检查：…」这一行用 */
    public String lastCheckText() {
        long t = lastCheck();
        if (t <= 0) return "从未检查";
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT);
        return f.format(new java.util.Date(t));
    }
}
