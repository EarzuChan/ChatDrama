package me.earzuchan.chatdrama.framework.di

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

actual val frameworkPlatformModule = module {
    single<HttpClient> { HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(30, TimeUnit.SECONDS)
                readTimeout(120, TimeUnit.SECONDS)
                writeTimeout(120, TimeUnit.SECONDS)
                callTimeout(120, TimeUnit.SECONDS)
            }
        }
    } }
}
