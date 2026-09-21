package control.convention

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class KmpLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("org.jetbrains.kotlin.multiplatform")
                apply("com.android.kotlin.multiplatform.library")
                apply("org.jetbrains.kotlin.plugin.serialization")
            }

            extensions.configure<KotlinMultiplatformExtension> {
                @OptIn(ExperimentalWasmDsl::class)
                wasmJs {
                    browser()
                }

                js {
                    browser()
                }
            }
        }
    }
}