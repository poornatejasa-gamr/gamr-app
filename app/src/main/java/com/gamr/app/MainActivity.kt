package com.gamr.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.gamr.app.ble.GamrBleClient
import com.gamr.app.ble.GamrBleScanner
import com.gamr.app.ble.GamrDevice
import com.gamr.app.ble.GamrDeviceInfo
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
            onStatusChanged = { status ->
                runOnUiThread {
                    connectionStatus = status
                    if (status == "Disconnected" || status.startsWith("Connection failed")) {
                        connectedDevice = null
                        discoveredDevices = emptyList()
                        hasScanned = false
                    }
                }
            },
            onDeviceInfoRead = { info -> runOnUiThread { deviceInfo = info } },
            onMatFrameChanged = { rows -> runOnUiThread { matRows = rows } },
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
                    onSensitivitySelected = bleClient::setTouchThreshold,
                    onCustomActionSelected = bleClient::setCustomAction,
                    onCustomReset = bleClient::resetCustomActions,
                    onShutdownMinutesSelected = bleClient::setAutoShutdownMinutes,
                    onDeviceNameSelected = bleClient::setDeviceName,
                    onRestart = bleClient::restart,
                    onEraseUserData = bleClient::eraseUserData,
                    onFactoryReset = bleClient::factoryReset,
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
        connectedDevice = null
        discoveredDevices = emptyList()
        matRows = List(5) { 0 }
        hasScanned = false
        connectionStatus = "Not connected"
        scanStatus = "Ready to search for GAMR."
    }

    private fun connectToDevice(device: GamrDevice) {
        scanner.stop()
        connectedDevice = device
        connectionStatus = "Connecting..."
        bleClient.connect(device)
    }

    private fun disconnect() {
        bleClient.disconnect()
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
    onSensitivitySelected: (Int) -> Unit,
    onCustomActionSelected: (Int, GamrMatAction) -> Unit,
    onCustomReset: () -> Unit,
    onShutdownMinutesSelected: (Int) -> Unit,
    onDeviceNameSelected: (String) -> Unit,
    onRestart: () -> Unit,
    onEraseUserData: () -> Unit,
    onFactoryReset: () -> Unit,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Spacer(Modifier.height(6.dp)) }
            item { GamrHero(connectedDevice != null && connectionStatus == "Connected") }

            if (connectedDevice != null) {
                item {
                    ConnectedDeviceCard(
                        device = connectedDevice,
                        status = connectionStatus,
                        info = deviceInfo,
                        matRows = matRows,
                        onDisconnectClick = onDisconnectClick,
                        onReturnToScan = onReturnToScan,
                        onModeSelected = onModeSelected,
                        onSensitivitySelected = onSensitivitySelected,
                        onCustomActionSelected = onCustomActionSelected,
                        onCustomReset = onCustomReset,
                        onShutdownMinutesSelected = onShutdownMinutesSelected,
                        onDeviceNameSelected = onDeviceNameSelected,
                        onRestart = onRestart,
                        onEraseUserData = onEraseUserData,
                        onFactoryReset = onFactoryReset,
                    )
                }
            } else {
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

@Composable
private fun ConnectedDeviceCard(
    device: GamrDevice,
    status: String,
    info: GamrDeviceInfo,
    matRows: List<Int>,
    onDisconnectClick: () -> Unit,
    onReturnToScan: () -> Unit,
    onModeSelected: (GamrMode) -> Unit,
    onSensitivitySelected: (Int) -> Unit,
    onCustomActionSelected: (Int, GamrMatAction) -> Unit,
    onCustomReset: () -> Unit,
    onShutdownMinutesSelected: (Int) -> Unit,
    onDeviceNameSelected: (String) -> Unit,
    onRestart: () -> Unit,
    onEraseUserData: () -> Unit,
    onFactoryReset: () -> Unit,
) {
    var view by remember(device.address) { mutableStateOf(ConnectedView.DASHBOARD) }
    var previewMode by remember(device.address) { mutableStateOf(info.mode) }

    when (view) {
        ConnectedView.DASHBOARD -> ConnectedDashboard(
            device = device,
            status = status,
            info = info,
            onConfigure = { view = ConnectedView.CONFIGURATION },
            onRestart = onRestart,
            onEraseUserData = onEraseUserData,
            onFactoryReset = onFactoryReset,
            onReturnToScan = onReturnToScan,
            onDisconnect = {
                onReturnToScan()
                onDisconnectClick()
            },
        )
        ConnectedView.CONFIGURATION -> ConfigurationPanel(
            device = device,
            info = info,
            onBack = { view = ConnectedView.DASHBOARD },
            onModeSelected = {
                previewMode = it
                onModeSelected(it)
                if (it != GamrMode.CUSTOM) {
                    view = ConnectedView.MODE_PREVIEW
                }
            },
            onOpenModePreview = {
                previewMode = it
                view = ConnectedView.MODE_PREVIEW
            },
            onSensitivitySelected = onSensitivitySelected,
            onShutdownMinutesSelected = onShutdownMinutesSelected,
            onDeviceNameSelected = onDeviceNameSelected,
            onCustomActionSelected = onCustomActionSelected,
            onCustomReset = onCustomReset,
        )
        ConnectedView.MODE_PREVIEW -> ModePreviewPanel(
            mode = previewMode,
            info = info,
            matRows = matRows,
            onBack = { view = ConnectedView.CONFIGURATION },
        )
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
            DeviceStat("SENSITIVITY", info.touchThreshold?.toString() ?: "-", Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Button(onClick = onConfigure, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = GamrPurple)) { Text("CONFIGURATION") }
            OutlinedButton(onClick = { confirmation = "Update" }, modifier = Modifier.fillMaxWidth()) {
                Text("UPDATE")
            }
            OutlinedButton(onClick = { confirmation = "Restart" }, modifier = Modifier.fillMaxWidth()) {
                Text("RESTART")
            }
            OutlinedButton(onClick = { confirmation = "Erase user data" }, modifier = Modifier.fillMaxWidth()) {
                Text("ERASE USER DATA")
            }
            OutlinedButton(onClick = { confirmation = "Factory reset" }, modifier = Modifier.fillMaxWidth()) {
                Text("FACTORY RESET")
            }
            OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("DISCONNECT") }
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
            message = "This clears Bluetooth bonds, the custom name, MAT mode, sensitivity, Custom mapping, and auto-shutdown setting. Firmware remains installed.",
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
private fun ConfigurationPanel(
    device: GamrDevice,
    info: GamrDeviceInfo,
    onBack: () -> Unit,
    onModeSelected: (GamrMode) -> Unit,
    onOpenModePreview: (GamrMode) -> Unit,
    onSensitivitySelected: (Int) -> Unit,
    onShutdownMinutesSelected: (Int) -> Unit,
    onDeviceNameSelected: (String) -> Unit,
    onCustomActionSelected: (Int, GamrMatAction) -> Unit,
    onCustomReset: () -> Unit,
) {
    var sensitivity by remember(info.touchThreshold) { mutableFloatStateOf((info.touchThreshold ?: 500).toFloat()) }
    var timeout by remember(info.autoShutdownMinutes) { mutableFloatStateOf(info.autoShutdownMinutes.toFloat()) }
    var nameDraft by remember(info.deviceName, device.name) {
        mutableStateOf(if (info.deviceName == "-") device.name else info.deviceName)
    }
    var nameEdited by remember { mutableStateOf(false) }
    var selectedMode by remember(info.mode) { mutableStateOf(info.mode) }
    val nameValid = nameDraft.trim().isNotEmpty() && nameDraft.trim().length <= 24 &&
        nameDraft.trim().all { it.code in 0x20..0x7E }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onBack) { Text("← BACK") }
            Text("CONFIGURATION", style = MaterialTheme.typography.headlineSmall)
            Text("DEVICE NAME", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = nameDraft,
                onValueChange = { nameDraft = it; nameEdited = true },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                isError = nameEdited && !nameValid,
                supportingText = { Text(if (nameEdited && !nameValid)
                    "A name is required (1–24 standard characters)."
                else "Used for the next BLE advertisement.") },
            )
            OutlinedButton(onClick = { onDeviceNameSelected(nameDraft) },
                enabled = nameValid && nameDraft.trim() != info.deviceName,
                modifier = Modifier.fillMaxWidth()) { Text("SAVE DEVICE NAME") }
            Text("TOUCH SENSITIVITY · ${sensitivity.toInt()}", style = MaterialTheme.typography.labelLarge)
            Slider(value = sensitivity, onValueChange = { sensitivity = it },
                onValueChangeFinished = { onSensitivitySelected(sensitivity.toInt()) }, valueRange = 50f..4095f)
            Text("AUTO SHUTDOWN · ${timeout.toInt()} MIN", style = MaterialTheme.typography.labelLarge)
            Slider(value = timeout, onValueChange = { timeout = it },
                onValueChangeFinished = { onShutdownMinutesSelected(timeout.toInt()) },
                valueRange = 5f..60f, steps = 54)
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
}

@Composable
private fun ModePreviewPanel(mode: GamrMode, info: GamrDeviceInfo, matRows: List<Int>, onBack: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onBack) { Text("← CONFIGURATION") }
            Text("${mode.label} · LIVE MAT", style = MaterialTheme.typography.headlineSmall)
            Text("Press the physical MAT; active regions light up below.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (mode) {
                GamrMode.GAMR -> GamrMatPreview(matRows)
                GamrMode.BALANCE -> BalancePreview(matRows)
                else -> ZonePreview(mode, info, matRows)
            }
        }
    }
}

@Composable
private fun GamrMatPreview(rows: List<Int>) {
    val active = (0..8).map { zone -> zonePressed(zone, rows) }
    val l3Active = (1..3).any { row -> rowPressed(rows, row, 1) }
    val r3Active = (1..3).any { row -> rowPressed(rows, row, 3) }
    val pressed = gamrActions(rows)

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
            modifier = Modifier.fillMaxSize(),
        )
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            MatOverlayRow(active[0], active[1], active[2])
            MatOverlayRow(active[3], l3Active || r3Active, active[5]) {
                Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MatOverlayCell(l3Active, Modifier.weight(1f))
                    MatOverlayCell(r3Active, Modifier.weight(1f))
                }
            }
            MatOverlayRow(active[6], active[7], active[8])
        }
    }
    Text(
        if (pressed.isEmpty()) "No input" else pressed.joinToString(" · ") { "$it pressed" },
        color = GamrCyan,
    )
}

@Composable
private fun ColumnScope.MatOverlayRow(left: Boolean, centre: Boolean, right: Boolean, centreContent: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MatOverlayCell(left, Modifier.weight(1f))
        if (centreContent == null) MatOverlayCell(centre, Modifier.weight(1f))
        else Box(modifier = Modifier.weight(1f)) { centreContent() }
        MatOverlayCell(right, Modifier.weight(1f))
    }
}

@Composable
private fun MatOverlayCell(active: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(if (active) GamrPurple.copy(alpha = 0.55f) else Color.Transparent),
    )
}

@Composable
private fun ZonePreview(mode: GamrMode, info: GamrDeviceInfo, rows: List<Int>) {
    val labels = when (mode) {
        GamrMode.GAMR -> listOf("X", "↑", "A", "←", "L3 / R3", "→", "Y", "↓", "B")
        GamrMode.RHYTHM -> listOf("↖", "↑", "↗", "←", "•", "→", "↙", "↓", "↘")
        GamrMode.CUSTOM -> info.customActions.map { it.label }
        GamrMode.BALANCE -> emptyList()
    }
    val active = (0..8).map { zone -> zonePressed(zone, rows) }
    val activeColour = when (mode) {
        GamrMode.GAMR -> GamrPurple
        GamrMode.RHYTHM -> Color(0xFFFF8A3D)
        GamrMode.CUSTOM -> GamrCyan
        GamrMode.BALANCE -> GamrGreen
    }
    Box(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(activeColour.copy(alpha = 0.14f)).padding(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) { column ->
                        val index = row * 3 + column
                        Box(modifier = Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (active[index]) activeColour else Color.Black.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center) { Text(labels[index]) }
                    }
                }
            }
        }
    }
    val pressed = if (mode == GamrMode.GAMR) gamrActions(rows) else labels.filterIndexed { index, _ -> active[index] }
    Text(if (pressed.isEmpty()) "No input" else pressed.joinToString(" · ") { "$it pressed" },
        color = GamrCyan)
}

@Composable
private fun BalancePreview(rows: List<Int>) {
    val labels = listOf("↖", "↑", "↗", "←", "•", "→", "↙", "↓", "↘")
    repeat(3) { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(3) { column ->
                val pressed = row < rows.size && (rows[row + 1] and (1 shl (column + 1))) != 0
                Box(modifier = Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (pressed) GamrGreen else MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center) { Text(labels[row * 3 + column]) }
            }
        }
        if (row < 2) Spacer(Modifier.height(8.dp))
    }
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
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(zones[zone], style = MaterialTheme.typography.labelSmall)
                                Text(action.label, style = MaterialTheme.typography.bodySmall)
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
                Text("${device.rssi} dBm", style = MaterialTheme.typography.labelMedium, color = GamrCyan)
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
