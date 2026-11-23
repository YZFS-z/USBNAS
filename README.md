# USBNAS

把一台闲置的 Android 手机变成局域网里的 NAS。

服务端跑在手机上（插着 U 盘 / SD 卡），把音乐、影视、图书共享到局域网；客户端有 Windows 桌面版和 Android 版，负责浏览、播放、阅读。全部流量走局域网，不经过任何服务器。

```
┌──────────────┐   Wi-Fi / 热点   ┌─────────────────┐
│  旧手机       │ ◄──────────────► │  PC（桌面客户端） │
│  USBNAS 服务端 │                  ├─────────────────┤
│  插 U 盘/存储卡 │                  │  另一台手机(APK)  │
└──────────────┘                  └─────────────────┘
```

## 它解决什么问题

路由器插 U 盘能共享文件，但：

- **没有曲库** —— 只给你一个文件列表，1116 首歌要自己翻目录；它有曲库、按歌手/专辑分类、能读 lrc 歌词。
- **没有刮削** —— 电影只有 `xxx.2019.1080p.mkv` 这种名字；它会读同目录的 nfo 和海报，识别剧集（`S01E02`、`第3集`）。
- **没有阅读器** —— 手机上打开 467 页的 PDF 会黑屏；它内嵌 PDFium 渲染。
- **不支持 WebDAV** —— 它支持，所以能挂载成网络驱动器。

## 组成

| 目录 | 产物 | 说明 |
| --- | --- | --- |
| `server/` | `usbnas-<版本>-arm64-v8a.apk` | 服务端 App，装在当 NAS 的手机上 |
| `client/core/` | （库） | 两端共用的纯 Java：探测、会话、曲库、影视、图书、更新 |
| `client/desktop/` | `USBNASLink-<版本>-win64.zip` | Windows 桌面客户端（Swing + VLCJ） |
| `client/android/` | `usbnaslink-<版本>-arm64-v8a.apk` | Android 客户端 |

## 服务端能做什么

装好、给存储权限、点一下开关就完事，之后浏览器打开 `http://手机IP:8080` 就能用。

- **网页端**：曲库、影视、图书、管理；四套主题（深色/浅色/水墨/护眼）
- **曲库** `/_music?dir=` —— 递归扫描，读 ID3 与 lrc 头部标签；支持按目录浏览
- **影视** `/_videos?dir=` —— 剧集自动归组，nfo 三级查找（直查 → 同级大小写不敏感 → 去尾部画质标记），海报配对
- **图书** `/_books?dir=` —— epub/pdf/mobi/azw3/fb2/djvu/cbz/cbr/txt/md；PDF 用 `<object>` 内嵌而不是 iframe（手机上 iframe 会黑屏）
- **WebDAV** —— 可挂载成网络驱动器
- **在线歌词刮削** —— `/lyrics/search` 查 lrclib + netease，`/lyrics/save` 写回同目录 `.lrc`
- **防烧屏 / U 盘休眠 / 屏保** —— 长期插电当 NAS 用的那台手机需要这些

## 客户端能做什么

**桌面（Windows）**

- 扫描局域网找服务器（UDP 广播 + /24 TCP 探测 + `/_status` 指纹确认）
- 影视 · 音乐 · 图书三个页签；音乐分歌手/专辑，支持顺序/单曲循环/列表循环/随机
- VLCJ 播放全格式音频（flac/ape/wma 都行）、桌面歌词窗口、内嵌视频窗口
- 图书阅读器 + PDF 阅读器

**Android**

- 同样三页签，播放页有黑胶唱片区（自绘 `DiscView`：盘随进度转、唱针随进度沿径向走、切歌时盘左右滑）
- 桌面歌词悬浮窗、刮削歌词、PDF 内嵌渲染（PDFium）
- 音乐/视频/图书/PDF 四页都是沉浸式全屏

## 编译

### 服务端 / Android 客户端

需要 JDK 17 + Android SDK（`compileSdk 35`）。

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/Android/Sdk

cd server        && ./gradlew assembleDebug    # 或 .\gradlew.bat on Windows
cd ../client/android && ./gradlew assembleDebug
```

> 服务端和客户端**不共享** gradle 工程，各自独立构建。
> 客户端把 `client/core/src/main/java` 直接挂进 sourceSets，改 core 两边一起生效。

### 桌面客户端（Windows）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\client\build.ps1
# 产物：client\dist\USBNASLink-<版本>-win64.zip
```

`build.ps1` 需要 `%TEMP%\vlcjsdk\` 里有 VLCJ 运行时：

```
vlcj-4.8.3.jar
vlcj-natives-4.8.3.jar        ← 少了这个会 NoClassDefFoundError
jna-5.13.0.jar
jna-platform-5.13.0.jar
vlc\vlc-3.0.21\               ← 精简过的 VLC 运行时（~108MB）
```

脚本自己会打 fat jar、编 C# 启动器（`csc /target:winexe`）、拼 zip。

## 发版

改版本号（**三处**）：

1. `server/app/src/main/java/com/littlesauce/usbnas/MainActivity.kt` 的 `APP_VER`
   （`HttpServer.APP_VER` 直接引用它，不用重复改）
2. `client/desktop/.../UpdUI.java` 的 `Build.VERSION`
3. `client/android/app/build.gradle.kts` 的 `versionName` / `versionCode`

打 tag `v1.0.1`，Release 附件命名必须是：

```
usbnas-1.0.1-arm64-v8a.apk          ← 服务端
usbnaslink-1.0.1-arm64-v8a.apk      ← 客户端 APK
USBNASLink-1.0.1-win64.zip          ← 桌面端
```

客户端与服务端按**后缀**挑资产（`-win64.zip` / `-arm64-v8a.apk`），所以名字里的版本号跟着 tag 走就行。

## 自动更新

三端都从本仓库的 Release 拉取，都**可以关**。

| 端 | 默认 | 开关位置 | 行为 |
| --- | --- | --- | --- |
| 服务端 | 关 | App 首页「自动更新」按钮；网页 `/_admin` | 只提示不自行安装 —— 那台机器的屏幕常盖在抽屉里 |
| 桌面 | 开 | 「管理设置」页底部 | 下载 zip、解包校验、写换文件脚本、退出后覆盖 |
| Android | 开 | 媒体库「设置」按钮 | 下载到 cache，交给系统安装器 |

间隔：服务端 12 小时，桌面 24 小时，APK 6 小时。检查只发一个 GET，匿名访问，不消耗 token。

## 隐私

这个项目是给自己家里用的，所以：

- 不收集任何数据，没有埋点、没有统计、没有崩溃上报
- 唯一的对外请求是 `api.github.com` 查新版本（可关）
- 局域网内没有认证也能看**公开目录**；私有目录需要登录（服务端设账号）

## 许可

MIT，见 [LICENSE](LICENSE)。
