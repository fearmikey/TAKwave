// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.ptt

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Parcelable
import android.os.ParcelUuid
import com.fearmikey.takwave.core.io.BleKiss
import java.util.UUID

/**
 * Finds Bluetooth LE TNCs through Android's companion device chooser. The system does the
 * scanning and shows only devices that advertise the BLE KISS service; the operator picks one,
 * and TAKwave may then connect to it. This keeps the rule that TAKwave never scans by itself, so
 * it needs neither the Bluetooth scan nor the location permission (docs/DECISIONS.md 65, 149).
 */
object BleCompanion {
    private val request: AssociationRequest
        get() = AssociationRequest.Builder()
            .addDeviceFilter(
                BluetoothLeDeviceFilter.Builder()
                    .setScanFilter(ScanFilter.Builder().setServiceUuid(ParcelUuid(UUID.fromString(BleKiss.SERVICE))).build())
                    .build(),
            )
            .setSingleDevice(false)
            .build()

    /**
     * Asks the system to look for BLE TNCs. [onChooser] gets the chooser to launch (with
     * StartIntentSenderForResult); [onError] a readable reason when nothing can be shown.
     */
    fun find(context: Context, onChooser: (IntentSender) -> Unit, onError: (String) -> Unit) {
        val manager = context.getSystemService(CompanionDeviceManager::class.java)
        if (manager == null) {
            onError("this phone cannot look for companion devices")
            return
        }
        val callback = object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) = onChooser(intentSender)

            @Deprecated("Called instead of onAssociationPending before Android 13")
            override fun onDeviceFound(intentSender: IntentSender) = onChooser(intentSender)

            override fun onFailure(error: CharSequence?) =
                onError(error?.toString()?.takeIf { it.isNotBlank() } ?: "no Bluetooth LE TNC was found nearby")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.associate(request, context.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                manager.associate(request, callback, null)
            }
        } catch (e: Exception) {
            onError(e.message ?: "the device chooser could not be opened")
        }
    }

    /** The address of the device the operator picked, from the chooser's result, or null. */
    fun addressFrom(result: Intent?): String? {
        if (result == null) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.getParcelableExtra(CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
                ?.deviceMacAddress?.toString()?.uppercase()?.let { return it }
        }
        @Suppress("DEPRECATION")
        return when (val device = result.getParcelableExtra<Parcelable>(CompanionDeviceManager.EXTRA_DEVICE)) {
            is ScanResult -> device.device.address
            is BluetoothDevice -> device.address
            else -> null
        }
    }

    /** Addresses of the devices picked in the chooser so far (upper case). */
    fun associated(context: Context): List<String> {
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return emptyList()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.myAssociations.mapNotNull { it.deviceMacAddress?.toString()?.uppercase() }
            } else {
                @Suppress("DEPRECATION")
                manager.associations.map { it.uppercase() }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
