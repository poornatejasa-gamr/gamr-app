package com.gamr.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32

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

data class GamrOtaProgress(
    val fileName: String = "",
    val progress: Int = 0,
    val message: String = "",
    val active: Boolean = false,
    val completed: Boolean = false,
    val failed: Boolean = false,
)

private enum class OtaPhase {
    PREPARING,
    STARTING,
    SENDING,
    FINISHING,
    REBOOTING,
}

private enum class OtaStartFormat {
    SHA256,
    CRC32_SHA256,
    CRC32_ONLY,
}

private class OtaSession(
    val fileName: String,
    val image: ByteArray,
    val sha256: ByteArray,
    var phase: OtaPhase = OtaPhase.PREPARING,
    var offset: Int = 0,
    var pendingChunkSize: Int = 0,
    var lastProgress: Int = -1,
    var startFormat: OtaStartFormat = OtaStartFormat.SHA256,
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
    private val onOtaProgressChanged: (Long, GamrOtaProgress) -> Unit,
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
    private var pairingInProgress = false
    private var pairingDeadlineMs = 0L
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
    private var negotiatedMtu = DEFAULT_ATT_MTU
    private var otaSession: OtaSession? = null
    private var otaTimeoutToken = 0

    private fun reportStatus(message: String) {
        onStatusChanged(activeSession, message)
    }

    private fun reportDeviceInfo(info: GamrDeviceInfo) {
        onDeviceInfoRead(activeSession, info)
    }

    private fun reportMatFrame(rows: List<Int>) {
        onMatFrameChanged(activeSession, rows)
    }

    private fun reportOta(progress: GamrOtaProgress) {
        onOtaProgressChanged(activeSession, progress)
    }

    @SuppressLint("MissingPermission")
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== this@GamrBleClient.gatt) {
                closeGatt(gatt, disconnect = false)
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED &&
                otaSession?.phase == OtaPhase.REBOOTING) {
                finishOtaSuccessfully()
                close()
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (otaSession != null) {
                    failOta("Firmware update connection failed (GATT $status).")
                    return
                }
                retryInitialConnection("GATT $status")
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (gatt.device.bondState == BluetoothDevice.BOND_NONE) {
                    startPairing(gatt)
                } else {
                    requestEnabledProfileConnection(gatt.device)
                    beginServiceDiscovery(gatt)
                }
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

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (gatt !== this@GamrBleClient.gatt) return
            handleCharacteristicRead(gatt, characteristic.uuid, characteristic.value ?: byteArrayOf(), status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (gatt !== this@GamrBleClient.gatt) return
            handleCharacteristicRead(gatt, characteristic.uuid, value, status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (gatt !== this@GamrBleClient.gatt) return
            if (characteristic.uuid == OTA_CONTROL_UUID) {
                handleOtaControlWrite(status)
                return
            }
            if (characteristic.uuid == OTA_DATA_UUID) {
                handleOtaDataWrite(status)
                return
            }
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
                    reportStatus("$systemAction requested. MAT is restarting...")
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

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (gatt !== this@GamrBleClient.gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
            }
            if (otaSession?.phase == OtaPhase.PREPARING) {
                sendOtaStart()
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (gatt !== this@GamrBleClient.gatt) return
            handleMatFrame(characteristic.uuid, characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (gatt !== this@GamrBleClient.gatt) return
            handleMatFrame(characteristic.uuid, value)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (gatt !== this@GamrBleClient.gatt ||
                descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG_UUID ||
                descriptor.characteristic.uuid != DEBUG_MAT_FRAME_UUID) {
                return
            }

            connectionReady = true
            reportStatus(if (status == BluetoothGatt.GATT_SUCCESS) {
                "Connected"
            } else {
                "Connected (live input unavailable)"
            })
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
        pairingInProgress = false
        pairingDeadlineMs = 0L
        readRetryCount = 0
        negotiatedMtu = DEFAULT_ATT_MTU
        otaSession = null
        cancelOtaTimeout()
        deviceInfo = GamrDeviceInfo()
        reportDeviceInfo(deviceInfo)
        if (!hasBluetoothConnectPermission()) {
            reportStatus("Bluetooth connection permission is required.")
            return activeSession
        }
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
        if (otaSession != null) failOta("Firmware update cancelled.")
        manualDisconnect = true
        reconnectHandler.removeCallbacksAndMessages(null)
        val currentGatt = gatt ?: return
        if (!hasBluetoothConnectPermission()) {
            close()
            return
        }
        try {
            currentGatt.disconnect()
        } catch (_: SecurityException) {
            close()
        }
    }

    @SuppressLint("MissingPermission")
    fun startFirmwareUpdate(fileName: String, image: ByteArray) {
        if (!hasBluetoothConnectPermission()) {
            reportOta(GamrOtaProgress(
                fileName = fileName,
                message = "Bluetooth connection permission is required.",
                failed = true,
            ))
            return
        }
        val currentGatt = gatt
        if (currentGatt == null || !connectionReady) {
            reportOta(GamrOtaProgress(
                fileName = fileName,
                message = "Connect to GAMR before starting the update.",
                failed = true,
            ))
            return
        }
        if (otaSession != null) return
        if (image.isEmpty() || image.size > MAX_OTA_IMAGE_SIZE ||
            (image[0].toInt() and 0xFF) != ESP_IMAGE_MAGIC) {
            reportOta(GamrOtaProgress(
                fileName = fileName,
                message = "Select a valid GAMR firmware .bin file (maximum 2 MiB).",
                failed = true,
            ))
            return
        }
        if (currentGatt.getService(OTA_SERVICE_UUID) == null) {
            reportOta(GamrOtaProgress(
                fileName = fileName,
                message = "This firmware does not expose the OTA service.",
                failed = true,
            ))
            return
        }

        otaSession = OtaSession(
            fileName = fileName,
            image = image,
            sha256 = MessageDigest.getInstance("SHA-256").digest(image),
        )
        reportOta(GamrOtaProgress(
            fileName = fileName,
            message = "Preparing secure BLE transfer…",
            active = true,
        ))

        val mtuRequested = try {
            currentGatt.requestMtu(OTA_REQUESTED_MTU)
        } catch (_: SecurityException) {
            false
        }
        if (!mtuRequested) {
            sendOtaStart()
            return
        }
        reconnectHandler.postDelayed({
            if (otaSession?.phase == OtaPhase.PREPARING) sendOtaStart()
        }, MTU_REQUEST_TIMEOUT_MS)
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
        if (!writeCharacteristic(
                currentGatt,
                characteristic,
                byteArrayOf(CONTROL_CMD_SET_MAT_MODE, mode.wireValue.toByte()),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )) {
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
        if (!writeCharacteristic(
                currentGatt,
                characteristic,
                byteArrayOf(
                    CONTROL_CMD_SET_MAT_THRESHOLD,
                    (threshold and 0xFF).toByte(),
                    ((threshold shr 8) and 0xFF).toByte(),
                ),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )) {
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
        if (!writeCharacteristic(
                currentGatt,
                characteristic,
                byteArrayOf(
                    CONTROL_CMD_SET_CUSTOM_ACTION,
                    zone.toByte(),
                    action.wireValue.toByte(),
                ),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )) {
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
        if (!writeCharacteristic(
                currentGatt,
                characteristic,
                byteArrayOf(CONTROL_CMD_RESET_CUSTOM_ACTIONS),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )) {
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
        if (!writeCharacteristic(
                currentGatt,
                characteristic,
                byteArrayOf(
                    CONTROL_CMD_SET_SHUTDOWN_SECONDS,
                    (seconds and 0xFF).toByte(),
                    ((seconds shr 8) and 0xFF).toByte(),
                ),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )) {
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
        if (!writeCharacteristic(
                currentGatt,
                characteristic,
                byteArrayOf(CONTROL_CMD_SET_INPUT_PROFILE, profile.wireValue.toByte()),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )) {
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
        if (!writeCharacteristic(
                currentGatt,
                characteristic,
                byteArrayOf(CONTROL_CMD_SET_DEVICE_NAME) + name.encodeToByteArray(),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )) {
            pendingDeviceName = null
            reportStatus("Could not save device name.")
        } else {
            reportStatus("Saving device name...")
        }
    }

    fun restart() = sendSystemAction(CONTROL_CMD_RESTART, "Restart")

    fun resetUserConfiguration() =
        sendSystemAction(CONTROL_CMD_RESET_USER_CONFIGURATION, "Reset User Config")

    fun factoryReset() = sendSystemAction(CONTROL_CMD_FACTORY_RESET, "Factory reset")

    @SuppressLint("MissingPermission")
    private fun sendSystemAction(command: Byte, label: String) {
        val currentGatt = gatt ?: return
        val characteristic = currentGatt.getService(CONTROL_SERVICE_UUID)
            ?.getCharacteristic(CONTROL_CHARACTERISTIC_UUID) ?: return

        pendingSystemAction = label
        if (!writeCharacteristic(
                currentGatt,
                characteristic,
                byteArrayOf(command),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )) {
            pendingSystemAction = null
            reportStatus("Could not request $label.")
        } else {
            reportStatus("Requesting $label...")
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendOtaStart(format: OtaStartFormat = OtaStartFormat.SHA256) {
        val session = otaSession ?: return
        if (session.phase != OtaPhase.PREPARING) return
        val currentGatt = gatt ?: return failOta("GAMR disconnected before OTA started.")
        val characteristic = currentGatt.getService(OTA_SERVICE_UUID)
            ?.getCharacteristic(OTA_CONTROL_UUID)
            ?: return failOta("OTA control characteristic is unavailable.")

        val packet = when (format) {
            OtaStartFormat.SHA256 -> ByteBuffer.allocate(OTA_START_PACKET_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(OTA_CMD_START)
                .putInt(session.image.size)
                .put(session.sha256)
                .array()
            OtaStartFormat.CRC32_SHA256 -> ByteBuffer.allocate(LEGACY_OTA_START_PACKET_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(OTA_CMD_START)
                .putInt(session.image.size)
                .putInt(calculateCrc32(session.image))
                .put(session.sha256)
                .array()
            OtaStartFormat.CRC32_ONLY -> ByteBuffer.allocate(CRC32_OTA_START_PACKET_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(OTA_CMD_START)
                .putInt(session.image.size)
                .putInt(calculateCrc32(session.image))
                .array()
        }

        session.startFormat = format
        session.phase = OtaPhase.STARTING
        if (!writeCharacteristic(
                currentGatt,
                characteristic,
                packet,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )) {
            failOta("Could not send the OTA START packet.")
            return
        }
        armOtaTimeout("The MAT did not accept the OTA START packet.")
    }

    private fun handleOtaControlWrite(status: Int) {
        val session = otaSession ?: return
        cancelOtaTimeout()

        if (status != BluetoothGatt.GATT_SUCCESS) {
            val nextFormat = if (session.phase == OtaPhase.STARTING &&
                status == GATT_INVALID_ATTRIBUTE_LENGTH) {
                when (session.startFormat) {
                    OtaStartFormat.SHA256 -> OtaStartFormat.CRC32_SHA256
                    OtaStartFormat.CRC32_SHA256 -> OtaStartFormat.CRC32_ONLY
                    OtaStartFormat.CRC32_ONLY -> null
                }
            } else null
            if (nextFormat != null) {
                session.phase = OtaPhase.PREPARING
                reportOta(GamrOtaProgress(
                    fileName = session.fileName,
                    message = if (nextFormat == OtaStartFormat.CRC32_ONLY) {
                        "Migrating CRC32-only firmware…"
                    } else {
                        "Migrating the previous OTA protocol…"
                    },
                    active = true,
                ))
                sendOtaStart(nextFormat)
                return
            }
            failOta("OTA command failed (GATT $status).")
            return
        }

        when (session.phase) {
            OtaPhase.STARTING -> {
                session.phase = OtaPhase.SENDING
                reportOta(GamrOtaProgress(
                    fileName = session.fileName,
                    message = "Uploading firmware…",
                    active = true,
                ))
                writeNextOtaChunk()
            }
            OtaPhase.FINISHING -> {
                reportOta(GamrOtaProgress(
                    fileName = session.fileName,
                    progress = 100,
                    message = "Firmware verified. Restarting GAMR…",
                    active = true,
                ))
                writeOtaCommand(OTA_CMD_REBOOT, OtaPhase.REBOOTING)
            }
            OtaPhase.REBOOTING -> {
                reconnectHandler.postDelayed({
                    if (otaSession?.phase == OtaPhase.REBOOTING) {
                        finishOtaSuccessfully()
                        close()
                    }
                }, OTA_REBOOT_TIMEOUT_MS)
            }
            else -> Unit
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeNextOtaChunk() {
        val session = otaSession ?: return
        if (session.phase != OtaPhase.SENDING) return
        if (session.offset >= session.image.size) {
            writeOtaCommand(OTA_CMD_END, OtaPhase.FINISHING)
            return
        }

        val currentGatt = gatt ?: return failOta("GAMR disconnected during upload.")
        val characteristic = currentGatt.getService(OTA_SERVICE_UUID)
            ?.getCharacteristic(OTA_DATA_UUID)
            ?: return failOta("OTA data characteristic is unavailable.")
        val payloadSize = (negotiatedMtu - ATT_HEADER_SIZE)
            .coerceIn(MIN_OTA_CHUNK_SIZE, MAX_OTA_CHUNK_SIZE)
        val end = (session.offset + payloadSize).coerceAtMost(session.image.size)
        val chunk = session.image.copyOfRange(session.offset, end)
        session.pendingChunkSize = chunk.size

        if (!writeCharacteristic(
                currentGatt,
                characteristic,
                chunk,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            )) {
            failOta("Android could not queue the next firmware chunk.")
            return
        }
        armOtaTimeout("Firmware transfer stopped responding.")
    }

    private fun handleOtaDataWrite(status: Int) {
        val session = otaSession ?: return
        if (session.phase != OtaPhase.SENDING) return
        cancelOtaTimeout()
        if (status != BluetoothGatt.GATT_SUCCESS) {
            failOta("Firmware data write failed (GATT $status).")
            return
        }

        session.offset += session.pendingChunkSize
        session.pendingChunkSize = 0
        val progress = ((session.offset.toLong() * 100L) / session.image.size).toInt()
        if (progress != session.lastProgress) {
            session.lastProgress = progress
            reportOta(GamrOtaProgress(
                fileName = session.fileName,
                progress = progress,
                message = "Uploading firmware…",
                active = true,
            ))
        }
        writeNextOtaChunk()
    }

    @SuppressLint("MissingPermission")
    private fun writeOtaCommand(command: Byte, phase: OtaPhase) {
        val session = otaSession ?: return
        val currentGatt = gatt ?: return failOta("GAMR disconnected during verification.")
        val characteristic = currentGatt.getService(OTA_SERVICE_UUID)
            ?.getCharacteristic(OTA_CONTROL_UUID)
            ?: return failOta("OTA control characteristic is unavailable.")

        session.phase = phase
        if (!writeCharacteristic(
                currentGatt,
                characteristic,
                byteArrayOf(command),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )) {
            failOta("Could not send the OTA command.")
            return
        }
        armOtaTimeout("The MAT did not finish the OTA command.")
    }

    private fun armOtaTimeout(message: String) {
        val token = ++otaTimeoutToken
        reconnectHandler.postDelayed({
            if (token == otaTimeoutToken && otaSession != null) failOta(message)
        }, OTA_OPERATION_TIMEOUT_MS)
    }

    private fun cancelOtaTimeout() {
        otaTimeoutToken++
    }

    private fun calculateCrc32(image: ByteArray): Int =
        CRC32().apply { update(image) }.value.toInt()

    private fun failOta(message: String) {
        val session = otaSession ?: return
        cancelOtaTimeout()
        abortOtaOnDevice()
        otaSession = null
        reportOta(GamrOtaProgress(
            fileName = session.fileName,
            progress = session.lastProgress.coerceAtLeast(0),
            message = message,
            failed = true,
        ))
    }

    @SuppressLint("MissingPermission")
    private fun abortOtaOnDevice() {
        val currentGatt = gatt ?: return
        val characteristic = currentGatt.getService(OTA_SERVICE_UUID)
            ?.getCharacteristic(OTA_CONTROL_UUID) ?: return
        writeCharacteristic(
            currentGatt,
            characteristic,
            byteArrayOf(OTA_CMD_ABORT),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        )
    }

    private fun finishOtaSuccessfully() {
        val session = otaSession ?: return
        cancelOtaTimeout()
        otaSession = null
        reportOta(GamrOtaProgress(
            fileName = session.fileName,
            progress = 100,
            message = "Firmware installed successfully. GAMR is restarting.",
            completed = true,
        ))
    }

    @SuppressLint("MissingPermission")
    private fun readNextCharacteristic() {
        if (!hasBluetoothConnectPermission()) {
            reportStatus("Bluetooth connection permission was removed.")
            close()
            return
        }
        val currentGatt = gatt ?: return
        if (nextReadIndex >= pendingReads.size) {
            reportDeviceInfo(deviceInfo)
            if (startMatFrameNotifications()) {
                reportStatus("Enabling live input…")
            } else {
                connectionReady = true
                reportStatus("Connected (live input unavailable)")
            }
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
            currentGatt.device.bondState != BluetoothDevice.BOND_BONDED) {
            startPairing(currentGatt)
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
    private fun startPairing(target: BluetoothGatt) {
        if (target !== gatt || pairingInProgress) return

        pairingInProgress = true
        pairingDeadlineMs = SystemClock.elapsedRealtime() + PAIRING_TIMEOUT_MS
        reportStatus("Pairing with GAMR…")
        if (!target.device.createBond()) {
            pairingInProgress = false
            reportStatus("Could not start Bluetooth pairing.")
            close()
            return
        }
        waitForBond(target)
    }

    private fun waitForBond(target: BluetoothGatt) {
        reconnectHandler.postDelayed({
            if (target !== gatt || !pairingInProgress) return@postDelayed

            if (target.device.bondState == BluetoothDevice.BOND_BONDED) {
                pairingInProgress = false
                requestEnabledProfileConnection(target.device)
                beginServiceDiscovery(target)
            } else if (SystemClock.elapsedRealtime() >= pairingDeadlineMs) {
                pairingInProgress = false
                reportStatus("Pairing failed. Forget GAMR in Bluetooth Settings and try again.")
                close()
            } else {
                waitForBond(target)
            }
        }, PAIRING_POLL_DELAY_MS)
    }

    @SuppressLint("MissingPermission")
    private fun beginServiceDiscovery(target: BluetoothGatt) {
        if (target !== gatt) return
        reportStatus("Connected. Reading GAMR details...")
        reconnectHandler.postDelayed({
            if (target !== gatt) return@postDelayed
            if (!hasBluetoothConnectPermission()) {
                reportStatus("Bluetooth permission was removed.")
                close()
            } else if (!target.discoverServices()) {
                retryInitialConnection("Could not start service discovery")
            }
        }, SERVICE_DISCOVERY_DELAY_MS)
    }

    /**
     * Ask Android to restore every user-enabled profile (including HID) for a
     * bonded GAMR. This is separate from this companion's GATT connection.
     * Android exposed this public API in API 37; older releases intentionally
     * keep HID profile connection under system control, so GATT remains the
     * supported companion connection there.
     */
    @SuppressLint("MissingPermission")
    private fun requestEnabledProfileConnection(device: BluetoothDevice) {
        if (Build.VERSION.SDK_INT < 37 || !hasBluetoothConnectPermission()) return
        try {
            device.connect()
        } catch (_: SecurityException) {
            // The GATT session can still continue if profile connection is denied.
        }
    }

    @SuppressLint("MissingPermission")
    private fun startMatFrameNotifications(): Boolean {
        if (!hasBluetoothConnectPermission()) return false
        val currentGatt = gatt ?: return false
        val characteristic = currentGatt.getService(DEBUG_SERVICE_UUID)
            ?.getCharacteristic(DEBUG_MAT_FRAME_UUID) ?: return false
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID) ?: return false

        if (!currentGatt.setCharacteristicNotification(characteristic, true)) {
            return false
        }

        return writeDescriptor(
            currentGatt,
            descriptor,
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
        )
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
        if (!hasBluetoothConnectPermission()) {
            reportStatus("Bluetooth connection permission is required.")
            return
        }
        val adapter = bluetoothManager.adapter
        if (adapter == null) {
            reportStatus("Bluetooth is not available on this phone.")
            return
        }
        reportStatus(status)
        gatt = try {
            val remoteDevice = adapter.getRemoteDevice(device.address)
            if (Build.VERSION.SDK_INT >= 37) {
                val settings = BluetoothGattConnectionSettings.Builder()
                    .setAutoConnectEnabled(false)
                    .setAutomaticMtuEnabled(false)
                    .setTransport(BluetoothDevice.TRANSPORT_LE)
                    .build()
                remoteDevice.connectGatt(settings, appContext.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                remoteDevice.connectGatt(
                    appContext,
                    false,
                    callback,
                    BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M_MASK,
                    reconnectHandler,
                )
            }
        } catch (_: SecurityException) {
            reportStatus("Bluetooth connection permission was removed.")
            null
        }
    }

    private fun close() {
        val oldGatt = gatt ?: return
        pairingInProgress = false
        pairingDeadlineMs = 0L
        gatt = null
        closeGatt(oldGatt)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt(target: BluetoothGatt, disconnect: Boolean = true) {
        if (!hasBluetoothConnectPermission()) return
        try {
            if (disconnect) target.disconnect()
            target.close()
        } catch (_: SecurityException) {
            // Permission can be revoked while a BLE callback is in flight.
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(
        target: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
    ): Boolean {
        if (!hasBluetoothConnectPermission()) {
            reportStatus("Bluetooth connection permission was removed.")
            return false
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                target.writeCharacteristic(characteristic, value, writeType) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = writeType
                @Suppress("DEPRECATION")
                characteristic.value = value
                @Suppress("DEPRECATION")
                target.writeCharacteristic(characteristic)
            }
        } catch (_: SecurityException) {
            reportStatus("Bluetooth connection permission was removed.")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptor(
        target: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean {
        if (!hasBluetoothConnectPermission()) {
            reportStatus("Bluetooth connection permission was removed.")
            return false
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                target.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                target.writeDescriptor(descriptor)
            }
        } catch (_: SecurityException) {
            reportStatus("Bluetooth connection permission was removed.")
            false
        }
    }

    private companion object {
        val DEVICE_INFO_SERVICE_UUID: UUID = uuid16(0x180A)
        val BATTERY_SERVICE_UUID: UUID = uuid16(0x180F)
        val SERIAL_NUMBER_UUID: UUID = uuid16(0x2A25)
        val FIRMWARE_REVISION_UUID: UUID = uuid16(0x2A26)
        val BATTERY_LEVEL_UUID: UUID = uuid16(0x2A19)
        val CONTROL_SERVICE_UUID: UUID = uuid16(0xFFF5)
        val CONTROL_CHARACTERISTIC_UUID: UUID = uuid16(0xFFF6)
        val OTA_SERVICE_UUID: UUID = uuid16(0xFFF0)
        val OTA_CONTROL_UUID: UUID = uuid16(0xFFF1)
        val OTA_DATA_UUID: UUID = uuid16(0xFFF2)
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
        const val CONTROL_CMD_RESET_USER_CONFIGURATION: Byte = 0x09
        const val CONTROL_CMD_FACTORY_RESET: Byte = 0x0A
        const val OTA_CMD_START: Byte = 0x01
        const val OTA_CMD_END: Byte = 0x02
        const val OTA_CMD_ABORT: Byte = 0x03
        const val OTA_CMD_REBOOT: Byte = 0x04
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
        const val PAIRING_POLL_DELAY_MS = 250L
        const val PAIRING_TIMEOUT_MS = 15_000L
        const val ESP_IMAGE_MAGIC = 0xE9
        const val MAX_OTA_IMAGE_SIZE = 0x200000
        const val OTA_START_PACKET_SIZE = 37
        const val LEGACY_OTA_START_PACKET_SIZE = 41
        const val CRC32_OTA_START_PACKET_SIZE = 9
        const val DEFAULT_ATT_MTU = 23
        const val ATT_HEADER_SIZE = 3
        const val MIN_OTA_CHUNK_SIZE = 20
        const val MAX_OTA_CHUNK_SIZE = 512
        const val OTA_REQUESTED_MTU = 512
        const val MTU_REQUEST_TIMEOUT_MS = 1200L
        const val OTA_OPERATION_TIMEOUT_MS = 15_000L
        const val OTA_REBOOT_TIMEOUT_MS = 4_000L
        const val GATT_INVALID_ATTRIBUTE_LENGTH = 13

        fun isValidDeviceName(name: String): Boolean =
            name.isNotBlank() && name.length <= MAX_DEVICE_NAME_LENGTH &&
                name.all { it.code in 0x20..0x7E }

        fun uuid16(value: Int): UUID =
            UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(value))
    }
}
