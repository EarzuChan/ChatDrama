package me.earzuchan.chatdrama.client.di

import me.earzuchan.chatdrama.client.data.repository.AiChatRepository
import me.earzuchan.chatdrama.client.data.repository.LlmSettingsRepository
import me.earzuchan.chatdrama.client.data.store.TempFakeChatStore
import me.earzuchan.chatdrama.client.viewmodel.*
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single
import org.koin.plugin.module.dsl.viewModel

val clientModule = module {
    // Ktor is being provided by the Framework

    single<TempFakeChatStore>()
    single<LlmSettingsRepository>()
    single<AiChatRepository>()

    viewModel<RootViewModel>()
    viewModel<MainScreenViewModel>()
    viewModel<ChatListPageViewModel>()
    viewModel<MyPageViewModel>()
    viewModel<ChatScreenViewModel>()
    viewModel<TestAiChatViewModel>()
}

expect val clientPlatformModule : Module
