package me.earzuchan.chatdrama.client.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import me.earzuchan.chatdrama.client.viewmodel.ChatListPageViewModel
import me.earzuchan.chatdrama.client.viewmodel.ChatScreenViewModel
import me.earzuchan.chatdrama.client.viewmodel.RootViewModel
import me.earzuchan.chatdrama.client.viewmodel.MainScreenViewModel
import me.earzuchan.chatdrama.client.viewmodel.MyPageViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val clientModules = module {
    single { HttpClient(Js) }

    viewModel { RootViewModel() }
    viewModel { MainScreenViewModel() }
    viewModel { ChatListPageViewModel() }
    viewModel { MyPageViewModel() }
    viewModel { parameters -> ChatScreenViewModel(parameters.get()) }
}
