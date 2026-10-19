plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.fuguang.lueying"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fuguang.lueying"
        minSdk = 29
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        // 下载引擎（yt-dlp + ffmpeg）带着本机代码：只打 64 位 ARM，安装包小一半（近几年的手机平板都是）
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("fuguang") {
            storeFile = file("fuguang.keystore")
            storePassword = "fuguang2026"
            keyAlias = "fuguang"
            keyPassword = "fuguang2026"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fuguang")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fuguang")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // youtubedl-android 要把 python、ffmpeg 解压出来运行，本机库不能压在 APK 里
    packaging {
        jniLibs.useLegacyPackaging = true
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    sourceSets {
        getByName("main") {
            // 与网站共用同一份背景视频、静帧与掠影内容
            assets.srcDirs("../../public/assets", "../../public/content")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.compose.foundation:foundation:1.12.0")
    implementation("androidx.compose.ui:ui:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.media3:media3-exoplayer:1.9.2")
    implementation("androidx.media3:media3-exoplayer-hls:1.9.2")
    implementation("androidx.media3:media3-datasource:1.9.2")
    implementation("androidx.media3:media3-database:1.9.2")
    implementation("dev.chrisbanes.haze:haze:1.7.3")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.caverock:androidsvg-aar:1.4")
    // 下载引擎：yt-dlp + ffmpeg（和开源下载器 Seal 同一套）
    implementation("io.github.junkfood02.youtubedl-android:library:0.17.3")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.3")
    testImplementation("junit:junit:4.13.2")
}
