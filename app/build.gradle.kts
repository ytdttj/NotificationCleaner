plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "cc.ytdttj.noticleaner"
    // 1.2.1：libxposed service 102 要求 compileSdk ≥ 37（仅编译期，targetSdk 保持 36）
    compileSdk = 37

    defaultConfig {
        applicationId = "cc.ytdttj.noticleaner"
        // dev 分支 UI 改造（Material 3 Expressive / Material You）：minSdk 提升至 33
        // —— Android 12+ 动态取色全量可用，且无需为低版本维护取色降级路径
        minSdk = 33
        targetSdk = 36
        versionCode = 55
        versionName = "2.0.1 Dev 5"
        // 1.3.2（P3-7③）：只保留 arm64-v8a——剔除其余架构（armeabi-v7a/x86/x86_64）
        // 的原生库，精简 APK 体积；目标设备为真机 ARM64（模块端同样仅注入 arm64 设备）
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    defaultConfig {
        // 应用内更新候选源（1.3.2 更新分流）：
        //   稳定版通道 → Gitee 的 latest.json（仅正式版，随正式版发版更新）
        //   Dev 版通道 → GitHub 的 latest-dev.json（最新 Dev 版，随 Dev 发版更新）
        // GitHub 上 latest.json 仍保持稳定版信息（= 稳定版在 GitHub 的镜像），
        // 即 GitHub 同时承载稳定版 + Dev 版，Gitee 仅稳定版。
        // 下载 URL 按版本号模板构造：{base}/v{versionName 去空格}/NotiCleaner-{versionName 去空格}.apk
        buildConfigField("String", "UPDATE_LATEST_GITHUB",
            "\"https://raw.githubusercontent.com/ytdttj/NotificationCleaner/main/latest-dev.json\"")
        buildConfigField("String", "UPDATE_LATEST_GITEE",
            "\"https://gitee.com/ytdttj/NotiCleaner/raw/main/latest.json\"")
        buildConfigField("String", "UPDATE_APK_GITHUB",
            "\"https://github.com/ytdttj/NotificationCleaner/releases/download\"")
        buildConfigField("String", "UPDATE_APK_GITEE",
            "\"https://gitee.com/ytdttj/NotiCleaner/releases/download\"")
    }
    sourceSets {
        // 1.3.2（P3-7②）：model.bin 已移至 src/main/resources/model/（单通道打包）——
        // APP 端与模块端统一走 classLoader.getResourceAsStream("model/model.bin")，
        // 不再经 assets srcDir 双份打包（原 assets/resources 各 ~0.5MB 冗余）
    }
    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    // dev 分支 UI 改造：material3 1.4.0（Material 3 Expressive + Material You 动态取色）。
    // 注意：选 2026.06.01 而非最新 2026.09.00——后者映射 compose-ui 1.12.1，
    // 要求 AGP 9.1+，与当前 AGP 8.13 不兼容；1.11.4 + material3 1.4.0 为兼容组合
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.navigation:navigation-compose:2.9.5")

    // dev 分支 UI 改造（1.4.0 Dev 4，方案 C）：Kyant0 Backdrop——液态玻璃效果
    // （backdrop 采样 + AGSL 折射着色器 + RenderEffect 模糊，minSdk 33 全量可用）。
    // 注意：2.x 依赖 CMP 1.12（androidx compose 1.12 要求 AGP 9.1+），1.0.6 基于
    // androidx compose 1.10 与当前 AGP 8.13 / compose 1.11 兼容
    implementation("io.github.kyant0:backdrop:1.0.6")
    // backdrop 的平滑圆角形状库（Capsule/RoundedRectangle + Continuous 连续曲率）：
    // pom 里声明了传递依赖，但 Gradle 按 .module 元数据解析 KMP 库时 Android 变体不带它，
    // 必须显式引入——lens 折射着色器的形状白名单只认这个库的 RoundedRectangularShape
    implementation("io.github.kyant0:shapes:1.2.0")

    implementation("androidx.room:room-runtime:2.8.2")
    implementation("androidx.room:room-ktx:2.8.2")
    ksp("androidx.room:room-compiler:2.8.2")

    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.work:work-runtime-ktx:2.10.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // Shizuku（用户主动启用时才请求授权）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // LSPosed Modern API（libxposed API 102）：模块端仅编译期，运行时由 LSPosed 提供（不打入 APK）
    compileOnly("io.github.libxposed:api:102.0.0")
    compileOnly("io.github.libxposed:annotation:1.0.0")
    // APP 侧框架服务（1.2.1）：模块激活检测 + delta 远程文件通道
    implementation("io.github.libxposed:service:102.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
