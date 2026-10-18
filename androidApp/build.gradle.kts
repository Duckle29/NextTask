plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The version is not kept in the repository: a release build gets it from the git tag
// (-PversionName / -PversionCode, see scripts/version-from-tag.sh). Local builds are marked as such.
val appVersionName = providers.gradleProperty("versionName").orElse("0.0.0-dev")
val appVersionCode = providers.gradleProperty("versionCode").map(String::toInt).orElse(1)

// Release signing comes from the environment, so no key or password is ever stored in the repository.
// Without ANDROID_KEYSTORE_PATH the release build is simply left unsigned.
val keystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH")

android {
    namespace = "cc.sche.apps.nexttask"
    compileSdk = 37

    defaultConfig {
        applicationId = "cc.sche.apps.nexttask"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode.get()
        versionName = appVersionName.get()
    }

    signingConfigs {
        if (keystorePath.isPresent) {
            create("release") {
                storeFile = file(keystorePath.get())
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            // Removes unused code and resources: the release APK shrinks from 14 MB to under 2 MB.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
