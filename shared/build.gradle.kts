plugins {
    id("control.convention.kmp.compose")
}

kotlin {
    android {
        namespace = "studio.acmenra.control.shared"

        androidResources {
            enable = true
        }
        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
    }
}
