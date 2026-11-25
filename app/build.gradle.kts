import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

// 发布签名：密钥不进仓库。环境变量 OPENHC_SIGNING 或 local.properties 里的 openhc.signing 指向一份配置文件
// （storeFile、storePassword、keyAlias、keyPassword）；都没有时正式包不签名，调试包照常用调试密钥。
val signingProperties: Properties? = (
    providers.environmentVariable("OPENHC_SIGNING").orNull
        ?: localProperties.getProperty("openhc.signing")
    )
    ?.takeIf { it.isNotBlank() }
    ?.let { path -> Properties().apply { file(path).inputStream().use { load(it) } } }

// 标准包不变更包名；本机实测包和公开无身份预览包分别使用独立包名，避免互相覆盖。
val api17Profile = providers.gradleProperty("openhc.api17Profile").orElse("standard").get()
require(api17Profile in setOf("standard", "local-test", "public-preview")) { "Unknown openhc.api17Profile: $api17Profile" }
val publicApi17Preview = api17Profile == "public-preview"

// 连接参数（openhc-identity.json，格式见 docs/identity.example.json）：不进仓库，构建时从本机目录打进 assets。
// 环境变量 OPENHC_IDENTITY_DIR 优先，其次 local.properties 的 openhc.identity.dir；都没有就不打包，装上后首页提示缺少。
val identityFileName = "openhc-identity.json"
val identityDir: File? = (
    providers.environmentVariable("OPENHC_IDENTITY_DIR").orNull
        ?: localProperties.getProperty("openhc.identity.dir")
    )
    ?.takeIf { it.isNotBlank() }
    ?.let { file(it) }
    ?.also { dir ->
        val names = dir.listFiles()?.map { it.name }?.sorted()
        require(names == listOf(identityFileName)) {
            "连接参数目录 $dir 里必须只有 $identityFileName（实际：$names），防止把别的私有文件打进安装包"
        }
    }

if (publicApi17Preview) {
    require(identityDir == null && fileTree("src").matching { include("**/$identityFileName") }.isEmpty) {
        "公开预览包不允许携带连接身份；请移除身份配置及 src 下的 $identityFileName"
    }
}

android {
    namespace = "io.github.gcbond.openhc"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = when (api17Profile) {
            "local-test" -> "io.github.gcbond.openhc.api17test"
            "public-preview" -> "io.github.gcbond.openhc.api17public"
            else -> "io.github.gcbond.openhc"
        }
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // 这个分支是上游 0.2.7 的全套协议/音频能力 + 安卓 4.1～7.x 车机适配（minSdk 16）。
        // 主线（main）是 0.2.7-android6（minSdk 23，versionCode 307）。
        //
        // 40210 是保留 Android 4 底座、含实车桌面恢复修复的发布快照；沿用 4xxxx 号段。
        versionCode = 40210
        versionName = when (api17Profile) {
            "local-test" -> "0.2.7-android4-api17-ng-test-v40210"
            "public-preview" -> "0.2.7-android4-api17-public-preview-v40210"
            else -> "0.2.7-android4"
        }
        if (api17Profile == "local-test") resValue("string", "app_name", "OpenHC API17 Test")
        if (publicApi17Preview) resValue("string", "app_name", "OpenHC API17 Preview")
        // 方法数超过 64K，安卓 4.x 要用 MultiDex 分包（5.0 起系统自带）。
        multiDexEnabled = true
    }

    sourceSets {
        getByName("main") {
            identityDir?.let { assets.srcDir(it) }
        }
    }

    signingConfigs {
        signingProperties?.let { props ->
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 代码压缩等协议层稳定后再开；开之前要先补齐保留规则。
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            // BouncyCastle（core:auth 用）和 jspecify 都带了这份 OSGi 清单，打包时重名；安卓用不到 OSGi 清单。
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    // 会话编排：把四个协议层串成一次有线连接，执行器用 java.net，安卓上直接用。
    implementation(project(":core:session"))
    implementation(project(":platform"))
    // 连接状态用 StateFlow 交给界面。
    implementation(libs.kotlinx.coroutines.core)
    // Dispatchers.Main 的实现。只引 core 的话 Main 调度器是 missing，
    // launch(Dispatchers.Main) 会抛 IllegalStateException 直接闪退（安卓 5.1 上实测）。
    // 主线靠 Compose 的传递依赖带进来；本分支不用 Compose，必须显式声明。
    implementation(libs.kotlinx.coroutines.android)
    // 无线连接页显示配对二维码（Apache-2.0）。
    implementation(libs.zxing.core)

    // android4-support 分支：不用 Compose（最低 API 21），界面用传统 View。
    // core-ktx 1.12.0 的 minSdk 是 14，能在安卓 4.1 上跑；activity 用系统 Activity + startActivityForResult。
    implementation("androidx.core:core-ktx:1.12.0")
    // 安卓 4.1～4.4 上支持 MultiDex 分包（72k 方法超过单 dex 上限）。5.0 起系统自带，这个库会自动跳过。
    implementation("androidx.multidex:multidex:2.0.1")

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
