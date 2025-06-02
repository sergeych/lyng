plugins {
    kotlin("multiplatform") version "2.1.21"
}

group = "net.sergeych"
version = "unspecified"

repositories {
    mavenCentral()
}

kotlin {
    jvm()
    linuxX64 {
        binaries {
            executable()
        }
    }
    sourceSets {
        val commonMain by getting {
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            }
        }
        val linuxX64Main by getting {

        }
    }
}