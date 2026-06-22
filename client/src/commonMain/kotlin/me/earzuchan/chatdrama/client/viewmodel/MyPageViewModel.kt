package me.earzuchan.chatdrama.client.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.earzuchan.chatdrama.client.data.repository.LlmSettingsRepository
import me.earzuchan.chatdrama.client.data.model.LlmSettings
import me.earzuchan.chatdrama.framework.llm.LlmProvider

sealed interface MyPageUiState {
    object Loading : MyPageUiState
    data class Success(val llmSettings: LlmSettings) : MyPageUiState
}

class MyPageViewModel(private val llmSettingsRepository: LlmSettingsRepository) : ViewModel() {
    private val _switchState = MutableStateFlow(false)

    val switchState = _switchState.asStateFlow()

    val uiState: StateFlow<MyPageUiState> = llmSettingsRepository.settings.map { MyPageUiState.Success(llmSettings = it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MyPageUiState.Loading)

    fun setSwitchState(state: Boolean) {
        _switchState.value = state
    }

    fun setLlmProvider(provider: LlmProvider) {
        viewModelScope.launch { llmSettingsRepository.setProvider(provider) }
    }

    fun setLlmEndpoint(endpoint: String) {
        viewModelScope.launch { llmSettingsRepository.setEndpoint(endpoint) }
    }

    fun setLlmModel(model: String) {
        viewModelScope.launch { llmSettingsRepository.setModel(model) }
    }

    fun setLlmApiKey(apiKey: String) {
        viewModelScope.launch { llmSettingsRepository.setApiKey(apiKey) }
    }

    fun setPreferReasoning(preferReasoning: Boolean) {
        viewModelScope.launch { llmSettingsRepository.setPreferReasoning(preferReasoning) }
    }
}
