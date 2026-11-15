// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.cot

import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.time.Instant

/**
 * Hardened CoT XML parser.
 *
 * Input comes from the network, so: documents with a DTD or entity declarations are refused
 * outright (no XXE, no entity expansion), the size is capped, and any parse error simply yields
 * null. Nothing here throws.
 */
object CotParser {
    const val MAX_EVENT_CHARS = 64 * 1024
    private const val MAX_DEPTH = 16
    private const val MAX_TEXT = 8 * 1024
    private const val MAX_SHORT_TEXT = 256

    fun parse(xml: String): CotEvent? {
        if (xml.length > MAX_EVENT_CHARS) return null
        if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) return null
        return try {
            parseOrThrow(xml)
        } catch (e: Exception) {
            null
        } catch (e: StackOverflowError) {
            null
        }
    }

    private fun parseOrThrow(xml: String): CotEvent? {
        val p = KXmlParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        // FEATURE_PROCESS_DOCDECL stays off (the default): kXML then never reads a DTD, and any
        // entity other than the five predefined ones is a parse error.
        p.setInput(StringReader(xml))

        var uid: String? = null
        var type: String? = null
        var how = ""
        var time = 0L
        var start = 0L
        var stale = 0L
        var lat = Double.NaN
        var lon = Double.NaN
        var hae = CotEvent.UNKNOWN
        var ce = CotEvent.UNKNOWN
        var le = CotEvent.UNKNOWN
        var callsign: String? = null
        var hasTakv = false
        var groupName: String? = null
        var groupRole: String? = null
        var battery: Int? = null
        var speed: Double? = null
        var course: Double? = null
        var remarks: StringBuilder? = null
        var inRemarks = false
        var chatId: String? = null
        var chatroom: String? = null
        var chatSender: String? = null
        var chatMsgId: String? = null
        var chatUid0: String? = null
        var hasChat = false
        var linkUid: String? = null
        var color: Int? = null
        var hasUserIcon = false
        var hasArchive = false
        var hasEmergency = false
        var emergencyType: String? = null
        var emergencyCancel = false
        var emergencyText: StringBuilder? = null
        var inEmergency = false
        var fromRf = false
        var sawEvent = false
        var closed = false
        var inDetail = false

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val depth = p.depth
                    if (depth > MAX_DEPTH) return null
                    val name = p.name
                    when {
                        depth == 1 -> {
                            if (name != "event") return null
                            sawEvent = true
                            uid = p.getAttributeValue(null, "uid")
                            type = p.getAttributeValue(null, "type")
                            how = p.getAttributeValue(null, "how") ?: ""
                            time = parseTime(p.getAttributeValue(null, "time"))
                            start = parseTime(p.getAttributeValue(null, "start"))
                            stale = parseTime(p.getAttributeValue(null, "stale"))
                        }
                        depth == 2 && name == "point" -> {
                            lat = num(p, "lat") ?: Double.NaN
                            lon = num(p, "lon") ?: Double.NaN
                            hae = num(p, "hae") ?: CotEvent.UNKNOWN
                            ce = num(p, "ce") ?: CotEvent.UNKNOWN
                            le = num(p, "le") ?: CotEvent.UNKNOWN
                        }
                        depth == 2 && name == "detail" -> inDetail = true
                        depth == 3 && inDetail -> when (name) {
                            "contact" -> callsign = p.getAttributeValue(null, "callsign") ?: callsign
                            "takv" -> hasTakv = true
                            "__group" -> {
                                groupName = p.getAttributeValue(null, "name")
                                groupRole = p.getAttributeValue(null, "role")
                            }
                            "status" -> battery = p.getAttributeValue(null, "battery")?.toDoubleOrNull()?.toInt()
                            "track" -> {
                                speed = num(p, "speed")
                                course = num(p, "course")
                            }
                            "remarks" -> {
                                inRemarks = true
                                if (remarks == null) remarks = StringBuilder()
                            }
                            "__chat" -> {
                                hasChat = true
                                chatId = p.getAttributeValue(null, "id")
                                chatroom = p.getAttributeValue(null, "chatroom")
                                chatSender = p.getAttributeValue(null, "senderCallsign")
                                chatMsgId = p.getAttributeValue(null, "messageId")
                            }
                            "link" -> if (linkUid == null) linkUid = p.getAttributeValue(null, "uid")
                            "color" -> color = p.getAttributeValue(null, "argb")?.toLongOrNull()?.toInt()
                            "usericon" -> hasUserIcon = true
                            "archive" -> hasArchive = true
                            "emergency" -> {
                                hasEmergency = true
                                inEmergency = true
                                emergencyType = p.getAttributeValue(null, "type")
                                emergencyCancel = p.getAttributeValue(null, "cancel").equals("true", ignoreCase = true)
                                if (emergencyText == null) emergencyText = StringBuilder()
                            }
                            "__takwave" -> fromRf = true
                        }
                        depth == 4 && inDetail && name == "chatgrp" -> chatUid0 = p.getAttributeValue(null, "uid0")
                    }
                }
                XmlPullParser.TEXT -> {
                    val sb = remarks
                    if (inRemarks && p.depth == 3 && sb != null && sb.length < MAX_TEXT) {
                        sb.append(p.text, 0, minOf(p.text.length, MAX_TEXT - sb.length))
                    }
                    val eb = emergencyText
                    if (inEmergency && p.depth == 3 && eb != null && eb.length < MAX_SHORT_TEXT) {
                        eb.append(p.text, 0, minOf(p.text.length, MAX_SHORT_TEXT - eb.length))
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (p.depth == 3) {
                        inRemarks = false
                        inEmergency = false
                    }
                    if (p.depth == 2 && p.name == "detail") inDetail = false
                    // Stop at the end of the first event; anything after it is not ours.
                    if (p.depth == 1) {
                        closed = true
                        break
                    }
                }
            }
            ev = p.next()
        }

        if (!sawEvent || !closed) return null
        val u = uid?.takeIf { it.isNotEmpty() } ?: return null
        val t = type?.takeIf { it.isNotEmpty() } ?: return null
        if (lat.isNaN() || lon.isNaN() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
        return CotEvent(
            uid = u, type = t, how = how, timeMs = time, startMs = start, staleMs = stale,
            lat = lat, lon = lon, hae = hae, ce = ce, le = le,
            callsign = callsign, hasTakv = hasTakv, groupName = groupName, groupRole = groupRole,
            battery = battery, speed = speed, course = course,
            remarks = remarks?.toString(),
            chat = if (hasChat) ChatDetail(chatId, chatroom, chatSender, chatMsgId, chatUid0) else null,
            linkUid = linkUid, colorArgb = color, hasUserIcon = hasUserIcon, hasArchive = hasArchive,
            emergency = if (hasEmergency) EmergencyDetail(emergencyType, emergencyCancel, emergencyText?.toString()?.trim()) else null,
            fromRf = fromRf,
        )
    }

    private fun num(p: XmlPullParser, attr: String): Double? =
        p.getAttributeValue(null, attr)?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun parseTime(s: String?): Long {
        if (s.isNullOrEmpty()) return 0
        return try {
            Instant.parse(s).toEpochMilli()
        } catch (e: Exception) {
            0
        }
    }
}
