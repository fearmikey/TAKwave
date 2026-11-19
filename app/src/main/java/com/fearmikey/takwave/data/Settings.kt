// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fearmikey.takwave.core.ax25.Ax25Address
import com.fearmikey.takwave.core.gateway.Gateway
import com.fearmikey.takwave.core.io.P25DataConfig
import com.fearmikey.takwave.core.io.SerialDataProfile
import com.fearmikey.takwave.core.io.SerialEncoding
import com.fearmikey.takwave.core.ip.MulticastDelivery
import com.fearmikey.takwave.core.ip.DmrIp
import com.fearmikey.takwave.core.ip.DmrVendor
import com.fearmikey.takwave.core.ip.IpFraming
import com.fearmikey.takwave.core.ip.Ipv4
import com.fearmikey.takwave.core.ip.P25ProbeResult
import com.fearmikey.takwave.core.radio.Band
import com.fearmikey.takwave.core.radio.ChannelWidth
import com.fearmikey.takwave.core.radio.RadioGuide
import com.fearmikey.takwave.core.radio.RadioService
import com.fearmikey.takwave.core.radio.Terrain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class PttMethod(val label: String) {
    SERIAL("Serial (RTS/DTR)"),
    BLUETOOTH("Bluetooth serial (SPP)"),
    VOX("VOX"),
    NONE("None"),
}

enum class PttLine { RTS, DTR }

/** What is sent over the Bluetooth serial link to key and unkey the radio. */
enum class BtPttPreset(val label: String, val key: String, val unkey: String) {
    KENWOOD("Kenwood / Elecraft CAT (TX; and RX;)", "TX;", "RX;"),
    YAESU("Yaesu CAT (TX1; and TX0;)", "TX1;", "TX0;"),
    KENWOOD_HT("Kenwood TH-D74 / TH-D75 (TX and RX, ending in CR)", "TX\\r", "RX\\r"),
    ICOM_IC705(
        "Icom IC-705 CI-V, address A4 (Bluetooth serial port set to CI-V)",
        "\\xFE\\xFE\\xA4\\xE0\\x1C\\x00\\x01\\xFD",
        "\\xFE\\xFE\\xA4\\xE0\\x1C\\x00\\x00\\xFD",
    ),
    CUSTOM("Custom commands", "", ""),
}

/** Which kind of Bluetooth link reaches a KISS TNC. */
enum class BluetoothKissLink(val label: String) {
    AUTO("Automatic (classic serial, else Bluetooth LE)"),
    CLASSIC("Classic Bluetooth serial (SPP)"),
    BLE("Bluetooth LE (BLE KISS service, for example Mobilinkd TNC4)"),
}

enum class ModemBackend(val label: String) {
    SOFT("Soft modem (audio)"),
    KISS_TCP("KISS over TCP"),
    KISS_BLUETOOTH("KISS over Bluetooth (radio with built-in TNC, or Bluetooth TNC)"),
    P25_DATA("P25 radio packet data (USB, serial data cable or Bluetooth adapter)"),
    DMR_DATA("DMR radio data: MOTOTRBO or Hytera (USB cable)"),
    SERIAL_DATA("D-STAR or NXDN radio data (USB or Bluetooth serial)"),
}

/** Radios whose data port passes bytes over the air unchanged, for [ModemBackend.SERIAL_DATA]. */
enum class SerialRadioType(val label: String, val profile: SerialDataProfile) {
    DSTAR_SLOW("Icom D-STAR, DV data (slow data, voice still possible)", SerialDataProfile.DSTAR_SLOW),
    DSTAR_FAST("Icom D-STAR, DV Fast Data (about 3 times faster)", SerialDataProfile.DSTAR_FAST),
    KENWOOD_NXDN("Kenwood NX (NXDN) transparent data", SerialDataProfile.NXDN),
    ICOM_IDAS("Icom IDAS (NXDN) data", SerialDataProfile.NXDN);

    val isDstar: Boolean get() = this == DSTAR_SLOW || this == DSTAR_FAST
}

/** The make's name as shown to the operator. */
val DmrVendor.label: String
    get() = when (this) {
        DmrVendor.MOTOTRBO -> "MOTOTRBO"
        DmrVendor.HYTERA -> "Hytera"
    }

/** How the phone reaches a serial data radio. */
enum class SerialLinkKind(val label: String) {
    USB("USB serial (radio's USB port or a USB-to-RS-232 adapter)"),
    BLUETOOTH("Bluetooth serial (radio's own Bluetooth or a Bluetooth serial adapter)"),
}

enum class EncryptionMode(val label: String) {
    OFF("Off"),
    PASSPHRASE("Pre-shared passphrase"),
    KEY_FILE("Symmetric key file"),
    ECDH_PAIR("Key pair with one peer (ECDH)"),
    ECIES_TEAM("Team public key (ECIES)"),
}

/** Symmetric cipher used with a passphrase, key file or ECDH-derived key. */
enum class CipherChoice(val label: String) {
    AES_GCM("AES-GCM"),
    CHACHA20_POLY1305("ChaCha20-Poly1305"),
}

/** FX.25 forward error correction on transmit. Received FX.25 is always decoded. */
enum class Fx25Mode(val checkBytes: Int, val label: String) {
    OFF(0, "Off (plain AX.25)"),
    CHECK_16(16, "16 check bytes: repairs up to 8 damaged bytes"),
    CHECK_32(32, "32 check bytes: repairs up to 16 damaged bytes"),
    CHECK_64(64, "64 check bytes: repairs up to 32 damaged bytes");

    companion object {
        fun of(checkBytes: Int): Fx25Mode? = entries.firstOrNull { it.checkBytes == checkBytes }
    }
}

enum class TimeSource(val label: String) {
    SYSTEM("Phone clock (network time)"),
    GNSS("GNSS (GPS) time"),
}

/** Every user setting, with the defaults from the build spec. Read once when the service starts. */
data class Settings(
    val callsign: String = "",
    val ssid: Int = 0,
    val takPort: Int = DEFAULT_TAK_PORT,
    val allowLan: Boolean = false,
    val pliBaseS: Int = 60,
    val pliMinS: Int = 15,
    val pliDistanceM: Int = 50,
    val pliHeadingDeg: Int = 30,
    /** Audio device keys as produced by AudioDevices.keyOf(); empty means the system default. */
    val inputDevice: String = "",
    val outputDevice: String = "",
    val pttMethod: PttMethod = PttMethod.VOX,
    val pttLine: PttLine = PttLine.RTS,
    val pttInverted: Boolean = false,
    /** "vendorId:productId" of the USB serial adapter; empty means the first one found. */
    val pttDevice: String = "",
    /** MAC address of the paired Bluetooth serial device used for PTT; empty means none chosen. */
    val btPttDevice: String = "",
    val btPttPreset: BtPttPreset = BtPttPreset.KENWOOD,
    /** Custom key and unkey commands in PttCommand syntax; used when the preset is CUSTOM. */
    val btKeyCommand: String = "",
    val btUnkeyCommand: String = "",
    val txDelayMs: Int = 300,
    val txTailMs: Int = 30,
    /** Output level in percent of full scale. */
    val txLevel: Int = 50,
    val txTimeoutS: Int = 10,
    val slotTimeMs: Int = 100,
    val persistence: Int = 63,
    /** Busy threshold in percent of full-scale RMS input; 0 disables energy detection. */
    val energyThreshold: Int = 0,
    val backend: ModemBackend = ModemBackend.SOFT,
    val kissHost: String = "127.0.0.1",
    val kissPort: Int = 8001,
    /** MAC address of the paired radio or TNC that speaks KISS over Bluetooth serial; empty means none chosen. */
    val kissBtDevice: String = "",
    /** Classic serial or Bluetooth LE to the KISS TNC. */
    val kissBtLink: BluetoothKissLink = BluetoothKissLink.AUTO,
    /** "vendorId:productId" of the USB serial port wired to the P25 radio's data port; empty means the first one found. */
    val p25Device: String = "",
    /** How the phone reaches the P25 radio: USB (network or serial), or a Bluetooth serial adapter on its data cable. */
    val p25Link: SerialLinkKind = SerialLinkKind.USB,
    /** MAC address of the paired Bluetooth serial adapter on the P25 radio's data cable; empty means none chosen. */
    val p25BtDevice: String = "",
    val p25Baud: Int = 9600,
    val p25Framing: IpFraming = IpFraming.PPP,
    /** This end of the serial link (the radio's "mobile computer" address). */
    val p25LocalIp: String = "192.168.128.2",
    /** The radio's end of the serial link. */
    val p25RadioIp: String = "192.168.128.1",
    /** Where frames go: the limited broadcast, or the other radios' air-interface addresses. */
    val p25Destinations: String = "255.255.255.255",
    val p25Port: Int = P25DataConfig.DEFAULT_PORT,
    /**
     * Find the baud rate and framing each time the radio is connected (and keep what was found),
     * and keep the addresses PPP negotiates. On by default.
     */
    val p25AutoDetect: Boolean = true,
    /** "vendorId:productId" of the MOTOTRBO radio on USB; empty means the first USB network radio found. */
    val dmrDevice: String = "",
    /** This end of the USB link (the PC address the radio's DHCP server normally hands out). */
    val dmrLocalIp: String = DmrIp.DEFAULT_LOCAL_ADDRESS,
    /** The radio's end of the USB link. */
    val dmrRadioIp: String = DmrIp.DEFAULT_RADIO_ADDRESS,
    /** Ask the radio for the addresses (DHCP) on every connect and keep them. On by default. */
    val dmrAutoAddresses: Boolean = true,
    /** Talkgroup that every frame is sent to as a group data call; 0 for none. */
    val dmrTalkgroup: Int = 1,
    /** Radio IDs that every frame is also sent to as individual data calls, comma separated. */
    val dmrRadioIds: String = "",
    /** First octet of radio air-interface addresses (CPS: CAI Network). */
    val dmrCaiNetwork: Int = DmrIp.DEFAULT_CAI_NETWORK,
    /** First octet of talkgroup addresses (CPS: CAI Group Network). */
    val dmrCaiGroupNetwork: Int = DmrIp.DEFAULT_CAI_GROUP_NETWORK,
    val dmrPort: Int = P25DataConfig.DEFAULT_PORT,
    /** Make of the DMR radio; seeds the network numbers. */
    val dmrVendor: DmrVendor = DmrVendor.MOTOTRBO,
    val serialRadioType: SerialRadioType = SerialRadioType.DSTAR_SLOW,
    val serialRadioLink: SerialLinkKind = SerialLinkKind.USB,
    /** "vendorId:productId" of the USB serial port; empty means the first one found. */
    val serialRadioUsbDevice: String = "",
    /** Port of a multi-port USB serial device, from 0 (an IC-705's data port is the second, 1). */
    val serialRadioUsbPort: Int = 0,
    val serialRadioBaud: Int = 9600,
    /** MAC address of the paired radio or Bluetooth serial adapter; empty means none chosen. */
    val serialRadioBtDevice: String = "",
    /** How frames are written to a serial data radio; both are always read. */
    val serialEncoding: SerialEncoding = SerialEncoding.BINARY,
    /** Where DMR talkgroup (multicast) datagrams go on the USB link. */
    val dmrGroupDelivery: MulticastDelivery = MulticastDelivery.RADIO,
    /** IP addresses typed by hand that replace the talkgroup and radio IDs when not blank. */
    val dmrCustomDestinations: String = "",
    /** Digital radio data rate for pacing (P25, DMR, D-STAR, NXDN) in bit/s; 0 uses the radio type's default. */
    val radioDataBps: Int = 0,
    /** Digital radio key-up time for pacing in ms; -1 uses the radio type's default. */
    val radioKeyUpMs: Int = -1,
    /** Log what a digital radio sends that TAKwave does not use, and raw serial bytes, for troubleshooting. */
    val radioDataLog: Boolean = false,
    val encryption: EncryptionMode = EncryptionMode.OFF,
    val cipher: CipherChoice = CipherChoice.AES_GCM,
    val passphrase: String = "",
    /**
     * Also encrypt the callsign: frames carry a shared placeholder as AX.25 source and the real
     * address inside the ciphertext. Only takes effect while [encryption] is on; off by default.
     */
    val hideCallsign: Boolean = false,
    /** Time-slotted transmit (TDMA overlay on top of carrier sensing). */
    val slotted: Boolean = false,
    val slotLengthMs: Int = 3000,
    val slotCount: Int = 8,
    /** This station's slot, or -1 to derive it from the callsign. */
    val slotIndex: Int = -1,
    val timeSource: TimeSource = TimeSource.SYSTEM,
    /** Store and forward: send own chat and markers again when a station comes into range. */
    val resendOwn: Boolean = true,
    /** Store and forward: pass on chat and markers heard from other stations. Off by default. */
    val relay: Boolean = false,
    /** Minutes a held message stays eligible for repeats. */
    val repeatHoldMin: Int = 30,
    /** Most times this station repeats one message. */
    val repeatMax: Int = 3,
    /** Minimum seconds between two transmissions of the same message. */
    val repeatGapS: Int = 120,
    /** Most repeats (re-sends and relays together) per ten minutes. */
    val repeatBudget: Int = 6,
    /** Send own packets as FX.25 (soft modem only). */
    val fx25: Fx25Mode = Fx25Mode.OFF,
    /** False until the first-run setup guide has been finished or skipped. */
    val onboardingDone: Boolean = false,
    /** Radio guide choices. Advice only: nothing in the gateway reads them. */
    val radioService: RadioService = RadioService.AMATEUR,
    val radioBand: Band = Band.VHF,
    val channelWidth: ChannelWidth = ChannelWidth.WIDE,
    val terrain: Terrain = Terrain.OPEN,
) {
    /** The channel width in effect: business band is always narrow. */
    val effectiveChannelWidth: ChannelWidth get() = RadioGuide.effectiveWidth(radioService, channelWidth)

    /** The Bluetooth PTT commands in effect, in PttCommand syntax. */
    val btKeyText: String get() = if (btPttPreset == BtPttPreset.CUSTOM) btKeyCommand else btPttPreset.key
    val btUnkeyText: String get() = if (btPttPreset == BtPttPreset.CUSTOM) btUnkeyCommand else btPttPreset.unkey

    /** The station's AX.25 address, or null while the callsign is missing or invalid. */
    val address: Ax25Address?
        get() = if (callsign.isBlank()) null else Ax25Address.parse("$callsign-$ssid")?.takeIf { it != Gateway.HIDDEN_SOURCE }

    /** True when frames go out with the callsign sealed inside the encrypted payload. */
    val callsignHidden: Boolean get() = hideCallsign && encryption != EncryptionMode.OFF

    /** These settings with what a probe of the radio's data port found. */
    fun withP25Detected(result: P25ProbeResult): Settings = copy(
        p25Framing = result.framing,
        p25Baud = result.baud,
        p25RadioIp = result.radioAddress?.let { Ipv4.format(it) } ?: p25RadioIp,
    )

    /** What is wrong with the P25 packet data settings, or null when they can be used. */
    val p25Problem: String?
        get() = when {
            p25Link == SerialLinkKind.BLUETOOTH && p25BtDevice.isEmpty() -> "No Bluetooth adapter chosen for the radio."
            Ipv4.parse(p25LocalIp).let { it == null || it == 0 } -> "The phone's IP address is not valid."
            Ipv4.parse(p25RadioIp) == null -> "The radio's IP address is not valid."
            Ipv4.parseList(p25Destinations) == null -> "The destination addresses are not valid."
            else -> null
        }

    /** The addresses every frame goes to on a MOTOTRBO radio, or null when the settings do not give any. */
    val dmrDestinations: List<Int>?
        get() = if (dmrCustomDestinations.isNotBlank()) {
            Ipv4.parseList(dmrCustomDestinations)
        } else {
            DmrIp.parseIds(dmrRadioIds)?.let { DmrIp.destinations(dmrTalkgroup, it, dmrCaiNetwork, dmrCaiGroupNetwork) }
        }

    /** What is wrong with the MOTOTRBO data settings, or null when they can be used. */
    val dmrProblem: String?
        get() = when {
            Ipv4.parse(dmrLocalIp).let { it == null || it == 0 } -> "The phone's IP address is not valid."
            Ipv4.parse(dmrRadioIp) == null -> "The radio's IP address is not valid."
            dmrCustomDestinations.isNotBlank() -> if (Ipv4.parseList(dmrCustomDestinations) == null) "The custom addresses are not valid." else null
            dmrTalkgroup != 0 && !DmrIp.validId(dmrTalkgroup) -> "The talkgroup ID is not valid."
            DmrIp.parseIds(dmrRadioIds) == null -> "The radio IDs are not valid."
            !DmrIp.validCaiNetwork(dmrCaiNetwork) -> "The CAI network is not valid."
            !DmrIp.validCaiGroupNetwork(dmrCaiGroupNetwork) -> "The CAI group network is not valid."
            dmrDestinations == null -> "Set a talkgroup or at least one radio ID to send to."
            else -> null
        }

    /** These settings switched to DMR radios of make [vendor], with that make's network numbers. */
    fun withDmrVendor(vendor: DmrVendor): Settings = copy(
        dmrVendor = vendor,
        dmrCaiNetwork = vendor.caiNetwork,
        dmrCaiGroupNetwork = vendor.caiGroupNetwork,
    )

    /** The pacing the selected digital radio backend uses by default (bit/s to key-up ms), or null for the others. */
    val radioPacingDefaults: Pair<Int, Int>?
        get() = when (backend) {
            ModemBackend.P25_DATA -> P25DataConfig.P25_THROUGHPUT_BPS to P25DataConfig.P25_KEY_UP_MS
            ModemBackend.DMR_DATA -> P25DataConfig.DMR_THROUGHPUT_BPS to P25DataConfig.DMR_KEY_UP_MS
            ModemBackend.SERIAL_DATA -> serialRadioType.profile.throughputBps to serialRadioType.profile.keyUpMs
            else -> null
        }

    /** The pacing in effect for the selected digital radio backend: the overrides where set, else its defaults. */
    val radioPacing: Pair<Int, Int>
        get() {
            val (bps, keyUp) = radioPacingDefaults ?: (P25DataConfig.P25_THROUGHPUT_BPS to P25DataConfig.P25_KEY_UP_MS)
            return (if (radioDataBps > 0) radioDataBps else bps) to (if (radioKeyUpMs >= 0) radioKeyUpMs else keyUp)
        }

    /** What is wrong with the serial data radio settings, or null when they can be used. */
    val serialRadioProblem: String?
        get() = when {
            serialRadioLink == SerialLinkKind.BLUETOOTH && serialRadioBtDevice.isEmpty() -> "No Bluetooth device chosen for the radio."
            else -> null
        }

    /** One line describing the serial data radio setup. */
    val serialRadioSummary: String
        get() = serialRadioType.label.substringBefore(" (") + when (serialRadioLink) {
            SerialLinkKind.USB -> " over USB serial at $serialRadioBaud baud"
            SerialLinkKind.BLUETOOTH -> " over Bluetooth serial"
        }

    /** One line describing where MOTOTRBO frames go, for summaries and the log. */
    val dmrTargetsText: String
        get() = if (dmrCustomDestinations.isNotBlank()) {
            Ipv4.parseList(dmrCustomDestinations)?.joinToString { Ipv4.format(it) } ?: "invalid custom addresses"
        } else buildList {
            if (dmrTalkgroup != 0) add("talkgroup $dmrTalkgroup")
            DmrIp.parseIds(dmrRadioIds)?.forEach { add("radio $it") }
        }.joinToString().ifEmpty { "nowhere" }

    companion object {
        const val DEFAULT_TAK_PORT = 8099

        /**
         * ATAK's own built-in TCP CoT input. With TAKwave on this port ATAK can end up connected to
         * itself, gets no ping replies and drops the link every ~30 s. It was TAKwave's default up
         * to 0.3.0, so a stored 8087 is read as [DEFAULT_TAK_PORT], and the setting refuses it.
         */
        const val ATAK_INPUT_PORT = 8087
    }
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Settings persistence on Jetpack DataStore. */
class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsDataStore

    val settings: Flow<Settings> = store.data.map { read(it) }

    suspend fun update(transform: (Settings) -> Settings) {
        store.edit { prefs -> write(prefs, transform(read(prefs))) }
    }

    private fun read(p: Preferences): Settings {
        val d = Settings()
        return Settings(
            callsign = p[CALLSIGN] ?: d.callsign,
            ssid = p[SSID] ?: d.ssid,
            takPort = p[TAK_PORT]?.takeIf { it != Settings.ATAK_INPUT_PORT } ?: d.takPort,
            allowLan = p[ALLOW_LAN] ?: d.allowLan,
            pliBaseS = p[PLI_BASE] ?: d.pliBaseS,
            pliMinS = p[PLI_MIN] ?: d.pliMinS,
            pliDistanceM = p[PLI_DISTANCE] ?: d.pliDistanceM,
            pliHeadingDeg = p[PLI_HEADING] ?: d.pliHeadingDeg,
            inputDevice = p[INPUT_DEVICE] ?: d.inputDevice,
            outputDevice = p[OUTPUT_DEVICE] ?: d.outputDevice,
            pttMethod = enumOf(p[PTT_METHOD], d.pttMethod),
            pttLine = enumOf(p[PTT_LINE], d.pttLine),
            pttInverted = p[PTT_INVERTED] ?: d.pttInverted,
            pttDevice = p[PTT_DEVICE] ?: d.pttDevice,
            btPttDevice = p[BT_PTT_DEVICE] ?: d.btPttDevice,
            btPttPreset = enumOf(p[BT_PTT_PRESET], d.btPttPreset),
            btKeyCommand = p[BT_KEY_COMMAND] ?: d.btKeyCommand,
            btUnkeyCommand = p[BT_UNKEY_COMMAND] ?: d.btUnkeyCommand,
            txDelayMs = p[TX_DELAY] ?: d.txDelayMs,
            txTailMs = p[TX_TAIL] ?: d.txTailMs,
            txLevel = p[TX_LEVEL] ?: d.txLevel,
            txTimeoutS = p[TX_TIMEOUT] ?: d.txTimeoutS,
            slotTimeMs = p[SLOT_TIME] ?: d.slotTimeMs,
            persistence = p[PERSISTENCE] ?: d.persistence,
            energyThreshold = p[ENERGY_THRESHOLD] ?: d.energyThreshold,
            backend = enumOf(p[BACKEND], d.backend),
            kissHost = p[KISS_HOST] ?: d.kissHost,
            kissPort = p[KISS_PORT] ?: d.kissPort,
            kissBtDevice = p[KISS_BT_DEVICE] ?: d.kissBtDevice,
            kissBtLink = enumOf(p[KISS_BT_LINK], d.kissBtLink),
            p25Device = p[P25_DEVICE] ?: d.p25Device,
            p25Link = enumOf(p[P25_LINK], d.p25Link),
            p25BtDevice = p[P25_BT_DEVICE] ?: d.p25BtDevice,
            p25Baud = p[P25_BAUD] ?: d.p25Baud,
            p25Framing = enumOf(p[P25_FRAMING], d.p25Framing),
            p25LocalIp = p[P25_LOCAL_IP] ?: d.p25LocalIp,
            p25RadioIp = p[P25_RADIO_IP] ?: d.p25RadioIp,
            p25Destinations = p[P25_DESTINATIONS] ?: d.p25Destinations,
            p25Port = p[P25_PORT] ?: d.p25Port,
            p25AutoDetect = p[P25_AUTO_DETECT] ?: d.p25AutoDetect,
            dmrDevice = p[DMR_DEVICE] ?: d.dmrDevice,
            dmrLocalIp = p[DMR_LOCAL_IP] ?: d.dmrLocalIp,
            dmrRadioIp = p[DMR_RADIO_IP] ?: d.dmrRadioIp,
            dmrAutoAddresses = p[DMR_AUTO_ADDRESSES] ?: d.dmrAutoAddresses,
            dmrTalkgroup = p[DMR_TALKGROUP] ?: d.dmrTalkgroup,
            dmrRadioIds = p[DMR_RADIO_IDS] ?: d.dmrRadioIds,
            dmrCaiNetwork = p[DMR_CAI_NETWORK] ?: d.dmrCaiNetwork,
            dmrCaiGroupNetwork = p[DMR_CAI_GROUP_NETWORK] ?: d.dmrCaiGroupNetwork,
            dmrPort = p[DMR_PORT] ?: d.dmrPort,
            dmrVendor = enumOf(p[DMR_VENDOR], d.dmrVendor),
            serialRadioType = enumOf(p[SERIAL_RADIO_TYPE], d.serialRadioType),
            serialRadioLink = enumOf(p[SERIAL_RADIO_LINK], d.serialRadioLink),
            serialRadioUsbDevice = p[SERIAL_RADIO_USB_DEVICE] ?: d.serialRadioUsbDevice,
            serialRadioUsbPort = p[SERIAL_RADIO_USB_PORT] ?: d.serialRadioUsbPort,
            serialRadioBaud = p[SERIAL_RADIO_BAUD] ?: d.serialRadioBaud,
            serialRadioBtDevice = p[SERIAL_RADIO_BT_DEVICE] ?: d.serialRadioBtDevice,
            serialEncoding = enumOf(p[SERIAL_ENCODING], d.serialEncoding),
            dmrGroupDelivery = enumOf(p[DMR_GROUP_DELIVERY], d.dmrGroupDelivery),
            dmrCustomDestinations = p[DMR_CUSTOM_DESTINATIONS] ?: d.dmrCustomDestinations,
            radioDataBps = p[RADIO_DATA_BPS] ?: d.radioDataBps,
            radioKeyUpMs = p[RADIO_KEY_UP_MS] ?: d.radioKeyUpMs,
            radioDataLog = p[RADIO_DATA_LOG] ?: d.radioDataLog,
            encryption = enumOf(p[ENCRYPTION], d.encryption),
            cipher = enumOf(p[CIPHER], d.cipher),
            passphrase = p[PASSPHRASE] ?: d.passphrase,
            hideCallsign = p[HIDE_CALLSIGN] ?: d.hideCallsign,
            slotted = p[SLOTTED] ?: d.slotted,
            slotLengthMs = p[SLOT_LENGTH] ?: d.slotLengthMs,
            slotCount = p[SLOT_COUNT] ?: d.slotCount,
            slotIndex = p[SLOT_INDEX] ?: d.slotIndex,
            timeSource = enumOf(p[TIME_SOURCE], d.timeSource),
            resendOwn = p[RESEND_OWN] ?: d.resendOwn,
            relay = p[RELAY] ?: d.relay,
            repeatHoldMin = p[REPEAT_HOLD] ?: d.repeatHoldMin,
            repeatMax = p[REPEAT_MAX] ?: d.repeatMax,
            repeatGapS = p[REPEAT_GAP] ?: d.repeatGapS,
            repeatBudget = p[REPEAT_BUDGET] ?: d.repeatBudget,
            fx25 = enumOf(p[FX25], d.fx25),
            onboardingDone = p[ONBOARDING_DONE] ?: d.onboardingDone,
            radioService = enumOf(p[RADIO_SERVICE], d.radioService),
            radioBand = enumOf(p[RADIO_BAND], d.radioBand),
            channelWidth = enumOf(p[CHANNEL_WIDTH], d.channelWidth),
            terrain = enumOf(p[TERRAIN], d.terrain),
        )
    }

    private fun write(p: androidx.datastore.preferences.core.MutablePreferences, s: Settings) {
        p[CALLSIGN] = s.callsign
        p[SSID] = s.ssid
        p[TAK_PORT] = s.takPort
        p[ALLOW_LAN] = s.allowLan
        p[PLI_BASE] = s.pliBaseS
        p[PLI_MIN] = s.pliMinS
        p[PLI_DISTANCE] = s.pliDistanceM
        p[PLI_HEADING] = s.pliHeadingDeg
        p[INPUT_DEVICE] = s.inputDevice
        p[OUTPUT_DEVICE] = s.outputDevice
        p[PTT_METHOD] = s.pttMethod.name
        p[PTT_LINE] = s.pttLine.name
        p[PTT_INVERTED] = s.pttInverted
        p[PTT_DEVICE] = s.pttDevice
        p[BT_PTT_DEVICE] = s.btPttDevice
        p[BT_PTT_PRESET] = s.btPttPreset.name
        p[BT_KEY_COMMAND] = s.btKeyCommand
        p[BT_UNKEY_COMMAND] = s.btUnkeyCommand
        p[TX_DELAY] = s.txDelayMs
        p[TX_TAIL] = s.txTailMs
        p[TX_LEVEL] = s.txLevel
        p[TX_TIMEOUT] = s.txTimeoutS
        p[SLOT_TIME] = s.slotTimeMs
        p[PERSISTENCE] = s.persistence
        p[ENERGY_THRESHOLD] = s.energyThreshold
        p[BACKEND] = s.backend.name
        p[KISS_HOST] = s.kissHost
        p[KISS_PORT] = s.kissPort
        p[KISS_BT_DEVICE] = s.kissBtDevice
        p[KISS_BT_LINK] = s.kissBtLink.name
        p[P25_DEVICE] = s.p25Device
        p[P25_LINK] = s.p25Link.name
        p[P25_BT_DEVICE] = s.p25BtDevice
        p[P25_BAUD] = s.p25Baud
        p[P25_FRAMING] = s.p25Framing.name
        p[P25_LOCAL_IP] = s.p25LocalIp
        p[P25_RADIO_IP] = s.p25RadioIp
        p[P25_DESTINATIONS] = s.p25Destinations
        p[P25_PORT] = s.p25Port
        p[P25_AUTO_DETECT] = s.p25AutoDetect
        p[DMR_DEVICE] = s.dmrDevice
        p[DMR_LOCAL_IP] = s.dmrLocalIp
        p[DMR_RADIO_IP] = s.dmrRadioIp
        p[DMR_AUTO_ADDRESSES] = s.dmrAutoAddresses
        p[DMR_TALKGROUP] = s.dmrTalkgroup
        p[DMR_RADIO_IDS] = s.dmrRadioIds
        p[DMR_CAI_NETWORK] = s.dmrCaiNetwork
        p[DMR_CAI_GROUP_NETWORK] = s.dmrCaiGroupNetwork
        p[DMR_PORT] = s.dmrPort
        p[DMR_VENDOR] = s.dmrVendor.name
        p[SERIAL_RADIO_TYPE] = s.serialRadioType.name
        p[SERIAL_RADIO_LINK] = s.serialRadioLink.name
        p[SERIAL_RADIO_USB_DEVICE] = s.serialRadioUsbDevice
        p[SERIAL_RADIO_USB_PORT] = s.serialRadioUsbPort
        p[SERIAL_RADIO_BAUD] = s.serialRadioBaud
        p[SERIAL_RADIO_BT_DEVICE] = s.serialRadioBtDevice
        p[SERIAL_ENCODING] = s.serialEncoding.name
        p[DMR_GROUP_DELIVERY] = s.dmrGroupDelivery.name
        p[DMR_CUSTOM_DESTINATIONS] = s.dmrCustomDestinations
        p[RADIO_DATA_BPS] = s.radioDataBps
        p[RADIO_KEY_UP_MS] = s.radioKeyUpMs
        p[RADIO_DATA_LOG] = s.radioDataLog
        p[ENCRYPTION] = s.encryption.name
        p[CIPHER] = s.cipher.name
        p[PASSPHRASE] = s.passphrase
        p[HIDE_CALLSIGN] = s.hideCallsign
        p[SLOTTED] = s.slotted
        p[SLOT_LENGTH] = s.slotLengthMs
        p[SLOT_COUNT] = s.slotCount
        p[SLOT_INDEX] = s.slotIndex
        p[TIME_SOURCE] = s.timeSource.name
        p[RESEND_OWN] = s.resendOwn
        p[RELAY] = s.relay
        p[REPEAT_HOLD] = s.repeatHoldMin
        p[REPEAT_MAX] = s.repeatMax
        p[REPEAT_GAP] = s.repeatGapS
        p[REPEAT_BUDGET] = s.repeatBudget
        p[FX25] = s.fx25.name
        p[ONBOARDING_DONE] = s.onboardingDone
        p[RADIO_SERVICE] = s.radioService.name
        p[RADIO_BAND] = s.radioBand.name
        p[CHANNEL_WIDTH] = s.channelWidth.name
        p[TERRAIN] = s.terrain.name
    }

    private inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        val CALLSIGN = stringPreferencesKey("callsign")
        val SSID = intPreferencesKey("ssid")
        val TAK_PORT = intPreferencesKey("tak_port")
        val ALLOW_LAN = booleanPreferencesKey("allow_lan")
        val PLI_BASE = intPreferencesKey("pli_base_s")
        val PLI_MIN = intPreferencesKey("pli_min_s")
        val PLI_DISTANCE = intPreferencesKey("pli_distance_m")
        val PLI_HEADING = intPreferencesKey("pli_heading_deg")
        val INPUT_DEVICE = stringPreferencesKey("input_device")
        val OUTPUT_DEVICE = stringPreferencesKey("output_device")
        val PTT_METHOD = stringPreferencesKey("ptt_method")
        val PTT_LINE = stringPreferencesKey("ptt_line")
        val PTT_INVERTED = booleanPreferencesKey("ptt_inverted")
        val PTT_DEVICE = stringPreferencesKey("ptt_device")
        val TX_DELAY = intPreferencesKey("tx_delay_ms")
        val TX_TAIL = intPreferencesKey("tx_tail_ms")
        val TX_LEVEL = intPreferencesKey("tx_level")
        val TX_TIMEOUT = intPreferencesKey("tx_timeout_s")
        val SLOT_TIME = intPreferencesKey("slot_time_ms")
        val PERSISTENCE = intPreferencesKey("persistence")
        val ENERGY_THRESHOLD = intPreferencesKey("energy_threshold")
        val BACKEND = stringPreferencesKey("backend")
        val KISS_HOST = stringPreferencesKey("kiss_host")
        val KISS_PORT = intPreferencesKey("kiss_port")
        val KISS_BT_DEVICE = stringPreferencesKey("kiss_bt_device")
        val KISS_BT_LINK = stringPreferencesKey("kiss_bt_link")
        val P25_DEVICE = stringPreferencesKey("p25_device")
        val P25_LINK = stringPreferencesKey("p25_link")
        val P25_BT_DEVICE = stringPreferencesKey("p25_bt_device")
        val P25_BAUD = intPreferencesKey("p25_baud")
        val P25_FRAMING = stringPreferencesKey("p25_framing")
        val P25_LOCAL_IP = stringPreferencesKey("p25_local_ip")
        val P25_RADIO_IP = stringPreferencesKey("p25_radio_ip")
        val P25_DESTINATIONS = stringPreferencesKey("p25_destinations")
        val P25_PORT = intPreferencesKey("p25_port")
        val P25_AUTO_DETECT = booleanPreferencesKey("p25_auto_detect")
        val DMR_DEVICE = stringPreferencesKey("dmr_device")
        val DMR_LOCAL_IP = stringPreferencesKey("dmr_local_ip")
        val DMR_RADIO_IP = stringPreferencesKey("dmr_radio_ip")
        val DMR_AUTO_ADDRESSES = booleanPreferencesKey("dmr_auto_addresses")
        val DMR_TALKGROUP = intPreferencesKey("dmr_talkgroup")
        val DMR_RADIO_IDS = stringPreferencesKey("dmr_radio_ids")
        val DMR_CAI_NETWORK = intPreferencesKey("dmr_cai_network")
        val DMR_CAI_GROUP_NETWORK = intPreferencesKey("dmr_cai_group_network")
        val DMR_PORT = intPreferencesKey("dmr_port")
        val DMR_VENDOR = stringPreferencesKey("dmr_vendor")
        val SERIAL_RADIO_TYPE = stringPreferencesKey("serial_radio_type")
        val SERIAL_RADIO_LINK = stringPreferencesKey("serial_radio_link")
        val SERIAL_RADIO_USB_DEVICE = stringPreferencesKey("serial_radio_usb_device")
        val SERIAL_RADIO_USB_PORT = intPreferencesKey("serial_radio_usb_port")
        val SERIAL_RADIO_BAUD = intPreferencesKey("serial_radio_baud")
        val SERIAL_RADIO_BT_DEVICE = stringPreferencesKey("serial_radio_bt_device")
        val SERIAL_ENCODING = stringPreferencesKey("serial_encoding")
        val DMR_GROUP_DELIVERY = stringPreferencesKey("dmr_group_delivery")
        val DMR_CUSTOM_DESTINATIONS = stringPreferencesKey("dmr_custom_destinations")
        val RADIO_DATA_BPS = intPreferencesKey("radio_data_bps")
        val RADIO_KEY_UP_MS = intPreferencesKey("radio_key_up_ms")
        val RADIO_DATA_LOG = booleanPreferencesKey("radio_data_log")
        val ENCRYPTION = stringPreferencesKey("encryption")
        val PASSPHRASE = stringPreferencesKey("passphrase")
        val CIPHER = stringPreferencesKey("cipher")
        val HIDE_CALLSIGN = booleanPreferencesKey("hide_callsign")
        val SLOTTED = booleanPreferencesKey("slotted")
        val SLOT_LENGTH = intPreferencesKey("slot_length_ms")
        val SLOT_COUNT = intPreferencesKey("slot_count")
        val SLOT_INDEX = intPreferencesKey("slot_index")
        val TIME_SOURCE = stringPreferencesKey("time_source")
        val BT_PTT_DEVICE = stringPreferencesKey("bt_ptt_device")
        val BT_PTT_PRESET = stringPreferencesKey("bt_ptt_preset")
        val BT_KEY_COMMAND = stringPreferencesKey("bt_key_command")
        val BT_UNKEY_COMMAND = stringPreferencesKey("bt_unkey_command")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val RESEND_OWN = booleanPreferencesKey("resend_own")
        val RELAY = booleanPreferencesKey("relay")
        val REPEAT_HOLD = intPreferencesKey("repeat_hold_min")
        val REPEAT_MAX = intPreferencesKey("repeat_max")
        val REPEAT_GAP = intPreferencesKey("repeat_gap_s")
        val REPEAT_BUDGET = intPreferencesKey("repeat_budget")
        val FX25 = stringPreferencesKey("fx25")
        val RADIO_SERVICE = stringPreferencesKey("radio_service")
        val RADIO_BAND = stringPreferencesKey("radio_band")
        val CHANNEL_WIDTH = stringPreferencesKey("channel_width")
        val TERRAIN = stringPreferencesKey("terrain")
    }
}
