plugins { id("com.android.application") }

android {
    namespace = "com.mavinokta.mncast.sender"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mavinokta.mncast.sender"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
