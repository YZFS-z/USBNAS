package com.littlesauce.usbnaslink.desktop;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * 桌面端本地偏好：目前只有「日间/夜间」这一项。
 *
 * <p>为什么不复用 core 的 {@code Prefs}：那个类是安卓的（走
 * {@code SharedPreferences}），桌面端有自己的 {@link Store} 风格（纯文本文件）。
 * 这里刻意做成一个独立小文件，坏了大不了删掉重来，不会连带别的配置一起丢。
 *
 * <p>键：{@code theme=dark|light}。读写都不抛异常 —— 偏好丢了就用默认深色，
 * 界面能正常起来比「配置一定正确」重要得多。
 */
final class ThemePrefs {

    private static final String FILE = "theme.properties";
    private static final String KEY = "theme";

    private ThemePrefs() {
    }

    private static File file() {
        // 与 known.txt / update.properties 同目录（%APPDATA%\USBNASLink）。
        // **不能用 usbnaslink.home**：那是临时解包目录，升级时会整目录替换，
        // 偏好放那里等于每次更新都丢掉。
        String appdata = System.getenv("APPDATA");
        File dir = appdata != null && !appdata.isEmpty()
                ? new File(appdata, "USBNASLink")
                : new File(System.getProperty("user.home", "."), ".usbnaslink");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            dir = new File(".").getAbsoluteFile();
        }
        return new File(dir, FILE);
    }

    /** 上次存的模式是不是日间。读不到一律按夜间算（和网页端默认一致） */
    static boolean isDay() {
        Properties p = load();
        return "light".equalsIgnoreCase(p.getProperty(KEY, ""));
    }

    static void setDay(boolean day) {
        Properties p = load();
        p.setProperty(KEY, day ? "light" : "dark");
        try (FileOutputStream out = new FileOutputStream(file())) {
            p.store(out, "USBNASLink desktop prefs");
        } catch (IOException ignored) {
        }
    }

    private static Properties load() {
        Properties p = new Properties();
        File f = file();
        if (!f.isFile()) return p;
        try (FileInputStream in = new FileInputStream(f)) {
            p.load(in);
        } catch (IOException ignored) {
        }
        return p;
    }
}
