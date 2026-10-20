import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.fmswitch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fmswitch"
        minSdk = 26
        targetSdk = 35
        versionCode = resolvedVersionCode()
        versionName = resolvedVersionName()
    }

    signingConfigs {
        getByName("debug") {
            storeFile = materialiseDebugKeystore()
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":fm1kit"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
}

/**
 * CI signs every debug APK with this committed key, and bumps versionCode from
 * GITHUB_RUN_NUMBER, so tapping a new APK updates the one already on the phone
 * instead of Android answering "App not installed".
 */
fun materialiseDebugKeystore(): java.io.File {
    val encoded = file("debug.keystore.b64")
    val decoded = file("debug.keystore")
    val bytes = Base64.getMimeDecoder().decode(encoded.readText())
    if (!decoded.exists() || !decoded.readBytes().contentEquals(bytes)) {
        decoded.writeBytes(bytes)
    }
    return decoded
}

fun resolvedVersionCode(): Int {
    listOf("VERSION_CODE", "GITHUB_RUN_NUMBER").forEach { key ->
        System.getenv(key)?.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
    }
    return try {
        providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim().toIntOrNull()?.coerceAtLeast(1) ?: 1
    } catch (_: Exception) {
        1
    }
}

fun resolvedVersionName(): String =
    System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.0"
