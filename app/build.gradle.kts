plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.filemanager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.filemanager"
        minSdk = 24
        targetSdk = 35
        // GitHub par har build ka number versionCode banta hai, taaki naya APK purane ke upar install ho sake
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1
        versionName = "1.0"
    }

    // Release APK ki chaabi GitHub Secrets se aati hai (release.yml usse file bana kar KEYSTORE_FILE me deta hai)
    signingConfigs {
        create("release") {
            val path = System.getenv("KEYSTORE_FILE")
            if (!path.isNullOrBlank() && File(path).exists()) {
                storeFile = File(path)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8: code aur resources chhote karta hai. Agar release app me kuch galat chale to
            // neeche ki dono line false kar do (debug jaisa bada par sabse safe APK ban jayega).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val hasKey = !System.getenv("KEYSTORE_FILE").isNullOrBlank()
            // Chaabi na mile to debug key se sign hota hai, taaki APK phir bhi install ho sake
            signingConfig = if (hasKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(bom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
}
