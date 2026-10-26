plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
}


// The ABIs native code is built for, and the libraries of which end up in the app: 64-bit ARM only, for now.
// Change `greenhouse.abis` in gradle.properties (or pass -Pgreenhouse.abis=...) to build for more, as a
// comma-separated list: arm64-v8a, armeabi-v7a, x86, x86_64. This covers every Android module, the ones of
// the external aap-core included.
val nativeAbis: List<String> = (findProperty("greenhouse.abis") as String?)
    ?.split(",")
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?: listOf("arm64-v8a")

subprojects {
    group = "org.androidaudioplugin"

    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            defaultConfig {
                ndk {
                    abiFilters.clear()
                    abiFilters.addAll(nativeAbis)
                }

                externalNativeBuild {
                    cmake {
                        arguments("-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384")
                    }
                }
            }
        }
    }

    plugins.withId("com.android.application") {
        extensions.configure<com.android.build.api.dsl.ApplicationExtension> {
            defaultConfig {
                ndk {
                    abiFilters.clear()
                    abiFilters.addAll(nativeAbis)
                }

                externalNativeBuild {
                    cmake {
                        arguments("-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384")
                    }
                }
            }
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
