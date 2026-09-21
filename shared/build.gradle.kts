import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("control.convention.kmp.compose")
}

kotlin {
    android {
        namespace = "studio.acmenra.control.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }

        androidMain.dependencies {
            implementation(libs.compose.uiTooling)
        }

        jsMain.dependencies {
            implementation(libs.wrappers.browser)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}