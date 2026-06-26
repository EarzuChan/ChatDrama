package me.earzuchan.chatdrama.framework.di

import io.ktor.client.*
import io.ktor.client.engine.apache5.*
import org.koin.dsl.module

actual val frameworkPlatformModule = module {
    single<HttpClient> { HttpClient(Apache5) {
        engine {
            socketTimeout = 120_000
            connectTimeout = 30_000
            connectionRequestTimeout = 30_000
        }
    } }
}
