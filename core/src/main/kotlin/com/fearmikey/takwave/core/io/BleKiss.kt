// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.io

/**
 * The Bluetooth LE KISS TNC service, as published by Mobilinkd and used by the TNC4, newer TNC3
 * firmware and other BLE TNCs: the app writes the KISS byte stream to [TX_CHARACTERISTIC] and
 * receives the TNC's KISS byte stream as notifications of [RX_CHARACTERISTIC]. Neither direction
 * keeps frame boundaries, so both sides run KISS framing over the concatenated bytes.
 *
 * The GATT plumbing is Android's and lives in `:app`; what can be tested without a radio is here.
 */
object BleKiss {
    const val SERVICE = "00000001-ba2a-46c9-ae49-01b0961f68bb"
    const val TX_CHARACTERISTIC = "00000002-ba2a-46c9-ae49-01b0961f68bb"
    const val RX_CHARACTERISTIC = "00000003-ba2a-46c9-ae49-01b0961f68bb"

    /** Client Characteristic Configuration descriptor, written to switch notifications on. */
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    /** The ATT MTU every LE link starts with. */
    const val DEFAULT_MTU = 23

    /** What is asked for after connecting: room for a whole TAKwave frame in a few writes. */
    const val REQUESTED_MTU = 247

    /** Largest attribute value a write may carry; ATT caps it at 512 whatever the MTU. */
    private const val MAX_ATTRIBUTE = 512

    /** Bytes one characteristic write can carry over a link with ATT MTU [mtu] (3 go to the ATT header). */
    fun payloadSize(mtu: Int): Int = (mtu - 3).coerceIn(DEFAULT_MTU - 3, MAX_ATTRIBUTE)

    /** Splits [data] into pieces of at most [max] bytes, in order, for one write each. */
    fun split(data: ByteArray, max: Int): List<ByteArray> {
        require(max > 0) { "max must be positive" }
        if (data.isEmpty()) return emptyList()
        return (data.indices step max).map { data.copyOfRange(it, minOf(it + max, data.size)) }
    }
}
