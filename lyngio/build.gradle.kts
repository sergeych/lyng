/*
 * Copyright 2026 Sergey S. Chernov real.sergeych@gmail.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

/*
 * LyngIO: Compose Multiplatform library module depending on :lynglib
 */

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    `maven-publish`
}

group = "net.sergeych"
version = "0.0.1-SNAPSHOT"

kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget {
        publishLibraryVariants("release")
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    macosX64()
    macosArm64()
    mingwX64()
    linuxX64()
    linuxArm64()
    js {
        browser()
        nodejs()
    }
//    @OptIn(ExperimentalWasmDsl::class)
//    wasmJs() {
//        browser()
//        nodejs()
//    }

    // Keep expect/actual warning suppressed consistently with other modules
    targets.configureEach {
        compilations.configureEach {
            compilerOptions.configure {
                freeCompilerArgs.add("-Xexpect-actual-classes")
            }
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlin.ExperimentalUnsignedTypes")
            languageSettings.optIn("kotlin.time.ExperimentalTime")
        }
        val commonMain by getting {
            dependencies {
                api(project(":lynglib"))
                api(libs.okio)
                api(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        // JS: use runtime detection in jsMain to select Node vs Browser implementation
        val jsMain by getting {
            dependencies {
                api(libs.okio)
                implementation(libs.okio.fakefilesystem)
                implementation("com.squareup.okio:okio-nodefilesystem:${libs.versions.okioVersion.get()}")
            }
        }
//        // For Wasm we use in-memory VFS for now
//        val wasmJsMain by getting {
//            dependencies {
//                api(libs.okio)
//                implementation(libs.okio.fakefilesystem)
//            }
//        }
    }
}

android {
    namespace = "net.sergeych.lyngio"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        // Prevent Android Lint from failing the build due to Kotlin toolchain
        // version mismatches in the environment. This keeps CI green while
        // still generating lint reports locally.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// Disable Android Lint tasks for this module to avoid toolchain incompatibility
// until AGP and Kotlin versions align perfectly in the environment.
tasks.matching { it.name.startsWith("lint", ignoreCase = true) }.configureEach {
    this.enabled = false
}

publishing {
    val mavenToken by lazy {
        File("${System.getProperty("user.home")}/.gitea_token").readText()
    }
    repositories {
        maven {
            credentials(HttpHeaderCredentials::class) {
                name = "Authorization"
                value = mavenToken
            }
            url = uri("https://gitea.sergeych.net/api/packages/SergeychWorks/maven")
            authentication {
                create("Authorization", HttpHeaderAuthentication::class)
            }
        }
    }
}
