plugins {
    id("control.convention.android.app")
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)

    implementation(project.dependencies.platform(libs.koin.bom))
    implementation(libs.koin.android)
}
