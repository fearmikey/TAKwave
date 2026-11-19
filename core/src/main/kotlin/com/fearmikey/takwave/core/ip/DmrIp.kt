// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.core.ip

/**
 * Makes of DMR radio whose USB cable presents a network adapter that carries data calls, with
 * the starting values for their networks. Everything here can be changed in the radios'
 * programming software, so these only seed the settings. The Hytera values are unverified.
 */
enum class DmrVendor(
    val caiNetwork: Int,
    val caiGroupNetwork: Int,
    val radioAddress: String,
    val localAddress: String,
) {
    MOTOTRBO(DmrIp.DEFAULT_CAI_NETWORK, DmrIp.DEFAULT_CAI_GROUP_NETWORK, DmrIp.DEFAULT_RADIO_ADDRESS, DmrIp.DEFAULT_LOCAL_ADDRESS),

    /** Hytera radios also map radio IDs into a class A network, 10 by default. */
    HYTERA(10, DmrIp.DEFAULT_CAI_GROUP_NETWORK, DmrIp.DEFAULT_RADIO_ADDRESS, DmrIp.DEFAULT_LOCAL_ADDRESS),
}

/**
 * How MOTOTRBO (DMR) radios map DMR IDs onto IPv4 addresses for their data service. Hytera radios
 * use the same scheme with their own network numbers (see [DmrVendor]).
 *
 * Every radio has a 24-bit DMR radio ID, and its air-interface address is the CAI network (first
 * octet, 12 by default) followed by that ID: radio 1234 is 12.0.4.210. A talkgroup is reached
 * the same way through the CAI group network (225 by default): talkgroup 1 is 225.0.0.1. A
 * computer behind the radio sends a UDP datagram to such an address, and the radio carries it as
 * an individual or group data call. Both networks can be changed in the radio's programming
 * software (Radio Wide > Network), so they are parameters here.
 *
 * All addresses are IPv4 addresses as Ints (see [Ipv4]).
 */
object DmrIp {
    /** Default first octet of radio air-interface addresses. */
    const val DEFAULT_CAI_NETWORK = 12

    /** Default first octet of talkgroup addresses. */
    const val DEFAULT_CAI_GROUP_NETWORK = 225

    /** Highest radio or talkgroup ID MOTOTRBO accepts; the IDs above it are reserved. */
    const val MAX_ID = 16_776_415

    /** The radio's own address on the USB link, as shipped. */
    const val DEFAULT_RADIO_ADDRESS = "192.168.10.1"

    /** The address the radio's DHCP server hands the computer, as shipped. */
    const val DEFAULT_LOCAL_ADDRESS = "192.168.10.2"

    fun validId(id: Int): Boolean = id in 1..MAX_ID

    /** A unicast first octet: not 0, not loopback, not multicast or above. */
    fun validCaiNetwork(network: Int): Boolean = network in 1..223 && network != 127

    /** A multicast first octet. */
    fun validCaiGroupNetwork(network: Int): Boolean = network in 224..239

    /** Air-interface address of radio [radioId]. */
    fun individual(radioId: Int, caiNetwork: Int = DEFAULT_CAI_NETWORK): Int {
        require(validId(radioId)) { "radio ID out of range" }
        require(validCaiNetwork(caiNetwork)) { "CAI network out of range" }
        return (caiNetwork shl 24) or radioId
    }

    /** Address of talkgroup [talkgroup] (a group data call). */
    fun group(talkgroup: Int, caiGroupNetwork: Int = DEFAULT_CAI_GROUP_NETWORK): Int {
        require(validId(talkgroup)) { "talkgroup ID out of range" }
        require(validCaiGroupNetwork(caiGroupNetwork)) { "CAI group network out of range" }
        return (caiGroupNetwork shl 24) or talkgroup
    }

    /** The radio or talkgroup ID inside a DMR address. */
    fun idOf(address: Int): Int = address and 0xFFFFFF

    /**
     * Parses radio IDs separated by commas, semicolons or spaces. Blank text is an empty list;
     * null when any entry is not a valid ID. Duplicates are removed.
     */
    fun parseIds(text: String): List<Int>? {
        val items = text.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        return items.map { item ->
            if (item.length > 8 || !item.all { it in '0'..'9' }) return null
            item.toInt().takeIf { validId(it) } ?: return null
        }.distinct()
    }

    /**
     * Where each frame goes: the talkgroup (unless [talkgroup] is 0) and then every radio in
     * [radioIds]. Null when nothing is left or an ID or network is out of range.
     */
    fun destinations(
        talkgroup: Int,
        radioIds: List<Int>,
        caiNetwork: Int = DEFAULT_CAI_NETWORK,
        caiGroupNetwork: Int = DEFAULT_CAI_GROUP_NETWORK,
    ): List<Int>? {
        if (!validCaiNetwork(caiNetwork) || !validCaiGroupNetwork(caiGroupNetwork)) return null
        if (talkgroup != 0 && !validId(talkgroup)) return null
        if (radioIds.any { !validId(it) }) return null
        val list = buildList {
            if (talkgroup != 0) add(group(talkgroup, caiGroupNetwork))
            radioIds.distinct().forEach { add(individual(it, caiNetwork)) }
        }
        return list.ifEmpty { null }
    }
}
