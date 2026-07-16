plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.google.android.material.color.utilities"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

}

dependencies {
    api(libs.androidx.annotation)
}
