package com.gamr.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.UUID

data class GamrDeviceInfo(
    val deviceId: String = "-",
    val firmwareVersion: String = "-",
    val batteryPercentage: Int? = null,
    val mode: GamrMode = GamrMode.GAMR,
    val touchThreshold: Int? = null,
    val customActions: List<GamrMatAction> = List(9) { GamrMatAction.DISABLED },
)

enum class GamrMode(val wireValue: Int, val label: String) {
    GAMR(0, "GAMR"),
    RHYTHM(1, "Rhythm"),
    BALANCE(2, "Balance"),
    CUSTOM(3, "Custom");

    companion object {
        fun fromWireValue(value: Int): GamrMode = entries.firstOrNull {
            it.wireValue == value
        } ?: GAMR
    }
}

enum class GamrMatAction(val wireValue: Int, val label: String) {
    DISABLED(0, "Disabled"),
    UP(1, "Up"), DOWN(2, "Down"), LEFT(3, "Left"), RIGHT(4, "Right"),
    UP_LEFT(5, "Up-left"), UP_RIGHT(6, "Up-right"),
    DOWN_LEFT(7, "Down-left"), DOWN_RIGHT(8, "Down-right"),
    A(9, "A"), B(10, "B"), X(11, "X"), Y(12, "Y"),
    L1(13, "L1"), R1(14, "R1"), L2(15, "L2"), R2(16, "R2"),
    L3(17, "L3"), R3(18, "R3");

    companion object {
        fun fromWireValue(value: Int): GamrMatAction = entries.firstOrNull {
            it.wireValue == value
        } ?: DISABLED
    }
}

class GamrBleClient(
    context: Context,
    private val onStatusChanged: (String) -> Unit,
    private val onDeviceInfoRead: (GamrDeviceInfo) -> Unit,
    private val onMatFrameChanged: (List<Int>) -> Unit,
) {
    private val appContext = context.applicationContext
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private var gatt: BluetoothGatt? = null
    private var deviceInfo = GamrDeviceInfo()
    private var pendingReads = emptyList<Pair<UUID, UUID>>()
    private var nextReadIndex = 0
    private var pendingMode: GamrMode? = null
    private var pendingThreshold: Int? = null
    private var pendingCustomAction: Pair<Int, GamrMatAction>? = null
    private var pendingCustomReset = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStatusChanged("Connection failed (GATT $status).")
                close()
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onStatusChanged("Connected. Reading GAMR details...")
                gatt.discoverServices()
            } else {
                onStatusChanged("Disconnected")
                close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStatusChanged("Could not discover GAMR services.")
                return
            }

            pendingReads = listOf(
                DEVICE_INFO_SERVICE_UUID to SERIAL_NUMBER_UUID,
                DEVICE_INFO_SERVICE_UUID to FIRMWARE_REVISION_UUID,
                BATTERY_SERVICE_UUID to BATTERY_LEVEL_UUID,
                CONTROL_SERVICE_UUID to CONTROL_CHARACTERISTIC_UUID,
                DEBUG_SERVICE_UUID to DEBUG_MAT_FRAME_UUID,
            )
            nextReadIndex = 0
            readNextCharacteristic()
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
            status: Int,
        ) {
            handleCharacteristicRead(characteristic.uuid, characteristic.value ?: byteArrayOf(), status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handleCharacteristicRead(characteristic.uuid, value, status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (characteristic.uuid != CONTROL_CHARACTERISTIC_UUID) return

            val mode = pendingMode
            val threshold = pendingThreshold
            val customAction = pendingCustomAction
            val customReset = pendingCustomReset
            if (mode == null && threshold == null && customAction == null && !customReset) return
            pendingMode = null
            pendingThreshold = null
            pendingCustomAction = null
            pendingCustomReset = false
            if (status == BluetoothGatt.GATT_SUCCESS) {
                deviceInfo = when {
                    mode != null -> deviceInfo.copy(mode = mode)
                    threshold != null -> deviceInfo.copy(touchThreshold = threshold)
                    customAction != null -> deviceInfo.copy(
                        customActions = deviceInfo.customActions.toMutableList().also {
                            it[customAction.first] = customAction.second
                        },
                    )
                    else -> deviceInfo.copy(customActions = List(CUSTOM_ZONE_COUNT) {
                        GamrMatAction.DISABLED
                    })
                }
                onDeviceInfoRead(deviceInfo)
                onStatusChanged("Connected")
            } else {
                onStatusChanged("Could not save configuration (GATT $status).")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
        ) {
            handleMatFrame(characteristic.uuid, characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleMatFrame(characteristic.uuid, value)
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: GamrDevice) {
        close()
        deviceInfo = GamrDeviceInfo()
        onDeviceInfoRead(deviceInfo)
        val adapter = bluetoothManager.adapter
        if (adapter == null) {
            onStatusChanged("Bluetooth is not available on this phone.")
            return
        }

        onStatusChanged("Connecting to ${device.name}...")
        gatt = adapter.getRemoteDevice(device.address)
            .connectGatt(appContext, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
    }

    @SuppressLint("MissingPermission")
    fun setMode(mode: GamrMode) {
        val currentGatt = gatt ?: return
        val characteristic = currentGatt.getService(CONTROL_SERVICE_UUID)
            ?.getCharacteristic(CONTROL_CHARACTERISTIC_UUID)
        if (characteristic == null) {
            onStatusChanged("GAMR control service is unavailable.")
            return
        }

        pendingMode = mode
        characteristic.writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = byteArrayOf(CONTROL_CMD_SET_MAT_MODE, mode.wireValue.toByte())
        if (!currentGatt.writeCharacteristic(characteristic)) {
            pendingMode = null
            onStatusChanged("Could not send the mode change.")
        } else {
            onStatusChanged("Setting ${mode.label} mode...")
        }
    }

    @SuppressLint("MissingPermission")
    fun setTouchThreshold(value: Int) {
        val currentGatt = gatt ?: return
        val characteristic = currentGatt.getService(CONTROL_SERVICE_UUID)
            ?.getCharacteristic(CONTROL_CHARACTERISTIC_UUID)
        if (characteristic == null) {
            onStatusChanged("GAMR control service is unavailable.")
            return
        }

        val threshold = value.coerceIn(MIN_TOUCH_THRESHOLD, MAX_TOUCH_THRESHOLD)
        pendingThreshold = threshold
        characteristic.writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = byteArrayOf(
            CONTROL_CMD_SET_MAT_THRESHOLD,
            (threshold and 0xFF).toByte(),
            ((threshold shr 8) and 0xFF).toByte(),
        )
        if (!currentGatt.writeCharacteristic(characteristic)) {
            pendingThreshold = null
            onStatusChanged("Could not send the sensitivity change.")
        } else {
            onStatusChanged("Saving sensitivity...")
        }
    }

    @SuppressLint("MissingPermission")
    fun setCustomAction(zone: Int, action: GamrMatAction) {
        if (zone !in 0 until CUSTOM_ZONE_COUNT) return
        val currentGatt = gatt ?: return
        val characteristic = currentGatt.getService(CONTROL_SERVICE_UUID)
            ?.getCharacteristic(CONTROL_CHARACTERISTIC_UUID) ?: return

        pendingCustomAction = zone to action
        characteristic.writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = byteArrayOf(
            CONTROL_CMD_SET_CUSTOM_ACTION,
            zone.toByte(),
            action.wireValue.toByte(),
        )
        if (!currentGatt.writeCharacteristic(characteristic)) {
            pendingCustomAction = null
            onStatusChanged("Could not save Custom mapping.")
        } else {
            onStatusChanged("Saving Custom mapping...")
        }
    }

    @SuppressLint("MissingPermission")
    fun resetCustomActions() {
        val currentGatt = gatt ?: return
        val characteristic = currentGatt.getService(CONTROL_SERVICE_UUID)
            ?.getCharacteristic(CONTROL_CHARACTERISTIC_UUID) ?: return

        pendingCustomReset = true
        characteristic.writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = byteArrayOf(CONTROL_CMD_RESET_CUSTOM_ACTIONS)
        if (!currentGatt.writeCharacteristic(characteristic)) {
            pendingCustomReset = false
            onStatusChanged("Could not reset Custom mapping.")
        } else {
            onStatusChanged("Resetting Custom mapping...")
        }
    }

    @SuppressLint("MissingPermission")
    private fun readNextCharacteristic() {
        val currentGatt = gatt ?: return
        if (nextReadIndex >= pendingReads.size) {
            onDeviceInfoRead(deviceInfo)
            startMatFrameNotifications()
            onStatusChanged("Connected")
            return
        }

        val (serviceUuid, characteristicUuid) = pendingReads[nextReadIndex]
        val characteristic = currentGatt.getService(serviceUuid)?.getCharacteristic(characteristicUuid)
        if (characteristic == null || !currentGatt.readCharacteristic(characteristic)) {
            nextReadIndex++
            readNextCharacteristic()
        }
    }

    private fun updateDeviceInfo(characteristicUuid: UUID, value: ByteArray) {
        when (characteristicUuid) {
            SERIAL_NUMBER_UUID -> deviceInfo = deviceInfo.copy(deviceId = value.decodeToString())
            FIRMWARE_REVISION_UUID -> deviceInfo = deviceInfo.copy(firmwareVersion = value.decodeToString())
            BATTERY_LEVEL_UUID -> deviceInfo = deviceInfo.copy(batteryPercentage = value.firstOrNull()?.toInt())
            CONTROL_CHARACTERISTIC_UUID -> {
                if (value.size >= 3) {
                    val threshold = (value[1].toInt() and 0xFF) or
                        ((value[2].toInt() and 0xFF) shl 8)
                    deviceInfo = deviceInfo.copy(
                        mode = GamrMode.fromWireValue(value[0].toInt() and 0xFF),
                        touchThreshold = threshold,
                        customActions = if (value.size >= 3 + CUSTOM_ZONE_COUNT) {
                            (0 until CUSTOM_ZONE_COUNT).map { index ->
                                GamrMatAction.fromWireValue(value[3 + index].toInt() and 0xFF)
                            }
                        } else deviceInfo.customActions,
                    )
                }
            }
            DEBUG_MAT_FRAME_UUID -> handleMatFrame(characteristicUuid, value)
        }
        onDeviceInfoRead(deviceInfo)
    }

    private fun handleCharacteristicRead(characteristicUuid: UUID, value: ByteArray, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            updateDeviceInfo(characteristicUuid, value)
        }
        nextReadIndex++
        readNextCharacteristic()
    }

    @SuppressLint("MissingPermission")
    private fun startMatFrameNotifications() {
        val currentGatt = gatt ?: return
        val characteristic = currentGatt.getService(DEBUG_SERVICE_UUID)
            ?.getCharacteristic(DEBUG_MAT_FRAME_UUID) ?: return
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID) ?: return

        if (!currentGatt.setCharacteristicNotification(characteristic, true)) {
            return
        }

        descriptor.value = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        currentGatt.writeDescriptor(descriptor)
    }

    private fun handleMatFrame(characteristicUuid: UUID, value: ByteArray) {
        if (characteristicUuid != DEBUG_MAT_FRAME_UUID || value.size < MAT_ROWS) {
            return
        }
        onMatFrameChanged(value.take(MAT_ROWS).map { it.toInt() and 0xFF })
    }

    private fun close() {
        gatt?.close()
        gatt = null
    }

    private companion object {
        val DEVICE_INFO_SERVICE_UUID: UUID = uuid16(0x180A)
        val BATTERY_SERVICE_UUID: UUID = uuid16(0x180F)
        val SERIAL_NUMBER_UUID: UUID = uuid16(0x2A25)
        val FIRMWARE_REVISION_UUID: UUID = uuid16(0x2A26)
        val BATTERY_LEVEL_UUID: UUID = uuid16(0x2A19)
        val CONTROL_SERVICE_UUID: UUID = uuid16(0xFFF5)
        val CONTROL_CHARACTERISTIC_UUID: UUID = uuid16(0xFFF6)
        val DEBUG_SERVICE_UUID: UUID = uuid16(0xFFF7)
        val DEBUG_MAT_FRAME_UUID: UUID = uuid16(0xFFF9)
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = uuid16(0x2902)
        const val CONTROL_CMD_SET_MAT_MODE: Byte = 0x03
        const val CONTROL_CMD_SET_MAT_THRESHOLD: Byte = 0x02
        const val CONTROL_CMD_SET_CUSTOM_ACTION: Byte = 0x04
        const val CONTROL_CMD_RESET_CUSTOM_ACTIONS: Byte = 0x05
        const val MIN_TOUCH_THRESHOLD = 50
        const val MAX_TOUCH_THRESHOLD = 4095
        const val MAT_ROWS = 5
        const val CUSTOM_ZONE_COUNT = 9

        fun uuid16(value: Int): UUID =
            UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(value))
    }
}
