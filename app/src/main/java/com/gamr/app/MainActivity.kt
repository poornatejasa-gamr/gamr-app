package com.gamr.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.gamr.app.ble.GamrBleClient
import com.gamr.app.ble.GamrBleScanner
import com.gamr.app.ble.GamrDevice
import com.gamr.app.ble.GamrDeviceInfo
import com.gamr.app.ble.GamrDeviceSource
import com.gamr.app.ble.GamrInputProfile
import com.gamr.app.ble.GamrMode
import com.gamr.app.ble.GamrMatAction
import com.gamr.app.ui.theme.GamrCyan
import com.gamr.app.ui.theme.GamrGreen
import com.gamr.app.ui.theme.GamrPurple
import com.gamr.app.ui.theme.GamrPurpleDark
import com.gamr.app.ui.theme.GamrTheme

class MainActivity : ComponentActivity() {
    private lateinit var scanner: GamrBleScanner
    private lateinit var bleClient: GamrBleClient
    private var scanStatus by mutableStateOf("Ready to search for GAMR.")
    private var connectionStatus by mutableStateOf("Not connected")
    private var connectedDevice by mutableStateOf<GamrDevice?>(null)
    private var deviceInfo by mutableStateOf(GamrDeviceInfo())
    private var discoveredDevices by mutableStateOf(emptyList<GamrDevice>())
    private var matRows by mutableStateOf(List(5) { 0 })
    private var hasScanned by mutableStateOf(false)
    private var activeBleSession = 0L
    private var consumeMatHidKeys = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions.values.all { it }) startGamrScan()
        else scanStatus = "Bluetooth permission is required to find GAMR."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        scanner = GamrBleScanner(this) { devices ->
            runOnUiThread { discoveredDevices = devices }
        }
        bleClient = GamrBleClient(
            context = this,
            onStatusChanged = { session, status ->
                runOnUiThread {
                    if (session != activeBleSession) return@runOnUiThread
                    if (status == "Disconnected" || status.startsWith("Connection failed")) {
                        returnToScan()
                        return@runOnUiThread
                    }
                    connectionStatus = status
                }
            },
            onDeviceInfoRead = { session, info ->
                runOnUiThread {
                    if (session == activeBleSession) deviceInfo = info
                }
            },
            onMatFrameChanged = { session, rows ->
                runOnUiThread {
                    if (session == activeBleSession) matRows = rows
                }
            },
        )

        setContent {
            GamrTheme {
                GamrHomeScreen(
                    scanStatus = scanStatus,
                    connectionStatus = connectionStatus,
                    connectedDevice = connectedDevice,
                    deviceInfo = deviceInfo,
                    matRows = matRows,
                    devices = discoveredDevices,
                    hasScanned = hasScanned,
                    onScanClick = ::requestScan,
                    onConnectClick = ::connectToDevice,
                    onDisconnectClick = ::disconnect,
                    onReturnToScan = ::returnToScan,
                    onModeSelected = bleClient::setMode,
                    onInputProfileSelected = bleClient::setInputProfile,
                    onSensitivitySelected = bleClient::setTouchThreshold,
                    onCustomActionSelected = bleClient::setCustomAction,
                    onCustomReset = bleClient::resetCustomActions,
                    onShutdownSecondsSelected = bleClient::setAutoShutdownSeconds,
                    onDeviceNameSelected = bleClient::setDeviceName,
                    onRestart = bleClient::restart,
                    onEraseUserData = bleClient::eraseUserData,
                    onFactoryReset = bleClient::factoryReset,
                    onPreviewVisibilityChanged = { consumeMatHidKeys = it },
                )
            }
        }
    }

    override fun onDestroy() {
        if (::scanner.isInitialized) scanner.stop()
        if (::bleClient.isInitialized) bleClient.disconnect()
        super.onDestroy()
    }

    private fun requestScan() {
        if (hasBluetoothPermissions()) startGamrScan()
        else permissionLauncher.launch(requiredBluetoothPermissions())
    }

    private fun startGamrScan() {
        hasScanned = true
        scanStatus = scanner.start().message
    }

    private fun returnToScan() {
        activeBleSession++
        consumeMatHidKeys = false
        connectedDevice = null
        discoveredDevices = emptyList()
        matRows = List(5) { 0 }
        hasScanned = false
        connectionStatus = "Not connected"
        scanStatus = "Ready to search for GAMR."
    }

    private fun connectToDevice(device: GamrDevice) {
        scanner.stop()
        consumeMatHidKeys = false
        connectedDevice = device
        deviceInfo = GamrDeviceInfo()
        matRows = List(5) { 0 }
        connectionStatus = "Connecting..."
        activeBleSession = bleClient.connect(device)
    }

    private fun disconnect() {
        bleClient.disconnect()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val gamepadSource = event.source and
            (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD)
        val keyboardSource = event.source and InputDevice.SOURCE_KEYBOARD
        val matKeyboardKey = isMatKeyboardKey(event.keyCode)

        if (consumeMatHidKeys && (gamepadSource != 0 ||
                (keyboardSource != 0 && matKeyboardKey))) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun hasBluetoothPermissions(): Boolean {
        return requiredBluetoothPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredBluetoothPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}

private fun isMatKeyboardKey(keyCode: Int): Boolean = when (keyCode) {
    KeyEvent.KEYCODE_ESCAPE,
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DEL,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_SPACE,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_TAB,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_ENTER -> true
    else -> false
}

@Composable
private fun GamrHomeScreen(
    scanStatus: String,
    connectionStatus: String,
    connectedDevice: GamrDevice?,
    deviceInfo: GamrDeviceInfo,
    matRows: List<Int>,
    devices: List<GamrDevice>,
    hasScanned: Boolean,
    onScanClick: () -> Unit,
    onConnectClick: (GamrDevice) -> Unit,
    onDisconnectClick: () -> Unit,
    onReturnToScan: () -> Unit,
    onModeSelected: (GamrMode) -> Unit,
    onInputProfileSelected: (GamrInputProfile) -> Unit,
    onSensitivitySelected: (Int) -> Unit,
    onCustomActionSelected: (Int, GamrMatAction) -> Unit,
    onCustomReset: () -> Unit,
    onShutdownSecondsSelected: (Int) -> Unit,
    onDeviceNameSelected: (String) -> Unit,
    onRestart: () -> Unit,
    onEraseUserData: () -> Unit,
    onFactoryReset: () -> Unit,
    onPreviewVisibilityChanged: (Boolean) -> Unit,
) {
    var connectedView by remember(connectedDevice?.address) { mutableStateOf(ConnectedView.DASHBOARD) }
    var previewMode by remember(connectedDevice?.address) { mutableStateOf(deviceInfo.mode) }

    BackHandler(enabled = connectedDevice != null) {
        when (connectedView) {
            ConnectedView.MODE_PREVIEW -> {
                connectedView = ConnectedView.CONFIGURATION
                onPreviewVisibilityChanged(false)
            }
            ConnectedView.CONFIGURATION -> connectedView = ConnectedView.DASHBOARD
            ConnectedView.DASHBOARD -> {
                onPreviewVisibilityChanged(false)
                onReturnToScan()
                onDisconnectClick()
            }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (connectedDevice != null) {
                when (connectedView) {
                    ConnectedView.DASHBOARD -> {
                        item { Spacer(Modifier.height(6.dp)) }
                        item { GamrHero(connectionStatus == "Connected") }
                        item {
                            ConnectedDashboard(
                                device = connectedDevice,
                                status = connectionStatus,
                                info = deviceInfo,
                                onConfigure = { connectedView = ConnectedView.CONFIGURATION },
                                onRestart = onRestart,
                                onEraseUserData = onEraseUserData,
                                onFactoryReset = onFactoryReset,
                                onReturnToScan = onReturnToScan,
                                onDisconnect = {
                                    onPreviewVisibilityChanged(false)
                                    onReturnToScan()
                                    onDisconnectClick()
                                },
                            )
                        }
                    }
                    ConnectedView.CONFIGURATION -> item {
                        ConfigurationPanel(
                            device = connectedDevice,
                            info = deviceInfo,
                            onBack = { connectedView = ConnectedView.DASHBOARD },
                            onModeSelected = { mode ->
                                previewMode = mode
                                onModeSelected(mode)
                                if (mode != GamrMode.CUSTOM) {
                                    connectedView = ConnectedView.MODE_PREVIEW
                                    onPreviewVisibilityChanged(true)
                                }
                            },
                            onInputProfileSelected = onInputProfileSelected,
                            onOpenModePreview = { mode ->
                                previewMode = mode
                                connectedView = ConnectedView.MODE_PREVIEW
                                onPreviewVisibilityChanged(true)
                            },
                            onSensitivitySelected = onSensitivitySelected,
                            onShutdownSecondsSelected = onShutdownSecondsSelected,
                            onDeviceNameSelected = onDeviceNameSelected,
                            onCustomActionSelected = onCustomActionSelected,
                            onCustomReset = onCustomReset,
                        )
                    }
                    ConnectedView.MODE_PREVIEW -> item {
                        ModePreviewPanel(
                            mode = previewMode,
                            info = deviceInfo,
                            matRows = matRows,
                            onBack = {
                                connectedView = ConnectedView.CONFIGURATION
                                onPreviewVisibilityChanged(false)
                            },
                        )
                    }
                }
            } else {
                item { Spacer(Modifier.height(6.dp)) }
                item { GamrHero(false) }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Discover GAMR", style = MaterialTheme.typography.titleLarge)
                            Text(scanStatus, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(
                            onClick = onScanClick,
                            colors = ButtonDefaults.buttonColors(containerColor = GamrPurple),
                        ) { Text("SCAN") }
                    }
                }
                if (hasScanned && devices.isEmpty()) {
                    item { EmptyDeviceCard() }
                } else if (hasScanned) {
                    items(devices, key = { it.address }) { device ->
                        DeviceCard(device = device, onConnectClick = { onConnectClick(device) })
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun GamrHero(connected: Boolean) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(GamrPurpleDark, GamrPurple, GamrCyan)))
            .padding(24.dp),
    ) {
        Column {
            Text("GAMR", style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onPrimary)
            Text("CONTROL HUB", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (connected) GamrGreen else MaterialTheme.colorScheme.onPrimary),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    if (connected) "MAT CONNECTED" else "READY TO CONNECT",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

private enum class ConnectedView { DASHBOARD, CONFIGURATION, MODE_PREVIEW }

@Composable
private fun ConnectedDashboard(
    device: GamrDevice,
    status: String,
    info: GamrDeviceInfo,
    onConfigure: () -> Unit,
    onRestart: () -> Unit,
    onEraseUserData: () -> Unit,
    onFactoryReset: () -> Unit,
    onReturnToScan: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var confirmation by remember { mutableStateOf<String?>(null) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, GamrGreen.copy(alpha = 0.65f)),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("CONNECTED MAT", style = MaterialTheme.typography.labelLarge, color = GamrGreen)
            Text(if (info.deviceName == "-") device.name else info.deviceName,
                style = MaterialTheme.typography.headlineSmall)
            Text(status, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            DeviceStat("DEVICE ID", info.deviceId, Modifier.fillMaxWidth())
            DeviceStat("BATTERY", info.batteryPercentage?.let { "$it%" } ?: "-", Modifier.fillMaxWidth())
            DeviceStat("FIRMWARE", info.firmwareVersion, Modifier.fillMaxWidth())
            DeviceStat("MODE", info.mode.label, Modifier.fillMaxWidth())
            DeviceStat("INPUT PROFILE", info.inputProfile.label, Modifier.fillMaxWidth())
            DeviceStat("SENSITIVITY", info.touchThreshold?.toString() ?: "-", Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Button(onClick = onConfigure, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = GamrPurple)) { Text("CONFIGURATION") }
            OutlinedButton(onClick = { confirmation = "Update" }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = GamrPurple,
                    contentColor = Color.Black,
                )) {
                Text("UPDATE")
            }
            OutlinedButton(onClick = { confirmation = "Restart" }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = GamrPurple,
                    contentColor = Color.Black,
                )) {
                Text("RESTART")
            }
            OutlinedButton(onClick = { confirmation = "Erase user data" }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = GamrPurple,
                    contentColor = Color.Black,
                )) {
                Text("ERASE USER DATA")
            }
            OutlinedButton(onClick = { confirmation = "Factory reset" }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = GamrPurple,
                    contentColor = Color.Black,
                )) {
                Text("FACTORY RESET")
            }
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = GamrPurple,
                    contentColor = Color.Black,
                )) { Text("DISCONNECT") }
        }
    }

    when (confirmation) {
        "Update" -> ActionDialog(
            title = "Firmware update",
            message = "The OTA file-picker and upload progress screen is the next app feature. Your current firmware version is ${info.firmwareVersion}.",
            confirmLabel = "OK",
            onConfirm = { confirmation = null },
            onDismiss = { confirmation = null },
        )
        "Restart" -> ActionDialog(
            title = "Restart MAT?",
            message = "The MAT disconnects briefly and then starts normally again.",
            confirmLabel = "Restart",
            onConfirm = { confirmation = null; onReturnToScan(); onRestart() },
            onDismiss = { confirmation = null },
        )
        "Erase user data" -> ActionDialog(
            title = "Erase Bluetooth user data?",
            message = "This removes BLE bonds and the custom device name. MAT mode and other settings stay unchanged.",
            confirmLabel = "Erase",
            onConfirm = { confirmation = null; onReturnToScan(); onEraseUserData() },
            onDismiss = { confirmation = null },
        )
        "Factory reset" -> ActionDialog(
            title = "Factory reset MAT?",
            message = "This clears Bluetooth bonds, the custom name, MAT mode, input profile, sensitivity, Custom mapping, and auto-shutdown setting. Firmware remains installed.",
            confirmLabel = "Reset",
            onConfirm = { confirmation = null; onReturnToScan(); onFactoryReset() },
            onDismiss = { confirmation = null },
        )
    }
}

@Composable
private fun ActionDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BackButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("←", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
            )
        }
    }
}

@Composable
private fun ConfigurationPanel(
    device: GamrDevice,
    info: GamrDeviceInfo,
    onBack: () -> Unit,
    onModeSelected: (GamrMode) -> Unit,
    onInputProfileSelected: (GamrInputProfile) -> Unit,
    onOpenModePreview: (GamrMode) -> Unit,
    onSensitivitySelected: (Int) -> Unit,
    onShutdownSecondsSelected: (Int) -> Unit,
    onDeviceNameSelected: (String) -> Unit,
    onCustomActionSelected: (Int, GamrMatAction) -> Unit,
    onCustomReset: () -> Unit,
) {
    var sensitivity by remember(info.touchThreshold) {
        mutableFloatStateOf((info.touchThreshold ?: 500).coerceIn(50, 1000).toFloat())
    }
    var timeout by remember(info.autoShutdownSeconds) { mutableFloatStateOf(info.autoShutdownSeconds.toFloat()) }
    var nameDraft by remember(info.deviceName, device.name) {
        mutableStateOf(if (info.deviceName == "-") device.name else info.deviceName)
    }
    var nameEdited by remember { mutableStateOf(false) }
    var selectedMode by remember(info.mode) { mutableStateOf(info.mode) }
    var selectedProfile by remember(info.inputProfile) { mutableStateOf(info.inputProfile) }
    val nameValid = nameDraft.trim().isNotEmpty() && nameDraft.trim().length <= 24 &&
        nameDraft.trim().all { it.code in 0x20..0x7E }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BackButton(label = "BACK TO DASHBOARD", onClick = onBack)
            Text("CONFIGURATION", style = MaterialTheme.typography.headlineSmall)
            Text("DEVICE NAME", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = nameDraft,
                onValueChange = { nameDraft = it; nameEdited = true },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                isError = nameEdited && !nameValid,
                supportingText = { Text(if (nameEdited && !nameValid)
                    "A name is required (1–24 standard characters)."
                else "Used by GAMR advertisements. Android may retain an old paired-device label; forget and pair again in Bluetooth Settings if it does.") },
            )
            OutlinedButton(onClick = { onDeviceNameSelected(nameDraft) },
                enabled = nameValid && nameDraft.trim() != info.deviceName,
                modifier = Modifier.fillMaxWidth()) { Text("SAVE DEVICE NAME") }
            Text("TOUCH SENSITIVITY · ${sensitivity.toInt()}", style = MaterialTheme.typography.labelLarge)
            Slider(value = sensitivity, onValueChange = { sensitivity = it },
                onValueChangeFinished = { onSensitivitySelected(sensitivity.toInt()) }, valueRange = 50f..1000f)
            Text("AUTO SHUTDOWN · ${formatShutdownTimeout(timeout.toInt())}", style = MaterialTheme.typography.labelLarge)
            Slider(value = timeout, onValueChange = { timeout = it },
                onValueChangeFinished = { onShutdownSecondsSelected(timeout.toInt()) },
                valueRange = 30f..3600f, steps = 118)
            Text("INPUT PROFILE", style = MaterialTheme.typography.labelLarge, color = GamrCyan)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GamrInputProfile.entries.forEach { profile ->
                    val selected = profile == selectedProfile
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            selectedProfile = profile
                            onInputProfileSelected(profile)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) GamrGreen else GamrPurple,
                        ),
                    ) { Text(profile.label) }
                }
            }
            Text("MAT MODES", style = MaterialTheme.typography.labelLarge, color = GamrCyan)
            GamrMode.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { mode ->
                        val selected = mode == selectedMode
                        Button(modifier = Modifier.weight(1f),
                            onClick = {
                                selectedMode = mode
                                onModeSelected(mode)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) GamrGreen else GamrPurple,
                            )) { Text(mode.label) }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (selectedMode == GamrMode.CUSTOM) {
                CustomMappingEditor(info.customActions, onCustomActionSelected, onCustomReset)
                OutlinedButton(onClick = { onOpenModePreview(GamrMode.CUSTOM) },
                    modifier = Modifier.fillMaxWidth()) { Text("VIEW LIVE CUSTOM MAT") }
            }
    }
}

private fun formatShutdownTimeout(seconds: Int): String {
    return if (seconds < 60) "$seconds SEC" else "${seconds / 60} MIN"
}

@Composable
private fun ModePreviewPanel(mode: GamrMode, info: GamrDeviceInfo, matRows: List<Int>, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BackButton(label = "BACK TO CONFIGURATION", onClick = onBack)
        Text("${mode.label} · LIVE MAT", style = MaterialTheme.typography.headlineSmall)
        Text("Press the physical MAT; active regions light up below.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (mode) {
            GamrMode.GAMR -> GamrMatPreview(matRows, info.inputProfile)
            GamrMode.RHYTHM -> RhythmMatPreview(matRows)
            GamrMode.BALANCE -> BalanceMatPreview(matRows)
            GamrMode.CUSTOM -> CustomMatPreview(info, matRows)
        }
    }
}

@Composable
private fun GamrMatPreview(rows: List<Int>, profile: GamrInputProfile) {
    val active = (0..8).map { zone -> zonePressed(zone, rows) }
    val outerZones = active.toMutableList().also { it[4] = false }
    val centerActive = (1..3).any { row -> rowPressed(rows, row, 2) }
    val l3Active = (1..3).any { row -> rowPressed(rows, row, 1) }
    val r3Active = (1..3).any { row -> rowPressed(rows, row, 3) }
    val keyboard = profile == GamrInputProfile.KEYBOARD
    val labels = if (keyboard) {
        listOf("ESC", "UP", "BKSP", "LEFT", "", "RIGHT", "TAB", "DOWN", "ENTER")
    } else {
        listOf("X", "UP", "A", "LEFT", "", "RIGHT", "Y", "DOWN", "B")
    }
    val pressed = if (keyboard) gamrKeyboardActions(rows) else gamrActions(rows)

    MatImagePreview {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            StandardMatOverlays(
                active = outerZones,
                labels = labels,
                tint = GamrPurple,
                maxWidth = maxWidth,
                maxHeight = maxHeight,
            )
            if (keyboard) {
                MatImageOverlayCell(centerActive, 0.45f, 0.40f, 0.10f, 0.25f,
                    maxWidth, maxHeight, GamrPurple, "SPACE")
            } else {
                MatImageOverlayCell(l3Active, 0.32f, 0.40f, 0.16f, 0.25f,
                    maxWidth, maxHeight, GamrPurple, "L3")
                MatImageOverlayCell(r3Active, 0.52f, 0.40f, 0.16f, 0.25f,
                    maxWidth, maxHeight, GamrPurple, "R3")
            }
        }
    }
    PressedActions(pressed)
}

@Composable
private fun RhythmMatPreview(rows: List<Int>) {
    val labels = listOf("↖", "↑", "↗", "←", "•", "→", "↙", "↓", "↘")
    val active = (0..8).map { zone -> zonePressed(zone, rows) }
    MatImagePreview {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            StandardMatOverlays(
                active, labels, Color(0xFFFF8A3D), maxWidth, maxHeight,
                emphasizeLabel = true,
            )
        }
    }
    PressedActions(labels.filterIndexed { index, _ -> active[index] })
}

@Composable
private fun CustomMatPreview(info: GamrDeviceInfo, rows: List<Int>) {
    val labels = info.customActions.map { it.label }
    val active = (0..8).map { zone -> zonePressed(zone, rows) }
    MatImagePreview {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            StandardMatOverlays(active, labels, GamrCyan, maxWidth, maxHeight)
        }
    }
    PressedActions(labels.filterIndexed { index, _ -> active[index] })
}

@Composable
private fun BalanceMatPreview(rows: List<Int>) {
    val labels = listOf("↖", "↑", "↗", "←", "•", "→", "↙", "↓", "↘")
    val active = List(9) { index ->
        val row = index / 3 + 1
        val column = index % 3 + 1
        rowPressed(rows, row, column)
    }
    MatImagePreview {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            repeat(3) { row ->
                repeat(3) { column ->
                    val index = row * 3 + column
                    MatImageOverlayCell(
                        active = active[index],
                        left = 0.34f + column * 0.11f,
                        top = 0.40f + row * 0.085f,
                        width = 0.10f,
                        height = 0.075f,
                        maxWidth = maxWidth,
                        maxHeight = maxHeight,
                        tint = GamrGreen,
                        label = labels[index],
                        emphasizeLabel = true,
                    )
                }
            }
        }
    }
    PressedActions(labels.filterIndexed { index, _ -> active[index] })
}

@Composable
private fun MatImagePreview(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(24.dp)),
    ) {
        Image(
            painter = painterResource(R.drawable.gamr_mat),
            contentDescription = "GAMR mat",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().alpha(0.58f),
        )
        content()
    }
}

@Composable
private fun StandardMatOverlays(
    active: List<Boolean>,
    labels: List<String>,
    tint: Color,
    maxWidth: androidx.compose.ui.unit.Dp,
    maxHeight: androidx.compose.ui.unit.Dp,
    emphasizeLabel: Boolean = false,
) {
    val regions = listOf(
        floatArrayOf(0.08f, 0.13f, 0.21f, 0.22f),
        floatArrayOf(0.31f, 0.13f, 0.38f, 0.22f),
        floatArrayOf(0.71f, 0.13f, 0.21f, 0.22f),
        floatArrayOf(0.08f, 0.37f, 0.21f, 0.30f),
        floatArrayOf(0.31f, 0.37f, 0.38f, 0.30f),
        floatArrayOf(0.71f, 0.37f, 0.21f, 0.30f),
        floatArrayOf(0.08f, 0.70f, 0.21f, 0.22f),
        floatArrayOf(0.31f, 0.70f, 0.38f, 0.22f),
        floatArrayOf(0.71f, 0.70f, 0.21f, 0.22f),
    )
    regions.forEachIndexed { index, region ->
        MatImageOverlayCell(
            active = active.getOrElse(index) { false },
            left = region[0], top = region[1], width = region[2], height = region[3],
            maxWidth = maxWidth, maxHeight = maxHeight, tint = tint,
            label = labels.getOrElse(index) { "" },
            emphasizeLabel = emphasizeLabel,
        )
    }
}

@Composable
private fun MatImageOverlayCell(
    active: Boolean,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    maxWidth: androidx.compose.ui.unit.Dp,
    maxHeight: androidx.compose.ui.unit.Dp,
    tint: Color,
    label: String,
    emphasizeLabel: Boolean = false,
) {
    Box(
        modifier = Modifier
            .offset(x = maxWidth * left, y = maxHeight * top)
            .size(width = maxWidth * width, height = maxHeight * height)
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) tint.copy(alpha = 0.60f) else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        if (active && label.isNotEmpty()) {
            Text(
                text = label,
                style = if (emphasizeLabel) MaterialTheme.typography.titleLarge
                    else MaterialTheme.typography.labelLarge,
                fontWeight = if (emphasizeLabel) FontWeight.Bold else FontWeight.Normal,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun PressedActions(actions: List<String>) {
    Text(
        if (actions.isEmpty()) "No input" else actions.joinToString(" · ") { "$it pressed" },
        color = GamrCyan,
    )
}

private fun zonePressed(zone: Int, rows: List<Int>): Boolean {
    fun cell(row: Int, column: Int) = rowPressed(rows, row, column)
    return when (zone) {
        0 -> cell(0, 0); 1 -> (1..3).any { cell(0, it) }; 2 -> cell(0, 4)
        3 -> (1..3).any { cell(it, 0) }; 4 -> (1..3).any { row -> (1..3).any { cell(row, it) } }
        5 -> (1..3).any { cell(it, 4) }; 6 -> cell(4, 0)
        7 -> (1..3).any { cell(4, it) }; else -> cell(4, 4)
    }
}

private fun rowPressed(rows: List<Int>, row: Int, column: Int): Boolean =
    row < rows.size && (rows[row] and (1 shl column)) != 0

private fun gamrActions(rows: List<Int>): List<String> {
    fun cell(row: Int, column: Int) = rowPressed(rows, row, column)
    return buildList {
        if (cell(0, 0)) add("X")
        if ((1..3).any { cell(0, it) }) add("Up")
        if (cell(0, 4)) add("A")
        if ((1..3).any { cell(it, 0) }) add("Left")
        if ((1..3).any { cell(it, 1) }) add("L3")
        if ((1..3).any { cell(it, 3) }) add("R3")
        if ((1..3).any { cell(it, 4) }) add("Right")
        if (cell(4, 0)) add("Y")
        if ((1..3).any { cell(4, it) }) add("Down")
        if (cell(4, 4)) add("B")
    }
}

private fun gamrKeyboardActions(rows: List<Int>): List<String> {
    fun cell(row: Int, column: Int) = rowPressed(rows, row, column)
    return buildList {
        if (cell(0, 0)) add("Esc")
        if ((1..3).any { cell(0, it) }) add("Up")
        if (cell(0, 4)) add("Backspace")
        if ((1..3).any { cell(it, 0) }) add("Left")
        if ((1..3).any { cell(it, 2) }) add("Space")
        if ((1..3).any { cell(it, 4) }) add("Right")
        if (cell(4, 0)) add("Tab")
        if ((1..3).any { cell(4, it) }) add("Down")
        if (cell(4, 4)) add("Enter")
    }
}

@Composable
private fun CustomMappingEditor(
    actions: List<GamrMatAction>,
    onActionSelected: (Int, GamrMatAction) -> Unit,
    onReset: () -> Unit,
) {
    val zones = listOf("Top left", "Top", "Top right", "Left", "Centre", "Right", "Bottom left", "Bottom", "Bottom right")
    var expandedZone by remember { mutableStateOf<Int?>(null) }

    Column {
        Text("CUSTOM BUTTON MAP", style = MaterialTheme.typography.labelLarge, color = GamrCyan)
        Spacer(Modifier.height(4.dp))
        Text("Tap a zone to choose its gamepad action.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        repeat(3) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { column ->
                    val zone = row * 3 + column
                    val action = actions.getOrElse(zone) { GamrMatAction.DISABLED }
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { expandedZone = zone },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 64.dp),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = zones[zone],
                                    style = MaterialTheme.typography.labelSmall,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = action.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = expandedZone == zone,
                            onDismissRequest = { expandedZone = null },
                        ) {
                            GamrMatAction.entries.forEach { choice ->
                                DropdownMenuItem(
                                    text = { Text(choice.label) },
                                    onClick = {
                                        expandedZone = null
                                        onActionSelected(zone, choice)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (row < 2) Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text("Reset Custom map")
        }
    }
}

@Composable
private fun DeveloperMatGrid(rows: List<Int>) {
    Column {
        Text("DEVELOPER · LIVE 5×5 MAP", style = MaterialTheme.typography.labelLarge,
            color = GamrCyan)
        Spacer(Modifier.height(8.dp))
        repeat(5) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(5) { column ->
                    val pressed = row < rows.size && (rows[row] and (1 shl column)) != 0
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (pressed) GamrGreen else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${row + 1}${column + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (pressed) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (row < 4) Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun DeviceStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(3.dp))
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun DeviceCard(device: GamrDevice, onConnectClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(GamrPurple.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("G", style = MaterialTheme.typography.titleLarge, color = GamrPurple)
            }
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Text(device.address, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (device.source == GamrDeviceSource.ADVERTISING) {
                        "${device.rssi} dBm"
                    } else {
                        "PAIRED ON THIS PHONE"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = GamrCyan,
                )
            }
            Button(onClick = onConnectClick) { Text("CONNECT") }
        }
    }
}

@Composable
private fun EmptyDeviceCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("No GAMR found", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Turn on the mat and ensure it is advertising, then scan again.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
