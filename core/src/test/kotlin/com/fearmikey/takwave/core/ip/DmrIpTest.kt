// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.ip

import com.fearmikey.takwave.core.io.P25DataConfig
import com.fearmikey.takwave.core.io.P25DataTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DmrIpTest {
    @Test
    fun radioIdsMapIntoTheCaiNetwork() {
        assertEquals("12.0.4.210", Ipv4.format(DmrIp.individual(1234)))
        assertEquals("12.255.252.223", Ipv4.format(DmrIp.individual(DmrIp.MAX_ID)))
        assertEquals("13.0.0.7", Ipv4.format(DmrIp.individual(7, caiNetwork = 13)))
        assertEquals(1234, DmrIp.idOf(DmrIp.individual(1234)))
    }

    @Test
    fun talkgroupsMapIntoTheGroupNetwork() {
        assertEquals("225.0.0.1", Ipv4.format(DmrIp.group(1)))
        assertEquals("225.47.135.11", Ipv4.format(DmrIp.group(3_114_763)))
        assertEquals("230.0.0.9", Ipv4.format(DmrIp.group(9, caiGroupNetwork = 230)))
        assertEquals(1, DmrIp.idOf(DmrIp.group(1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun idZeroIsRefused() {
        DmrIp.individual(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun reservedIdsAreRefused() {
        DmrIp.group(DmrIp.MAX_ID + 1)
    }

    @Test
    fun networksMustBeOfTheRightKind() {
        assertTrue(DmrIp.validCaiNetwork(12))
        assertFalse(DmrIp.validCaiNetwork(0))
        assertFalse(DmrIp.validCaiNetwork(127))
        assertFalse(DmrIp.validCaiNetwork(225))
        assertTrue(DmrIp.validCaiGroupNetwork(225))
        assertFalse(DmrIp.validCaiGroupNetwork(12))
        assertFalse(DmrIp.validCaiGroupNetwork(240))
    }

    @Test
    fun idListsParse() {
        assertEquals(emptyList<Int>(), DmrIp.parseIds("  "))
        assertEquals(listOf(101, 102, 3_114_763), DmrIp.parseIds("101, 102;3114763 101"))
        assertNull(DmrIp.parseIds("101, abc"))
        assertNull(DmrIp.parseIds("0"))
        assertNull(DmrIp.parseIds("99999999"))
        assertNull(DmrIp.parseIds("-5"))
    }

    @Test
    fun destinationsAreTheTalkgroupThenTheRadios() {
        assertEquals(
            listOf(DmrIp.group(1), DmrIp.individual(101), DmrIp.individual(102)),
            DmrIp.destinations(1, listOf(101, 102, 101)),
        )
        assertEquals(listOf(DmrIp.individual(101)), DmrIp.destinations(0, listOf(101)))
        assertNull("nothing to send to", DmrIp.destinations(0, emptyList()))
        assertNull(DmrIp.destinations(1, emptyList(), caiNetwork = 225))
        assertNull(DmrIp.destinations(1, emptyList(), caiGroupNetwork = 12))
        assertNull(DmrIp.destinations(DmrIp.MAX_ID + 1, emptyList()))
    }

    @Test
    fun everyMakeStartsFromUsableNetworks() {
        for (v in DmrVendor.entries) {
            assertTrue("$v", DmrIp.validCaiNetwork(v.caiNetwork))
            assertTrue("$v", DmrIp.validCaiGroupNetwork(v.caiGroupNetwork))
            assertTrue("$v", Ipv4.parse(v.radioAddress) != null && Ipv4.parse(v.localAddress) != null)
        }
        assertEquals("10.0.4.210", Ipv4.format(DmrIp.individual(1234, DmrVendor.HYTERA.caiNetwork)))
    }

    @Test
    fun dmrPacingIsSlowerThanP25() {
        val p25 = P25DataTransport("p25", P25DataConfig(listOf(DmrIp.group(1)))) { error("not connected") }
        val dmr = P25DataTransport("dmr", P25DataConfig.dmr(listOf(DmrIp.group(1)))) { error("not connected") }
        val frame = 120
        assertTrue(dmr.estimateAirtimeMs(frame) > p25.estimateAirtimeMs(frame))
        // 500 ms + (120 + 28) bytes at 1200 bit/s.
        assertEquals(500L + (frame + 28) * 8L * 1000L / 1200L, dmr.estimateAirtimeMs(frame))
    }
}
