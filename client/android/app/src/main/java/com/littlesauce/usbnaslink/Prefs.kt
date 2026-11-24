package com.littlesauce.usbnaslink

import android.content.Context
import android.content.SharedPreferences

/**
 * 记住上次连成功的那台 NAS。
 *
 * 为什么不复用 core 的 Store（那个是给桌面端写的、吃 java.io.File）：
 * 安卓侧没有「随手写文件」的合理位置 —— 内部存储要给用户备份就得进 SharedPreferences
 * 或 Room，而这里只有三个字符串，SharedPreferences 一行搞定。
 */
class Prefs(ctx: Context) {

    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("usbnaslink", Context.MODE_PRIVATE)

    var lastUrl: String?
        get() = sp.getString(KEY_URL, null)
        set(v) = sp.edit().putString(KEY_URL, v).apply()

    var lastName: String?
        get() = sp.getString(KEY_NAME, null)
        set(v) = sp.edit().putString(KEY_NAME, v).apply()

    var userName: String?
        get() = sp.getString(KEY_USER, null)
        set(v) = sp.edit().putString(KEY_USER, v).apply()

    /** 会话 id：服务端 usbnas_sid。重启 App 后还能复用，直到服务端重启。 */
    var sid: String?
        get() = sp.getString(KEY_SID, null)
        set(v) = sp.edit().putString(KEY_SID, v).apply()

    fun clearSession() {
        sp.edit().remove(KEY_SID).apply()
    }

    // ------------------------------------------------------------ 自动更新
    //
    // 三个键放这里而不是新开一个文件：自动更新的状态本来就和「上次连的是哪台」
    // 同属「这个 App 自己的小状态」，拆成两个文件只会让备份/迁移多一步。

    /** 自动更新开关。默认开 —— 手机是随身设备，替用户留意新版本是应该的 */
    var autoUpdate: Boolean
        get() = sp.getBoolean(KEY_AUTO_UPDATE, true)
        set(v) {
            sp.edit().putBoolean(KEY_AUTO_UPDATE, v).apply()
        }

    /** 用户点了「跳过这个版本」的号；空串表示没跳过 */
    var skipVersion: String
        get() = sp.getString(KEY_SKIP_VERSION, "") ?: ""
        set(v) {
            sp.edit().putString(KEY_SKIP_VERSION, v).apply()
        }

    /** 上次检查更新的 wall clock 毫秒（要跨重启保留，所以不能存 elapsedRealtime） */
    var lastUpdateCheck: Long
        get() = sp.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(v) {
            sp.edit().putLong(KEY_LAST_UPDATE_CHECK, v).apply()
        }

    // ------------------------------------------------------------ 登录与配色

    /**
     * 「记住该设备」复选框的勾选状态。
     *
     * 它只决定登录表单里要不要带 remember=1（服务端据此把 Cookie 的 Max-Age
     * 从一天放成一年）。勾选状态本身也存下来，下次登录框默认就是勾上的 ——
     * 用户勾过一次通常不想再勾。
     */
    var remember: Boolean
        get() = sp.getBoolean(KEY_REMEMBER, false)
        set(v) {
            sp.edit().putBoolean(KEY_REMEMBER, v).apply()
        }

    /**
     * 上次从服务端拿到的主题（`dark` / `light` / `ink`）。
     *
     * 为什么要缓存：主题的真源在服务端，但网络断掉或服务端还没起时，
     * 界面不该因为「问不到主题」而闪回默认色。启动时先用这个离线值上色，
     * 拿到服务端的答案之后再纠正。
     */
    var theme: String
        get() = sp.getString(KEY_THEME, "dark") ?: "dark"
        set(v) {
            sp.edit().putString(KEY_THEME, v).apply()
        }

    private companion object {
        const val KEY_URL = "last_url"
        const val KEY_NAME = "last_name"
        const val KEY_USER = "last_user"
        const val KEY_SID = "sid"
        const val KEY_AUTO_UPDATE = "auto_update"
        const val KEY_SKIP_VERSION = "skip_version"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        const val KEY_REMEMBER = "remember_device"
        const val KEY_THEME = "theme"
    }
}
