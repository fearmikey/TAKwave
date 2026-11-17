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
    CUSTOM("Custom commands", "", ""),
}

enum class ModemBackend(val label: String) {
    SOFT("Soft modem (audio)"),
    KISS_TCP("KISS over TCP"),
    KISS_BLUETOOTH("KISS over Bluetooth (radio with built-in TNC)"),
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
