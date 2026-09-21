plugins {
    id("control.convention.wasm.app")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)

            // Подключаем доступ к document.body
            implementation(libs.wrappers.browser)
        }
    }
}