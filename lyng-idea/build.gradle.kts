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
    kotlin("jvm")
    id("org.jetbrains.intellij") version "1.17.3"
}

group = "net.sergeych.lyng"
version = "0.0.1-SNAPSHOT"

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
    // Use the same repositories as the rest of the project so plugin runtime deps resolve
    maven("https://maven.universablockchain.com/")
    maven("https://gitea.sergeych.net/api/packages/SergeychWorks/maven")
    mavenLocal()
}

dependencies {
    implementation(project(":lynglib"))
}

intellij {
    type.set("IC")
    // Run sandbox on IntelliJ IDEA 2024.3.x
    version.set("2024.3.1")
    // Include only available bundled plugins for this IDE build
    plugins.set(listOf(
        "com.intellij.java"
    ))
}

tasks {
    patchPluginXml {
        // Compatible with 2024.3+
        sinceBuild.set("243")
        untilBuild.set(null as String?)
    }
}
