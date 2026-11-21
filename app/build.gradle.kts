import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.popcat19.thorutil"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "dev.popcat19.thorutil"
        minSdk = 33
        targetSdk = 35
        versionCode = 6
        versionName = "0.4.1"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // CI passes the key via env; local builds read ~/key/thorutil-release.properties.
        val env = System.getenv()
        val releaseProps = file("${env["HOME"]}/key/thorutil-release.properties")
        val props = Properties().apply { if (releaseProps.exists()) load(releaseProps.inputStream()) }
        fun key(name: String, envName: String): String? = env[envName] ?: props.getProperty(name)
        val store = key("storeFile", "THORUTIL_KEYSTORE")
        if (store != null) {
            create("release") {
                storeFile = file(store)
                storePassword = key("storePassword", "THORUTIL_STORE_PASSWORD")
                keyAlias = key("keyAlias", "THORUTIL_KEY_ALIAS")
                keyPassword = key("keyPassword", "THORUTIL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}

// The daemon is a static musl binary, packaged as a "lib" so it is extracted executable.
val daemonOut = layout.buildDirectory.dir("generated/daemon")

val buildDaemon by tasks.registering(Exec::class) {
    val src = rootProject.file("daemon/thor-mouse.c")
    val out = daemonOut.map { it.file("arm64-v8a/libthormouse.so") }
    inputs.file(src)
    outputs.file(out)
    doFirst { out.get().asFile.parentFile.mkdirs() }
    commandLine(
        "zig", "cc", "-target", "aarch64-linux-musl", "-static", "-O2", "-s",
        "-o", out.get().asFile.absolutePath, src.absolutePath,
    )
}

android.sourceSets.getByName("main").jniLibs.srcDir(daemonOut)

tasks.named("preBuild") { dependsOn(buildDaemon) }
