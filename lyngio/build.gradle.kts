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

private fun Project.sqliteLinuxLinkerOpts(vararg defaultDirs: String): List<String> {
    val overrideDir = providers.gradleProperty("sqlite3.lib.dir").orNull
        ?: providers.environmentVariable("SQLITE3_LIB_DIR").orNull
    val candidateDirs = buildList {
        if (!overrideDir.isNullOrBlank()) {
            add(file(overrideDir))
        }
        defaultDirs.forEach { add(file(it)) }
    }.distinctBy { it.absolutePath }

    val discoveredLib = sequenceOf("libsqlite3.so", "libsqlite3.so.0")
        .mapNotNull { libraryName ->
            candidateDirs.firstOrNull { it.resolve(libraryName).isFile }?.let { dir ->
                listOf("-L${dir.absolutePath}", "-l:$libraryName")
            }
        }
        .firstOrNull()
        ?: listOf("-lsqlite3")

    return discoveredLib + listOf(
        "-ldl",
        "-lpthread",
        "-lm",
        "-Wl,--allow-shlib-undefined"
    )
}

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

    targets.withType(org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget::class.java).configureEach {
        compilations.getByName("main").cinterops.create("sqlite3") {
            defFile(project.file("src/nativeInterop/cinterop/sqlite/sqlite3.def"))
            packageName("net.sergeych.lyng.io.db.sqlite.cinterop")
            includeDirs(project.file("src/nativeInterop/cinterop/sqlite"))
        }
        binaries.all {
            when (konanTarget.name) {
                "linux_x64" -> linkerOpts(
                    *project.sqliteLinuxLinkerOpts(
                        "/lib/x86_64-linux-gnu",
                        "/usr/lib/x86_64-linux-gnu",
                        "/lib64",
                        "/usr/lib64",
                        "/lib",
                        "/usr/lib"
                    ).toTypedArray()
                )
                "linux_arm64" -> linkerOpts(
                    *project.sqliteLinuxLinkerOpts(
                        "/lib/aarch64-linux-gnu",
                        "/usr/lib/aarch64-linux-gnu",
                        "/lib64",
                        "/usr/lib64",
                        "/lib",
                        "/usr/lib"
                    ).toTypedArray()
                )
                else -> linkerOpts("-lsqlite3")
            }
        }
    }

    // Keep expect/actual warning suppressed consistently with other modules
    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
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
                api(libs.mordant.core)
                api(libs.ktor.client.core)
                implementation(libs.ktor.client.websockets)
            }
        }
        val nativeMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.ktor.network)
            }
        }
        val darwinMain by creating {
            dependsOn(nativeMain)
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
        val iosMain by creating {
            dependsOn(darwinMain)
        }
        val linuxMain by creating {
            dependsOn(nativeMain)
            dependencies {
                implementation(libs.ktor.client.curl)
            }
        }
        val macosMain by creating {
            dependsOn(darwinMain)
        }
        val mingwMain by creating {
            dependsOn(nativeMain)
            dependencies {
                implementation(libs.ktor.client.winhttp)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(libs.testcontainers)
                implementation(libs.testcontainers.postgresql)
            }
        }
        val linuxTest by creating {
            dependsOn(commonTest)
        }
        val iosX64Main by getting { dependsOn(iosMain) }
        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }
        val macosArm64Main by getting { dependsOn(macosMain) }
        val mingwX64Main by getting { dependsOn(mingwMain) }
        val linuxX64Main by getting { dependsOn(linuxMain) }
        val linuxArm64Main by getting { dependsOn(linuxMain) }
        val linuxX64Test by getting { dependsOn(linuxTest) }
        val linuxArm64Test by getting { dependsOn(linuxTest) }

        // JS: use runtime detection in jsMain to select Node vs Browser implementation
        val jsMain by getting {
            dependencies {
                api(libs.okio)
                implementation(libs.okio.fakefilesystem)
                implementation("com.squareup.okio:okio-nodefilesystem:${libs.versions.okioVersion.get()}")
                implementation(libs.ktor.client.js)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.network)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.mordant.jvm.jna)
                implementation("org.jline:jline-reader:3.29.0")
                implementation("org.jline:jline-terminal:3.29.0")
                implementation(libs.ktor.client.cio)
                implementation(libs.ktor.network)
                implementation(libs.sqlite.jdbc)
                implementation(libs.h2)
                implementation(libs.postgresql)
                implementation(libs.hikaricp)
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

abstract class GenerateLyngioDecls : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val targetPkg = "net.sergeych.lyngio.stdlib_included"
        val pkgPath = targetPkg.replace('.', '/')
        val targetDir = outputDir.get().asFile.resolve(pkgPath)
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()

        fun escapeForQuoted(s: String): String = buildString {
            for (ch in s) when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> {}
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }

        val out = buildString {
            append("package ").append(targetPkg).append("\n\n")
            append("@Suppress(\"Unused\", \"MemberVisibilityCanBePrivate\")\n")
            sourceDir.get().asFile
                .listFiles { file -> file.isFile && file.extension == "lyng" }
                ?.sortedBy { it.name }
                ?.forEach { file ->
                    val propertyName = buildString {
                        append(file.nameWithoutExtension)
                        append("Lyng")
                    }
                    append("internal val ").append(propertyName).append(" = \"")
                    append(escapeForQuoted(file.readText()))
                    append("\"\n")
                }
        }
        targetDir.resolve("lyngio_types_lyng.generated.kt").writeText(out)
    }
}

val lyngioDeclsDir = layout.projectDirectory.dir("stdlib/lyng/io")
val generatedLyngioDeclsDir = layout.buildDirectory.dir("generated/source/lyngioDecls/commonMain/kotlin")

val generateLyngioDecls by tasks.registering(GenerateLyngioDecls::class) {
    sourceDir.set(lyngioDeclsDir)
    outputDir.set(generatedLyngioDeclsDir)
}

kotlin.sourceSets.named("commonMain") {
    kotlin.srcDir(generateLyngioDecls)
}

kotlin.targets.configureEach {
    compilations.configureEach {
        compileTaskProvider.configure {
            dependsOn(generateLyngioDecls)
        }
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
