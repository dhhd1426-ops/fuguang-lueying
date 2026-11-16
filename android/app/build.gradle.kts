import java.net.URI
import java.security.MessageDigest

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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        // 下载引擎（yt-dlp + ffmpeg）带着本机代码：只打 64 位 ARM，安装包小一半（近几年的手机平板都是）
        ndk { abiFilters += if (project.hasProperty("motionProbe")) listOf("x86_64") else listOf("arm64-v8a") }
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
        // sherpa-onnx 和 onnxruntime Java 接口各带一份 libonnxruntime.so：同为 1.27.1、逐字节相同，留一份
        jniLibs.pickFirsts += "**/libonnxruntime.so"
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    sourceSets {
        getByName("main") {
            // 与网站共用同一份背景视频、静帧与掠影内容
            assets.srcDirs("../../public/assets", "../../public/content", "build/turn-assets")
        }
    }
}

val torrentNative: Configuration by configurations.creating

// 本地听写引擎 sherpa-onnx（k2-fsa，Apache-2.0）：官方 Releases 的 AAR（含 Kotlin 接口 + onnxruntime），第一次构建时下到 libs/，不进仓库
val sherpaVer = "1.13.7"
val sherpaAar = file("libs/sherpa-onnx-$sherpaVer.aar")
if (!sherpaAar.exists()) {
    sherpaAar.parentFile.mkdirs()
    val tmp = file("libs/sherpa-onnx-$sherpaVer.aar.part")
    URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVer/sherpa-onnx-$sherpaVer.aar").toURL().openStream().use { i -> tmp.outputStream().use { i.copyTo(it) } }
    tmp.renameTo(sherpaAar)
}

// onnxruntime 的 Java 接口（跑 Smart Turn 用）：必须和 sherpa 自带的 libonnxruntime.so 同版本（1.27.1，.so 带符号版本 VERS_1.27.1），
// Maven 上安卓包只有 1.27.0，所以用 sherpa 作者构建 sherpa 时用的同一份（csukuangfj/onnxruntime-libs，官方安卓包原样）
val ortVer = "1.27.1"
val ortAar = file("libs/onnxruntime-android-$ortVer.aar")
if (!ortAar.exists()) {
    ortAar.parentFile.mkdirs()
    val tmp = file("libs/onnxruntime-android-$ortVer.aar.part")
    URI("https://github.com/csukuangfj/onnxruntime-libs/releases/download/v$ortVer/onnxruntime-android-$ortVer.zip").toURL().openStream().use { i -> tmp.outputStream().use { i.copyTo(it) } }
    tmp.renameTo(ortAar)
}

// 说完没说完：Smart Turn v3.2（pipecat-ai，BSD-2，8.7MB int8），钉在 pipecat 某次提交上，校验 sha256，打进 assets/turn/
val turnModel = file("build/turn-assets/turn/smart-turn-v3.2-cpu.onnx")
val turnSha = "2bb026316b14a660486a75b1733cd3fbab8c2fd0314dc9af7be49f8cca967e4f"
fun sha256(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
if (!turnModel.exists() || sha256(turnModel) != turnSha) {
    turnModel.parentFile.mkdirs()
    val tmp = File(turnModel.path + ".part")
    URI("https://github.com/pipecat-ai/pipecat/raw/348a9d8c83f6fb6c26a119cea1f5c43c1704fa31/src/pipecat/audio/turn/smart_turn/data/smart-turn-v3.2-cpu.onnx")
        .toURL().openStream().use { i -> tmp.outputStream().use { i.copyTo(it) } }
    check(sha256(tmp) == turnSha) { "Smart Turn 模型校验不对" }
    tmp.renameTo(turnModel)
}

dependencies {
    implementation(files(sherpaAar))
    implementation(files(ortAar))
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
    // 阿里实时识别的 WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.caverock:androidsvg-aar:1.4")
    // 下载引擎：yt-dlp + ffmpeg（和开源下载器 Seal 同一套）
    implementation("io.github.junkfood02.youtubedl-android:library:0.17.3")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.3")
    // 磁力 / 种子：libtorrent 的 Java 封装（开源安卓 BT 客户端 LibreTorrent 用的同一套），只打 64 位 ARM
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-38")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-38")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    // CI 上的磁力探测（TorrentProbeTest）：电脑上跑同一个 libtorrent，用 Linux 版本机库
    testImplementation("org.libtorrent4j:libtorrent4j:2.1.0-38")
    torrentNative("org.libtorrent4j:libtorrent4j-linux:2.1.0-38")
}

// 单元测试里 libtorrent4j 从 libtorrent4j.jni.path 读本机库：从 Linux 版的 jar 里取出 x86_64 那份
tasks.withType<Test>().configureEach {
    val jars = torrentNative
    val dir = layout.buildDirectory.dir("torrent-native")
    doFirst {
        val so = jars.files.flatMap { j -> zipTree(j).matching { include("**/libtorrent4j.so") }.files }
            .let { all -> all.firstOrNull { "x86_64" in it.path || "amd64" in it.path } ?: all.firstOrNull() }
        if (so != null) {
            val dst = dir.get().file("libtorrent4j.so").asFile
            dst.parentFile.mkdirs(); so.copyTo(dst, true)
            systemProperty("libtorrent4j.jni.path", dst.absolutePath)
        }
    }
}

