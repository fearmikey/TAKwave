// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave

import android.app.Application
import android.content.Context
import com.fearmikey.takwave.data.KeyFiles
import com.fearmikey.takwave.data.SettingsStore
import com.fearmikey.takwave.data.StateFile
import com.fearmikey.takwave.engine.PacketLog
import com.fearmikey.takwave.engine.RadioEngine
import com.fearmikey.takwave.engine.RadioStatus
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A radio seen on USB. [deviceKey] is "vendorId:productId" as used by the P25 and MOTOTRBO device
 * settings; [link] is how TAKwave can talk to it, if at all; [system] is what its USB product name
 * suggests (MOTOTRBO and APX radios share Motorola's vendor id and both use a USB network link).
 */
data class DetectedRadio(val deviceKey: String, val label: String, val link: Link, val system: System = System.UNKNOWN) {
    enum class System {
        /** P25: APX or ASTRO 25. */
        P25,

        /** DMR: MOTOTRBO. */
        DMR,

        /** The product name does not say; the operator is asked. */
        UNKNOWN,
    }

    companion object {
        /**
         * Motorola's USB product id for MOTOTRBO portables (seen on an XPR 6550, which reports the
         * product name "Motorola Solutions LTD Device", so the name alone does not identify it).
         */
        const val MOTOTRBO_PORTABLE_PRODUCT_ID = 0x1020

        /**
         * Guesses the radio family from its USB product id, then its product name. A serial link is
         * always P25 (MOTOTRBO radios have no USB serial data port). Only the MOTOTRBO portable id has
         * been checked against a real radio (docs/TODO.md).
         */
        fun systemOf(productId: Int, productName: String?, link: Link): System {
            if (link == Link.SERIAL) return System.P25
            if (productId == MOTOTRBO_PORTABLE_PRODUCT_ID) return System.DMR
            val name = productName?.uppercase() ?: return System.UNKNOWN
            return when {
                "TRBO" in name || "XPR" in name || "DMR" in name -> System.DMR
                "APX" in name || "ASTRO" in name || "P25" in name -> System.P25
                else -> System.UNKNOWN
            }
        }
    }

    enum class Link {
        /** A virtual network adapter (RNDIS or CDC-ECM), as on APX portables. */
        USB_NETWORK,

        /** A USB serial port (CDC ACM), carrying PPP or SLIP. */
        SERIAL,

        /** Nothing TAKwave can use. */
        NONE,
    }
}

/**
 * Manual dependency wiring (no DI framework). One instance lives for the life of the process and
 * is shared by the activity and the foreground service.
 */
class AppContainer(context: Context) {
    val settingsStore = SettingsStore(context)
    val keyFiles = KeyFiles(context)
    val status = MutableStateFlow(RadioStatus())
    val log = PacketLog()
    val stateFile = StateFile(context)

    /**
     * Text of a team profile waiting for the operator to review (from a takwave:// link, a file or
     * the clipboard). It is only applied after they confirm it in the dialog.
     */
    val pendingProfile = MutableStateFlow<String?>(null)

    /** A Motorola radio that was just plugged in, waiting for the operator to decide whether to use it. */
    val detectedRadio = MutableStateFlow<DetectedRadio?>(null)

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
