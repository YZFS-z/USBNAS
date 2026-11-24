# Builds the USBNASLink desktop app. Kept in pure ASCII on purpose:
# Windows PowerShell 5.1 mis-decodes a UTF-8 script as GBK on a zh-CN box,
# which turns Chinese comments into broken bytes and breaks the parser.
# All user-facing Chinese strings live in the Java sources instead.
param(
    [switch]$Verify,
    [switch]$Clean,
    [string]$Jdk = "C:\Program Files\Java\jdk-17"
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
# jpackage copies everything under --input into the app image; deep paths trip
# its 32000-character limit, so intermediates go to a short %TEMP% path and
# only the final zip lands in the project's dist/ folder.
# Version: keep in sync with UpdUI.Build.VERSION and gradle versionName
$ver  = '1.0.6'
$out  = Join-Path $env:TEMP "usbnaslink-build"
$dist = Join-Path $root "dist"

if ($Clean -and (Test-Path $out)) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path $out, $dist | Out-Null

$javac    = Join-Path $Jdk "bin\javac.exe"
$jar      = Join-Path $Jdk "bin\jar.exe"
$jpackage = Join-Path $Jdk "bin\jpackage.exe"
$java     = Join-Path $Jdk "bin\java.exe"
foreach ($t in @($javac, $jar, $jpackage, $java)) {
    if (-not (Test-Path $t)) { throw "missing $t (check -Jdk)" }
}

# ---------- 1) compile ----------
$classes = Join-Path $out "classes"
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$srcDirs = @(
    (Join-Path $root "core\src\main\java"),
    (Join-Path $root "desktop\src\main\java")
)
$sources = Get-ChildItem -Path $srcDirs -Recurse -Filter *.java | ForEach-Object { $_.FullName }

Write-Host "[1/5] javac $($sources.Count) files..." -ForegroundColor Cyan
$argFile = Join-Path $out "sources.txt"
$quoted = $sources | ForEach-Object { '"' + ($_ -replace '\\', '\\') + '"' }
[IO.File]::WriteAllLines($argFile, $quoted, [Text.UTF8Encoding]::new($false))
$depCp = @(
    (Join-Path $env:TEMP "vlcjsdk\vlcj-4.8.3.jar"),
    (Join-Path $env:TEMP "vlcjsdk\vlcj-natives-4.8.3.jar"),
    (Join-Path $env:TEMP "vlcjsdk\jna-5.13.0.jar"),
    (Join-Path $env:TEMP "vlcjsdk\jna-platform-5.13.0.jar")
) -join ';'
& $javac -encoding UTF-8 -cp $depCp -d $classes -Xlint:-options "@$argFile"
if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }

# ---------- 2) fat jar ----------
$jarFile = Join-Path $out "USBNASLink.jar"
Write-Host "[2/5] jar -> $jarFile" -ForegroundColor Cyan
$manifestPath = Join-Path $out "MANIFEST.MF"
$manifest = "Manifest-Version: 1.0`r`n" +
            "Main-Class: com.littlesauce.usbnaslink.desktop.Main`r`n" +
            "Implementation-Title: USBNASLink`r`n" +
            "Implementation-Version: $ver`r`n`r`n"
[IO.File]::WriteAllText($manifestPath, $manifest, [Text.UTF8Encoding]::new($false))
if (Test-Path $jarFile) { Remove-Item -Force $jarFile }
& $jar --create --file $jarFile --manifest $manifestPath -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed: $LASTEXITCODE" }

$appImg = Join-Path $out "USBNASLink"
if (Test-Path $appImg) { Remove-Item -Recurse -Force $appImg }
New-Item -ItemType Directory -Force -Path $appImg | Out-Null
Copy-Item $jarFile (Join-Path $appImg "USBNASLink.jar") -Force

# ---------- 3) runtime deps: vlcj + jna + libvlc ----------
# vlcj is split in two artifacts on Maven Central: vlcj-4.8.3.jar has the Java
# API, vlcj-natives-4.8.3.jar has uk.co.caprica.vlcj.binding.* (LibVlc, the JNA
# interfaces). Without the second one MediaPlayerFactory dies with
# NoClassDefFoundError: .../binding/support/runtime/RuntimeUtil. jna itself is
# also required. All four go into lib\ next to the exe and the launcher puts
# them on -cp explicitly.
$vlcSrc = Join-Path $env:TEMP "vlcjsdk"
if (-not (Test-Path $vlcSrc)) { throw "missing $vlcSrc (vlcj + jna + vlc runtime)" }
$libDir = Join-Path $appImg "lib"
New-Item -ItemType Directory -Force -Path $libDir | Out-Null
$libJars = @(
    "vlcj-4.8.3.jar",
    "vlcj-natives-4.8.3.jar",
    "jna-5.13.0.jar",
    "jna-platform-5.13.0.jar"
)
foreach ($n in $libJars) {
    $p = Join-Path $vlcSrc $n
    if (-not (Test-Path $p)) { throw "missing dependency $p" }
    Copy-Item $p (Join-Path $libDir $n) -Force
}

# The libvlc runtime (libvlc.dll + libvlccore.dll + plugins) is bundled so the
# user does not have to install VLC. Launcher passes -Dusbnaslink.vlc=<exe>\vlc
# and VlcEngine checks that property before anything else.
$vlcRuntime = Join-Path $vlcSrc "vlc\vlc-3.0.21"
if (-not (Test-Path (Join-Path $vlcRuntime "libvlc.dll"))) { throw "missing libvlc.dll under $vlcRuntime" }
$vlcDst = Join-Path $appImg "vlc"
Write-Host "[3/5] copy vlc runtime -> $vlcDst" -ForegroundColor Cyan
Copy-Item $vlcRuntime $vlcDst -Recurse -Force

# ---------- 4) launcher exe ----------
# jpackage cannot produce an app image on this machine (it fails with a bogus
# "path exceeding 32000 characters" and then hangs outright), so we compile a
# tiny native launcher with the .NET Framework's csc.exe instead. The launcher
# locates a JDK/JRE at runtime and spawns javaw -cp USBNASLink.jar <MainClass>.
$launcherSrc = Join-Path $root "desktop\launcher\Launcher.cs"
$exe = Join-Path $appImg "USBNASLink.exe"
Write-Host "[4/5] csc -> $exe" -ForegroundColor Cyan
$csc = Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if (-not (Test-Path $csc)) { $csc = Join-Path $env:WINDIR "Microsoft.NET\Framework\v4.0.30319\csc.exe" }
if (-not (Test-Path $csc)) { throw "csc.exe not found (need .NET Framework 4.x)" }
& $csc /nologo /target:winexe /optimize+ /out:$exe /r:System.dll /r:System.Core.dll $launcherSrc
if ($LASTEXITCODE -ne 0) { throw "csc failed: $LASTEXITCODE" }

Set-Content -Path (Join-Path $appImg "run.bat") -Encoding ASCII -Value @(
    '@echo off',
    'rem Fallback launcher: same effect as USBNASLink.exe, but keeps a console',
    'rem window so Java stack traces stay visible. Useful for troubleshooting.',
    'setlocal',
    'cd /d "%~dp0"',
    'if defined USBNASLINK_JAVA_HOME set "JAVA=%USBNASLINK_JAVA_HOME%\bin\java.exe"',
    'if not defined JAVA if exist "%~dp0runtime\bin\java.exe" set "JAVA=%~dp0runtime\bin\java.exe"',
    'if not defined JAVA if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"',
    'if not defined JAVA set "JAVA=java.exe"',
    'set "CP=USBNASLink.jar"',
    'for %%f in ("lib\*.jar") do call set "CP=%%CP%%;%%~ff"',
    '"%JAVA%" -Dfile.encoding=UTF-8 -Xmx256m "-Dusbnaslink.home=%CD%" "-Dusbnaslink.vlc=%CD%\vlc" -cp "%CP%" com.littlesauce.usbnaslink.desktop.Main %*',
    'if errorlevel 1 pause'
)

# ---------- 5) zip ----------
$zip = Join-Path $dist "USBNASLink-$ver-win64.zip"
if (Test-Path $zip) { Remove-Item -Force $zip }
Write-Host "[5/5] zip -> $zip" -ForegroundColor Cyan
Compress-Archive -Path $appImg -DestinationPath $zip -CompressionLevel Optimal
Write-Host ""
Write-Host "=== build ok ===" -ForegroundColor Green
Write-Host "  exe    : $exe"
Write-Host "  zip    : $zip"
Write-Host "  jar    : $jarFile"
Write-Host "  classes: $classes"
Write-Host "  vlc    : $vlcDst"

if ($Verify) {
    Write-Host ""
    Write-Host "=== scan verify ===" -ForegroundColor Cyan
    & $java -cp $classes "com.littlesauce.usbnaslink.desktop.ScanCli" @($args | ForEach-Object { $_ })
}
