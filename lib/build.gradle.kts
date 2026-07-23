plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mg4.hardware"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
        // Vehicle access is all reflection against android.car (absent from the compile SDK);
        // there is nothing to test on an emulator, so no instrumentation runner.
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        // The catalogue's string labels (cond_*/act_*) resolve against this library's own R,
        // which is exactly why MG4Hardware is a real Android library and not a shared source
        // set: shared source cannot reference a fixed R.
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.annotation)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
