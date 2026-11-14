import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    jvmToolchain(17)
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
