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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
                    onScanClick = ::requestScan,
                    onConnectClick = ::connectToDevice,
                    onDisconnectClick = ::disconnect,
                    onModeSelected = bleClient::setMode,
                    onSensitivitySelected = bleClient::setTouchThreshold,
                    onCustomActionSelected = bleClient::setCustomAction,
                    onCustomReset = bleClient::resetCustomActions,
                    onShutdownMinutesSelected = bleClient::setAutoShutdownMinutes,
                    onDeviceNameSelected = bleClient::setDeviceName,
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
        scanStatus = scanner.start().message
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
    onScanClick: () -> Unit,
    onConnectClick: (GamrDevice) -> Unit,
    onDisconnectClick: () -> Unit,
    onModeSelected: (GamrMode) -> Unit,
    onSensitivitySelected: (Int) -> Unit,
    onCustomActionSelected: (Int, GamrMatAction) -> Unit,
    onCustomReset: () -> Unit,
    onShutdownMinutesSelected: (Int) -> Unit,
    onDeviceNameSelected: (String) -> Unit,
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
                        onModeSelected = onModeSelected,
                        onSensitivitySelected = onSensitivitySelected,
                        onCustomActionSelected = onCustomActionSelected,
                        onCustomReset = onCustomReset,
                        onShutdownMinutesSelected = onShutdownMinutesSelected,
                        onDeviceNameSelected = onDeviceNameSelected,
                    )
                }
            }

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

            if (devices.isEmpty()) {
                item {
                    EmptyDeviceCard()
                }
            } else {
                items(devices, key = { it.address }) { device ->
                    DeviceCard(device = device, onConnectClick = { onConnectClick(device) })
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
    onModeSelected: (GamrMode) -> Unit,
    onSensitivitySelected: (Int) -> Unit,
    onCustomActionSelected: (Int, GamrMatAction) -> Unit,
    onCustomReset: () -> Unit,
    onShutdownMinutesSelected: (Int) -> Unit,
    onDeviceNameSelected: (String) -> Unit,
) {
    var sensitivity by remember(info.touchThreshold) {
        mutableFloatStateOf((info.touchThreshold ?: 500).toFloat())
    }
    var shutdownMinutes by remember(info.autoShutdownMinutes) {
        mutableFloatStateOf(info.autoShutdownMinutes.toFloat())
    }
    var nameDraft by remember(info.deviceName, device.name) {
        mutableStateOf(if (info.deviceName == "-") device.name else info.deviceName)
    }
    var nameEdited by remember { mutableStateOf(false) }
    val nameValid = nameDraft.trim().isNotEmpty() && nameDraft.trim().length <= 24 &&
        nameDraft.trim().all { it.code in 0x20..0x7E }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, GamrGreen.copy(alpha = 0.65f)),
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("CONNECTED MAT", style = MaterialTheme.typography.labelLarge, color = GamrGreen)
            Spacer(Modifier.height(4.dp))
            Text(
                if (info.deviceName == "-") device.name else info.deviceName,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(status, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DeviceStat("DEVICE ID", info.deviceId, Modifier.weight(1f))
                DeviceStat("BATTERY", info.batteryPercentage?.let { "$it%" } ?: "-", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            DeviceStat("FIRMWARE", info.firmwareVersion, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DeviceStat("MODE", info.mode.label, Modifier.weight(1f))
                DeviceStat("SENSITIVITY", info.touchThreshold?.toString() ?: "-", Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            Text("DEVICE NAME", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = nameDraft,
                onValueChange = {
                    nameDraft = it
                    nameEdited = true
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = nameEdited && !nameValid,
                supportingText = {
                    if (nameEdited && !nameValid) {
                        Text("A name is required (1–24 standard characters).")
                    } else {
                        Text("Shown when the MAT advertises after disconnecting.")
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onDeviceNameSelected(nameDraft) },
                enabled = nameValid && nameDraft.trim() != info.deviceName,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("SAVE DEVICE NAME") }
            Spacer(Modifier.height(16.dp))
            Text("TOUCH SENSITIVITY", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Lower value = more sensitive · ${sensitivity.toInt()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = sensitivity,
                onValueChange = { sensitivity = it },
                onValueChangeFinished = { onSensitivitySelected(sensitivity.toInt()) },
                valueRange = 50f..4095f,
            )
            Spacer(Modifier.height(12.dp))
            Text("AUTO SHUTDOWN", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("After ${shutdownMinutes.toInt()} minutes without MAT activity",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = shutdownMinutes,
                onValueChange = { shutdownMinutes = it },
                onValueChangeFinished = {
                    onShutdownMinutesSelected(shutdownMinutes.toInt())
                },
                valueRange = 5f..60f,
                steps = 54,
            )
            Spacer(Modifier.height(16.dp))
            Text("MAT MODE", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GamrMode.entries.take(2).forEach { mode ->
                    OutlinedButton(onClick = { onModeSelected(mode) }, modifier = Modifier.weight(1f)) {
                        Text(mode.label)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GamrMode.entries.drop(2).forEach { mode ->
                    OutlinedButton(onClick = { onModeSelected(mode) }, modifier = Modifier.weight(1f)) {
                        Text(mode.label)
                    }
                }
            }
            if (info.mode == GamrMode.CUSTOM) {
                Spacer(Modifier.height(18.dp))
                CustomMappingEditor(
                    actions = info.customActions,
                    onActionSelected = onCustomActionSelected,
                    onReset = onCustomReset,
                )
            }
            Spacer(Modifier.height(18.dp))
            DeveloperMatGrid(matRows)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onDisconnectClick, modifier = Modifier.fillMaxWidth()) {
                Text("Disconnect")
            }
        }
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
