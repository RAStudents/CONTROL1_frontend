package control.convention

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

class WasmAppPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("org.jetbrains.kotlin.multiplatform")
                apply("org.jetbrains.compose")
                apply("org.jetbrains.kotlin.plugin.compose")
                apply("org.jlleitschuh.gradle.ktlint")
            }

            extensions.configure<KtlintExtension> {
                filter {
                    exclude { element -> element.file.path.contains("/build/") }
                }
            }

            extensions.configure<KotlinMultiplatformExtension> {
                @OptIn(ExperimentalWasmDsl::class)
                wasmJs {
                    browser {
                        commonWebpackConfig {
                            outputFileName = "control-web.js"
                        }
                    }
                    binaries.executable()
                }

                sourceSets.named("commonMain").configure {
                    dependencies {
                        implementation(libs.findLibrary("compose-runtime").get())
                        implementation(libs.findLibrary("compose-ui").get())
                    }
                }
            }
        }
    }
}
