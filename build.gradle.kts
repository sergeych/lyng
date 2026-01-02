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
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply  false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
}

// Convenience alias to run the IntelliJ IDE with the Lyng plugin from the project root.
// Usage: ./gradlew runIde
// It simply delegates to :lyng-idea:runIde provided by the Gradle IntelliJ Plugin.
tasks.register<org.gradle.api.DefaultTask>("runIde") {
    group = "intellij"
    description = "Run IntelliJ IDEA with the Lyng plugin (:lyng-idea)"
    dependsOn(":lyng-idea:runIde")
}

tasks.register<Exec>("generateDocs") {
    group = "documentation"
    description = "Generates a single-file documentation HTML using bin/generate_docs.sh"
    commandLine("./bin/generate_docs.sh")
}
