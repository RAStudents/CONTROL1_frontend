plugins {
    id("control.convention.kmp.compose")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android {
        namespace = "studio.acmenra.control.feature.auth"

        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:network"))
            implementation(project(":core:ui"))

            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

compose.resources {
    packageOfResClass = "studio.acmenra.control.feature.auth.generated.resources"
}
