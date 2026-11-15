// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.data

import android.content.Context
import androidx.core.util.AtomicFile
import java.io.File
import java.io.IOException

/**
 * Store-and-forward state (held messages and the list of messages already seen) kept between
 * runs, so a restart neither loses messages waiting for a teammate nor shows repeats twice.
 *
 * Lives in app-private no-backup storage. Held messages are stored decoded, including on an
 * encrypting station (a send-only ECIES station could not read them back otherwise); they are as
 * private as the key files next to them. Written atomically, so a crash mid-write leaves the
 * previous copy.
 */
class StateFile(context: Context) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "store_forward.bin"))

    /** The saved state, or null when there is none or it cannot be read. Call off the main thread. */
    fun read(): ByteArray? = try {
        file.readFully().takeIf { it.size <= MAX_BYTES }
    } catch (_: IOException) {
        null
    }

    /** Replaces the saved state. Call off the main thread. */
    fun write(bytes: ByteArray) {
        val out = try {
            file.startWrite()
        } catch (_: IOException) {
            return
        }
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (_: IOException) {
            file.failWrite(out)
        }
    }

    private companion object {
        /** Far above what the store can hold; anything larger is not ours. */
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}
