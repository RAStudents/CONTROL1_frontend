import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.BOOLEAN
import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.STRING
import java.util.Properties

plugins {
    id("control.convention.kmp.library")
    alias(libs.plugins.buildkonfig)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android {
        namespace = "studio.acmenra.control.core.network"

        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.auth)
            implementation(libs.ktor.client.contentNegotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.serialization.kotlinxJson)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.multiplatformSettings)
            implementation(libs.multiplatformSettings.noArg)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.multiplatformSettings.test)
        }
    }
}

val localProperties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

buildkonfig {
    packageName = "studio.acmenra.control"
    objectName = "NetworkConfig"

    defaultConfigs {
        buildConfigField(
            STRING,
            "BASE_URL",
            localProperties.getProperty("BASE_URL") ?: System.getenv("BASE_URL") ?: "",
        )
        buildConfigField(
            BOOLEAN,
            "ENABLE_LOGS",
            localProperties.getProperty("ENABLE_LOGS") ?: System.getenv("ENABLE_LOGS") ?: "false",
        )
        buildConfigField(
            BOOLEAN,
            "DEBUG_MODE",
            localProperties.getProperty("DEBUG_MODE") ?: System.getenv("DEBUG_MODE") ?: "false",
        )
        buildConfigField(
            BOOLEAN,
            "ALLOW_INSECURE_TRANSPORT",
            localProperties.getProperty("ALLOW_INSECURE_TRANSPORT")
                ?: System.getenv("ALLOW_INSECURE_TRANSPORT")
                ?: "false",
        )
    }
}
