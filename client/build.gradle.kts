import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

val namespace = "me.earzuchan.chatdrama.client"

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.koinCompiler)
    alias(libs.plugins.room3)
    alias(libs.plugins.ksp)
}

room3 {
    schemaDirectory("$projectDir/schemas")
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
            implementation(projects.framework)
            
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.resources)
            implementation(libs.compose.preview)

            implementation(libs.datastore.core)
            implementation(libs.datastore.preferences.core)

            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.navigation3)
            implementation(libs.koin.compose.viewmodel)

            implementation(libs.kotlinx.serialization.json)

            implementation(libs.lifecycle.viewmodel)
            implementation(libs.lifecycle.viewmodel.compose)

            implementation(libs.navy3.runtime)

            implementation(libs.room3.runtime)

            implementation(libs.miuix.ui)
            implementation(libs.miuix.preference)
            implementation(libs.miuix.icons)
            implementation(libs.miuix.blur)
            implementation(libs.miuix.squircle)
            implementation(libs.miuix.navy3ui)
        }

        webMain.dependencies {
            implementation(libs.navigation3.browser)
            implementation(libs.sqlite.web)
        }

        jsMain.dependencies {
            implementation(libs.wrappers.browser)
        }
    }
}

dependencies {
    add("kspJs", libs.room3.compiler)
    add("kspWasmJs", libs.room3.compiler)
}

compose {
    resources {
        packageOfResClass = "$namespace.resources"
        generateResClass = auto
    }
}
