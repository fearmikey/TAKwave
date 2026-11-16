// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.fearmikey.takwave.core.io.AudioSink
import com.fearmikey.takwave.core.io.AudioSource
import com.fearmikey.takwave.core.modem.Bell202

/** A selectable audio endpoint. [key] is what gets stored in settings. */
data class AudioDeviceOption(val key: String, val label: String, val isUsb: Boolean)

object AudioDevices {
    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (voice)"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth (media)"
        AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
        else -> "Audio device (type $type)"
    }

    private fun isUsb(d: AudioDeviceInfo) = d.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
        d.type == AudioDeviceInfo.TYPE_USB_HEADSET || d.type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    /** True when a USB sound card is attached, for example a Digirig or an auto-PTT cable such as the APRS-K1 PRO. */
    fun hasUsbAudio(context: Context): Boolean = devices(context, AudioManager.GET_DEVICES_ALL).any(::isUsb)

    /** Device ids change every time a device is plugged in, so settings store type and product name. */
    fun keyOf(d: AudioDeviceInfo): String = "${d.type}:${d.productName}"

    private fun devices(context: Context, flag: Int): Array<AudioDeviceInfo> =
        context.getSystemService(AudioManager::class.java).getDevices(flag)
            .filter { it.type != AudioDeviceInfo.TYPE_TELEPHONY }.toTypedArray()

    fun inputs(context: Context): List<AudioDeviceOption> = options(devices(context, AudioManager.GET_DEVICES_INPUTS))
    fun outputs(context: Context): List<AudioDeviceOption> = options(devices(context, AudioManager.GET_DEVICES_OUTPUTS))

    private fun options(list: Array<AudioDeviceInfo>): List<AudioDeviceOption> =
        list.map { AudioDeviceOption(keyOf(it), "${typeName(it.type)}: ${it.productName}", isUsb(it)) }.distinctBy { it.key }

    fun findInput(context: Context, key: String): AudioDeviceInfo? =
        if (key.isEmpty()) null else devices(context, AudioManager.GET_DEVICES_INPUTS).firstOrNull { keyOf(it) == key }

    fun findOutput(context: Context, key: String): AudioDeviceInfo? =
        if (key.isEmpty()) null else devices(context, AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { keyOf(it) == key }
}

/**
 * Microphone / USB audio capture at 48 kHz mono. Uses the UNPROCESSED source where the device
 * supports it (no AGC, noise suppression or echo canceling, all of which damage a modem signal)
 * and VOICE_RECOGNITION otherwise.
 */
class AndroidAudioSource(context: Context, device: AudioDeviceInfo?) : AudioSource {
    private val record: AudioRecord

    init {
        val am = context.getSystemService(AudioManager::class.java)
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuf > 0) { "48 kHz mono capture is not supported on this device" }
        record = create(source, maxOf(minBuf * 2, RATE / 5 * 2))
        check(record.state == AudioRecord.STATE_INITIALIZED) { "could not open the audio input" }
        if (device != null) record.preferredDevice = device
        record.startRecording()
        check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "audio input did not start (is the microphone in use?)" }
    }

    @SuppressLint("MissingPermission") // The service checks RECORD_AUDIO before constructing this.
    private fun create(source: Int, bufferBytes: Int): AudioRecord = AudioRecord.Builder()
        .setAudioSource(source)
        .setAudioFormat(
            AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
        )
        .setBufferSizeInBytes(bufferBytes)
        .build()

    override fun read(buffer: ShortArray, offset: Int, length: Int): Int {
        val n = record.read(buffer, offset, length)
        return if (n < 0) -1 else n
    }

    fun close() {
        try {
            record.stop()
        } catch (e: IllegalStateException) {
            // Already stopped.
        }
        record.release()
    }

    private companion object {
        const val RATE = Bell202.SAMPLE_RATE
    }
}

/** Speaker / USB audio playback at 48 kHz mono. */
class AndroidAudioSink(device: AudioDeviceInfo?) : AudioSink {
    private val track: AudioTrack
    @Volatile private var aborted = false
    private var framesWritten = 0

    init {
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuf > 0) { "48 kHz mono playback is not supported on this device" }
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf * 2, RATE / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        check(track.state == AudioTrack.STATE_INITIALIZED) { "could not open the audio output" }
        if (device != null) track.preferredDevice = device
    }

    override fun write(samples: ShortArray, offset: Int, length: Int) {
        aborted = false
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            framesWritten = 0
            track.play()
        }
        var p = offset
        val end = offset + length
        while (p < end && !aborted) {
            val n = track.write(samples, p, minOf(CHUNK, end - p))
            if (n < 0) throw IllegalStateException("audio output error $n")
            if (n == 0) Thread.sleep(5)
            p += n
            framesWritten += n
        }
    }

    override fun drain() {
        // Wait for the playback head to reach what was written, bounded in case the head stalls.
        val deadline = System.currentTimeMillis() + framesWritten * 1000L / RATE + 1_000
        while (!aborted && track.playbackHeadPosition < framesWritten && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        // The head position runs ahead of the DAC by the output latency; let the tail play out.
        if (!aborted) Thread.sleep(OUTPUT_LATENCY_MARGIN_MS)
        stopTrack()
    }

    override fun abort() {
        aborted = true
        stopTrack()
    }

    private fun stopTrack() {
        try {
            track.pause()
            track.flush()
        } catch (e: IllegalStateException) {
            // Released.
        }
    }

    fun close() {
        aborted = true
        stopTrack()
        track.release()
    }

    private companion object {
        const val RATE = Bell202.SAMPLE_RATE
        const val CHUNK = 4800
        const val OUTPUT_LATENCY_MARGIN_MS = 80L
    }
}
