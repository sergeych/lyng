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

import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.STRING
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

group = "net.sergeych"
version = "1.5.3-SNAPSHOT"

// Removed legacy buildscript classpath declarations; plugins are applied via the plugins DSL below

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
//    alias(libs.plugins.vanniktech.mavenPublish)
    kotlin("plugin.serialization") version "2.3.20"
    id("com.codingfeline.buildkonfig") version "0.17.1"
    `maven-publish`
}

buildkonfig {
    packageName = "net.sergeych.lyng"
    // objectName = "YourAwesomeConfig"
    // exposeObjectWithName = "YourAwesomePublicConfig"

    defaultConfigs {
        buildConfigField(STRING, "bcprovider", "codingfeline")
        buildConfigField(STRING, "version", version.toString())
    }
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
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs() {
        browser()
        nodejs()
    }

    // Suppress Beta warning for expect/actual classes across all targets
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
            languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
            languageSettings.optIn("kotlin.ExperimentalUnsignedTypes")
            // Correct opt-in markers for coroutines
            languageSettings.optIn("kotlinx.coroutines.DelicateCoroutinesApi")
            languageSettings.optIn("kotlin.contracts.ExperimentalContracts")
            languageSettings.optIn("kotlinx.coroutines.FlowPreview")
            languageSettings.optIn("kotlin.time.ExperimentalTime")
        }

        val commonMain by getting {
            kotlin.srcDir(layout.buildDirectory.dir("generated/buildConfig/commonMain/kotlin"))
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
                //put your multiplatform dependencies here
                api(libs.kotlinx.coroutines.core)
                api(libs.mp.bintools)
                implementation(libs.ionspin.bignum)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val matrixMultikMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.multik.default)
            }
        }
        val nativeMain by creating {
            dependsOn(commonMain)
        }
        val matrixPureMain by creating {
            dependsOn(commonMain)
        }
        val jvmMain by getting { dependsOn(matrixMultikMain) }
        val androidMain by getting { dependsOn(matrixPureMain) }
        val jsMain by getting { dependsOn(matrixMultikMain) }
        val wasmJsMain by getting { dependsOn(matrixMultikMain) }
        // Multik 0.3.0 does not publish ios native artifacts, so keep iOS on the pure backend.
        val iosX64Main by getting {
            dependsOn(nativeMain)
            dependsOn(matrixPureMain)
        }
        val iosArm64Main by getting {
            dependsOn(nativeMain)
            dependsOn(matrixPureMain)
        }
        val iosSimulatorArm64Main by getting {
            dependsOn(nativeMain)
            dependsOn(matrixPureMain)
        }
        val macosArm64Main by getting {
            dependsOn(nativeMain)
            dependsOn(matrixPureMain)
        }
        val mingwX64Main by getting {
            dependsOn(nativeMain)
            dependsOn(matrixPureMain)
        }
        val linuxX64Main by getting {
            dependsOn(nativeMain)
            dependsOn(matrixPureMain)
        }
        val linuxArm64Main by getting {
            dependsOn(nativeMain)
            dependsOn(matrixPureMain)
        }
        val jvmTest by getting {
            dependencies {
                // Allow tests to load external docs like lyng.io.fs via registrar
                implementation(project(":lyngio"))
            }
        }
    }
}

android {
    namespace = "net.sergeych.lynglib"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// ---- Build-time generation of stdlib text from .lyng files into a Kotlin constant ----
// Implemented as a proper task type compatible with Gradle Configuration Cache

abstract class GenerateLyngStdlib : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val targetPkg = "net.sergeych.lyng.stdlib_included"
        val pkgPath = targetPkg.replace('.', '/')
        val outBase = outputDir.get().asFile
        val targetDir = outBase.resolve(pkgPath)
        targetDir.mkdirs()
        targetDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".generated.kt") }
            ?.forEach { it.delete() }

        val srcDir = sourceDir.get().asFile
        val files = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "lyng" }
            .sortedBy { it.name }
            .toList()

        fun escapeForQuoted(s: String): String = buildString {
            for (ch in s) when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '$' -> append("\\$")
                '\n' -> append("\\n")
                '\r' -> {}
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }

        fun constantName(baseName: String): String {
            val parts = baseName.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
            if (parts.isEmpty()) return "moduleLyng"
            val head = parts.first().replaceFirstChar { it.lowercase() }
            val tail = parts.drop(1).joinToString("") { part ->
                part.replaceFirstChar { it.uppercase() }
            }
            return "${head}${tail}Lyng"
        }

        for (file in files) {
            val body = escapeForQuoted(file.readText())
            val sb = StringBuilder()
            sb.append("package ").append(targetPkg).append("\n\n")
            sb.append("@Suppress(\"Unused\", \"MemberVisibilityCanBePrivate\")\n")
            sb.append("internal val ").append(constantName(file.nameWithoutExtension)).append(" = \"")
            sb.append(body)
            sb.append("\"\n")
            targetDir.resolve("${file.nameWithoutExtension}_lyng.generated.kt").writeText(sb.toString())
        }
    }
}

// The .lyng source of the stdlib lives here (module-relative path):
val lyngStdlibDir = layout.projectDirectory.dir("stdlib/lyng")
// The generated Kotlin source will be placed here and added to commonMain sources:
val generatedLyngStdlibDir = layout.buildDirectory.dir("generated/source/lyngStdlib/commonMain/kotlin")

val generateLyngStdlib by tasks.registering(GenerateLyngStdlib::class) {
    group = "build"
    description = "Generate Kotlin source with embedded lyng stdlib text"
    sourceDir.set(lyngStdlibDir)
    outputDir.set(generatedLyngStdlibDir)
}

// Add the generated directory to commonMain sources
kotlin.sourceSets.named("commonMain") {
    kotlin.srcDir(generatedLyngStdlibDir)
}

// Ensure ALL Kotlin compilations (all targets/variants) depend on the generator
kotlin.targets.configureEach {
    compilations.configureEach {
        compileTaskProvider.configure {
            dependsOn(generateLyngStdlib)
        }
    }
}

// Ensure any SourcesJar tasks (for all targets/variants) are properly wired to the generator
tasks.withType<Jar>().configureEach {
    if (name == "sourcesJar" || name.endsWith("SourcesJar")) {
        // Declare both dependency and inputs to satisfy Gradle validation and up-to-date checks
        dependsOn(generateLyngStdlib)
        inputs.dir(generatedLyngStdlibDir)
    }
}

// Extra safety: in case the SourcesJar task is not of type Jar (AGP/MPP variations),
// wire it up by name as well. This guarantees the dependency even if the concrete type differs.
tasks.configureEach {
    if (name == "androidReleaseSourcesJar" || name == "sourcesJar" || name.endsWith("SourcesJar")) {
        dependsOn(generateLyngStdlib)
        inputs.dir(generatedLyngStdlibDir)
    }
}

// Be explicit for the aggregate metadata sources task too
tasks.named("sourcesJar").configure {
    dependsOn(generateLyngStdlib)
    inputs.dir(generatedLyngStdlibDir)
}

android {
    namespace = "org.jetbrains.kotlinx.multiplatform.library.template"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
dependencies {
    implementation(libs.firebase.crashlytics.buildtools)
    implementation(libs.compiler)
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

// Ensure JVM test stdout is visible and runs are single-threaded for stable timings
tasks.withType<org.gradle.api.tasks.testing.Test> {
    testLogging {
        showStandardStreams = true
    }
    maxParallelForks = 1

    // Benchmarks toggle: disabled by default, enable when optimizing locally.
    // Enable via any of the following:
    //  - Gradle property:  ./gradlew :lynglib:jvmTest -Pbenchmarks=true
    //  - JVM system prop:  ./gradlew :lynglib:jvmTest -Dbenchmarks=true
    //  - Environment var:  BENCHMARKS=true ./gradlew :lynglib:jvmTest
    val benchmarksEnabled: Boolean = run {
        val p = (project.findProperty("benchmarks") as String?)?.toBooleanStrictOrNull()
        val s = System.getProperty("benchmarks")?.lowercase()?.let { it == "true" || it == "1" || it == "yes" }
        val e = System.getenv("BENCHMARKS")?.lowercase()?.let { it == "true" || it == "1" || it == "yes" }
        p ?: s ?: e ?: false
    }

    // Make the flag visible inside tests if they want to branch on it
    systemProperty("LYNG_BENCHMARKS", benchmarksEnabled.toString())

    if (!benchmarksEnabled) {
        // Exclude all JVM tests whose class name ends with or contains BenchmarkTest
        // This keeps CI fast and avoids noisy timing logs by default.
        filter {
            excludeTestsMatching("*BenchmarkTest")
            // Also guard against alternative naming
            excludeTestsMatching("*Bench*Test")
            // Exclude A/B performance tests unless explicitly enabled
            excludeTestsMatching("*ABTest")
            // Exclude stress/perf soak tests
            excludeTestsMatching("*Stress*Test")
            // Exclude allocation profiling tests by default
            excludeTestsMatching("*AllocationProfileTest")
        }
        logger.lifecycle("[tests] Benchmarks are DISABLED. To enable: -Pbenchmarks=true or -Dbenchmarks=true or set BENCHMARKS=true")
    } else {
        logger.lifecycle("[tests] Benchmarks are ENABLED: *BenchmarkTest will run")
    }
}

//mavenPublishing {
//    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
//
//    signAllPublications()
//
//    coordinates(group.toString(), "library", version.toString())
//
//    pom {
//        name = "Lyng language"
//        description = "Kotlin-bound scripting loanguage"
//        inceptionYear = "2025"
////        url = "https://sergeych.net"
//        licenses {
//            license {
//                name = "XXX"
//                url = "YYY"
//                distribution = "ZZZ"
//            }
//        }
//        developers {
//            developer {
//                id = "XXX"
//                name = "YYY"
//                url = "ZZZ"
//            }
//        }
//        scm {
//            url = "XXX"
//            connection = "YYY"
//            developerConnection = "ZZZ"
//        }
//    }
//}
