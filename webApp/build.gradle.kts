plugins {
    id("control.convention.wasm.app")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.navigation.compose)
        }
    }
}
