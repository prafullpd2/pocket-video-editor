plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.pocketvideotoolkit"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.pocketvideotoolkit"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        getByName("debug") {
            if (providers.gradleProperty("installBesideExisting").orNull == "true") {
                applicationIdSuffix = ".updated"
                manifestPlaceholders["appLabel"] = "Pocket Video Toolkit (Updated)"
            } else {
                manifestPlaceholders["appLabel"] = "Pocket Video Toolkit"
            }
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    val cameraX = "1.6.2"
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("androidx.camera:camera-extensions:$cameraX")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.media3:media3-transformer:1.6.1")
    implementation("androidx.media3:media3-effect:1.6.1")
    implementation("androidx.media3:media3-common:1.6.1")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("io.github.takusan23:akaricore:6.4.2")
    implementation("io.github.takusan23:libaicaroid:1.0.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
