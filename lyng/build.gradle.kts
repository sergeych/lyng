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

plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

group = "net.sergeych"
version = "unspecified"

repositories {
    mavenCentral()
    maven("https://maven.universablockchain.com/")
    maven("https://gitea.sergeych.net/api/packages/SergeychWorks/maven")
    mavenLocal()
    maven("https://gitea.sergeych.net/api/packages/SergeychWorks/maven")
}

kotlin {
    // Suppress Beta warning for expect/actual classes across all targets in this module
    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }

    jvm {
        binaries {
            executable {
                mainClass.set("net.sergeych.lyng_cli.MainKt")
            }
        }
    }
    linuxX64 {
        binaries {
            executable()
            all {
                linkerOpts(
                    "-L/lib/x86_64-linux-gnu",
                    "-l:libsqlite3.so.0",
                    "-ldl",
                    "-lpthread",
                    "-lm",
                    "-Wl,--allow-shlib-undefined"
                )
                if (buildType == org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE) {
                    debuggable = false
                    optimized = true
                }
            }
        }
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib-common"))
                implementation(project(":lynglib"))
                // Provide Lyng FS module to the CLI tool so it can install
                // filesystem access into the execution Scope by default.
                implementation(project(":lyngio"))
                implementation(libs.okio)
                implementation(libs.clikt.core)
                implementation(kotlin("stdlib-common"))
                // optional support for rendering markdown in help messages
//                implementation(libs.clikt.markdown)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.okio.fakefilesystem)
            }
        }
        val linuxTest by creating {
            dependsOn(commonTest)
        }
        val nativeMain by creating {
            dependsOn(commonMain)
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.slf4j.nop)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit"))
            }
        }
        val linuxX64Main by getting {
            dependsOn(nativeMain)
        }
        val linuxX64Test by getting {
            dependsOn(linuxTest)
        }
    }
}

tasks.named<KotlinNativeTest>("linuxX64Test") {
    dependsOn(tasks.named("linkDebugExecutableLinuxX64"))
    environment(
        "LYNG_CLI_NATIVE_BIN",
        layout.buildDirectory.file("bin/linuxX64/debugExecutable/lyng.kexe").get().asFile.absolutePath
    )
}
