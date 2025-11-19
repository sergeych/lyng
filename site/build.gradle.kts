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
                // Coroutines for JS (used for fetching docs)
                implementation(libs.kotlinx.coroutines.core)
                // Lyng highlighter (common, used from JS)
                implementation(project(":lynglib"))
                // Markdown parser (NPM)
                implementation(npm("marked", "12.0.2"))
            }
            // Serve project docs and images as static resources in the site
            resources.srcDir(rootProject.projectDir.resolve("docs"))
            resources.srcDir(rootProject.projectDir.resolve("images"))
            // Also include generated resources (e.g., docs index JSON)
            // Use Gradle's layout to properly reference the build directory provider
            resources.srcDir(layout.buildDirectory.dir("generated-resources"))
        }
        val jsTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

// Generate an index of markdown documents under project /docs as a JSON array
val generateDocsIndex by tasks.registering {
    group = "documentation"
    description = "Generates docs-index.json listing all Markdown files under /docs"

    val docsDir = rootProject.projectDir.resolve("docs")
    val outDir = layout.buildDirectory.dir("generated-resources")

    inputs.dir(docsDir)
    outputs.dir(outDir)

    doLast {
        val docs = mutableListOf<String>()
        if (docsDir.exists()) {
            docsDir.walkTopDown()
                .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
                .forEach { f ->
                    val rel = docsDir.toPath().relativize(f.toPath()).toString()
                        .replace('\\', '/')
                    // store paths relative to site root, e.g. "docs/Iterator.md"
                    docs += "docs/$rel"
                }
        }
        val out = outDir.get().asFile
        out.mkdirs()
        val file = out.resolve("docs-index.json")
        val json = buildString {
            append('[')
            docs.forEachIndexed { i, s ->
                if (i > 0) append(',')
                append('"').append(s.replace("\"", "\\\""))
                    .append('"')
            }
            append(']')
        }
        file.writeText(json)
        println("Generated ${'$'}{file.absolutePath} with ${'$'}{docs.size} entries")
    }
}

// Ensure any ProcessResources task depends on docs index generation so the JSON is packaged
tasks.configureEach {
    if (name.endsWith("ProcessResources")) {
        dependsOn(generateDocsIndex)
    }
}

// Also make common dev/prod tasks depend on docs index generation to avoid 404 during dev server
listOf(
    "browserDevelopmentRun",
    "browserProductionWebpack",
    "jsProcessResources"
).forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        dependsOn(generateDocsIndex)
    }
}

// Optional: configure toolchain if needed by the project; uses root Kotlin version from version catalog