package com.dskapp

import android.app.Application
import com.dskapp.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DskApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // First launch: give the history one thread so the drawer is never empty.
        appScope.launch {
            runCatching { ServiceLocator.chats(this@DskApp).ensureWelcomeConversation() }
        }
    }
}

