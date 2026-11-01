plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.popkter.ptvlauncher.networkbridge"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.popkter.ptvlauncher.networkbridge"
        minSdk = 24
        // Isolate Android's documented pre-Q Wi-Fi compatibility API from the modern launcher.
        targetSdk = 28
        versionCode = 2
        versionName = "2.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    lint {
        // Local, signature-gated compatibility APK: raising this target disables its
        // documented WifiManager.addNetwork/enableNetwork compatibility contract.
        disable += "ExpiredTargetSdkVersion"
    }
}
