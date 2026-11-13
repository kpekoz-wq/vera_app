import com.android.build.api.dsl.ApplicationExtension
import com.google.firebase.appdistribution.gradle.firebaseAppDistribution
import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.firebase.appdistribution)
}

extensions.configure<ApplicationExtension> {
    namespace = "com.vera.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vera.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 31
        versionName = "5.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Load local.properties safely using standard Java imports
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localProperties.load(FileInputStream(localPropertiesFile))
        }

        // Inject API Key into BuildConfig.GEMINI_API_KEY safely
        val rawApiKey = localProperties.getProperty("GEMINI_API_KEY") ?: ""
        val formattedKey = if (rawApiKey.startsWith("\"") && rawApiKey.endsWith("\"")) {
            rawApiKey
        } else {
            "\"$rawApiKey\""
        }
        buildConfigField("String", "GEMINI_API_KEY", formattedKey)

        val rawPicovoiceKey = localProperties.getProperty("PICOVOICE_ACCESS_KEY") ?: ""
        val formattedPicovoiceKey = if (rawPicovoiceKey.startsWith("\"") && rawPicovoiceKey.endsWith("\"")) {
            rawPicovoiceKey
        } else {
            "\"$rawPicovoiceKey\""
        }
        buildConfigField("String", "PICOVOICE_ACCESS_KEY", formattedPicovoiceKey)

        useLibrary("org.apache.http.legacy")
    }

    buildTypes {
        getByName("debug") {
            firebaseAppDistribution {
                appId = "1:200348372358:android:ed39b182e2c68cde57b007"
                testers = "kpekoz@gmail.com"
                groups = "beta-testers-vera"
                releaseNotes = "VERA v2.8: Metric tile unit alignment and vertical line spacing fix across Live Streams and Advanced Dynamics; OBD serial zero-deadtime, pre-2018 CAN census adaptive discovery, and hardened ReadOnlyGuard."
                artifactType = "APK"
            }
        }

        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/notice.txt"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // ViewModel & Compose integration
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation("androidx.compose.material:material-icons-extended")

    // Google AI Client Kotlin SDK & Serialization
    implementation(libs.google.ai.generativeai)
    implementation(libs.kotlinx.serialization.json)
    implementation("com.google.code.gson:gson:2.11.0")

    // USB Serial Driver for Wired OBD2 Adapters (Vgate vLinker FS USB / FTDI / CH340 / CP210x)
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")

    // OkHttp3 for Gemini Live Multimodal WebSocket Streaming
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Google Play Services Location & Fused Location Provider for Road Topography & Navigation
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Picovoice Porcupine Wake Word Engine for on-device "Hey Vera" keyword detection
    implementation("ai.picovoice:porcupine-android:3.0.3")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.matching { it.name == "appDistributionUploadDebug" }.configureEach {
    dependsOn("assembleDebug")
}