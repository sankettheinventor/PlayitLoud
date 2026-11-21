plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.wave.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.wave.player"
        minSdk = 26
        targetSdk = 35
        // CI builds count up so each new APK installs over the previous one.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.2.${versionCode}"
        // Only ship English strings from libraries (saves ~1 MB of translations).
        resourceConfigurations += listOf("en")
    }

    // Signing: bring your own key. Nothing secret lives in this repository.
    // Set these four environment variables (GitHub Actions reads them from
    // repository secrets). Without them the build falls back to this machine's
    // own Android debug key, which is fine for trying the app locally.
    val keystorePath = System.getenv("PLAYITLOUD_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null && file(keystorePath).exists()) {
            create("own") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("PLAYITLOUD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PLAYITLOUD_KEY_ALIAS")
                keyPassword = System.getenv("PLAYITLOUD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // Test builds: optimised + shrunk (R8), signed with your key (see above).
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("own") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures { compose = true }
    lint {
        // Media3 "unstable" APIs are used on purpose (custom audio processing, caching).
        disable += "UnsafeOptInUsageError"
        checkReleaseBuilds = false
    }
    packaging {
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "/META-INF/*.kotlin_module",
                "DebugProbesKt.bin", "kotlin-tooling-metadata.json", "/META-INF/androidx*.version",
            )
        }
    }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
    implementation("androidx.media3:media3-database:$media3")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
