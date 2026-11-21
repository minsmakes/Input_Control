package io.inputcontrol.ui.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp
import io.inputcontrol.*
import io.inputcontrol.input.Input
import io.inputcontrol.preferences.*
import io.inputcontrol.ui.components.*

@Composable
private fun Setting(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        content()
    }
}

@Composable
private fun Toggle(label: String, key: Option, cfg: Config, vm: RemoteModel) {
    Setting(label) {
        Switch(
            modifier = Modifier.tactile(),
            checked = cfg.on(key),
            onCheckedChange = { vm.save(key, if (it) "On" else "Off") },
        )
    }
}

@Composable
private fun Choice(label: String, key: Option, values: List<String>, cfg: Config, vm: RemoteModel) {
    var open by remember { mutableStateOf(false) }
    Setting(label) {
        Box {
            TextButton(modifier = Modifier.tactile(), onClick = { open = true }) {
                Text("${cfg[key]}  ▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                values.forEach {
                    DropdownMenuItem(
                        modifier = Modifier.tactile(),
                        text = { Text(it) },
                        onClick = {
                            vm.save(key, it)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Speed(label: String, key: Option, cfg: Config, vm: RemoteModel) {
    var value by remember(cfg[key]) { mutableFloatStateOf(cfg.speed(key)) }
    Setting(label) {
        Slider(
            value = value,
            onValueChange = { value = it },
            valueRange = .25f..3f,
            modifier = Modifier.width(96.dp),
            onValueChangeFinished = { vm.save(key, value.toString()) },
        )
        Text("%.1f".format(value), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun Settings(vm: RemoteModel, cfg: Config, appearance: () -> Unit) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Group("Input Layout", "keyboard") {
                Setting("Keyboard layout") { Text("QWERTY") }
                Choice("Default language", Option.Language, listOf("English", "한국어"), cfg, vm)
                Toggle(
                    "Automatically open keyboard when selecting a PC text field",
                    Option.AutoKeyboard,
                    cfg,
                    vm,
                )
                if (cfg.on(Option.AutoKeyboard))
                    Choice(
                        "Focus debounce",
                        Option.FocusDelay,
                        listOf("150 ms", "350 ms", "700 ms"),
                        cfg,
                        vm,
                    )
                Toggle("Delete words on sustained Backspace", Option.WordBackspace, cfg, vm)
                Toggle("Numeric keypad", Option.Numpad, cfg, vm)
                Toggle("Function keys", Option.Functions, cfg, vm)
            }
            Group("Gesture Mapping", "move") {
                val actions = listOf("Left click", "Right click", "Off")
                Choice("Single tap", Option.Single, actions, cfg, vm)
                Choice("Two-finger tap", Option.Two, actions, cfg, vm)
                Choice("Two-finger drag", Option.Scroll, listOf("Scroll", "Off"), cfg, vm)
                Toggle("Double-tap drag", Option.Drag, cfg, vm)
                Toggle("Enable pinch-to-zoom", Option.Pinch, cfg, vm)
                if (cfg.on(Option.Pinch)) Speed("Zoom sensitivity", Option.ZoomSpeed, cfg, vm)
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Group("Touchpad", "mouse") {
                Speed("Pointer sensitivity", Option.PointerSpeed, cfg, vm)
                Speed("Scroll sensitivity", Option.ScrollSpeed, cfg, vm)
                Toggle("Natural scrolling", Option.Natural, cfg, vm)
                Toggle("Haptic feedback", Option.Haptic, cfg, vm)
            }
            Group("Connection", "wifi") {
                Choice(
                    "Preferred connection",
                    Option.Transport,
                    listOf("Wi-Fi", "Bluetooth"),
                    cfg,
                    vm,
                )
                Toggle("Auto-reconnect", Option.Reconnect, cfg, vm)
            }
            Group("Appearance") {
                TextButton(modifier = Modifier.tactile(), onClick = appearance) {
                    Text("Themes & Appearance")
                }
                Choice(
                    "System appearance preset",
                    Option.Dark,
                    listOf("System", "Light", "Dark"),
                    cfg,
                    vm,
                )
            }
            Group("About") {
                Text("Input Control")
                Text(
                    "Design specification 1.0 · Build ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (BuildConfig.DEBUG) {
                    var show by remember { mutableStateOf(false) }
                    TextButton(modifier = Modifier.tactile(), onClick = { show = !show }) {
                        Text("Input diagnostics")
                    }
                    if (show) {
                        DisposableEffect(Unit) {
                            Input.inspect = true
                            Input.refresh()
                            onDispose { Input.inspect = false }
                        }
                        val lines by Input.diagnostics.collectAsState()
                        Text(lines, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
