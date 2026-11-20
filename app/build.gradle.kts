plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.xianmod.payment"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xianmod.payment"
        minSdk = 26
        targetSdk = 35
        // versionCode naik otomatis di GitHub Actions supaya APK baru selalu diterima sebagai update.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0) + 100
        versionName = "1.5.1"
        buildConfigField("String", "API_BASE_URL", "\"https://xianmodbot.vercel.app\"")
    }

    // Kunci tanda tangan tetap (dari GitHub Secrets) agar APK bisa di-update tanpa uninstall.
    // Tanpa secret, build memakai kunci debug acak seperti sebelumnya.
    val keystorePath = System.getenv("XIANMOD_KEYSTORE_FILE")
    val keystorePassword = System.getenv("XIANMOD_KEYSTORE_PASSWORD")
    signingConfigs {
        if (!keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank() && file(keystorePath).exists()) {
            create("xianmod") {
                storeFile = file(keystorePath)
                storeType = "pkcs12"
                storePassword = keystorePassword
                keyAlias = "xianmod"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("xianmod")?.let { signingConfig = it }
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

