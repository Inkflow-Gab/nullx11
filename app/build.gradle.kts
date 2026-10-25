plugins {
    alias(libs.plugins.android.application)
}

// Release signing is provided by GitHub Actions as environment variables.
// When they are unset (e.g. building locally), the release APK stays unsigned.
val releaseKeystorePath: String? = System.getenv("NULLX11_KEYSTORE")

android {
    namespace = "com.nullx11.official"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.nullx11.official"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    if (releaseKeystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("NULLX11_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NULLX11_KEY_ALIAS")
                keyPassword = System.getenv("NULLX11_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            signingConfig = if (releaseKeystorePath != null) signingConfigs.getByName("release") else null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
}
