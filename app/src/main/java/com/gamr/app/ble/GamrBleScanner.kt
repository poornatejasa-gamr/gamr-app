package com.gamr.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import java.util.UUID

data class GamrDevice(
    val name: String,
    val address: String,
    val rssi: Int,
)

data class ScanStartResult(val message: String)

class GamrBleScanner(
    context: Context,
    private val onDevicesChanged: (List<GamrDevice>) -> Unit,
) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val devices = linkedMapOf<String, GamrDevice>()
    private var scanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addResult(result)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): ScanStartResult {
        val adapter = bluetoothManager.adapter ?: return ScanStartResult("Bluetooth is not available on this phone.")
        if (!adapter.isEnabled) return ScanStartResult("Turn on Bluetooth, then scan again.")

        stop()
        devices.clear()
        onDevicesChanged(emptyList())

        val otaService = ParcelUuid(UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB"))
        val filters = listOf(ScanFilter.Builder().setServiceUuid(otaService).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val scanner = adapter.bluetoothLeScanner ?: return ScanStartResult("Bluetooth LE Scanner is not available.")
        scanner.startScan(filters, settings, scanCallback)
        scanning = true
        return ScanStartResult("Scanning for nearby GAMR devices...")
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning) return
        bluetoothManager.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        scanning = false
    }

    @SuppressLint("MissingPermission")
    private fun addResult(result: ScanResult) {
        val advertisedName = result.scanRecord?.deviceName ?: result.device.name ?: "Unnamed GAMR"

        devices[result.device.address] = GamrDevice(
            name = advertisedName,
            address = result.device.address,
            rssi = result.rssi,
        )
        onDevicesChanged(devices.values.sortedByDescending { it.rssi })
    }

    private companion object {
    }
}
