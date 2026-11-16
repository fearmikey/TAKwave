// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.engine

import com.fearmikey.takwave.core.codec.EmergencyMessage
import com.fearmikey.takwave.core.gateway.HeardAlert
import com.fearmikey.takwave.core.gateway.HeardStation
import com.fearmikey.takwave.core.io.RfState
import com.fearmikey.takwave.core.stats.ChannelHealth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Snapshot of the running gateway for the UI and the notification. */
data class RadioStatus(
    val running: Boolean = false,
    val clients: Int = 0,
    val rf: RfState = RfState.IDLE,
    /** Input RMS and peak, 0.0-1.0 of full scale. */
    val level: Float = 0f,
    val peak: Float = 0f,
    val queueDepth: Int = 0,
    /** Chat messages and markers held for store and forward. */
    val heldCount: Int = 0,
    /** Store-and-forward mode in one line, or empty when it is off. */
    val repeatInfo: String = "",
    val heard: List<HeardStation> = emptyList(),
    val localPosition: Pair<Double, Double>? = null,
    /** True when the soft modem is in use, so calibration tones are available. */
    val softModem: Boolean = false,
    val txTimeoutS: Int = 10,
    /** One line describing the radio path, for example "Soft modem, PTT: VOX". */
    val backendInfo: String = "",
    /** The active payload cipher, or empty when sending in the clear. */
    val cipherInfo: String = "",
    /** Time-slot assignment and time source, or empty when slots are off. */
    val slotInfo: String = "",
    /** Problems the operator must fix before the gateway can transmit. */
    val warnings: List<String> = emptyList(),
    /** Emergency alerts raised by a local TAK client that this station is sending. */
    val ownAlerts: List<EmergencyMessage> = emptyList(),
    /** Emergency alerts heard on the air and not canceled. */
    val heardAlerts: List<HeardAlert> = emptyList(),
    /** Channel statistics over the last 10 minutes; null while stopped. */
    val health: ChannelHealth? = null,
)

enum class LogKind { RX, TX, INFO, ERROR }

data class LogEntry(
    val id: Long,
    val timeMs: Long,
    val kind: LogKind,
    val summary: String,
    val hex: String? = null,
    val size: Int = 0,
) {
    fun format(): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(timeMs))
        val base = "$time ${kind.name.padEnd(5)} $summary"
        return if (hex == null) base else "$base\n        [$size bytes] $hex"
    }
}

/** Bounded in-memory packet and event log. Thread-safe. */
class PacketLog(private val capacity: Int = 500) {
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()
    private var nextId = 0L

    @Synchronized
    fun add(kind: LogKind, summary: String, hex: String? = null, size: Int = 0) {
        val entry = LogEntry(nextId++, System.currentTimeMillis(), kind, summary, hex, size)
        val current = _entries.value
        _entries.value = if (current.size >= capacity) current.drop(current.size - capacity + 1) + entry else current + entry
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
    }

    fun asText(): String = _entries.value.joinToString("\n") { it.format() }
}
