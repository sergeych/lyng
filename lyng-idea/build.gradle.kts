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
version = "0.0.3-SNAPSHOT"

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
    // Rich Markdown renderer for Quick Docs
    implementation("com.vladsch.flexmark:flexmark-all:0.64.8")
}

intellij {
    type.set("IC")
    // Build against a modern baseline. Install range is controlled by since/until below.
    version.set("2024.3.1")
    // We manage <idea-version> ourselves in plugin.xml to keep it open-ended (no upper cap)
    updateSinceUntilBuild.set(false)
    // Include only available bundled plugins for this IDE build
    plugins.set(listOf(
        "com.intellij.java",
        // Provide Grazie API on compile classpath (bundled in 2024.3+, but add here for compilation)
        "tanvd.grazi"
        // Do not list com.intellij.spellchecker here: it is expected to be bundled with the IDE.
        // Listing it causes Gradle to search for a separate plugin artifact and fail on IC 2024.3.
    ))
}

tasks {
    patchPluginXml {
        // Keep version and other metadata patched by Gradle, but since/until are controlled in plugin.xml.
        // (intellij.updateSinceUntilBuild=false prevents Gradle from injecting an until-build cap)
    }

    // Build an installable plugin zip and copy it to $PROJECT_ROOT/distributables
    // Usage: ./gradlew :lyng-idea:buildInstallablePlugin
    // It depends on buildPlugin and overwrites any existing file with the same name
    register<Copy>("buildInstallablePlugin") {
        dependsOn("buildPlugin")

        // The Gradle IntelliJ Plugin produces: build/distributions/<project.name>-<version>.zip
        val zipName = "${project.name}-${project.version}.zip"
        val sourceZip = layout.buildDirectory.file("distributions/$zipName")

        from(sourceZip)
        into(rootProject.layout.projectDirectory.dir("distributables"))

        // Overwrite if a file with the same name exists
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}
