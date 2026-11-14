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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class PttMethod(val label: String) {
    SERIAL("Serial (RTS/DTR)"),
    VOX("VOX"),
    NONE("None"),
}

enum class PttLine { RTS, DTR }

enum class ModemBackend(val label: String) {
    SOFT("Soft modem (audio)"),
    KISS_TCP("KISS over TCP"),
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

enum class TimeSource(val label: String) {
    SYSTEM("Phone clock (network time)"),
    GNSS("GNSS (GPS) time"),
}

/** Every user setting, with the defaults from the build spec. Read once when the service starts. */
data class Settings(
    val callsign: String = "",
    val ssid: Int = 0,
    val takPort: Int = 8087,
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
    val encryption: EncryptionMode = EncryptionMode.OFF,
    val cipher: CipherChoice = CipherChoice.AES_GCM,
    val passphrase: String = "",
    /** Time-slotted transmit (TDMA overlay on top of carrier sensing). */
    val slotted: Boolean = false,
    val slotLengthMs: Int = 3000,
    val slotCount: Int = 8,
    /** This station's slot, or -1 to derive it from the callsign. */
    val slotIndex: Int = -1,
    val timeSource: TimeSource = TimeSource.SYSTEM,
) {
    /** The station's AX.25 address, or null while the callsign is missing or invalid. */
    val address: Ax25Address? get() = if (callsign.isBlank()) null else Ax25Address.parse("$callsign-$ssid")
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
            takPort = p[TAK_PORT] ?: d.takPort,
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
            encryption = enumOf(p[ENCRYPTION], d.encryption),
            cipher = enumOf(p[CIPHER], d.cipher),
            passphrase = p[PASSPHRASE] ?: d.passphrase,
            slotted = p[SLOTTED] ?: d.slotted,
            slotLengthMs = p[SLOT_LENGTH] ?: d.slotLengthMs,
            slotCount = p[SLOT_COUNT] ?: d.slotCount,
            slotIndex = p[SLOT_INDEX] ?: d.slotIndex,
            timeSource = enumOf(p[TIME_SOURCE], d.timeSource),
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
        p[ENCRYPTION] = s.encryption.name
        p[CIPHER] = s.cipher.name
        p[PASSPHRASE] = s.passphrase
        p[SLOTTED] = s.slotted
        p[SLOT_LENGTH] = s.slotLengthMs
        p[SLOT_COUNT] = s.slotCount
        p[SLOT_INDEX] = s.slotIndex
        p[TIME_SOURCE] = s.timeSource.name
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
        val ENCRYPTION = stringPreferencesKey("encryption")
        val PASSPHRASE = stringPreferencesKey("passphrase")
        val CIPHER = stringPreferencesKey("cipher")
        val SLOTTED = booleanPreferencesKey("slotted")
        val SLOT_LENGTH = intPreferencesKey("slot_length_ms")
        val SLOT_COUNT = intPreferencesKey("slot_count")
        val SLOT_INDEX = intPreferencesKey("slot_index")
        val TIME_SOURCE = stringPreferencesKey("time_source")
    }
}
