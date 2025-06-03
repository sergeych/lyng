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
                implementation(project(":library"))
                implementation(libs.okio)

                implementation(libs.clikt)

                // optional support for rendering markdown in help messages
//                implementation(libs.clikt.markdown)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation(libs.okio.fakefilesystem)
            }
        }
        val linuxX64Main by getting {

        }
    }
}