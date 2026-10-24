plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tengwear.tengfilemanager"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.tengwear.tengfilemanager"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
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
