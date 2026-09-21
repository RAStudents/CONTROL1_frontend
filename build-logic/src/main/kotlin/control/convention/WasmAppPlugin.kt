package control.convention

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class WasmAppPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("org.jetbrains.kotlin.multiplatform")
                apply("org.jetbrains.compose")
                apply("org.jetbrains.kotlin.plugin.compose")
            }

            extensions.configure<KotlinMultiplatformExtension> {
                @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
                wasmJs {
                    browser {
                        commonWebpackConfig {
                            outputFileName = "control-web.js"
                        }
                    }
                    binaries.executable()
                }
            }
        }
    }
}