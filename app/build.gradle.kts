plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.seagull.boxcast"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.seagull.boxcast"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    }

    // 海鸥桌面（seagull-desktop）源码整入：Java 源码 + res + assets(xposed_init)
    sourceSets {
        getByName("main") {
            java.srcDir("../seagull/src")
            res.srcDir("../seagull/res")
            assets.srcDir("../seagull/assets")
        }
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    // 若采用 so 调用路径做盒子认证，把 libencryption.so 放到 app/src/main/jniLibs/arm64-v8a/
    // 并取消下面注释（默认走纯算法路径，见 crypto/VerifyCode.kt）
    // sourceSets {
    //     getByName("main") {
    //         jniLibs.srcDir("src/main/jniLibs")
    //     }
    // }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // JSON（OTA 检查更新用）
    implementation("org.json:json:20240303")
    // Xposed API 签名桩（LSPosed 模块，仅编译期；无框架时入口不激活）
    compileOnly(files("libs/xposed-api-82.jar"))
}
