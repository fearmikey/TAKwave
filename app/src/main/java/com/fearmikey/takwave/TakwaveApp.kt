// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave

import android.app.Application
import android.content.Context
import com.fearmikey.takwave.data.SettingsStore
import com.fearmikey.takwave.engine.PacketLog
import com.fearmikey.takwave.engine.RadioEngine
import com.fearmikey.takwave.engine.RadioStatus
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Manual dependency wiring (no DI framework). One instance lives for the life of the process and
 * is shared by the activity and the foreground service.
 */
class AppContainer(context: Context) {
    val settingsStore = SettingsStore(context)
    val status = MutableStateFlow(RadioStatus())
    val log = PacketLog()

    /** The engine owned by the running service, or null when the service is stopped. */
    @Volatile
    var engine: RadioEngine? = null
}

class TakwaveApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as TakwaveApp).container
