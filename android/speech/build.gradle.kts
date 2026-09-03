// :speech —— 语音识别独立模块 (与 :app 解耦)
// 契约见 docs/speech-api.md; 本模块只提供接口与空实现,
// 语音引擎的完整实现由独立开发线在本模块内完成 (替换 NoopVoiceEngine 即可)。
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.smartfridge.app.speech"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // 协程 (引擎实现方需要)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Vosk 离线中文 ASR (方案已定, 见 docs/speech-api.md / HANDOVER.md)
    implementation("com.alphacephei:vosk-android:0.3.47")
}
