package com.littlesauce.usbnaslink

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * 界面语言（中文 / English）。
 *
 * ## 为什么不只靠系统语言
 *
 * Android 的标准做法是 `values-en/strings.xml` + 用户改系统语言，App 什么都不用管。
 * 但这里的用户要么是中文系统想改成英文（给外国朋友看 NAS 里的片子），要么是
 * 英文系统想改成中文（自己看）—— 都要在**不离开 App** 的前提下切。
 * 所以资源表照标准做法建，但语言由 App 自己决定并覆盖系统：
 *
 * 1. [prime] 在 `Activity.onCreate` 最开头把 App 的 Locale 塞进 `resources`，
 *    **必须在 `setContentView` 之前**，否则已经 inflate 的布局拿的还是旧语言。
 * 2. 切换时改 [Prefs.lang]，然后 `activity.recreate()` 让所有页面用新语言重建。
 *
 * ## 技术路线（minSdk 24，两条并存）
 *
 * - API 33+：`LocaleManager.setApplicationLocales()`，这是官方现在推荐的路子，
 *   系统会帮我们处理「App 内语言」和「系统语言」的关系，连 `recreate` 都省了。
 * - API 24–32：没有 LocaleManager，手动改 `Resources.configuration.locales`。
 *
 * 两条都写是因为降级到旧系统时不能悄悄失效 —— 那会比不支持更让人困惑。
 *
 * ## 为什么不放进 Palette
 *
 * `Palette` 管颜色，`ThemeCtl` 管配色同步，`L`（本文件）管语言。
 * 三者都是「App 级设置」，但触发时机不同：颜色要 `recreate`，语言要
 * `recreate`，配色同步要网络。混在一起会变成又一个「什么都管一点」的类。
 */
object L {

    /** 中文（默认）。用 [Lang.ZH] 而不是裸字符串，避免调用点写错。 */
    enum class Lang(val tag: String) {
        ZH("zh"),
        EN("en");

        companion object {
            fun of(tag: String?): Lang =
                if (tag != null && tag.startsWith("en", ignoreCase = true)) EN else ZH
        }
    }

    /** 当前语言。`prime` 之后一定有效；`prime` 之前默认中文。 */
    @Volatile
    var current: Lang = Lang.ZH
        private set

    fun isEnglish(): Boolean = current == Lang.EN

    /**
     * 在 `Activity.onCreate` 最开头调用（**必须早于 `setContentView`**）。
     *
     * 这里同时做两件事：读偏好、把 Locale 灌进 resources。
     * 之所以要求「最早」：`setContentView` 之后布局里的 `@string/xxx`
     * 已经用旧配置解出来了，再改 Configuration 不会让它们重读。
     */
    fun prime(ctx: Context) {
        current = Lang.of(Prefs(ctx).lang)
        applyLocale(ctx)
    }

    /**
     * 切换语言并重建界面。
     *
     * `recreate()` 而不是「逐个控件 setText」：文案散在二十几个文件里，
     * 逐个重设必然漏。重建是唯一能保证「切完就是对的」的做法，
     * 代价只是一次 Activity 重建（几百毫秒）。
     */
    fun switchTo(activity: Activity, lang: Lang) {
        if (lang == current) return
        Prefs(activity).lang = lang.tag
        current = lang
        // API 33+ 走官方接口：它会把已保存的 locales 落到系统侧，
        // 下次冷启动系统直接按这个配置起 Activity，不需要我们再 prime。
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val lm = activity.getSystemService(android.app.LocaleManager::class.java)
                lm?.applicationLocales = LocaleList.forLanguageTags(lang.tag)
                // setApplicationLocales 会自己重建 Activity；再调一次 recreate
                // 会重建两遍（肉眼可见闪两下），所以这里直接返回。
                return
            } catch (e: Throwable) {
                // 某些 ROM 把这个服务阉了，退回手动路线
                Diag.w("L: LocaleManager 不可用，改手动设置", e)
            }
        }
        applyLocale(activity)
        activity.recreate()
    }

    /** 把 [current] 灌进该 Context 的 resources（API 24–32 的主路径）。 */
    private fun applyLocale(ctx: Context) {
        // API 33+ 由 LocaleManager 统一管理，这里再改会和它打架：
        // 系统按 applicationLocales 给每个 Activity 建好配置，
        // 我们手动覆盖反而会让「系统语言」选项失效。
        if (Build.VERSION.SDK_INT >= 33) return
        try {
            val res = ctx.resources
            val cfg = Configuration(res.configuration)
            @Suppress("DEPRECATION")
            cfg.setLocale(Locale.forLanguageTag(current.tag))
            @Suppress("DEPRECATION")
            res.updateConfiguration(cfg, res.displayMetrics)
        } catch (e: Throwable) {
            Diag.w("L: 设置 Locale 失败", e)
        }
    }

    /** 「语言 / Language」那一行右侧显示的当前值。 */
    fun label(): String = if (current == Lang.EN) "English" else "中文"

    /** 语言选项在列表里的顺序（和 [Lang] 枚举顺序一致，别用 `2 - which` 之类的花招）。 */
    fun labels(): Array<String> = arrayOf("中文", "English")
}
