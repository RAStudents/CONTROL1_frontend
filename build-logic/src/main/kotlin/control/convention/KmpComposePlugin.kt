package control.convention

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class KmpComposePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("control.convention.kmp.library")
                apply("org.jetbrains.compose")
                apply("org.jetbrains.kotlin.plugin.compose")
            }

            extensions.configure<KotlinMultiplatformExtension> {
                @OptIn(ExperimentalWasmDsl::class)
                wasmJs {
                    browser {
                        testTask {
                            useKarma {
                                useChromiumHeadless()
                            }
                        }
                    }
                    binaries.executable()
                }
                sourceSets.named("commonMain").configure {
                    dependencies {
                        implementation(libs.findLibrary("compose-runtime").get())
                        implementation(libs.findLibrary("compose-foundation").get())
                        implementation(libs.findLibrary("compose-material3").get())
                        implementation(libs.findLibrary("compose-ui").get())
                        implementation(libs.findLibrary("compose-components-resources").get())
                        implementation(libs.findLibrary("compose-uiToolingPreview").get())
                    }
                }
            }
        }
    }
}
