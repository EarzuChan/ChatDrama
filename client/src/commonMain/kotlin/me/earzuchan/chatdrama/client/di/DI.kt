package me.earzuchan.chatdrama.client.di

import me.earzuchan.chatdrama.client.viewmodel.*
import org.koin.dsl.module
import org.koin.plugin.module.dsl.viewModel

val clientModule = module {
    // single { HttpClient(Js) }

    viewModel<RootViewModel>()
    viewModel<MainScreenViewModel>()
    viewModel<ChatListPageViewModel>()
    viewModel<MyPageViewModel>()
    viewModel<ChatScreenViewModel>()
}
