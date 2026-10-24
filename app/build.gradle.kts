import java.util.Base64
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

materialiseLauncherIcons()

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
            isDebuggable = false
            // Same CI certificate as the debug builds, so a later APK from the
            // v0.6.1 release installs over this one. The package id does not
            // get the .debug suffix, and the process is not debuggable.
            signingConfig = signingConfigs.getByName("debug")
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

// Unit tests load a host build of libfm1emu.so when there is one
// (cargo build --release --manifest-path native/fm1-jni/Cargo.toml --target-dir native/target).
tasks.withType<Test>().configureEach {
    systemProperty("java.library.path", rootProject.file("native/target/release").absolutePath)
    systemProperty("fm1.testPackage", rootProject.file("fm1kit/src/test/resources/FM-1_015.fwsc").absolutePath)
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

/** Launcher art is binary, so the committed copies are these zips. Writes the mipmap PNGs before resources are merged. The default black icon keeps the top-left knob's white indicator; the colour zips are hardware body variants. */
fun materialiseLauncherIcons() {
    val res = file("src/main/res")
    listOf(
        "launcher-icons-a.zip.b64",
        "launcher-icons-b.zip.b64",
        "launcher-icons-colours-a.zip.b64",
        "launcher-icons-colours-b.zip.b64",
        "launcher-icons-colours-c.zip.b64",
    ).forEach { name ->
        val bytes = Base64.getMimeDecoder().decode(file(name).readText())
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val path = entry.name
                require(!path.startsWith("/") && ".." !in path && path.startsWith("mipmap-")) { path }
                val dest = res.resolve(path)
                val data = zip.readBytes()
                if (!dest.exists() || !dest.readBytes().contentEquals(data)) {
                    dest.parentFile.mkdirs()
                    dest.writeBytes(data)
                }
            }
        }
    }
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
    System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.6.1"
