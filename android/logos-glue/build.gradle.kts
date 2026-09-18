// No org.jetbrains.kotlin.android plugin: AGP 9's built-in Kotlin support
// makes that plugin incompatible with the new DSL. See
// https://kotl.in/gradle/agp-built-in-kotlin.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.fryorcraken.logos.glue"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// This is the ONLY module in the repo permitted to depend on both
// native-bearing modules at once — see docs/adr/0001 and the root README's
// "Why not one library with build-time exclusion" section. An app that
// depends on logos-glue transitively pulls in both liblogosdelivery.so and
// libstorage.so; an app that never adds this dependency gets neither,
// regardless of what code it contains. No jniLibs of its own — this module
// is pure Kotlin glue over the two already-loaded native modules.
dependencies {
    api(project(":logos-delivery"))
    api(project(":logos-storage"))
    testImplementation(libs.junit)
}
