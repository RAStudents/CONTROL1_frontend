import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.BOOLEAN
import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.LONG
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
            api(libs.ktor.client.core)
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

fun findConfigProperty(key: String): String? =
    providers.gradleProperty(key).orNull
        ?: localProperties.getProperty(key)
        ?: System.getenv(key)

buildkonfig {
    packageName = "studio.acmenra.control"
    objectName = "NetworkConfig"

    defaultConfigs {
        buildConfigField(
            STRING,
            "BASE_URL",
            findConfigProperty("BASE_URL") ?: "",
        )
        buildConfigField(
            BOOLEAN,
            "ENABLE_LOGS",
            findConfigProperty("ENABLE_LOGS") ?: "false",
        )
        buildConfigField(
            BOOLEAN,
            "DEBUG_MODE",
            findConfigProperty("DEBUG_MODE") ?: "false",
        )
        buildConfigField(
            BOOLEAN,
            "ALLOW_INSECURE_TRANSPORT",
            findConfigProperty("ALLOW_INSECURE_TRANSPORT") ?: "false",
        )
        buildConfigField(
            LONG,
            "CONNECT_TIMEOUT_MILLIS",
            findConfigProperty("CONNECT_TIMEOUT_MILLIS") ?: "15000L",
        )
        buildConfigField(
            LONG,
            "REQUEST_TIMEOUT_MILLIS",
            findConfigProperty("REQUEST_TIMEOUT_MILLIS") ?: "30000L",
        )
        buildConfigField(
            LONG,
            "SOCKET_TIMEOUT_MILLIS",
            findConfigProperty("SOCKET_TIMEOUT_MILLIS") ?: "30000L",
        )
        buildConfigField(
            STRING,
            "REFRESH_PATH",
            findConfigProperty("REFRESH_PATH") ?: "api/auth/refresh",
        )
        buildConfigField(
            STRING,
            "LOGIN_PATH",
            findConfigProperty("LOGIN_PATH") ?: "api/auth/login",
        )
    }
}
