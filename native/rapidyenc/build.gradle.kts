plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.skjaere.usenet.rapidyenc"
    compileSdk = 36
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DRAPIDYENC_SRC_DIR=${rootDir}/third_party/rapidyenc",
                )
                targets += "rapidyenc_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // The wrapper jar bundles glibc builds of librapidyenc.so; the Android
            // build ships its own NDK build through jniLibs instead.
            excludes += listOf("linux-x86-64/**", "linux-aarch64/**", "META-INF/INDEX.LIST", "META-INF/{AL2.0,LGPL2.1}")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The wrapper declares the plain JNA jar; Android needs the AAR that carries libjnidispatch.so.
    api(libs.rapidyenc.wrapper) {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    api("${libs.jna.get().module}:${libs.versions.jna.get()}@aar")

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
