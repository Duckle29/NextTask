// A black-box test of an installed NextTask: it drives the app's screen with UI Automator and checks what reaches
// the fake Nextcloud in tools/fake-nextcloud. It never touches the app's code, so it can test the signed release
// APK exactly as published. Run it with scripts/smoke-test.sh.
plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "cc.sche.apps.nexttask.smoketest"
    compileSdk = 37
    targetProjectPath = ":androidApp"

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The test instruments itself rather than the app, so it does not have to be signed with the app's key.
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.junit)
    implementation(libs.androidx.test.uiautomator)
}
