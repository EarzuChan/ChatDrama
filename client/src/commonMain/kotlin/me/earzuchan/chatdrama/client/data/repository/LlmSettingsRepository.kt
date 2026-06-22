package me.earzuchan.chatdrama.client.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.earzuchan.chatdrama.client.data.model.LlmSettings
import me.earzuchan.chatdrama.client.data.model.withProvider
import me.earzuchan.chatdrama.framework.llm.LlmProvider

class LlmSettingsRepository(private val dataStore: DataStore<Preferences>) {

    // 使用扩展函数，暴露出干净的 Flow
    val settings: Flow<LlmSettings> = dataStore.data.map { it.toLlmSettings() }

    // 外部调用的 API 保持不变，非常语义化
    suspend fun setProvider(provider: LlmProvider) = update { it.withProvider(provider) }
    suspend fun setEndpoint(endpoint: String) = update { it.copy(endpoint = endpoint) }
    suspend fun setModel(model: String) = update { it.copy(model = model) }
    suspend fun setApiKey(apiKey: String) = update { it.copy(apiKey = apiKey) }
    suspend fun setPreferReasoning(preferReasoning: Boolean) = update { it.copy(preferReasoning = preferReasoning) }

    // 核心优化的 update 方法
    private suspend fun update(transform: (LlmSettings) -> LlmSettings) {
        dataStore.edit { preferences ->
            // 利用扩展函数直接拿到当前对象，应用变换
            val next = transform(preferences.toLlmSettings())

            // 序列化回 Preferences
            preferences[Keys.Provider] = next.provider.name
            preferences[Keys.Endpoint] = next.endpoint
            preferences[Keys.Model] = next.model
            preferences[Keys.ApiKey] = next.apiKey
            preferences[Keys.PreferReasoning] = next.preferReasoning
        }
    }

    // 将映射逻辑抽离成 Preferences 的扩展函数，实现高内聚
    private fun Preferences.toLlmSettings(): LlmSettings {
        val savedProvider = this[Keys.Provider]
        val provider = LlmProvider.entries.firstOrNull { it.name == savedProvider } ?: LlmProvider.OpenAiResponses

        return LlmSettings(provider, this[Keys.Endpoint].orEmpty(), this[Keys.Model].orEmpty(), this[Keys.ApiKey].orEmpty(), this[Keys.PreferReasoning] ?: false).let {
            if (it.model.isBlank()) it.withProvider(provider) else it
        }
    }

    private object Keys {
        val Provider = stringPreferencesKey("llm_provider")
        val Endpoint = stringPreferencesKey("llm_endpoint")
        val Model = stringPreferencesKey("llm_model")
        val ApiKey = stringPreferencesKey("llm_api_key")
        val PreferReasoning = booleanPreferencesKey("llm_prefer_reasoning")
    }
}