// Plugin ids and dependency coordinates are hardcoded rather than pulled from a version
// catalog: this module is included as a subproject by three different app repos, each with
// its own `libs` catalog, and it must not depend on any of them defining the same aliases.
// The AGP/Kotlin plugins resolve from whichever root puts them on the classpath.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mg4.hardware"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.annotation:annotation:1.8.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
