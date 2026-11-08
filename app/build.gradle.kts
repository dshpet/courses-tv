import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.dshpet.coursestv"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.dshpet.coursestv"
        minSdk = 34
        targetSdk = 37
        versionCode = 17
        versionName = "0.17"
        buildConfigField("boolean", "WRITES", "true")
    }

    signingConfigs {
        // A kept key lets updates install over earlier sideloads from any machine; else the local debug key.
        val sideloadKey = file("sideload.keystore")
        if (sideloadKey.exists()) getByName("debug") { storeFile = sideloadKey }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "WRITES", "false")
        }
        // For the TV: Compose is slow unoptimized; debug-signed for sideloading.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
        // The release build for checks: changes nothing on the account.
        create("readOnly") {
            initWith(getByName("release"))
            buildConfigField("boolean", "WRITES", "false")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val media3 = "1.11.1"

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.tv:tv-material:1.1.0")

    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-ui-compose:$media3")

    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
