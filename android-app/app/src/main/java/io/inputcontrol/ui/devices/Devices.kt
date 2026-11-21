package io.inputcontrol.ui.devices

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.inputcontrol.RemoteModel
import io.inputcontrol.connection.*
import io.inputcontrol.ui.components.*

@Composable
fun Devices(vm: RemoteModel) {
    val wifi = vm.devices
    val list by wifi.list.collectAsState()
    val scanning by wifi.scanning.collectAsState()
    val current by wifi.active.collectAsState()
    val state by wifi.status.collectAsState()
    val message by wifi.notice.collectAsState()
    val check by wifi.challenge.collectAsState()
    val metrics by wifi.metrics.collectAsState()
    var manual by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("47831") }
    var code by remember(check) { mutableStateOf("") }
    var renaming by remember { mutableStateOf<Device?>(null) }
    renaming?.let { d ->
        var label by remember(d.pin) { mutableStateOf(d.displayName) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename PC") },
            text = {
                Column {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        singleLine = true,
                        label = { Text("Display name") },
                        supportingText = { Text("Up to 40 characters") },
                    )
                    TextButton(
                        modifier = Modifier.tactile(),
                        onClick = {
                            wifi.rename(d.pin, null)
                            renaming = null
                        },
                    ) {
                        Text("Restore original name")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.tactile(validNickname(label.trim())),
                    enabled = validNickname(label.trim()),
                    onClick = {
                        wifi.rename(d.pin, label)
                        renaming = null
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(modifier = Modifier.tactile(), onClick = { renaming = null }) {
                    Text("Cancel")
                }
            },
        )
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) wifi.scan()
            else
                wifi.notice.value =
                    "Local network access denied. Enable Nearby devices in Android app permissions."
        }
    DisposableEffect(wifi) { onDispose { wifi.stopScan() } }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(
            Modifier.weight(2.2f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Group("Wi-Fi Devices", "wifi") {
                if (list.isEmpty())
                    Text(
                        if (scanning) "Searching for receivers…"
                        else "Start the Windows receiver and tap Pair New Device."
                    )
                list.forEach { d ->
                    val selected = current?.endpoint == d.endpoint
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Glyph("pc", 20.dp)
                        Column(Modifier.weight(1f)) {
                            Text(d.displayName)
                            Text(
                                d.endpoint +
                                    " · " +
                                    (if (d.pin.isEmpty()) "Unpaired" else "Paired") +
                                    " · " +
                                    (if (selected) state.name
                                    else if (d.advertised) "Available" else "Saved"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        val busy =
                            selected &&
                                state in
                                    setOf(
                                        Status.Pairing,
                                        Status.Connecting,
                                        Status.Reconnecting,
                                        Status.Connected,
                                    )
                        TextButton(
                            modifier = Modifier.tactile(),
                            onClick = { if (busy) wifi.disconnect() else wifi.connect(d) },
                        ) {
                            Text(
                                if (busy && state == Status.Connected) "Disconnect"
                                else if (busy) "Cancel"
                                else if (d.pin.isEmpty()) "Pair" else "Connect"
                            )
                        }
                        if (d.pin.isNotEmpty())
                            TextButton(modifier = Modifier.tactile(), onClick = { renaming = d }) {
                                Text("Rename")
                            }
                        if (d.pin.isNotEmpty())
                            TextButton(
                                modifier = Modifier.tactile(),
                                onClick = { wifi.forget(d) },
                            ) {
                                Text("Forget")
                            }
                    }
                }
            }
            Group("Bluetooth Devices", "bluetooth") {
                Text("Bluetooth HID is unavailable in this version.")
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            KeyTile(if (scanning) "Rescan" else "＋  Pair New Device", Modifier.fillMaxWidth()) {
                wifi.scan()
            }
            Group(if (state == Status.Pairing) "Verify pairing" else "Connection") {
                current?.let { Text(it.displayName, style = MaterialTheme.typography.titleMedium) }
                if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
                if (
                    Build.VERSION.SDK_INT == 36 && message.contains("permission", ignoreCase = true)
                ) {
                    TextButton(
                        modifier = Modifier.tactile(),
                        onClick = { permission.launch(Manifest.permission.NEARBY_WIFI_DEVICES) },
                    ) {
                        Text("Allow nearby devices")
                    }
                }
                if (state == Status.Pairing) {
                    Text(
                        check.chunked(8).joinToString(" "),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Match these three groups on the PC. Enter its final group.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        code,
                        {
                            code =
                                it.uppercase()
                                    .filter { c -> c in '0'..'9' || c in 'A'..'F' }
                                    .take(8)
                        },
                        label = { Text("Last 8 hex digits") },
                        singleLine = true,
                    )
                    Button(
                        modifier = Modifier.tactile(code.length == 8),
                        onClick = { wifi.confirm(code) },
                        enabled = code.length == 8,
                    ) {
                        Text("Confirm match")
                    }
                }
                if (
                    state in
                        setOf(
                            Status.Connected,
                            Status.Pairing,
                            Status.Connecting,
                            Status.Reconnecting,
                        )
                )
                    TextButton(modifier = Modifier.tactile(), onClick = { wifi.disconnect() }) {
                        Text(if (state == Status.Connected) "Disconnect" else "Cancel")
                    }
                if (metrics.isNotEmpty()) Text(metrics, style = MaterialTheme.typography.labelSmall)
            }
            TextButton(modifier = Modifier.tactile(), onClick = { manual = !manual }) {
                Text("Manual connection")
            }
            if (manual)
                Group("Receiver address") {
                    OutlinedTextField(
                        host,
                        { host = it.take(253) },
                        label = { Text("IP or hostname") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        port,
                        { port = it.filter(Char::isDigit).take(5) },
                        label = { Text("Port") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Button(
                        modifier = Modifier.tactile(host.isNotBlank()),
                        onClick = { wifi.manual(host, port) },
                        enabled = host.isNotBlank(),
                    ) {
                        Text("Continue")
                    }
                }
        }
    }
}
