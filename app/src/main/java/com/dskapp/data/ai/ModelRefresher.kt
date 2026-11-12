package com.dskapp.data.ai

import com.dskapp.data.settings.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the saved model list in step with the provider without the user pressing
 * "모델 목록 불러오기".
 *
 * Providers do not announce changes, so the app re-reads GET /models when the saved list is
 * old: on app start (hours), and when a model picker opens (minutes). Only chat models are
 * kept; the chosen model stays in the list while the server still serves it.
 */
class ModelRefresher(
    private val settings: SettingsRepository,
    private val aiClient: AiClient
) {
    private val mutex = Mutex()

    /**
     * Refreshes the list when it is older than [maxAgeMs]. Returns the default model's name
     * the first time it is found missing from the server list, so the caller can warn once;
     * null otherwise (fresh list, no network, server without a list).
     */
    suspend fun refreshIfStale(maxAgeMs: Long): String? {
        if (!settings.state.value.isConfigured || !settings.modelsStale(maxAgeMs)) return null
        return mutex.withLock {
            // Another caller may have refreshed while this one waited.
            if (!settings.modelsStale(maxAgeMs)) return@withLock null
            val state = settings.state.value
            val listing = aiClient.listModels().getOrNull() ?: return@withLock null
            if (listing.all.isEmpty()) return@withLock null
            // The server changed while the list was loading: that list is for another server.
            if (settings.state.value.baseUrl != state.baseUrl) return@withLock null

            settings.saveKnownModels(keep(listing, state.model), fetchedFrom = state.baseUrl)
            val missing = state.model.isNotBlank() && state.model !in listing.all
            when {
                !missing -> {
                    settings.setMissingModelWarned(null)
                    null
                }
                settings.missingModelWarned() == state.model -> null
                else -> {
                    settings.setMissingModelWarned(state.model)
                    state.model
                }
            }
        }
    }

    companion object {
        const val ON_START_MAX_AGE_MS = 6 * 60 * 60 * 1000L
        const val ON_PICKER_MAX_AGE_MS = 10 * 60 * 1000L

        /** Chat models, plus the chosen model when the server lists it under another kind. */
        fun keep(listing: ModelListing, chosen: String): List<String> =
            if (chosen.isNotBlank() && chosen in listing.all && chosen !in listing.chat) {
                (listing.chat + chosen).sortedWith(String.CASE_INSENSITIVE_ORDER)
            } else {
                listing.chat
            }

        fun missingMessage(model: String): String =
            "기본 모델 '$model'이(가) AI 서버의 모델 목록에서 빠졌습니다. 설정에서 다른 모델을 골라 주세요."
    }
}
