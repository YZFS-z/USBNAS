package android.support.v4.util

/**
 * 兼容壳：给 Material Components 留下的旧 support 引用一个落脚点。
 *
 * 背景：客户端 APK 的 classes.dex 里，`Lcom/google/android/material/color/MaterialColors;`
 * 的 `resolveColor` 方法引用了一条 `Landroid/support/v4/util/ArrayMap;` 描述符
 * （Material 编译期能看见 support，但 AndroidX 迁移后的产物并不带它）。
 * 平时没人调这条路径就没事，一旦被解析就抛
 * `Failed resolution of: Landroid/support/v4/util/ArrayMap;`。
 *
 * 这里继承 AndroidX 的实现，既满足运行时的类解析，又不额外引依赖。
 */
class ArrayMap<K : Any, V : Any> : androidx.collection.ArrayMap<K, V> {
    constructor() : super()

    constructor(capacity: Int) : super(capacity)
}
