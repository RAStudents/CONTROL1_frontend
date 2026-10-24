import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.BOOLEAN
import com.codingfeline.buildkonfig.compiler.FieldSpec.Type.STRING
import java.util.Properties

plugins {
    id("control.convention.kmp.library")
    alias(libs.plugins.buildkonfig)
}

kotlin {
    android {
        namespace = "studio.acmenra.control.core.network"

        withHostTest {
            isIncludeAndroidResources = true
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
        buildConfigField(STRING, "BASE_URL", localProperties.getProperty("BASE_URL"))
        buildConfigField(BOOLEAN, "ENABLE_LOGS", localProperties.getProperty("ENABLE_LOGS") ?: "false")
        buildConfigField(BOOLEAN, "DEBUG_MODE", localProperties.getProperty("DEBUG_MODE") ?: "false")
    }
}
