package com.dskapp

import android.app.Application
import com.dskapp.data.attachment.AttachmentStore
import com.dskapp.data.log.ErrorLog
import com.dskapp.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DskApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // First thing: crashes from here on end up in Settings > 오류 기록.
        ErrorLog.init(this)

        appScope.launch {
            // First launch: give the history one thread so the drawer is never empty.
            runCatching { ServiceLocator.chats(this@DskApp).ensureWelcomeConversation() }
            runCatching { AttachmentStore.cleanCaches(this@DskApp) }
        }
    }
}
