package com.gamr.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

data class GamrDeviceInfo(
    val deviceName: String = "-",
    val deviceId: String = "-",
    val firmwareVersion: String = "-",
    val batteryPercentage: Int? = null,
    val mode: GamrMode = GamrMode.GAMR,
    val touchThreshold: Int? = null,
    val customActions: List<GamrMatAction> = List(9) { GamrMatAction.DISABLED },
    val autoShutdownSeconds: Int = 15 * 60,
    val inputProfile: GamrInputProfile = GamrInputProfile.GAMEPAD,
)

enum class GamrInputProfile(val wireValue: Int, val label: String) {
    GAMEPAD(0, "Gamepad"),
    KEYBOARD(1, "Keyboard");

    companion object {
        fun fromWireValue(value: Int): GamrInputProfile = entries.firstOrNull {
            it.wireValue == value
        } ?: GAMEPAD
    }
}

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
    L3(17, "L3"), R3(18, "R3"),
    CENTER(19, "Enter"),
    KEY_W(20, "W"), KEY_A(21, "A key"), KEY_S(22, "S"), KEY_D(23, "D"),
    KEY_Q(24, "Q"), KEY_E(25, "E"), KEY_R(26, "R key"), KEY_F(27, "F"),
    KEY_1(28, "1"), KEY_2(29, "2"), KEY_3(30, "3"), KEY_4(31, "4"),
    KEY_LEFT_SHIFT(32, "Left Shift"), KEY_LEFT_CONTROL(33, "Left Ctrl");

    fun labelFor(profile: GamrInputProfile): String = if (profile == GamrInputProfile.GAMEPAD) {
        label
    } else {
        when (this) {
            UP -> "Up Arrow"
            DOWN -> "Down Arrow"
            LEFT -> "Left Arrow"
            RIGHT -> "Right Arrow"
            UP_LEFT -> "Up + Left"
            UP_RIGHT -> "Up + Right"
            DOWN_LEFT -> "Down + Left"
            DOWN_RIGHT -> "Down + Right"
            A -> "Backspace"
            B -> "Space"
            X -> "Escape"
            Y -> "Tab"
            else -> label
        }
    }

    fun supports(profile: GamrInputProfile): Boolean = when (profile) {
        GamrInputProfile.GAMEPAD -> wireValue <= R3.wireValue
        GamrInputProfile.KEYBOARD -> this !in listOf(L1, R1, L2, R2, L3, R3)
    }

    companion object {
        fun fromWireValue(value: Int): GamrMatAction = entries.firstOrNull {
            it.wireValue == value
        } ?: DISABLED
    }
}

class GamrBleClient(
    context: Context,
    private val onStatusChanged: (Long, String) -> Unit,
    private val onDeviceInfoRead: (Long, GamrDeviceInfo) -> Unit,
    private val onMatFrameChanged: (Long, List<Int>) -> Unit,
) {
    private val appContext = context.applicationContext
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var activeSession = 0L
    private var lastDevice: GamrDevice? = null
    private var reconnectAttempts = 0
    private var connectionReady = false
    private var manualDisconnect = false
    private var deviceInfo = GamrDeviceInfo()
    private var pendingReads = emptyList<Pair<UUID, UUID>>()
    private var nextReadIndex = 0
    private var pendingMode: GamrMode? = null
    private var pendingThreshold: Int? = null
    private var pendingCustomAction: Pair<Int, GamrMatAction>? = null
    private var pendingCustomReset = false
    private var pendingShutdownSeconds: Int? = null
    private var pendingInputProfile: GamrInputProfile? = null
    private var pendingDeviceName: String? = null
    private var pendingSystemAction: String? = null
    private var readRetryCount = 0

    private fun reportStatus(message: String) {
        onStatusChanged(activeSession, message)
    }

    private fun reportDeviceInfo(info: GamrDeviceInfo) {
        onDeviceInfoRead(activeSession, info)
    }

    private fun reportMatFrame(rows: List<Int>) {
        onMatFrameChanged(activeSession, rows)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== this@GamrBleClient.gatt) {
                gatt.close()
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                retryInitialConnection("GATT $status")
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                reportStatus("Connected. Reading GAMR details...")
                reconnectHandler.postDelayed({
                    if (gatt === this@GamrBleClient.gatt) {
                        if (!gatt.discoverServices()) {
                            retryInitialConnection("Could not start service discovery")
                        }
                    }
                }, SERVICE_DISCOVERY_DELAY_MS)
            } else if (manualDisconnect || connectionReady) {
                reportStatus("Disconnected")
                close()
            } else {
                retryInitialConnection("Disconnected during setup")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== this@GamrBleClient.gatt) return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                retryInitialConnection("Service discovery failed")
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
            readRetryCount = 0
            reconnectHandler.postDelayed({
                if (gatt === this@GamrBleClient.gatt) {
                    readNextCharacteristic()
                }
            }, SERVICE_SETTLE_DELAY_MS)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (gatt !== this@GamrBleClient.gatt) return
            handleCharacteristicRead(gatt, characteristic.uuid, characteristic.value ?: byteArrayOf(), status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (gatt !== this@GamrBleClient.gatt) return
            handleCharacteristicRead(gatt, characteristic.uuid, value, status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (gatt !== this@GamrBleClient.gatt) return
            if (characteristic.uuid != CONTROL_CHARACTERISTIC_UUID) return

            val mode = pendingMode
            val threshold = pendingThreshold
            val customAction = pendingCustomAction
            val customReset = pendingCustomReset
            val shutdownSeconds = pendingShutdownSeconds
            val inputProfile = pendingInputProfile
            val deviceName = pendingDeviceName
            val systemAction = pendingSystemAction
            if (mode == null && threshold == null && customAction == null && !customReset &&
                shutdownSeconds == null && inputProfile == null && deviceName == null &&
                systemAction == null) return
            pendingMode = null
            pendingThreshold = null
            pendingCustomAction = null
            pendingCustomReset = false
            pendingShutdownSeconds = null
            pendingInputProfile = null
            pendingDeviceName = null
            pendingSystemAction = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (systemAction != null) {
                    reportStatus("${systemAction} requested. MAT is restarting...")
                    close()
                    return
                }
                deviceInfo = when {
                    mode != null -> deviceInfo.copy(mode = mode)
                    threshold != null -> deviceInfo.copy(touchThreshold = threshold)
                    customAction != null -> deviceInfo.copy(
                        customActions = deviceInfo.customActions.toMutableList().also {
                            it[customAction.first] = customAction.second
                        },
                    )
                    shutdownSeconds != null -> deviceInfo.copy(autoShutdownSeconds = shutdownSeconds)
                    inputProfile != null -> deviceInfo.copy(inputProfile = inputProfile)
                    deviceName != null -> deviceInfo.copy(deviceName = deviceName)
                    else -> deviceInfo.copy(customActions = List(CUSTOM_ZONE_COUNT) {
                        GamrMatAction.DISABLED
                    })
                }
                reportDeviceInfo(deviceInfo)
                reportStatus("Connected")
            } else {
                reportStatus("Could not save configuration (GATT $status).")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
        ) {
            if (gatt !== this@GamrBleClient.gatt) return
            handleMatFrame(characteristic.uuid, characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (gatt !== this@GamrBleClient.gatt) return
            handleMatFrame(characteristic.uuid, value)
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: GamrDevice): Long {
        activeSession++
        close()
        reconnectHandler.removeCallbacksAndMessages(null)
        lastDevice = device
        reconnectAttempts = 0
        connectionReady = false
        manualDisconnect = false
        readRetryCount = 0
        deviceInfo = GamrDeviceInfo()
        reportDeviceInfo(deviceInfo)
        val adapter = bluetoothManager.adapter
        if (adapter == null) {
            reportStatus("Bluetooth is not available on this phone.")
            return activeSession
        }

        openGatt(device, "Connecting to ${device.name}...")
        return activeSession
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        manualDisconnect = true
        reconnectHandler.removeCallbacksAndMessages(null)
        gatt?.disconnect()
    }

    @SuppressLint("MissingPermission")
    fun setMode(mode: GamrMode) {
        val currentGatt = gatt ?: return
        val characteristic = currentGatt.getService(CONTROL_SERVICE_UUID)
            ?.getCharacteristic(CONTROL_CHARACTERISTIC_UUID)
        if (characteristic == null) {
            reportStatus("GAMR control service is unavailable.")
            return
        }

        pendingMode = mode
        characteristic.writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = byteArrayOf(CONTROL_CMD_SET_MAT_MODE, mode.wireValue.toByte())
        if (!currentGatt.writeCharacteristic(characteristic)) {
            pendingMode = null
            reportStatus("Could not send the mode change.")
        } else {
            reportStatus("Setting ${mode.label} mode...")
        }
    }

    @SuppressLint("MissingPermission")
    fun setTouchThreshold(value: Int) {
        val currentGatt = gatt ?: return
        val characteristic = currentGatt.getService(CONTROL_SERVICE_UUID)
            ?.getCharacteristic(CONTROL_CHARACTERISTIC_UUID)
        if (characteristic == null) {
            reportStatus("GAMR control service is unavailable.")
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
            reportStatus("Could not send the sensitivity change.")
        } else {
            reportStatus("Saving sensitivity...")
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
            reportStatus("Could not save Custom mapping.")
        } else {
            reportStatus("Saving Custom mapping...")
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
            reportStatus("Could not reset Custom mapping.")
        } else {
            reportStatus("Resetting Custom mapping...")
        }
    }

    @SuppressLint("MissingPermission")
    fun setAutoShutdownSeconds(value: Int) {
        val currentGatt = gatt ?: return
        val characteristic = currentGatt.getService(CONTROL_SERVICE_UUID)
            ?.getCharacteristic(CONTROL_CHARACTERISTIC_UUID) ?: return

        val seconds = value.coerceIn(MIN_SHUTDOWN_SECONDS, MAX_SHUTDOWN_SECONDS)
        pendingShutdownSeconds = seconds
        characteristic.writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = byteArrayOf(
            CONTROL_CMD_SET_SHUTDOWN_SECONDS,
            (seconds and 0xFF).toByte(),
            ((seconds shr 8) and 0xFF).toByte(),
        )
        if (!currentGatt.writeCharacteristic(characteristic)) {
            pendingShutdownSeconds = null
            reportStatus("Could not save auto shutdown.")
        } else {
            reportStatus("Saving auto shutdown...")
        }
    }

    @SuppressLint("MissingPermission")
    fun setInputProfile(profile: GamrInputProfile) {
        val currentGatt = gatt ?: return
        val characteristic = currentGatt.getService(CONTROL_SERVICE_UUID)
            ?.getCharacteristic(CONTROL_CHARACTERISTIC_UUID) ?: return

        pendingInputProfile = profile
        characteristic.writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = byteArrayOf(
            CONTROL_CMD_SET_INPUT_PROFILE,
            profile.wireValue.toByte(),
        )
        if (!currentGatt.writeCharacteristic(characteristic)) {
            pendingInputProfile = null
            reportStatus("Could not change the input profile.")
        } else {
            reportStatus("Setting ${profile.label} profile...")
        }
    }

    @SuppressLint("MissingPermission")
    fun setDeviceName(value: String) {
        val name = value.trim()
        if (!isValidDeviceName(name)) {
            reportStatus("Enter a device name using 1–24 standard characters.")
            return
        }

        val currentGatt = gatt ?: return
        val characteristic = currentGatt.getService(CONTROL_SERVICE_UUID)
            ?.getCharacteristic(CONTROL_CHARACTERISTIC_UUID) ?: return

        pendingDeviceName = name
        characteristic.writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = byteArrayOf(CONTROL_CMD_SET_DEVICE_NAME) + name.encodeToByteArray()
        if (!currentGatt.writeCharacteristic(characteristic)) {
            pendingDeviceName = null
            reportStatus("Could not save device name.")
        } else {
            reportStatus("Saving device name...")
        }
    }

    fun restart() = sendSystemAction(CONTROL_CMD_RESTART, "Restart")

    fun eraseUserData() = sendSystemAction(CONTROL_CMD_ERASE_USER_DATA, "User-data erase")

    fun factoryReset() = sendSystemAction(CONTROL_CMD_FACTORY_RESET, "Factory reset")

    @SuppressLint("MissingPermission")
    private fun sendSystemAction(command: Byte, label: String) {
        val currentGatt = gatt ?: return
        val characteristic = currentGatt.getService(CONTROL_SERVICE_UUID)
            ?.getCharacteristic(CONTROL_CHARACTERISTIC_UUID) ?: return

        pendingSystemAction = label
        characteristic.writeType = android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = byteArrayOf(command)
        if (!currentGatt.writeCharacteristic(characteristic)) {
            pendingSystemAction = null
            reportStatus("Could not request $label.")
        } else {
            reportStatus("Requesting $label...")
        }
    }

    @SuppressLint("MissingPermission")
    private fun readNextCharacteristic() {
        val currentGatt = gatt ?: return
        if (nextReadIndex >= pendingReads.size) {
            reportDeviceInfo(deviceInfo)
            startMatFrameNotifications()
            connectionReady = true
            reportStatus("Connected")
            return
        }

        val (serviceUuid, characteristicUuid) = pendingReads[nextReadIndex]
        val characteristic = currentGatt.getService(serviceUuid)?.getCharacteristic(characteristicUuid)
        if (characteristic == null) {
            nextReadIndex++
            readNextCharacteristic()
            return
        }

        if (isEncryptedCharacteristic(characteristicUuid) &&
            currentGatt.device.bondState == BluetoothDevice.BOND_NONE &&
            currentGatt.device.createBond()) {
            reportStatus("Pairing with GAMR…")
            retryCurrentRead(currentGatt)
            return
        }

        if (!currentGatt.readCharacteristic(characteristic)) {
            retryCurrentRead(currentGatt)
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
                        autoShutdownSeconds = if (value.size >= 4 + CUSTOM_ZONE_COUNT) {
                            val legacyMinutes = value[3 + CUSTOM_ZONE_COUNT].toInt() and 0xFF
                            val nameLengthOffset = 4 + CUSTOM_ZONE_COUNT
                            val nameLength = value.getOrNull(nameLengthOffset)?.toInt()?.and(0xFF) ?: 0
                            val secondsOffset = nameLengthOffset + 1 + nameLength
                            if (value.size >= secondsOffset + 2) {
                                (value[secondsOffset].toInt() and 0xFF) or
                                    ((value[secondsOffset + 1].toInt() and 0xFF) shl 8)
                            } else {
                                legacyMinutes * 60
                            }
                        } else deviceInfo.autoShutdownSeconds,
                        inputProfile = run {
                            val nameLengthOffset = 4 + CUSTOM_ZONE_COUNT
                            val nameLength = value.getOrNull(nameLengthOffset)?.toInt()?.and(0xFF) ?: 0
                            val profileOffset = nameLengthOffset + 1 + nameLength + 2
                            value.getOrNull(profileOffset)?.let {
                                GamrInputProfile.fromWireValue(it.toInt() and 0xFF)
                            } ?: deviceInfo.inputProfile
                        },
                        deviceName = if (value.size >= 4 + CUSTOM_ZONE_COUNT + 1) {
                            val nameLength = value[4 + CUSTOM_ZONE_COUNT].toInt() and 0xFF
                            val nameStart = 5 + CUSTOM_ZONE_COUNT
                            if (nameLength > 0 && value.size >= nameStart + nameLength) {
                                value.copyOfRange(nameStart, nameStart + nameLength).decodeToString()
                            } else deviceInfo.deviceName
                        } else deviceInfo.deviceName,
                    )
                }
            }
            DEBUG_MAT_FRAME_UUID -> handleMatFrame(characteristicUuid, value)
        }
        reportDeviceInfo(deviceInfo)
    }

    private fun handleCharacteristicRead(
        sourceGatt: BluetoothGatt,
        characteristicUuid: UUID,
        value: ByteArray,
        status: Int,
    ) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            if (isEncryptedCharacteristic(characteristicUuid)) {
                reportStatus("Securing GAMR connection…")
            }
            retryCurrentRead(sourceGatt)
            return
        }
        readRetryCount = 0
        updateDeviceInfo(characteristicUuid, value)
        nextReadIndex++
        readNextCharacteristic()
    }

    @SuppressLint("MissingPermission")
    private fun retryCurrentRead(sourceGatt: BluetoothGatt) {
        if (sourceGatt !== gatt) return
        if (readRetryCount >= MAX_READ_RETRIES) {
            reportStatus("Connected (some data is unavailable)")
            readRetryCount = 0
            nextReadIndex++
            readNextCharacteristic()
            return
        }

        readRetryCount++
        reconnectHandler.postDelayed({
            if (sourceGatt === gatt) {
                readNextCharacteristic()
            }
        }, READ_RETRY_DELAY_MS)
    }

    private fun isEncryptedCharacteristic(characteristicUuid: UUID): Boolean =
        characteristicUuid == CONTROL_CHARACTERISTIC_UUID ||
            characteristicUuid == DEBUG_MAT_FRAME_UUID

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
        reportMatFrame(value.take(MAT_ROWS).map { it.toInt() and 0xFF })
    }

    @SuppressLint("MissingPermission")
    private fun retryInitialConnection(reason: String) {
        val device = lastDevice
        if (manualDisconnect || connectionReady || device == null || reconnectAttempts >= MAX_CONNECTION_RETRIES) {
            reportStatus("Connection failed ($reason).")
            close()
            return
        }

        reconnectAttempts++
        close()
        reportStatus("Refreshing BLE connection…")
        reconnectHandler.postDelayed({
            if (!manualDisconnect && gatt == null) {
                openGatt(device, "Reconnecting to ${device.name}...")
            }
        }, RECONNECT_DELAY_MS)
    }

    @SuppressLint("MissingPermission")
    private fun openGatt(device: GamrDevice, status: String) {
        val adapter = bluetoothManager.adapter
        if (adapter == null) {
            reportStatus("Bluetooth is not available on this phone.")
            return
        }
        reportStatus(status)
        gatt = adapter.getRemoteDevice(device.address)
            .connectGatt(appContext, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun close() {
        val oldGatt = gatt ?: return
        gatt = null
        oldGatt.disconnect()
        oldGatt.close()
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
        const val CONTROL_CMD_SET_SHUTDOWN_SECONDS: Byte = 0x0B
        const val CONTROL_CMD_SET_INPUT_PROFILE: Byte = 0x0C
        const val CONTROL_CMD_SET_DEVICE_NAME: Byte = 0x07
        const val CONTROL_CMD_RESTART: Byte = 0x08
        const val CONTROL_CMD_ERASE_USER_DATA: Byte = 0x09
        const val CONTROL_CMD_FACTORY_RESET: Byte = 0x0A
        const val MIN_TOUCH_THRESHOLD = 50
        const val MAX_TOUCH_THRESHOLD = 1000
        const val MAT_ROWS = 5
        const val CUSTOM_ZONE_COUNT = 9
        const val MIN_SHUTDOWN_SECONDS = 30
        const val MAX_SHUTDOWN_SECONDS = 60 * 60
        const val MAX_DEVICE_NAME_LENGTH = 24
        const val SERVICE_DISCOVERY_DELAY_MS = 600L
        const val SERVICE_SETTLE_DELAY_MS = 250L
        const val READ_RETRY_DELAY_MS = 600L
        const val MAX_READ_RETRIES = 5
        const val MAX_CONNECTION_RETRIES = 3
        const val RECONNECT_DELAY_MS = 1200L

        fun isValidDeviceName(name: String): Boolean =
            name.isNotBlank() && name.length <= MAX_DEVICE_NAME_LENGTH &&
                name.all { it.code in 0x20..0x7E }

        fun uuid16(value: Int): UUID =
            UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(value))
    }
}
