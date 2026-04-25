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
 * Compose HTML (JS-only) SPA module
 */

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    // Compose compiler plugin aligned with the project Kotlin version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
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
                // Shared web editor and highlighting utilities
                implementation(project(":lyngweb"))
                // Markdown parser (NPM)
                implementation(npm("marked", "12.0.2"))
                // Self-host MathJax via npm and bundle it with webpack
                implementation(npm("mathjax", "3.2.2"))
            }
            // Serve images as static resources in the site
            resources.srcDir(rootProject.projectDir.resolve("images"))
            // Also include generated resources (e.g., docs index JSON)
            // Use Gradle's layout to properly reference the build directory provider
            resources.srcDir(layout.buildDirectory.dir("generated-resources"))
        }
        val jsTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                // Compose test support (renderComposable)
                implementation("org.jetbrains.compose.runtime:runtime:1.9.3")
                implementation("org.jetbrains.compose.html:html-core:1.9.3")
                implementation(project(":lyngweb"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
            }
        }
    }
}

// Generate an index of markdown documents under project /docs as a JSON array
val generateSampleDocPages by tasks.registering {
    group = "documentation"
    description = "Generates Markdown wrapper pages for Lyng sample files"

    val examplesDir = rootProject.projectDir.resolve("examples")
    val docsSamplesDir = rootProject.projectDir.resolve("docs/samples")
    val outDir = layout.buildDirectory.dir("generated-sample-docs/docs")

    inputs.dir(examplesDir)
    inputs.dir(docsSamplesDir)
    outputs.dir(outDir)

    doLast {
        val outRoot = outDir.get().asFile
        outRoot.mkdirs()

        fun generateFrom(sourceRoot: java.io.File, targetSubdir: String) {
            if (!sourceRoot.exists()) return
            sourceRoot.walkTopDown()
                .filter { it.isFile && it.extension.equals("lyng", ignoreCase = true) }
                .forEach { source ->
                    val rel = sourceRoot.toPath().relativize(source.toPath()).toString().replace('\\', '/')
                    val target = outRoot.resolve("$targetSubdir/$rel.md")
                    target.parentFile.mkdirs()
                    val title = source.name
                    val sourceText = source.readText()
                    val body = buildString {
                        append("# ").append(title).append("\n\n")
                        append("Generated from `")
                        append(
                            when (targetSubdir) {
                                "examples" -> "examples/$rel"
                                "samples" -> "docs/samples/$rel"
                                else -> "$targetSubdir/$rel"
                            }
                        )
                        append("` during site build.\n\n")
                        append("```lyng\n")
                        append(sourceText)
                        if (!sourceText.endsWith("\n")) append('\n')
                        append("```\n")
                    }
                    target.writeText(body)
                }
        }

        generateFrom(examplesDir, "examples")
        generateFrom(docsSamplesDir, "samples")
    }
}

val generateDocsIndex by tasks.registering {
    group = "documentation"
    description = "Generates docs-index.json listing all Markdown files under /docs"

    val docsDir = rootProject.projectDir.resolve("docs")
    val generatedDocsDir = layout.buildDirectory.dir("generated-sample-docs/docs")
    val outDir = layout.buildDirectory.dir("generated-resources")

    inputs.dir(docsDir)
    inputs.dir(generatedDocsDir)
    outputs.dir(outDir)

    dependsOn(generateSampleDocPages)

    doLast {
        val docs = linkedSetOf<String>()
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
        val generatedRoot = generatedDocsDir.get().asFile
        if (generatedRoot.exists()) {
            generatedRoot.walkTopDown()
                .filter { it.isFile && it.extension.equals("md", ignoreCase = true) }
                .forEach { f ->
                    val rel = generatedRoot.toPath().relativize(f.toPath()).toString()
                        .replace('\\', '/')
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
        println("Generated ${file.absolutePath} with ${docs.size} entries")
    }
}

val generateSiteVersion by tasks.registering(Copy::class) {
    group = "documentation"
    description = "Generates lyng-version.js from :lynglib version"

    val outDir = layout.buildDirectory.dir("generated-resources")
    val versionText = project(":lynglib").version.toString()

    inputs.property("lyngVersion", versionText)
    from(layout.projectDirectory.dir("src/version-template")) {
        include("lyng-version.js")
        filter<org.apache.tools.ant.filters.ReplaceTokens>(
            "tokens" to mapOf("LYNG_VERSION" to versionText)
        )
    }
    into(outDir)
}

// Ensure any ProcessResources task depends on docs index generation so the JSON is packaged
tasks.configureEach {
    if (name.endsWith("ProcessResources")) {
        dependsOn(generateSampleDocPages, generateDocsIndex, generateSiteVersion)
    }
}

// Also make common dev/prod tasks depend on docs index generation to avoid 404 during dev server
listOf(
    "browserDevelopmentRun",
    "browserProductionWebpack",
    "jsProcessResources"
).forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        dependsOn(generateSampleDocPages, generateDocsIndex)
    }
}

// Copy Markdown docs into the "docs/" folder in the final resources, so paths in docs-index.json match files
tasks.named<Copy>("jsProcessResources").configure {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // Ensure we don't end up with two copies at root; we no longer add docs as a plain resources srcDir
    from(rootProject.projectDir.resolve("docs")) {
        into("docs")
    }
    from(layout.buildDirectory.dir("generated-sample-docs/docs")) {
        into("docs")
    }
}

// Optional: configure toolchain if needed by the project; uses root Kotlin version from version catalog
