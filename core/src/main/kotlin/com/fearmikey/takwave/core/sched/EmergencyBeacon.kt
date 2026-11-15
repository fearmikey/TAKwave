// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.sched

import com.fearmikey.takwave.core.codec.EmergencyMessage

data class EmergencyConfig(
    /** An active alert is sent again this often, carrying the latest position. */
    val repeatMs: Long = 30_000L,
    /** A cancellation is sent this many times, [repeatMs] apart, so that a station that missed one still clears the alert. */
    val cancelCount: Int = 3,
    /** An alert that the TAK client has not reported for this long is no longer sent (the client is gone). */
    val forgetMs: Long = 30 * 60_000L,
)

/**
 * Repeats this station's own emergency alerts. A TAK client raises an alert once; on a radio
 * channel one transmission can be lost, so an active alert goes out at once and then every
 * [EmergencyConfig.repeatMs] with the latest position until the client cancels it. The cancel
 * goes out at once and then a few more times.
 *
 * This only ever sends what the local TAK client reported; alerts heard on RF are passed on, if
 * at all, by [RepeatStore] on relay stations. The caller serialises access (the Gateway lock).
 */
class EmergencyBeacon(private val config: EmergencyConfig = EmergencyConfig()) {
    private class Entry(var latest: EmergencyMessage, var updatedAtMs: Long, var dueAtMs: Long, var cancelsLeft: Int)

    private val entries = LinkedHashMap<String, Entry>()

    /** The local TAK client reported [message] (an alert, a refresh of one, or a cancel). */
    fun update(message: EmergencyMessage, nowMs: Long) {
        val e = entries[message.uid]
        val cancels = if (message.active) 0 else config.cancelCount
        when {
            e == null -> entries[message.uid] = Entry(message, nowMs, nowMs, cancels)
            // A new alert, a different kind of alert, or the cancel: send it now.
            e.latest.kind != message.kind -> {
                e.latest = message
                e.updatedAtMs = nowMs
                e.dueAtMs = nowMs
                e.cancelsLeft = cancels
            }
            // The same alert reported again; the next repeat carries any new position.
            else -> {
                e.latest = message
                e.updatedAtMs = nowMs
            }
        }
    }

    /** Returns the alerts and cancels to transmit now, and schedules their next repeat. */
    fun due(nowMs: Long): List<EmergencyMessage> {
        entries.values.removeIf { nowMs - it.updatedAtMs > config.forgetMs }
        var out: ArrayList<EmergencyMessage>? = null
        for (e in entries.values) {
            if (nowMs < e.dueAtMs) continue
            if (!e.latest.active) {
                // Cancelled entries stay (until forgotten) so a client repeating its cancel does not restart the count.
                if (e.cancelsLeft <= 0) continue
                e.cancelsLeft--
            }
            e.dueAtMs = nowMs + config.repeatMs
            if (out == null) out = ArrayList()
            out.add(e.latest)
        }
        return out ?: emptyList()
    }

    /** The alerts this station is currently sending. */
    fun active(): List<EmergencyMessage> = entries.values.filter { it.latest.active }.map { it.latest }
}
