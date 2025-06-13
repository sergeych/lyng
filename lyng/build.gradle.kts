plugins {
    kotlin("multiplatform") version "2.1.21"
}

group = "net.sergeych"
version = "unspecified"

repositories {
    mavenCentral()
    maven("https://maven.universablockchain.com/")
    maven("https://gitea.sergeych.net/api/packages/SergeychWorks/maven")
    mavenLocal()
    maven("https://gitea.sergeych.net/api/packages/SergeychWorks/maven")
}

kotlin {
    jvm {
        binaries {
            executable {
                mainClass.set("net.sergeych.lyng_cli.MainKt")
            }
        }
    }
    linuxX64 {
        binaries {
            executable()
        }
    }
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib-common"))
                implementation(project(":lynglib"))
                implementation(libs.okio)
                implementation(libs.clikt)
                implementation(kotlin("stdlib-common"))
                // optional support for rendering markdown in help messages
//                implementation(libs.clikt.markdown)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.okio.fakefilesystem)
            }
        }
//        val nativeMain by getting {
//            dependencies {
//                implementation(kotlin("stdlib-common"))
//            }
//        }
        val linuxX64Main by getting {

        }
    }
}