plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.popkter.ptvlauncher.homebridge"
    compileSdk = 36
    defaultConfig {
        // This firmware checks this allowlisted application ID for HOME eligibility.
        // Local compatibility build only; unrelated to the owner of this package name.
        applicationId = "com.ts.launcher"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
