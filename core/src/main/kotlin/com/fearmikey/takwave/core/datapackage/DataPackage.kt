// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.datapackage

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the ATAK "data package" (mission package) that configures ATAK's connection to the
 * TAKwave local server. Importing the zip in ATAK adds a plain-TCP streaming connection.
 *
 * Layout (the same one TAK Server and other tools use for connection packages):
 * ```
 * MANIFEST/manifest.xml   MissionPackageManifest version 2
 * takwave.pref            ATAK preference file with the cot_streams entry
 * ```
 */
object DataPackage {
    const val PREF_ENTRY = "takwave.pref"
    const val MANIFEST_ENTRY = "MANIFEST/manifest.xml"

    fun manifestXml(uid: String): String = """
        |<MissionPackageManifest version="2">
        |  <Configuration>
        |    <Parameter name="uid" value="$uid"/>
        |    <Parameter name="name" value="TAKwave"/>
        |    <Parameter name="onReceiveDelete" value="true"/>
        |  </Configuration>
        |  <Contents>
        |    <Content ignore="false" zipEntry="$PREF_ENTRY"/>
        |  </Contents>
        |</MissionPackageManifest>
        |""".trimMargin()

    fun prefXml(host: String, port: Int): String = """
        |<?xml version='1.0' encoding='ASCII' standalone='yes'?>
        |<preferences>
        |  <preference version="1" name="cot_streams">
        |    <entry key="count" class="class java.lang.Integer">1</entry>
        |    <entry key="description0" class="class java.lang.String">TAKwave</entry>
        |    <entry key="enabled0" class="class java.lang.Boolean">true</entry>
        |    <entry key="connectString0" class="class java.lang.String">$host:$port:tcp</entry>
        |  </preference>
        |</preferences>
        |""".trimMargin()

    /**
     * @param uid a random UUID string identifying this package
     * @param port the TAK server port TAKwave listens on
     * @param host the address the TAK client should connect to; loopback when ATAK runs on the same phone
     */
    fun build(uid: String, port: Int, host: String = "127.0.0.1"): ByteArray {
        require(port in 1..65535) { "invalid port $port" }
        require(uid.matches(Regex("[0-9A-Za-z-]{1,64}"))) { "invalid package uid" }
        require(host.matches(Regex("[0-9A-Za-z.:-]{1,253}"))) { "invalid host" }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(manifestXml(uid).toByteArray(Charsets.US_ASCII))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(PREF_ENTRY))
            zip.write(prefXml(host, port).toByteArray(Charsets.US_ASCII))
            zip.closeEntry()
        }
        return out.toByteArray()
    }
}
