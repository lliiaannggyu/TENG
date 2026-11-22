plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

import java.util.Properties

android {
    namespace = "com.tengwear.tengfilemanager"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.tengwear.tengfilemanager"
        // 最低支持 Wear OS 2.x（API 25）。再往下会被 wear-compose-material3 的
        // minSdk 25 卡死，这就是不牺牲任何功能/体验前提下的硬件下限。
        // java.time 通过 coreLibraryDesugaring 在 API 25/26 上同样可用。
        minSdk = 25
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // 签名密钥从 local.properties 读，不进版本库。
        // 换自己的密钥时只需在 local.properties 里改这四个值。
        create("release") {
            val props = Properties().apply {
                val f = rootProject.file("local.properties")
                if (f.exists()) f.inputStream().use { load(it) }
            }
            storeFile = file(props.getProperty("release.storeFile", "release-key.jks")) // 相对于 app/ 目录
            storePassword = props.getProperty("release.storePassword", "")
            keyAlias = props.getProperty("release.keyAlias", "")
            keyPassword = props.getProperty("release.keyPassword", "")
            enableV1Signing = false
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            // R8 混淆 + 裁剪。debug 41MB（dex 38MB）的绝大部分来自
            // Compose / Media3 / Coil 的未使用代码，开 R8 后预期降到 10~14MB。
            optimization {
                enable = true
                keepRules {
                    files("proguard-rules.pro")
                }
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // java.time 脱糖到 API 25/26，运行行为与 API 26+ 完全一致
        isCoreLibraryDesugaringEnabled = true
    }
    useLibrary("wear-sdk")
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            // commons-compress / zip4j 会带重复的 META-INF 声明
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
            )
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.core.splashscreen)
    implementation(libs.core.ktx)
    implementation(libs.androidx.wear)
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.material.icons.core)

    // 生命周期 / 协程 / 偏好
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.datastore.preferences)

    // SAF
    implementation(libs.documentfile)

    // java.time 等 API 的脱糖实现（minSdk 25 所需）
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // 缩略图（图片 + 视频首帧）
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    // 音视频查看器
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    // 压缩：ZIP(AES/分卷/流式) 用 zip4j；7z 用 commons-compress
    implementation(libs.zip4j)
    implementation(libs.commons.compress)
    implementation(libs.xz)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.test.manifest)
    debugImplementation(libs.ui.tooling)
    // 预览/工具库只在开发期需要，不能进 release 包
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.ui.tooling.preview)
    debugImplementation(libs.wear.tooling.preview)
}
