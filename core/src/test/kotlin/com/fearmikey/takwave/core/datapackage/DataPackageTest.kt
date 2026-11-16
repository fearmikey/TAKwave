// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.datapackage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class DataPackageTest {
    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                out[e.name] = zip.readBytes().toString(Charsets.US_ASCII)
            }
        }
        return out
    }

    @Test
    fun packageHasManifestAndConnectionPreference() {
        val files = unzip(DataPackage.build("3f2b6c1e-9a44-4f0e-8a55-0c1d2e3f4a5b", 8099))
        assertEquals(setOf("MANIFEST/manifest.xml", "takwave.pref"), files.keys)

        val manifest = files.getValue("MANIFEST/manifest.xml")
        assertTrue(manifest.contains("<MissionPackageManifest version=\"2\">"))
        assertTrue(manifest.contains("<Parameter name=\"uid\" value=\"3f2b6c1e-9a44-4f0e-8a55-0c1d2e3f4a5b\"/>"))
        assertTrue(manifest.contains("<Parameter name=\"name\" value=\"TAKwave\"/>"))
        assertTrue(manifest.contains("zipEntry=\"takwave.pref\""))

        val pref = files.getValue("takwave.pref")
        assertTrue(pref.contains("<preference version=\"1\" name=\"cot_streams\">"))
        assertTrue(pref.contains("<entry key=\"count\" class=\"class java.lang.Integer\">1</entry>"))
        assertTrue(pref.contains("<entry key=\"description0\" class=\"class java.lang.String\">TAKwave</entry>"))
        assertTrue(pref.contains("<entry key=\"enabled0\" class=\"class java.lang.Boolean\">true</entry>"))
        assertTrue(pref.contains("<entry key=\"connectString0\" class=\"class java.lang.String\">127.0.0.1:8099:tcp</entry>"))
    }

    @Test
    fun portAndHostAreConfigurable() {
        val pref = unzip(DataPackage.build("abc", 4242, "192.168.43.1")).getValue("takwave.pref")
        assertTrue(pref.contains(">192.168.43.1:4242:tcp<"))
    }

    @Test
    fun rejectsValuesThatCouldBreakTheXml() {
        assertThrows(IllegalArgumentException::class.java) { DataPackage.build("abc", 0) }
        assertThrows(IllegalArgumentException::class.java) { DataPackage.build("a\"b", 8099) }
        assertThrows(IllegalArgumentException::class.java) { DataPackage.build("abc", 8099, "evil<host>") }
    }
}
