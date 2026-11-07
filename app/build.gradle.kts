import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingProperties = Properties()
val signingPropertiesFile = rootProject.file("keystore.properties")
if (signingPropertiesFile.exists()) {
    signingPropertiesFile.inputStream().use(signingProperties::load)
}
val releaseStoreFile =
    signingProperties.getProperty("storeFile")?.let(rootProject::file)
        ?: System.getenv("ADSKIPPER_KEYSTORE_FILE")?.let(rootProject::file)
val releaseStorePassword = signingProperties.getProperty("storePassword") ?: System.getenv("ADSKIPPER_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingProperties.getProperty("keyAlias") ?: System.getenv("ADSKIPPER_KEY_ALIAS")
val releaseKeyPassword = signingProperties.getProperty("keyPassword") ?: System.getenv("ADSKIPPER_KEY_PASSWORD")
val hasReleaseSigning =
    releaseStoreFile != null && !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.example.adskipper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.adskipper"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
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

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.yaml:snakeyaml:2.3")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
}
