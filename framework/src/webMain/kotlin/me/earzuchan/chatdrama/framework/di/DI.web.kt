package me.earzuchan.chatdrama.framework.di

import io.ktor.client.*
import io.ktor.client.engine.js.*
import org.koin.dsl.module

actual val frameworkPlatformModule = module {
    single<HttpClient> { HttpClient(Js) }
}