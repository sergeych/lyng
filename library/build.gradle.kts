import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.STRING
import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

group = "net.sergeych"
version = "0.6.1-SNAPSHOT"

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.4.0")
        classpath("com.codingfeline.buildkonfig:buildkonfig-gradle-plugin:latest_version")
    }
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.vanniktech.mavenPublish)
    kotlin("plugin.serialization") version "2.1.20"
    id("com.codingfeline.buildkonfig") version "0.17.1"
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
//    iosX64()
//    iosArm64()
//    iosSimulatorArm64()
    linuxX64()
    js {
        browser()
        nodejs()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs() {
        browser()
        nodejs()
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
            languageSettings.optIn("kotlin.contracts.ExperimentalContracts::class")
            languageSettings.optIn("kotlin.ExperimentalUnsignedTypes")
            languageSettings.optIn("kotlin.coroutines.DelicateCoroutinesApi")
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
}

mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    signAllPublications()

    coordinates(group.toString(), "library", version.toString())

    pom {
        name = "Lyng language"
        description = "Kotlin-bound scripting loanguage"
        inceptionYear = "2025"
//        url = "https://sergeych.net"
        licenses {
            license {
                name = "XXX"
                url = "YYY"
                distribution = "ZZZ"
            }
        }
        developers {
            developer {
                id = "XXX"
                name = "YYY"
                url = "ZZZ"
            }
        }
        scm {
            url = "XXX"
            connection = "YYY"
            developerConnection = "ZZZ"
        }
    }
}
//
//val projectVersion  by project.extra(provider {
//    // Compute value lazily
//    (version as String)
//})
//
//val generateBuildConfig by tasks.registering {
//    // Declare outputs safely
//    val outputDir = layout.buildDirectory.dir("generated/buildConfig/commonMain/kotlin")
//    outputs.dir(outputDir)
//
//    val version = projectVersion.get()
//
//    // Inputs: Version is tracked as an input
//    inputs.property("version", version)
//
//    doLast {
//        val packageName = "net.sergeych.lyng.buildconfig"
//        val packagePath = packageName.replace('.', '/')
//        val buildConfigFile = outputDir.get().file("$packagePath/BuildConfig.kt").asFile
//
//        buildConfigFile.parentFile?.mkdirs()
//        buildConfigFile.writeText(
//            """
//            |package $packageName
//            |
//            |object BuildConfig {
//            |    const val VERSION = "$version"
//            |}
//            """.trimMargin()
//        )
//    }
//}
//
//tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
//    dependsOn(generateBuildConfig)
//}
