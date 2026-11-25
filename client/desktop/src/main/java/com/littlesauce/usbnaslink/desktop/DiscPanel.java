package com.littlesauce.usbnaslink.desktop;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 唱片视图（桌面版）。
 *
 * 跟安卓那边 {@code DiscView} 是同一套观感：黑胶盘 + 一圈圈纹路 + 内圈封面贴纸 +
 * 右上角可以落下来的唱针。桌面端没有 View 的 onDraw/invalidate，所以用
 * 一个 16ms 的 {@link javax.swing.Timer} 驱动 repaint —— 只在转动时才跑，
 * 停下就 stop，别让一个静止的窗口一直烧 CPU。
 *
 * 为什么不用动画库或 GIF：GIF 不能按播放进度停针，也没法和主题换色。
 */
final class DiscPanel extends JComponent {

    /** 一圈多少度。手机上大约是 3 分钟 6 圈，这里取整数好看。 */
    private static final float SPIN_DEG_PER_SEC = 30f;
    /** 唱针落点：外圈（开头）到内圈（结尾）的半径比例。 */
    private static final float TRACK_OUT = 0.86f;
    private static final float TRACK_IN = 0.44f;
    /** 内圈贴纸半径比例。唱针内圈必须比它大，否则针尖会扎到封面正中。 */
    private static final float COVER_R = 0.34f;

    private final Timer clock = new Timer(16, e -> tick());

    private BufferedImage cover;
    private Image coverScaled;
    private boolean spinning;
    private float angleDeg;
    private long lastAt = System.currentTimeMillis();
    private double frac;
    private String tip = "未在播放";

    DiscPanel() {
        setOpaque(true);
        setBackground(Theme.BG_DEEP);
        setPreferredSize(new Dimension(420, 420));
        setMinimumSize(new Dimension(240, 240));
    }

    void setTip(String s) {
        tip = s == null ? "" : s;
        repaint();
    }

    /** 换封面。null = 没有封面，画默认贴纸。 */
    void setCover(BufferedImage img) {
        cover = img;
        coverScaled = null;
        repaint();
    }

    void setSpinning(boolean on) {
        if (spinning == on) return;
        spinning = on;
        lastAt = System.currentTimeMillis();
        if (on) clock.start();
        else clock.stop();
        repaint();
    }

    /** 进度只影响唱针落点，不影响转速。total<=0 时保持原样。 */
    void setProgress(long posMs, long totalMs) {
        double f = totalMs > 0 ? Math.max(0.0, Math.min(1.0, posMs / (double) totalMs)) : frac;
        if (Math.abs(f - frac) < 0.0005) return;
        frac = f;
        repaint();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long dt = Math.max(0L, Math.min(200L, now - lastAt));
        lastAt = now;
        angleDeg = (angleDeg + SPIN_DEG_PER_SEC * dt / 1000f) % 360f;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());

            int w = getWidth();
            int h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float r = Math.min(w, h) / 2f - 10f;
            if (r < 20f) return;

            drawDisc(g, cx, cy, r);
            drawArm(g, cx, cy, r);
        } finally {
            g.dispose();
        }
    }

    private void drawDisc(Graphics2D g, float cx, float cy, float r) {
        // 盘体：深色渐变 + 高光
        RadialGradientPaint body = new RadialGradientPaint(
                new Point2D.Float(cx - r * 0.15f, cy - r * 0.20f), r * 1.25f,
                new float[]{0f, 0.55f, 1f},
                new Color[]{new Color(0x2a2f38), new Color(0x12161c), new Color(0x0a0d11)});
        g.setPaint(body);
        g.fillOval((int) (cx - r), (int) (cy - r), (int) (r * 2), (int) (r * 2));

        // 纹路：跟着转动才会在视觉上"看得出在转"
        g.setStroke(new BasicStroke(Math.max(1f, r * 0.004f)));
        for (int i = 1; i <= 9; i++) {
            float rr = r * (0.36f + 0.06f * i);
            if (rr >= r) break;
            g.setColor(new Color(255, 255, 255, i % 2 == 0 ? 12 : 7));
            g.drawOval((int) (cx - rr), (int) (cy - rr), (int) (rr * 2), (int) (rr * 2));
        }
        // 一条随角度转的亮带，让静止时也能看出相位
        AffineTransform old = g.getTransform();
        g.rotate(Math.toRadians(angleDeg), cx, cy);
        g.setStroke(new BasicStroke(Math.max(1.2f, r * 0.012f)));
        g.setColor(new Color(255, 255, 255, 26));
        g.drawLine((int) cx, (int) (cy - r * 0.95f), (int) cx, (int) (cy - r * 0.42f));
        g.setTransform(old);

        // 内圈贴纸（封面）
        float lr = r * COVER_R;
        g.setColor(new Color(0x0d1117));
        g.fillOval((int) (cx - lr), (int) (cy - lr), (int) (lr * 2), (int) (lr * 2));
        Image pic = coverImage();
        if (pic != null) {
            Shape clip = g.getClip();
            g.setClip(new java.awt.geom.Ellipse2D.Float(cx - lr, cy - lr, lr * 2, lr * 2));
            g.drawImage(pic, (int) (cx - lr), (int) (cy - lr), (int) (lr * 2), (int) (lr * 2), null);
            g.setClip(clip);
        } else {
            Paint lb = new LinearGradientPaint(
                    new Point2D.Float(cx - lr, cy - lr), new Point2D.Float(cx + lr, cy + lr),
                    new float[]{0f, 1f},
                    new Color[]{new Color(0x1f5fc4), new Color(0x123a78)});
            g.setPaint(lb);
            g.fillOval((int) (cx - lr), (int) (cy - lr), (int) (lr * 2), (int) (lr * 2));
            g.setColor(new Color(0xe6e9ef));
            g.setFont(Theme.bold(Math.max(9, (int) (lr * 0.20f))));
            drawCenter(g, tip, cx, cy - lr * 0.12f);
            g.setColor(new Color(0xafc3dc));
            g.setFont(Theme.ui(Math.max(8, (int) (lr * 0.13f))));
            drawCenter(g, "USBNAS", cx, cy + lr * 0.26f);
        }

        // 中心孔
        float hole = Math.max(3f, r * 0.022f);
        g.setColor(new Color(0x05070a));
        g.fillOval((int) (cx - hole), (int) (cy - hole), (int) (hole * 2), (int) (hole * 2));
        g.setColor(new Color(0x3a4350));
        g.setStroke(new BasicStroke(1.2f));
        g.drawOval((int) (cx - hole), (int) (cy - hole), (int) (hole * 2), (int) (hole * 2));

        // 外缘描边
        g.setColor(new Color(0x2b323c));
        g.setStroke(new BasicStroke(Math.max(1f, r * 0.008f)));
        g.drawOval((int) (cx - r), (int) (cy - r), (int) (r * 2), (int) (r * 2));
    }

    private Image coverImage() {
        if (cover == null) return null;
        int side = (int) (Math.min(getWidth(), getHeight()) * COVER_R * 2f);
        if (side < 8) return null;
        if (coverScaled == null) {
            try {
                BufferedImage out = new BufferedImage(side, side, BufferedImage.TYPE_INT_RGB);
                Graphics2D gg = out.createGraphics();
                gg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                gg.drawImage(cover, 0, 0, side, side, null);
                gg.dispose();
                coverScaled = out;
            } catch (Throwable t) {
                return null;
            }
        }
        return coverScaled;
    }

    private void drawArm(Graphics2D g, float cx, float cy, float r) {
        float px = cx + r * 0.80f;
        float py = cy - r * 0.86f;
        float rad = r * (TRACK_OUT - (TRACK_OUT - TRACK_IN) * (float) frac);
        double ang = Math.toRadians(225);
        float tx = cx + (float) (rad * Math.cos(ang));
        float ty = cy + (float) (rad * Math.sin(ang));

        Path2D.Float p = new Path2D.Float();
        p.moveTo(px, py);
        p.quadTo((px + tx) / 2f + r * 0.06f, (py + ty) / 2f - r * 0.04f, tx, ty);
        g.setColor(new Color(0xb9c4d2));
        g.setStroke(new BasicStroke(Math.max(2f, r * 0.012f), BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        g.draw(p);

        // 针尖蓝点
        float head = Math.max(3f, r * 0.014f);
        g.setColor(new Color(0x2f80ed));
        g.fillOval((int) (tx - head), (int) (ty - head), (int) (head * 2), (int) (head * 2));

        // 支点底座
        float base = Math.max(5f, r * 0.026f);
        g.setColor(new Color(0x8d9aa9));
        g.fillOval((int) (px - base), (int) (py - base), (int) (base * 2), (int) (base * 2));
        g.setColor(new Color(0x2f80ed));
        float inner = base * 0.55f;
        g.fillOval((int) (px - inner), (int) (py - inner), (int) (inner * 2), (int) (inner * 2));
    }

    private void drawCenter(Graphics2D g, String s, float cx, float y) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, cx - fm.stringWidth(s) / 2f, y + fm.getAscent() / 2f);
    }

    /** 拉一张图当封面。必须在线程外调用（会阻塞）。 */
    static BufferedImage download(String url, String cookie) {
        if (url == null || url.isEmpty()) return null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "USBNASLink/1.0");
            if (cookie != null && !cookie.isEmpty()) {
                c.setRequestProperty("Cookie", com.littlesauce.usbnaslink.Session.cookieHeader(cookie));
            }
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                return ImageIO.read(in);
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
