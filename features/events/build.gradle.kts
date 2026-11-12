plugins {
    id("control.convention.kmp.compose")
}

kotlin {
    android {
        namespace = "studio.acmenra.control.feature.events"
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:network"))

            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
        }
    }
}
