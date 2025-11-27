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
 * JS library module providing Lyng web UI components and HTML highlighter
 */

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21"
    id("org.jetbrains.compose") version "1.9.3"
    `maven-publish`
}

group = "net.sergeych"
version = "0.0.1-SNAPSHOT"


kotlin {
    js(IR) {
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
            commonWebpackConfig {
                cssSupport { enabled.set(true) }
            }
        }
        nodejs {
            testTask {
                useMocha()
            }
        }
        binaries.library()
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation("org.jetbrains.compose.runtime:runtime:1.9.3")
                implementation("org.jetbrains.compose.html:html-core:1.9.3")
                implementation(libs.kotlinx.coroutines.core)
                api(project(":lynglib"))
            }
        }
        val jsTest by getting {
            dependencies { implementation(libs.kotlin.test) }
        }
    }
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
