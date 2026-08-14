// AGP 9 provides built-in Kotlin compilation, so no separate Kotlin plugin is applied.
// Plugin ids and dependency coordinates are hardcoded rather than pulled from a version
// catalog: this module is included as a subproject by multiple app repos, and must not
// depend on any of them defining the same catalog aliases.
plugins {
    id("com.android.library")
    // Publishes a release AAR (GitHub Packages / mavenLocal) so EXTERNAL projects can
    // depend on EVHardware as a binary. The EVSuite apps keep the git-submodule
    // source dependency; the AAR is purely for outside consumers.
    id("maven-publish")
}

// Coordinates for the published AAR. Version tracks git tags in CI (see publish workflow);
// the default here is for local publishing.
group = "com.evsuite"
version = System.getenv("EVHARDWARE_VERSION") ?: "0.1.0-SNAPSHOT"

android {
    namespace = "com.evsuite.hardware"
    compileSdk = 36

    defaultConfig {
        minSdk = 27
        consumerProguardFiles("consumer-rules.pro")
    }

    // Required for maven-publish to expose a component from an Android library.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    buildFeatures {
        // The catalogue's string labels (cond_*/act_*) resolve against this library's own R,
        // which is why EVHardware is a real Android library and not a shared source set.
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

// AGP builds the "release" component only after evaluation, so wire the publication there.
publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.evsuite"
            artifactId = "evhardware"
            afterEvaluate { from(components["release"]) }
        }
    }
    repositories {
        // GitHub Packages when the CI env vars are present; otherwise ./gradlew
        // :lib:publishToMavenLocal works for local/external experimentation.
        val ghUser = System.getenv("GITHUB_ACTOR")
        val ghToken = System.getenv("GITHUB_TOKEN")
        if (ghUser != null && ghToken != null) {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/malys/EVHardware")
                credentials { username = ghUser; password = ghToken }
            }
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
