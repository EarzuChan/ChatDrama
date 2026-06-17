package me.earzuchan.chatdrama.client.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MyPageViewModel : ViewModel() {
   private val _switchState = MutableStateFlow(false)

    val switchState = _switchState.asStateFlow()
    fun setSwitchState(state: Boolean) {
        _switchState.value = state
    }
}
