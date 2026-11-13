plugins {
    id("control.convention.kmp.compose")
}

kotlin {
    android {
        namespace = "studio.acmenra.control.core.ui"
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.material3)
            implementation(libs.kotlinx.coroutines.core)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
        }
        androidMain.dependencies {
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.android)
        }
    }
}

compose.resources {
    packageOfResClass = "studio.acmenra.control.core.ui.generated.resources"
}
