import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.resources
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

val appId = "me.earzuchan.chatdrama.client"

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    js {
        browser()
        binaries.executable()
    }
    
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }
    
    
    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)

            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)

            implementation(libs.miuix.ui)
            implementation(libs.miuix.preference)

            implementation(libs.miuix.icons)
            implementation(libs.miuix.blur)
        }

        jsMain.dependencies {
            implementation(libs.wrappers.browser)
        }
    }
}

compose {
    resources {
        packageOfResClass = "$appId.resources"
        generateResClass = auto
    }
}
