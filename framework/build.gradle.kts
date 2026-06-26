import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.koinCompiler)
}

koinCompiler {
    compileSafety = false
}

kotlin {
    jvm()

    js { browser() }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            
            implementation(libs.koin.core)

            implementation(libs.ktor.client.core)
        }

        webMain.dependencies {
            implementation(libs.ktor.client.js)
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.apache5)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}