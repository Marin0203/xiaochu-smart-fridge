import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 密钥从 local.properties 读取 (该文件在本机, 已被 .gitignore, 严禁提交)
// 模板见 local.properties.example
val secrets = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String, default: String = ""): String = secrets.getProperty(key) ?: default
// 转义: 值里出现 " 或 \ 会破坏生成的 BuildConfig.java
fun esc(v: String): String = v.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.smartfridge.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.smartfridge.app"
        minSdk = 26          // android 8.0+ (java.time 可用)
        targetSdk = 34
        versionCode = 8
        versionName = "1.1.4"   // 语义化三段式 (对齐 v1.0.0); 唯一事实源见仓库根 VERSIONS.json; 每构建 versionCode +1

        buildConfigField("String", "SUPABASE_URL", "\"${esc(secret("SUPABASE_URL"))}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${esc(secret("SUPABASE_KEY"))}\"")
        // AI_MODE: edge=Edge Function(密钥在服务端,推荐) / direct=直连OpenAI兼容API(本地自部署)
        buildConfigField("String", "AI_MODE", "\"${esc(secret("AI_MODE", "edge"))}\"")
        buildConfigField("String", "OPENAI_BASE_URL", "\"${esc(secret("OPENAI_BASE_URL", "https://api.deepseek.com/v1"))}\"")
        buildConfigField("String", "OPENAI_API_KEY", "\"${esc(secret("OPENAI_API_KEY"))}\"")
        buildConfigField("String", "OPENAI_MODEL", "\"${esc(secret("OPENAI_MODEL", "deepseek-chat"))}\"")
        // 家庭共享账号: 打开 App 自动登录+自动建家庭, 免注册流程 (仅两人自用场景)
        buildConfigField("String", "AUTO_EMAIL", "\"${esc(secret("AUTO_EMAIL"))}\"")
        buildConfigField("String", "AUTO_PASSWORD", "\"${esc(secret("AUTO_PASSWORD"))}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 跳过发布前 lint 检查 (与 IDE 缓存冲突的已知 bug, 出包不需要)
    lint {
        checkReleaseBuilds = false
        abortOnError = false
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
    // Compose (BOM 统一版本)
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // 协程 + JSON
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // 后台任务 (临期食材每日提醒)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // HTTP (REST 调 PostgREST/GoTrue + Realtime WebSocket)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // WebView 壳 (HTML 数据桥 UI): WebViewAssetLoader 安全加载本地资产
    implementation("androidx.webkit:webkit:1.11.0")
    // Capacitor 6 (宿主式): 桥 + 运行时, 数据层仍全部原生
    // 运行时 Maven 坐标 = com.capacitorjs:core:6.2.1（已验证含 BridgeActivity/Plugin）
    implementation("com.capacitorjs:core:6.2.1")
    implementation("androidx.appcompat:appcompat:1.7.0")  // BridgeActivity 依赖 AppCompatActivity
}
