// No org.jetbrains.kotlin.android plugin: AGP 9's built-in Kotlin support
// makes that plugin incompatible with the new DSL (applying it throws at
// sync time). See https://kotl.in/gradle/agp-built-in-kotlin.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.fryorcraken.logos.common"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Deliberately no `dependencies {}` block referencing any sibling module in
// this repo, and no native code / jniLibs source set. See
// docs/adr/0001-module-per-native-lib-not-flavors.md and the root README's
// "Why not one library with build-time exclusion" section: this module must
// stay dependency-free so that depending on it never pulls in a native
// library's .so.
dependencies {
    testImplementation(libs.junit)
}
