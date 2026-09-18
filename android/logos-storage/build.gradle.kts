// No org.jetbrains.kotlin.android plugin: AGP 9's built-in Kotlin support
// makes that plugin incompatible with the new DSL. See
// https://kotl.in/gradle/agp-built-in-kotlin.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.fryorcraken.logos.storage"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk {
            // Only 64-bit ABIs — fryorcraken/logos-storage-nim's Android
            // build currently covers arm64-v8a/x86_64 only (32-bit ABIs hit
            // a pre-existing overflow bug in storage/units.nim, out of
            // scope for the fork to fix; see docs/adr/0002).
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories += "src/main/jniLibs"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":logos-common"))
    testImplementation(libs.junit)
}
