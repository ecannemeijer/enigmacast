plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.enigmacast"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = libs.versions.applicationId.get()
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 89
        versionName = "1.0.0-beta.86"
        buildConfigField("String", "TMDB_API_KEY", "\"${tmdbApiKey()}\"")
        buildConfigField("String", "FOOTBALL_DATA_API_KEY", "\"${localProperty("FOOTBALL_DATA_API_KEY")}\"")
        buildConfigField("String", "PITCH_API_KEY", "\"${localProperty("PITCH_API_KEY")}\"")
        buildConfigField("String", "SUBSOURCE_API_KEY", "\"${localProperty("SUBSOURCE_API_KEY")}\"")
        // EnigmaCast accounts (VelixaTV API server). Not a secret.
        buildConfigField("String", "ACCOUNT_API_URL", "\"https://api.velixatv.com/\"")
        // The phone bridge (Companion). Remove or set false to build EnigmaCast without it.
        buildConfigField("boolean", "COMPANION_BRIDGE", "true")
    }

    // The app's languages (see AppLanguage and res/xml/locales_config.xml); other library translations are left out.
    androidResources {
        localeFilters += listOf("en", "nl", "de", "it", "fr")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.tvprovider)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.okhttp)
    // Torrent streaming: libtorrent (Java part + native .so per ABI) and a tiny local HTTP server.
    implementation(libs.jlibtorrent)
    implementation(libs.jlibtorrent.android.arm)
    implementation(libs.jlibtorrent.android.arm64)
    implementation(libs.jlibtorrent.android.x64)
    // 32-bit x86: the APK already has lib/x86 from other libraries, so x86 devices and Android TV
    // emulators (API 30 TV images are x86) pick that folder and need jlibtorrent there too.
    implementation(libs.jlibtorrent.android.x86)
    implementation(libs.nanohttpd)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.timber)
    implementation(libs.commons.net)
    implementation("com.github.mwiede:jsch:0.2.21")
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.nextlib.media3ext)
    implementation(libs.wireguard.tunnel)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.xerial:sqlite-jdbc:3.47.2.0")
}

/** TMDB v3 key from local.properties (TMDB_API_KEY=...), never from source control. Blank disables the film home page. */
fun tmdbApiKey(): String = localProperty("TMDB_API_KEY")

/** A value from the git-ignored local.properties (NAME=value), or "" when missing. */
fun localProperty(name: String): String {
    val file = rootProject.file("local.properties")
    if (!file.isFile) return ""
    return file.readLines().map { it.trim() }.firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')?.trim().orEmpty()
}

