import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "control.buildlogic"

// Настраиваем компилятор на генерацию байткода Java 17 силами текущего JDK (21)
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.compose.compiler.gradlePlugin)
    compileOnly(libs.kotlin.serialization.gradlePlugin)
}

gradlePlugin {
    plugins {
        create("multiplatformLibrary") {
            id = "control.convention.kmp.library"
            implementationClass = "control.convention.KmpLibraryPlugin"
        }
        create("multiplatformCompose") {
            id = "control.convention.kmp.compose"
            implementationClass = "control.convention.KmpComposePlugin"
        }
        create("androidApplication") {
            id = "control.convention.android.app"
            implementationClass = "control.convention.AndroidAppPlugin"
        }
        create("wasmApplication") {
            id = "control.convention.wasm.app"
            implementationClass = "control.convention.WasmAppPlugin"
        }
    }
}