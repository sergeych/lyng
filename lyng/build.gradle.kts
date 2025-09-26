/*
 * Copyright 2025 Sergey S. Chernov real.sergeych@gmail.com
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
    kotlin("multiplatform") version "2.2.20"
}

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
        }
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib-common"))
                implementation(project(":lynglib"))
                implementation(libs.okio)
                implementation(libs.clikt)
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
//        val nativeMain by getting {
//            dependencies {
//                implementation(kotlin("stdlib-common"))
//            }
//        }
        val linuxX64Main by getting {

        }
    }
}