package com.dskapp.di

import android.content.Context
import com.dskapp.data.ai.AiClient
import com.dskapp.data.ai.OpenAiCompatibleClient
import com.dskapp.data.chat.ChatRepository
import com.dskapp.data.commands.CommandRepository
import com.dskapp.data.link.LinkReader
import com.dskapp.data.settings.SettingsRepository
import okhttp3.OkHttpClient

/**
 * Minimal manual DI.
 *
 * One place decides *how* AI calls are made. Today [OpenAiCompatibleClient] talks directly
 * to the OpenAI-compatible endpoint from Settings (River AI by default). Adding a relay
 * server later means implementing [AiClient] and swapping the binding here; no ViewModel or
 * UI code changes.
 */
object ServiceLocator {

    @Volatile
    private var settingsRepository: SettingsRepository? = null

    @Volatile
    private var chatRepository: ChatRepository? = null

    @Volatile
    private var aiClient: AiClient? = null

    @Volatile
    private var commandRepository: CommandRepository? = null

    /** One connection pool for the AI server and web links alike. */
    private val httpClient: OkHttpClient by lazy { OpenAiCompatibleClient.defaultHttpClient() }

    private val linkReader: LinkReader by lazy { LinkReader(httpClient) }

    fun links(): LinkReader = linkReader

    fun commands(context: Context): CommandRepository =
        commandRepository ?: synchronized(this) {
            commandRepository ?: CommandRepository(context.applicationContext).also {
                commandRepository = it
            }
        }

    fun settings(context: Context): SettingsRepository =
        settingsRepository ?: synchronized(this) {
            settingsRepository ?: SettingsRepository(context.applicationContext).also {
                settingsRepository = it
            }
        }

    fun chats(context: Context): ChatRepository =
        chatRepository ?: synchronized(this) {
            chatRepository ?: ChatRepository.create(context.applicationContext).also {
                chatRepository = it
            }
        }

    /**
     * Config is read through [SettingsRepository.providerConfig] on every request, so
     * changing the URL/key/model in Settings applies immediately.
     */
    fun aiClient(context: Context): AiClient =
        aiClient ?: synchronized(this) {
            aiClient ?: run {
                val settings = settings(context)
                OpenAiCompatibleClient(
                    client = httpClient,
                    configProvider = { settings.providerConfig() },
                    usageRecorder = settings::recordUsage
                ).also { aiClient = it }
            }
        }
}

