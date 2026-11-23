package com.littlesauce.usbnas;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 账号与权限模型。
 *
 * 三个权限等级（level）：
 *   0 = 只读   READ      只能浏览 / 播放 / 下载
 *   1 = 读写   WRITE     额外可以上传(PUT)、新建目录(MKCOL)、改名(MOVE)
 *   2 = 管理   ADMIN     WRITE 的全部 + 删除(DELETE)
 *
 * 存储：SharedPreferences("usbnas_auth")，键 users 为一个序列化字符串。
 *
 * 每行一个账号，字段用 TAB（\u0009）分隔：
 *   username \t level \t sha256(salt+password) \t salt \t scopes
 *
 * scopes 是允许写的目录前缀，用 \u0003 分隔；空串表示「不限目录」（全盘）。
 * 旧版只有 4 个字段，读的时候按 f.length 兼容，缺省即全盘。
 *
 * 注意：早先用的是 \u0001 / \u0002 做分隔符，但 SharedPreferences 存的是 XML 文件，
 * 而 XML 1.0 不允许这些控制字符——写进去再读回来会被吞掉，
 * 结果是 level 之后整段糊成一坨、scopes 永远读到空。
 * 所以分隔符必须是 XML 允许的字符：TAB 与 U+0003 里只留 TAB 是安全的，
 * 这里用 TAB 做一级分隔、\u0003 二级分隔（\u0003 在 XML 里同样非法，
 * 因此二级分隔实际改用 '\\u001E' 的可见替身 —— 见 SEP2 定义）。
 *
 * 密码只存哈希，salt 每个账号随机 8 字节 hex。
 */
public final class Users {

    /** 权限等级 */
    public static final int LEVEL_READ = 0;
    public static final int LEVEL_WRITE = 1;
    public static final int LEVEL_ADMIN = 2;

    private static final String PREF = "usbnas_auth";
    private static final String KEY_USERS = "users";
    private static final String KEY_ANON = "allow_anonymous";

    /** 一级字段分隔符：TAB，XML 1.0 合法字符 */
    private static final char SEP = '\t';
    /** 二级（多值）分隔符：竖线，XML 1.0 合法字符 */
    private static final char SEP2 = '|';

    /** 单条账号记录 */
    public static final class User {
        public final String name;
        public final int level;
        public final String hash;
        public final String salt;
        /**
         * 允许写入的目录前缀列表；空表示不限目录。
         * 只影响写/删，读一律放行（只读账号本来就能浏览全盘）。
         */
        public final List<String> scopes;

        User(String name, int level, String hash, String salt, List<String> scopes) {
            this.name = name;
            this.level = level;
            this.hash = hash;
            this.salt = salt;
            this.scopes = scopes == null ? new ArrayList<String>() : scopes;
        }
        /** 是否限定了目录 */
        public boolean hasScope() {
            return !scopes.isEmpty();
        }

        /** 目录范围的人类可读描述，给 UI 用 */
        public String scopeText() {
            if (scopes.isEmpty()) return "全部目录";
            StringBuilder sb = new StringBuilder();
            for (String s : scopes) {
                if (sb.length() > 0) sb.append("、");
                sb.append(shortScope(s));
            }
            return sb.toString();
        }
    }

    /** 把 /mnt/media_rw/18DCB030DCB00A50/电影 缩短成 盘0/电影 */
    static String shortScope(String p) {
        if (p == null || p.isEmpty()) return "/";
        String s = p;
        for (String uuid : new String[]{
                "18DCB030DCB00A50", "5018A95918A93EBA"}) {
            int i = s.indexOf("/mnt/media_rw/" + uuid);
            if (i == 0) {
                s = "/mnt/media_rw/" + uuid + "/";
                String rest = p.substring(s.length());
                return rest.isEmpty() ? uuid : uuid + "/" + rest;
            }
        }
        if (s.startsWith("/storage/emulated/0")) {
            String rest = s.substring("/storage/emulated/0".length());
            return rest.isEmpty() ? "内部存储" : "内部存储" + rest;
        }
        return s;
    }

    private Users() {}

    // ==================== 等级文案 ====================

    public static String levelName(int level) {
        switch (level) {
            case LEVEL_ADMIN: return "管理员";
            case LEVEL_WRITE: return "读写";
            default: return "只读";
        }
    }

    public static String levelDesc(int level) {
        switch (level) {
            case LEVEL_ADMIN: return "浏览 · 播放 · 上传 · 改名 · 删除";
            case LEVEL_WRITE: return "浏览 · 播放 · 上传 · 改名";
            default: return "浏览 · 播放 · 下载";
        }
    }

    // ==================== 读写 ====================

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static List<User> list(Context c) {
        String raw = sp(c).getString(KEY_USERS, "");
        List<User> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            String[] f = line.split(String.valueOf(SEP), -1);
            if (f.length < 4) continue;
            try {
                List<String> scopes = new ArrayList<>();
                if (f.length >= 5 && !f[4].isEmpty()) {
                    for (String s : f[4].split("\\" + SEP2)) {
                        if (!s.isEmpty()) scopes.add(s);
                    }
                }
                out.add(new User(f[0], Integer.parseInt(f[1].trim()), f[2], f[3], scopes));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static void save(Context c, List<User> users) {
        StringBuilder sb = new StringBuilder();
        for (User u : users) {
            if (sb.length() > 0) sb.append('\n');
            StringBuilder sc = new StringBuilder();
            for (String s : u.scopes) {
                if (sc.length() > 0) sc.append(SEP2);
                sc.append(s);
            }
            sb.append(u.name).append(SEP)
                    .append(u.level).append(SEP)
                    .append(u.hash).append(SEP)
                    .append(u.salt).append(SEP)
                    .append(sc);
        }
        sp(c).edit().putString(KEY_USERS, sb.toString()).apply();
    }

    /** 新增或覆盖同名账号。password 为空时保留原密码。返回 false 表示用户名为空。 */
    public static boolean upsert(Context c, String name, int level, String password) {
        return upsertEx(c, name, level, password) == null;
    }

    /**
     * 同上，但返回错误文案；成功返回 null。
     * 「新建账号却没给密码」也会返回错误。
     */
    public static String upsertEx(Context c, String name, int level, String password) {
        return upsertEx(c, name, level, password, null);
    }

    /**
     * @param scopes 允许写的目录前缀；null 表示不改动原值，空列表表示不限目录
     */
    public static String upsertEx(Context c, String name, int level, String password,
                                  List<String> scopes) {
        if (name == null) return "用户名不能为空";
        name = name.trim();
        if (name.isEmpty()) return "用户名不能为空";
        if (name.indexOf('\u0001') >= 0) return "用户名不能含控制字符";
        if (password != null && password.isEmpty()) password = null;

        List<String> clean = null;
        if (scopes != null) {
            clean = new ArrayList<>();
            for (String s : scopes) {
                if (s == null) continue;
                String t = s.trim();
                while (t.endsWith("/") && t.length() > 1) {
                    t = t.substring(0, t.length() - 1);
                }
                if (!t.isEmpty() && !clean.contains(t)) clean.add(t);
            }
            if (clean.isEmpty()) clean = new ArrayList<>();
        }

        List<User> users = list(c);
        String salt = null, hash = null;
        List<String> oldScopes = null;
        boolean exists = false;
        for (User u : users) {
            if (u.name.equals(name)) {
                salt = u.salt;
                hash = u.hash;
                oldScopes = u.scopes;
                exists = true;
                break;
            }
        }
        if (password != null) {
            salt = randomSalt();
            hash = sha256(salt + password);
        }
        if (hash == null) {
            // 新账号必须给密码
            return "新账号必须设置密码";
        }
        if (!exists && password == null) return "新账号必须设置密码";

        List<String> finalScopes = clean != null ? clean
                : (oldScopes != null ? oldScopes : new ArrayList<String>());

        boolean replaced = false;
        List<User> next = new ArrayList<>();
        for (User u : users) {
            if (u.name.equals(name)) {
                next.add(new User(name, level, hash, salt, finalScopes));
                replaced = true;
            } else {
                next.add(u);
            }
        }
        if (!replaced) next.add(new User(name, level, hash, salt, finalScopes));
        save(c, next);
        return null;
    }

    public static void remove(Context c, String name) {
        List<User> users = list(c);
        List<User> next = new ArrayList<>();
        for (User u : users) {
            if (!u.name.equals(name)) next.add(u);
        }
        save(c, next);
    }

    public static void clear(Context c) {
        sp(c).edit().remove(KEY_USERS).apply();
    }

    // ==================== 匿名访问 ====================

    public static boolean allowAnonymous(Context c) {
        return sp(c).getBoolean(KEY_ANON, true);
    }

    public static void setAllowAnonymous(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_ANON, v).apply();
    }

    // ==================== 校验 ====================

    /** 返回匹配到的用户；无匹配返回 null */
    public static User verify(Context c, String name, String password) {
        if (name == null || password == null) return null;
        for (User u : list(c)) {
            if (u.name.equals(name) && u.hash.equals(sha256(u.salt + password))) {
                return u;
            }
        }
        return null;
    }

    /** 该等级是否允许写（PUT/MKCOL/MOVE） */
    public static boolean canWrite(int level) {
        return level >= LEVEL_WRITE;
    }

    /** 该等级是否允许删除 */
    public static boolean canDelete(int level) {
        return level >= LEVEL_ADMIN;
    }

    // ==================== 目录范围 ====================

    /**
     * 该用户能否写/删 absPath 这个绝对路径。
     *
     * scopes 为空表示不限目录（全盘放行）。
     * 否则要求 absPath 落在某个 scope 之内——注意是「之内」不是「等于」：
     * 授权 /mnt/media_rw/XXX/电影 时，它下面的子目录和文件都算。
     * 同时要挡住 /mnt/media_rw/XXX/电影2 这种前缀相同的兄弟目录，
     * 所以比较时补一个分隔符。
     */
    /**
     * 判断 absPath 是否落在 u 的授权目录里。
     *
     * 注意 absPath 必须先规范化（解掉 .. 与符号链接），否则
     *     /scope/inside/../outside/x
     * 字面上确实以 /scope/inside 开头，会被判成"在范围内"，
     * 而真正落盘的 File 已经被 canonical 解析到 outside 去了——
     * 这就是一条穿越漏洞。调用方传 File 时请用 getCanonicalPath()。
     */
    public static boolean inScope(User u, String absPath) {
        if (u == null) return false;
        if (u.scopes.isEmpty()) return true;
        if (absPath == null || absPath.isEmpty()) return false;
        String p = norm(absPath);
        for (String scope : u.scopes) {
            String s = norm(scope);
            if (s.isEmpty()) return true;
            if (p.equals(s)) return true;
            if (p.startsWith(s.endsWith("/") ? s : s + "/")) return true;
        }
        return false;
    }

    /** 对 File 求 canonical 路径再判范围，避免 .. 穿越 */
    public static boolean inScope(User u, java.io.File f) {
        if (f == null) return false;
        String p;
        try {
            p = f.getCanonicalPath();
        } catch (Exception e) {
            p = f.getAbsolutePath();
        }
        if (p != null && p.contains("..")) return false;
        return inScope(u, p);
    }

    /** 去掉多余的斜杠与末尾斜杠，好做前缀比较；顺带消掉 . 与 .. */
    private static String norm(String p) {
        String s = p.replaceAll("/{2,}", "/");
        // 路径分段归一：空段与 "." 丢弃，".." 回退一级
        if (s.indexOf("/.") >= 0) {
            ArrayList<String> seg = new ArrayList<>();
            for (String part : s.split("/")) {
                if (part.isEmpty() || part.equals(".")) continue;
                if (part.equals("..")) {
                    if (!seg.isEmpty()) seg.remove(seg.size() - 1);
                    continue;
                }
                seg.add(part);
            }
            StringBuilder b = new StringBuilder();
            for (String part : seg) b.append('/').append(part);
            if (b.length() == 0) b.append('/');
            s = b.toString();
        }
        while (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    // ==================== 哈希 ====================

    private static String randomSalt() {
        byte[] b = new byte[8];
        new java.security.SecureRandom().nextBytes(b);
        return hex(b);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return hex(d);
        } catch (Exception e) {
            return "";
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }

    // ==================== 快照（供 HttpServer 用，避免每次请求读 SP） ====================

    /** 不可变快照，HttpServer 持有一份，账号变更时热替换 */
    public static final class Snapshot {
        public final Map<String, User> byName;
        public final boolean anonymous;

        Snapshot(Map<String, User> byName, boolean anonymous) {
            this.byName = byName;
            this.anonymous = anonymous;
        }

        public static Snapshot of(Context c) {
            Map<String, User> m = new LinkedHashMap<>();
            for (User u : list(c)) m.put(u.name, u);
            return new Snapshot(m, allowAnonymous(c));
        }

        public User verify(String name, String password) {
            User u = byName.get(name);
            if (u == null || password == null) return null;
            return u.hash.equals(sha256(u.salt + password)) ? u : null;
        }
    }
}
