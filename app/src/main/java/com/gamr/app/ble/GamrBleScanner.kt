package com.gamr.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.util.UUID

data class GamrDevice(
    val name: String,
    val address: String,
    val rssi: Int? = null,
    val source: GamrDeviceSource = GamrDeviceSource.ADVERTISING,
)

enum class GamrDeviceSource { ADVERTISING, CONNECTED }

data class ScanStartResult(val message: String)

class GamrBleScanner(
    context: Context,
    private val onDevicesChanged: (List<GamrDevice>) -> Unit,
) {
    private val appContext = context.applicationContext
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val preferences = appContext.getSharedPreferences("gamr_app", Context.MODE_PRIVATE)
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val advertisingDevices = linkedMapOf<String, GamrDevice>()
    private val advertisingLastSeen = mutableMapOf<String, Long>()
    private var hidProfile: BluetoothProfile? = null
    private var profileRequested = false
    private var scanning = false

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != HID_DEVICE_PROFILE) return
            hidProfile = proxy
            if (scanning) publishDevices()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != HID_DEVICE_PROFILE) return
            hidProfile = null
            profileRequested = false
            if (scanning) publishDevices()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addResult(result)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            refreshHandler.removeCallbacks(refreshConnectedDevices)
        }
    }

    private val refreshConnectedDevices = object : Runnable {
        override fun run() {
            if (!scanning) return
            publishDevices()
            refreshHandler.postDelayed(this, CONNECTION_REFRESH_MS)
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): ScanStartResult {
        if (!hasBluetoothPermissions()) {
            return ScanStartResult("Bluetooth scan and connection permissions are required.")
        }
        val adapter = bluetoothManager.adapter ?: return ScanStartResult("Bluetooth is not available on this phone.")
        if (!adapter.isEnabled) return ScanStartResult("Turn on Bluetooth, then scan again.")

        stop()
        advertisingDevices.clear()
        advertisingLastSeen.clear()
        try {
            if (!profileRequested) {
                profileRequested = adapter.getProfileProxy(appContext, profileListener, HID_DEVICE_PROFILE)
            }
            publishDevices()

            val otaService = ParcelUuid(UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB"))
            val filters = listOf(ScanFilter.Builder().setServiceUuid(otaService).build())
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            val scanner = adapter.bluetoothLeScanner
                ?: return ScanStartResult("Bluetooth LE Scanner is not available.")
            scanner.startScan(filters, settings, scanCallback)
        } catch (_: SecurityException) {
            return ScanStartResult("Bluetooth permission was removed. Grant it and scan again.")
        }
        scanning = true
        refreshHandler.post(refreshConnectedDevices)
        return ScanStartResult("Scanning for advertising or connected GAMR devices...")
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        refreshHandler.removeCallbacks(refreshConnectedDevices)
        if (!scanning) return
        if (hasBluetoothPermissions()) {
            try {
                bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (_: SecurityException) {
                // Permission can be revoked while a scan is active.
            }
        }
        scanning = false
    }

    @SuppressLint("MissingPermission")
    fun close() {
        stop()
        if (hasBluetoothPermissions()) {
            try {
                hidProfile?.let { bluetoothManager.adapter?.closeProfileProxy(HID_DEVICE_PROFILE, it) }
            } catch (_: SecurityException) {
                // Permission can be revoked while the activity is closing.
            }
        }
        hidProfile = null
        profileRequested = false
    }

    @SuppressLint("MissingPermission")
    private fun addResult(result: ScanResult) {
        if (!hasBluetoothPermissions()) return
        val advertisedName = result.scanRecord?.deviceName ?: result.device.name ?: "Unnamed GAMR"

        advertisingDevices[result.device.address] = GamrDevice(
            name = advertisedName,
            address = result.device.address,
            rssi = result.rssi,
            source = GamrDeviceSource.ADVERTISING,
        )
        advertisingLastSeen[result.device.address] = SystemClock.elapsedRealtime()
        preferences.edit {
            putStringSet(
                KNOWN_GAMR_ADDRESSES,
                preferences.getStringSet(KNOWN_GAMR_ADDRESSES, emptySet()).orEmpty() + result.device.address,
            )
        }
        publishDevices()
    }

    @SuppressLint("MissingPermission")
    private fun connectedGamrDevices(): List<GamrDevice> {
        if (!hasBluetoothPermissions()) return emptyList()
        val knownAddresses = preferences.getStringSet(KNOWN_GAMR_ADDRESSES, emptySet()).orEmpty()
        val connectedDevices = (
            hidProfile?.connectedDevices.orEmpty() +
                bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
            ).distinctBy { it.address }

        return connectedDevices.mapNotNull { device ->
            val name = device.name ?: "Connected GAMR"
            if (device.address !in knownAddresses && !name.startsWith("GAMR-", ignoreCase = true)) {
                return@mapNotNull null
            }
            GamrDevice(
                name = name,
                address = device.address,
                source = GamrDeviceSource.CONNECTED,
            )
        }
    }

    private fun publishDevices() {
        val expiryTime = SystemClock.elapsedRealtime() - ADVERTISEMENT_EXPIRY_MS
        val expiredAddresses = advertisingLastSeen
            .filterValues { it < expiryTime }
            .keys
        expiredAddresses.forEach { address ->
            advertisingLastSeen.remove(address)
            advertisingDevices.remove(address)
        }

        val devices = advertisingDevices.toMutableMap()
        connectedGamrDevices().forEach { devices[it.address] = it }
        onDevicesChanged(devices.values.sortedWith(
            compareByDescending<GamrDevice> { it.source == GamrDeviceSource.CONNECTED }
                .thenByDescending { it.rssi ?: Int.MIN_VALUE },
        ))
    }

    private fun hasBluetoothPermissions(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_SCAN,
            ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    appContext,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ) == PackageManager.PERMISSION_GRANTED)

    private companion object {
        const val HID_DEVICE_PROFILE = 4
        const val CONNECTION_REFRESH_MS = 1000L
        const val ADVERTISEMENT_EXPIRY_MS = 5000L
        const val KNOWN_GAMR_ADDRESSES = "known_gamr_addresses"
    }
}
