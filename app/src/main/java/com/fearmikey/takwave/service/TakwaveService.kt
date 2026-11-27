// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.fearmikey.takwave.MainActivity
import com.fearmikey.takwave.R
import com.fearmikey.takwave.container
import com.fearmikey.takwave.core.io.RfState
import com.fearmikey.takwave.data.ModemBackend
import com.fearmikey.takwave.data.Settings
import com.fearmikey.takwave.engine.LogKind
import com.fearmikey.takwave.engine.RadioEngine
import com.fearmikey.takwave.engine.RadioStatus
import com.fearmikey.takwave.ptt.SerialDevices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the gateway alive when the UI is closed. It holds a partial wake
 * lock, shows a persistent notification, and makes sure the transmitter is released when it is
 * destroyed or the PTT adapter is unplugged.
 */
class TakwaveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var engine: RadioEngine? = null
    private var receiverRegistered = false
    private var lastNotificationText = ""

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
            engine?.onUsbDetached(SerialDevices.keyOf(device))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (engine != null) return START_NOT_STICKY

        createChannel()
        scope.launch {
            val settings = container.settingsStore.settings.first()
            if (!enterForeground(settings)) {
                stopSelf()
                return@launch
            }
            acquireWakeLock()
            ContextCompat.registerReceiver(
                this@TakwaveService, usbReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
            val e = RadioEngine(
                applicationContext, settings, container.status, container.log, container.keyFiles, container.stateFile,
                saveSettings = { transform -> scope.launch { container.settingsStore.update(transform) } },
            )
            engine = e
            container.engine = e
            e.start()
            while (true) {
                updateNotification(container.status.value)
                delay(NOTIFICATION_PERIOD_MS)
            }
        }
        // Not sticky: a microphone foreground service may not be restarted from the background.
        return START_NOT_STICKY
    }

    /** Returns false when the system refuses the foreground start. */
    private fun enterForeground(settings: Settings): Boolean {
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (settings.backend == ModemBackend.SOFT && micGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        val notification = buildNotification("Starting")
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground refused", e)
            container.log.add(LogKind.ERROR, "Android refused to start the foreground service: ${e.message}")
            false
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TAKwave:gateway").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.notification_channel_description)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TakwaveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_wave)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(status: RadioStatus) {
        // A radio or TNC that keys itself never reports TX or RX; show whether its link is up instead.
        val state = status.linkUp?.let { if (it) "Radio link up" else "No radio link" } ?: when (status.rf) {
            RfState.IDLE -> "Idle"
            RfState.RX -> "Receiving"
            RfState.TX -> "Transmitting"
        }
        val clients = if (status.clients == 1) "1 TAK client" else "${status.clients} TAK clients"
        val last = status.heard.firstOrNull()?.let { h ->
            val ageS = (System.currentTimeMillis() - h.lastHeardMs) / 1000
            // Minute resolution keeps the notification from being rewritten every few seconds.
            "last heard ${h.source} " + if (ageS < 60) "just now" else "${ageS / 60} min ago"
        } ?: "nothing heard yet"
        // ATAK raises the alarm itself; the notification just makes sure the alert is not missed here.
        val alert = (status.ownAlerts.firstOrNull()?.let { "Sending ${it.kind.label}" }
            ?: status.heardAlerts.firstOrNull()?.let { "EMERGENCY: ${it.message.kind.label} from ${it.message.text.ifEmpty { it.source }}" })
        val text = listOfNotNull(alert, state, clients, last).joinToString(" · ")
        if (text == lastNotificationText) return
        lastNotificationText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        // Releases PTT, audio and sockets.
        engine?.stop()
        engine = null
        container.engine = null
        if (receiverRegistered) unregisterReceiver(usbReceiver)
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TAKwave"
        private const val CHANNEL_ID = "gateway"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_PERIOD_MS = 2_000L
        const val ACTION_STOP = "com.fearmikey.takwave.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TakwaveService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TakwaveService::class.java))
        }
    }
}
