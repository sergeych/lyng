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

import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.STRING
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

group = "net.sergeych"
version = "1.0.4-SNAPSHOT"

// Removed legacy buildscript classpath declarations; plugins are applied via the plugins DSL below

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
//    alias(libs.plugins.vanniktech.mavenPublish)
    kotlin("plugin.serialization") version "2.2.21"
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
    jvm()
    androidTarget {
        publishLibraryVariants("release")
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    macosX64()
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
            compilerOptions.configure {
                freeCompilerArgs.add("-Xexpect-actual-classes")
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
        }

        val commonMain by getting {
            kotlin.srcDir("$buildDir/generated/buildConfig/commonMain/kotlin")
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
                //put your multiplatform dependencies here
                api(libs.kotlinx.coroutines.core)
                api(libs.mp.bintools)
                api("net.sergeych:mp_stools:1.5.2")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

// ---- Build-time generation of stdlib text from .lyng files into a Kotlin constant ----
// The .lyng source of the stdlib lives here (module-relative path):
val lyngStdlibDir = layout.projectDirectory.dir("stdlib/lyng")
// The generated Kotlin source will be placed here and added to commonMain sources:
val generatedLyngStdlibDir = layout.buildDirectory.dir("generated/source/lyngStdlib/commonMain/kotlin")

val generateLyngStdlib by tasks.registering {
    group = "build"
    description = "Generate Kotlin source with embedded lyng stdlib text"
    inputs.dir(lyngStdlibDir)
    outputs.dir(generatedLyngStdlibDir)
    // Simpler: opt out of configuration cache for this ad-hoc generator task
    notCompatibleWithConfigurationCache("Uses dynamic file IO in doLast; trivial generator")

    doLast {
        val targetPkg = "net.sergeych.lyng.stdlib_included"
        val targetDir = generatedLyngStdlibDir.get().asFile.resolve(targetPkg.replace('.', '/'))
        targetDir.mkdirs()

        val files = lyngStdlibDir.asFileTree.matching { include("**/*.lyng") }.files.sortedBy { it.name }
        val content = if (files.isEmpty()) "" else buildString {
            files.forEachIndexed { idx, f ->
                val text = f.readText()
                if (idx > 0) append("\n\n")
                append(text)
            }
        }

        // Emit as a regular quoted Kotlin string to avoid triple-quote edge cases
        fun escapeForQuoted(s: String): String = buildString {
            for (ch in s) when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> {} // drop CR
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        val body = escapeForQuoted(content)

        val sb = StringBuilder()
        sb.append("package ").append(targetPkg).append("\n\n")
        sb.append("@Suppress(\"Unused\", \"MemberVisibilityCanBePrivate\")\n")
        sb.append("internal val rootLyng = \"")
        sb.append(body)
        sb.append("\"\n")

        targetDir.resolve("root_lyng.generated.kt").writeText(sb.toString())
    }
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
