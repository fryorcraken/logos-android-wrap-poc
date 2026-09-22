// No org.jetbrains.kotlin.android plugin: AGP 9's built-in Kotlin support
// makes that plugin incompatible with the new DSL (kotlin.plugin.compose is
// unaffected — it's not part of built-in Kotlin and stays required for
// Compose). See https://kotl.in/gradle/agp-built-in-kotlin.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.fryorcraken.logos.demo.delivery"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.fryorcraken.logos.demo.delivery"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    // POC-appropriate signing only: the release build type reuses the
    // standard Android debug keystore (~/.android/debug.keystore, created
    // automatically by AGP/adb the first time it's needed -- including
    // freshly on a CI runner, so no key material needs to be committed or
    // provisioned as a GitHub Secret). This makes assembleRelease produce an
    // installable APK instead of an unsigned one, which is all this POC's
    // release pipeline needs. It is explicitly NOT a production signing
    // setup: a real release build must use a dedicated, secret-managed
    // release keystore. Setting one up (GitHub Secrets, Play App Signing,
    // etc.) is a deliberately out-of-scope open item for this proof of
    // concept -- see the root README's release process section.
    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

// Depends ONLY on logos-delivery (+ logos-common transitively). Must NOT
// depend on logos-storage or logos-glue, directly or transitively — this is
// the structural property CI's verify-no-storage-in-delivery-apk.sh checks
// actually held in the built APK. See the root README's "Why not one
// library with build-time exclusion" section.
dependencies {
    implementation(project(":logos-delivery"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
