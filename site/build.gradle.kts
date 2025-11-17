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

/*
 * Compose HTML (JS-only) SPA module
 */

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    // Compose compiler plugin for Kotlin 2.2.21 (matches version catalog)
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
    // Compose Multiplatform plugin for convenient dependencies (compose.html.core, etc.)
    id("org.jetbrains.compose") version "1.9.3"
}

kotlin {
    js(IR) {
        browser {
            commonWebpackConfig {
                // Enable CSS handling
                cssSupport {
                    enabled.set(true)
                }
                // Ensure predictable output name so we can reference it from index.html
                outputFileName = "site.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation("org.jetbrains.compose.runtime:runtime:1.9.3")
                implementation("org.jetbrains.compose.html:html-core:1.9.3")
            }
        }
        val jsTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

// Optional: configure toolchain if needed by the project; uses root Kotlin version from version catalog