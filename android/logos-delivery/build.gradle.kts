// No org.jetbrains.kotlin.android plugin: AGP 9's built-in Kotlin support
// makes that plugin incompatible with the new DSL. See
// https://kotl.in/gradle/agp-built-in-kotlin.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.fryorcraken.logos.delivery"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk {
            // Must match the ABIs nim-src/logos-delivery is actually built
            // for by scripts/build-nim-android.sh (all four — delivery has
            // full 4-ABI Android support upstream, unlike logos-storage,
            // see docs/adr/0002).
            abiFilters += listOf("arm64-v8a", "x86_64", "x86", "armeabi-v7a")
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
