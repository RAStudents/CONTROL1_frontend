package control.convention

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jlleitschuh.gradle.ktlint.KtlintExtension

class AndroidAppPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            with(pluginManager) {
                apply("com.android.application")
                apply("org.jetbrains.kotlin.plugin.compose")
                apply("org.jlleitschuh.gradle.ktlint")
            }

            configureKtlint()

            extensions.configure<ApplicationExtension> {
                namespace = "studio.acmenra.control"
                compileSdk = libs.findVersion("android-compileSdk").get().requiredVersion.toInt()

                defaultConfig {
                    applicationId = "studio.acmenra.control"
                    minSdk = libs.findVersion("android-minSdk").get().requiredVersion.toInt()
                    targetSdk = libs.findVersion("android-targetSdk").get().requiredVersion.toInt()
                    versionCode = 1
                    versionName = "1.0"
                }

                packaging {
                    resources {
                        excludes += "/META-INF/{AL2.0,LGPL2.1}"
                    }
                }

                buildTypes {
                    getByName("release") {
                        isMinifyEnabled = false
                        proguardFiles(
                            getDefaultProguardFile("proguard-android-optimize.txt"),
                            "proguard-rules.pro"
                        )
                    }
                }

                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }

                buildFeatures {
                    compose = true
                }
            }
        }
    }
}
