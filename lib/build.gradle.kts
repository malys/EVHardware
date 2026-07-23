// AGP 9 provides built-in Kotlin compilation, so no separate Kotlin plugin is applied.
// Plugin ids and dependency coordinates are hardcoded rather than pulled from a version
// catalog: this module is included as a subproject by multiple app repos, and must not
// depend on any of them defining the same catalog aliases.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.mg4.hardware"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        // The catalogue's string labels (cond_*/act_*) resolve against this library's own R,
        // which is why MG4Hardware is a real Android library and not a shared source set.
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
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
