plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.littlesauce.usbnaslink"
    compileSdk = 35

    defaultConfig {
        // 独立包名：和 NAS 服务端 App（com.littlesauce.usbnas）装在同一台手机上也不冲突
        applicationId = "com.littlesauce.usbnaslink"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "1.1.0"

        // 只带 arm 两个 ABI：libmodpdfium.so 单个就 5MB，x86/mips 那几套
        // 纯属陪葬（模拟器才用得上），四个 ABI 全留 APK 会多出 30MB。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // core/ 是桌面端和安卓端共用的纯 Java 源码目录，直接挂进来，
    // 免得两套实现漂移（指纹判定、cookie 处理各写一份是维护地狱）。
    //
    // 注意必须用绝对路径拼出来：AGP 里写 "../core/..." 这种相对的 srcDir
    // 不会被解析成 projectDir 的兄弟目录，它会静默忽略 —— 编译期才报
    // "Unresolved reference: Scanner"，很难一眼看出是源集没挂上。
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java", file("../../core/src/main/java").absolutePath)
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 自签调试使用：直接复用 debug 的签名配置，免得为了装一次机去生成 keystore
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // 版本刻意对齐本机 Gradle 缓存里已有的（离线可构建）：
    //   appcompat 1.7.0 / core-ktx 1.13.1 / material 1.12.0 /
    //   constraintlayout 2.1.4 / recyclerview 1.1.0 / activity 1.8.0
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity:1.8.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // ---- PDF 渲染：PDFium ----
    // 用预编译产物而不是从 Maven 拉：
    //   1) com.github.mhiew:android-pdf-viewer 的 POM 声明了
    //      com.android.support:support-compat:28.0.0，和 useAndroidX=true 冲突；
    //   2) com.github.barteksc:pdfium-android:1.9.0 那个 native 库是编在
    //      com.android.support:support-v4:26.1.0 上的。
    // 实测这两个 jar 里只有 DefaultScrollHandle 一个类引用了 support-v4 的
    // ContextCompat.getDrawable，把那一支删掉（本工程不用滚动把手）后整包
    // 对 support/androidx 零依赖，直接用 files() 引进来最干净。
    //   libs/pdfium-android-1.9.0.jar                  原生库的 Java 封装（PdfiumCore）
    //   libs/android-pdf-viewer-3.2.0-beta.1.jar       已裁剪的 PDFView
    //   src/main/jniLibs/<abi>/*.so                    libmodpdfium / libjniPdfium / …
    implementation(files("libs/pdfium-android-1.9.0.jar"))
    implementation(files("libs/android-pdf-viewer-3.2.0-beta.1.jar"))
}
