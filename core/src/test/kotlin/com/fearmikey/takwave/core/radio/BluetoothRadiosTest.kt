// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BluetoothRadiosTest {
    @Test
    fun recognisesKissRadiosWhateverTheSpelling() {
        for (name in listOf("UV-PRO", "UV PRO", "uvpro", "BTECH UV-PRO 1234")) {
            assertEquals(name, BluetoothRadioUse.KISS_CLASSIC, BluetoothRadios.match(name)?.use)
        }
        assertEquals("Kenwood TH-D75", BluetoothRadios.match("TH-D75")?.model)
        assertEquals(BluetoothRadioUse.KISS_CLASSIC, BluetoothRadios.match("VR-N76")?.use)
        assertEquals(BluetoothRadioUse.KISS_CLASSIC, BluetoothRadios.match("GA-5WB")?.use)
    }

    @Test
    fun tnc4IsBleAndOtherMobilinkdIsClassic() {
        assertEquals(BluetoothRadioUse.KISS_BLE, BluetoothRadios.match("TNC4 Mobilinkd")?.use)
        assertEquals(BluetoothRadioUse.KISS_CLASSIC, BluetoothRadios.match("Mobilinkd TNC3")?.use)
    }

    @Test
    fun recognisesIcomDstarRadios() {
        assertEquals("Icom IC-705", BluetoothRadios.match("ICOM BT(IC-705)")?.model)
        assertEquals(BluetoothRadioUse.DSTAR_DATA, BluetoothRadios.match("ICOM BT(ID-52)")?.use)
    }

    @Test
    fun unknownNamesGetNothing() {
        assertNull(BluetoothRadios.match(null))
        assertNull(BluetoothRadios.match(""))
        assertNull(BluetoothRadios.match("Pixel Buds"))
        assertNull(BluetoothRadios.match("HC-05"))
    }
}
