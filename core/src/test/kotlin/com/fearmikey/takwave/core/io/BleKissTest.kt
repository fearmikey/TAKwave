// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.io

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleKissTest {
    @Test
    fun payloadSizeFollowsMtuWithinAttLimits() {
        assertEquals(20, BleKiss.payloadSize(23))
        assertEquals(244, BleKiss.payloadSize(247))
        assertEquals(20, BleKiss.payloadSize(10)) // Never below the default.
        assertEquals(512, BleKiss.payloadSize(1000)) // ATT attribute cap.
    }

    @Test
    fun splitKeepsOrderAndSizes() {
        val data = ByteArray(45) { it.toByte() }
        val parts = BleKiss.split(data, 20)
        assertEquals(listOf(20, 20, 5), parts.map { it.size })
        assertArrayEquals(data, parts.fold(ByteArray(0)) { acc, p -> acc + p })
        assertTrue(BleKiss.split(ByteArray(0), 20).isEmpty())
        assertEquals(1, BleKiss.split(ByteArray(20), 20).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun splitRejectsZeroSize() {
        BleKiss.split(ByteArray(3), 0)
    }

    @Test
    fun uuidsAreTheMobilinkdService() {
        // A typo here would make every BLE TNC look like it lacks the service.
        assertEquals("00000001-ba2a-46c9-ae49-01b0961f68bb", BleKiss.SERVICE)
        assertEquals(BleKiss.SERVICE.substring(8), BleKiss.TX_CHARACTERISTIC.substring(8))
        assertEquals(BleKiss.SERVICE.substring(8), BleKiss.RX_CHARACTERISTIC.substring(8))
        assertTrue(BleKiss.TX_CHARACTERISTIC.startsWith("00000002"))
        assertTrue(BleKiss.RX_CHARACTERISTIC.startsWith("00000003"))
    }
}
