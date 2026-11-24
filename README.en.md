# USBNAS

> [中文版本](README.md)

Turn a spare Android phone into a NAS on your LAN.

The server runs on the phone (with a USB drive or SD card plugged in) and shares music, video and books over the local network. Clients come in two flavours — Windows desktop and Android — and handle browsing, playback and reading. All traffic stays on your network; nothing goes through anyone else's server.

```
┌──────────────┐   Wi-Fi / hotspot   ┌──────────────────┐
│ Old phone     │ ◄─────────────────► │ PC (desktop app) │
│ USBNAS server │                     ├──────────────────┤
│ USB drive/SD  │                     │ another phone    │
└──────────────┘                     └──────────────────┘
```

## What it does that a router's USB share doesn't

A router with a USB port can share files. But it gives you:

- **No music library** — just a file listing, so you dig through folders to find one of 1116 songs. This one scans a library, groups by artist/album and reads `.lrc` lyrics.
- **No scraping** — a movie is called `xxx.2019.1080p.mkv` and that's it. This one reads the `.nfo` and poster sitting next to it, and groups episodes (`S01E02`, `第3集`).
- **No reader** — a 467-page PDF on a phone is a black screen. This one renders it with embedded PDFium.
- **No WebDAV** — this one speaks it, so you can mount the phone as a network drive.

## What's in the box

| Folder | Artifact | Notes |
| --- | --- | --- |
| `server/` | `usbnas-<ver>-arm64-v8a.apk` | The server app, installed on the phone that becomes the NAS |
| `client/core/` | (library) | Plain Java shared by both clients: probing, sessions, library models, updates |
| `client/desktop/` | `USBNASLink-<ver>-win64.zip` | Windows desktop client (Swing + VLCJ) |
| `client/android/` | `usbnaslink-<ver>-arm64-v8a.apk` | Android client |

## Server features

Install it, grant storage access, flip the switch. Then open `http://<phone-ip>:8080` in any browser.

- **Web UI** — library, video, books, admin; four themes (dark / light / ink / eye-care)
- **Music** `/_music?dir=` — recursive scan, reads ID3 and `.lrc` header tags; browsable by folder
- **Video** `/_videos?dir=` — episodes auto-grouped; `.nfo` looked up three ways (exact → case-insensitive sibling → stem with quality tags stripped); posters paired
- **Books** `/_books?dir=` — epub/pdf/mobi/azw3/fb2/djvu/cbz/cbr/txt/md; PDFs render through `<object>` rather than an iframe (iframes go black on phones)
- **WebDAV** — mount it as a network drive
- **Online lyric scraping** — `/lyrics/search` queries lrclib + netease; `/lyrics/save` writes a `.lrc` next to the track
- **Burn-in protection / USB sleep / screensaver** — the things a phone left plugged in as a NAS actually needs

## Client features

**Desktop (Windows)**

- Finds servers on the LAN (UDP broadcast + `/24` TCP sweep + `/_status` fingerprint confirmation)
- Video · Music · Books tabs; music grouped by artist/album, with in-order / repeat-one / repeat-all / shuffle
- VLCJ plays every audio format (flac, ape, wma all fine), floating lyric window, embedded video window
- Book reader and PDF reader

**Android**

- Same three tabs; the player has a drawn vinyl record (`DiscView` — the platter follows playback progress, the tonearm tracks radially, records slide sideways on track change)
- Floating on-screen lyrics, lyric scraping, PDF rendered inline via PDFium
- Music, video, book and PDF pages are all edge-to-edge full screen

## Building

### Server / Android client

JDK 17 and the Android SDK (`compileSdk 35`).

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/Android/Sdk

cd server           && ./gradlew assembleDebug    # .\gradlew.bat on Windows
cd ../client/android && ./gradlew assembleDebug
```

> The server and the client are **separate** Gradle projects and build independently.
> The client mounts `client/core/src/main/java` straight into its sourceSets, so core changes affect both clients at once.

### Desktop client (Windows)

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\client\build.ps1
# output: client\dist\USBNASLink-<ver>-win64.zip
```

`build.ps1` expects the VLCJ runtime in `%TEMP%\vlcjsdk\`:

```
vlcj-4.8.3.jar
vlcj-natives-4.8.3.jar        ← without this you get NoClassDefFoundError
jna-5.13.0.jar
jna-platform-5.13.0.jar
vlc\vlc-3.0.21\               ← trimmed VLC runtime (~108 MB)
```

The script builds the fat jar, compiles the C# launcher (`csc /target:winexe`) and assembles the zip itself.

## Releasing

Bump the version in **three** places:

1. `APP_VER` in `server/app/src/main/java/com/littlesauce/usbnas/MainActivity.kt`
   (`HttpServer.APP_VER` references it, so no need to touch that)
2. `Build.VERSION` in `client/desktop/.../UpdUI.java`
3. `versionName` / `versionCode` in `client/android/app/build.gradle.kts`

Tag `v1.0.1` and name the release assets exactly:

```
usbnas-1.0.1-arm64-v8a.apk          ← server
usbnaslink-1.0.1-arm64-v8a.apk      ← client APK
USBNASLink-1.0.1-win64.zip          ← desktop
```

Clients pick their asset by **suffix** (`-win64.zip` / `-arm64-v8a.apk`), so the version in the filename just follows the tag.

## Auto-update

All three components pull from this repo's Releases, and all three **can be switched off**.

| Component | Default | Where the switch is | Behaviour |
| --- | --- | --- | --- |
| Server | off | "Auto-update" on the app's home screen; `/_admin` on the web UI | Only notifies, never self-installs — that phone usually lives in a drawer |
| Desktop | on | Bottom of the Settings tab | Downloads the zip, unpacks and verifies it, writes a swap script, replaces files after exit |
| Android | on | Settings button in the library | Downloads to cache, hands it to the system installer |

Intervals: server 12 h, desktop 24 h, APK 6 h. A check is a single anonymous GET — no token, no cost.

## Privacy

This is a project for one household, so:

- No data collection — no analytics, no telemetry, no crash reporting
- The only outbound request is `api.github.com` checking for a new version (can be disabled)
- On the LAN, **public folders** are readable without signing in; private content needs an account (set one up on the server)

## License

MIT — see [LICENSE](LICENSE).
