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
        minSdk = 26
        targetSdk = 36
        versionCode = 25
        versionName = "1.2.2"
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
        // 应用内更新候选源（latest.json / Release 附件模板），顺序 = 检查与下载优先级：Gitee → GitHub
        // 下载 URL 按版本号模板构造：{base}/v{versionName}/NotiCleaner-{versionName}.apk（1.1.10 起，不依赖 latest.json 的 url）
        buildConfigField("String", "UPDATE_LATEST_GITHUB",
            "\"https://raw.githubusercontent.com/ytdttj/NotificationCleaner/main/latest.json\"")
        buildConfigField("String", "UPDATE_LATEST_GITEE",
            "\"https://gitee.com/ytdttj/NotiCleaner/raw/main/latest.json\"")
        buildConfigField("String", "UPDATE_APK_GITHUB",
            "\"https://github.com/ytdttj/NotificationCleaner/releases/download\"")
        buildConfigField("String", "UPDATE_APK_GITEE",
            "\"https://gitee.com/ytdttj/NotiCleaner/releases/download\"")
    }
    sourceSets {
        // 1.2.1：assets 同时打包为 classpath resources——LSPosed 模块端（system_server）
        // 经 classLoader.getResourceAsStream("model/model.bin") 读取内置模型
        getByName("main").resources.srcDir("src/main/assets")
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

    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.navigation:navigation-compose:2.9.5")

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
