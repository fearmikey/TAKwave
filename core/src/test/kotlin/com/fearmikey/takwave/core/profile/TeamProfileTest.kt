// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TeamProfileTest {
    private val full = TeamProfile(
        name = "Blue team & friends",
        pliBaseS = 120, pliMinS = 20, pliDistanceM = 100, pliHeadingDeg = 45,
        csmaSlotMs = 100, persistence = 63,
        slotted = true, slotLengthMs = 3000, slotCount = 8,
        slotAssignments = mapOf("N0AAA" to 1, "N0BBB-7" to 2),
        resendOwn = true, repeatHoldMin = 30, repeatMax = 3, repeatGapS = 120, repeatBudget = 6,
        encryption = "OFF", cipher = "AES_GCM", hideCallsign = false, fx25CheckBytes = 16,
    )

    private fun refused(text: String): String {
        try {
            TeamProfile.parse(text)
        } catch (e: IllegalArgumentException) {
            return e.message!!
        }
        fail("accepted: $text")
        error("unreachable")
    }

    @Test
    fun roundTripsThroughALink() {
        val uri = full.toUri()
        assertTrue(uri.startsWith("takwave://profile?v=1&"))
        assertTrue("fits a QR code comfortably", uri.length < 400)
        assertEquals(full, TeamProfile.parse(uri))
    }

    @Test
    fun aLinkInsideAMessageIsFound() {
        val text = "Load this before Saturday:\n  ${full.toUri()}  \nthanks"
        assertEquals(full, TeamProfile.parse(text))
    }

    @Test
    fun missingFieldsLeaveSettingsAlone() {
        val p = TeamProfile.parse("takwave://profile?v=1&pli_base=90")
        assertEquals(90, p.pliBaseS)
        assertNull(p.slotted)
        assertNull(p.encryption)
        assertTrue(p.slotAssignments.isEmpty())
    }

    @Test
    fun slotLookupTriesTheSsidThenTheCallsign() {
        assertEquals(1, full.slotFor("N0AAA-3"))
        assertEquals(2, full.slotFor("n0bbb-7"))
        assertNull(full.slotFor("N0BBB"))
        assertNull(full.slotFor("N0ZZZ"))
    }

    @Test
    fun assignmentsTypedByAPerson() {
        val m = TeamProfile.parseAssignments("n0aaa = 1\nN0BBB-2:3, K1XYZ=8", 8)
        assertEquals(mapOf("N0AAA" to 1, "N0BBB-2" to 3, "K1XYZ" to 8), m)
        assertTrue(refused0 { TeamProfile.parseAssignments("N0AAA=9", 8) }.contains("1 to 8"))
        assertTrue(refused0 { TeamProfile.parseAssignments("N0AAA=1, n0aaa=2", 8) }.contains("twice"))
        assertTrue(refused0 { TeamProfile.parseAssignments("NOT A CALL=1", 8) }.contains("callsign"))
        assertTrue(refused0 { TeamProfile.parseAssignments("N0AAA", 8) }.contains("N0CALL=1"))
    }

    private fun refused0(block: () -> Unit): String {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return e.message!!
        }
        fail("accepted")
        error("unreachable")
    }

    @Test
    fun badInputIsRefusedAsAWhole() {
        assertTrue(refused("https://example.com/?v=1").contains("not a TAKwave profile"))
        assertTrue(refused("takwave://profile?pli_base=60").contains("no version"))
        assertTrue(refused("takwave://profile?v=2&pli_base=60").contains("newer"))
        assertTrue(refused("takwave://profile?v=1&pli_base=5").contains("10 to 3600"))
        assertTrue(refused("takwave://profile?v=1&pli_base=sixty").contains("not a number"))
        assertTrue(refused("takwave://profile?v=1&slots=yes").contains("0 or 1"))
        assertTrue(refused("takwave://profile?v=1&fx25=20").contains("16, 32 or 64"))
        assertTrue(refused("takwave://profile?v=1&enc=off;rm").contains("setting name"))
        assertTrue(refused("takwave://profile?v=1&slot_count=4&assign=N0AAA:5").contains("1 to 4"))
        assertTrue(refused("takwave://profile?v=1&name=%ZZ").contains("garbled"))
        assertTrue(refused("takwave://profile?v=1&name=" + "x".repeat(TeamProfile.MAX_LENGTH)).contains("too long"))
    }

    @Test
    fun theFirstValueOfARepeatedKeyWins() {
        assertEquals(60, TeamProfile.parse("takwave://profile?v=1&pli_base=60&pli_base=3600").pliBaseS)
    }

    @Test
    fun namesAreCleanedForDisplay() {
        val p = TeamProfile.parse("takwave://profile?v=1&name=" + "Team%0A%1BRed" + "x".repeat(60))
        assertEquals(TeamProfile.MAX_NAME, p.name.length)
        assertTrue(p.name.startsWith("TeamRed"))
    }
}
