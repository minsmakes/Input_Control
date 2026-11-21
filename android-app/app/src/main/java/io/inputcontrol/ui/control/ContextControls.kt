package io.inputcontrol.ui.control

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.inputcontrol.RemoteModel
import io.inputcontrol.connection.Status
import io.inputcontrol.input.*
import io.inputcontrol.preferences.*
import io.inputcontrol.ui.components.*
import org.json.JSONObject

private fun defaults(app: String) =
    when (app) {
        "chrome.exe",
        "msedge.exe",
        "firefox.exe",
        "brave.exe",
        "opera.exe" ->
            "Previous page = Alt+Left\nNext page = Alt+Right\nRefresh = F5\nNew tab = Ctrl+T"
        "explorer.exe" ->
            "Go back = Alt+Left\nGo forward = Alt+Right\nNew folder = Ctrl+Shift+N\nRefresh = F5"
        "notion.exe" -> "Search = Ctrl+P\nUndo = Ctrl+Z\nRedo = Ctrl+Shift+Z\nNew page = Ctrl+N"
        else -> "Copy = Ctrl+C\nPaste = Ctrl+V\nUndo = Ctrl+Z\nRedo = Ctrl+Y"
    }

private val usages = buildMap {
    ('A'..'Z').forEach { put(it.toString(), 4 + it.code - 'A'.code) }
    "1234567890".forEachIndexed { i, c -> put(c.toString(), 30 + i) }
    (1..12).forEach { put("F$it", 57 + it) }
    putAll(
        mapOf(
            "LEFT" to 80,
            "RIGHT" to 79,
            "UP" to 82,
            "DOWN" to 81,
            "ENTER" to 40,
            "ESC" to 41,
            "TAB" to 43,
            "SPACE" to 44,
            "DELETE" to 76,
            "BACKSPACE" to 42,
        )
    )
}

private fun parseActions(text: String): List<Pair<String, Int>>? {
    val lines = text.lines().filter { it.isNotBlank() }
    if (lines.size !in 1..4) return null
    return lines.map { line ->
        val label = line.substringBefore('=').trim()
        val keys = line.substringAfter('=', "").uppercase().split('+').map { it.trim() }
        val usage = usages[keys.last()] ?: return null
        if (label.length !in 1..30) return null
        var mask = 0
        keys.dropLast(1).forEach {
            mask =
                mask or
                    when (it) {
                        "CTRL" -> 1
                        "SHIFT" -> 2
                        "ALT" -> 4
                        "WIN" -> 8
                        else -> return null
                    }
        }
        label to (4096 + mask * 256 + usage)
    }
}

@Composable
internal fun ContextControls(vm: RemoteModel, cfg: Config) {
    val state by vm.devices.media.collectAsState()
    val peer by vm.devices.active.collectAsState()
    val status by vm.devices.status.collectAsState()
    val apps by vm.devices.apps.collectAsState()
    val root = remember(cfg) { JSONObject(cfg[Option.ShortcutProfiles]) }
    val profiles = root.optJSONObject(peer?.pin.orEmpty()) ?: JSONObject()
    val context = state.foreground.ifBlank { "Generic" }
    val fallback =
        defaults(context).let {
            if (it == defaults("Generic")) profiles.optString("Generic", it) else it
        }
    val profile = profiles.optString(context, fallback)
    var edit by remember(peer?.pin) { mutableStateOf(false) }
    Surface(shape = rounded, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    context.removeSuffix(".exe") + " shortcuts",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                )
                IconButton(
                    onClick = { edit = true },
                    enabled = status == Status.Connected,
                    modifier =
                        Modifier.tactile(status == Status.Connected).size(36.dp).semantics {
                            contentDescription = "Edit shortcut profiles"
                        },
                ) {
                    Glyph("settings", 18.dp)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                parseActions(profile).orEmpty().forEach { (label, id) ->
                    OutlinedButton(
                        onClick = { Input.send(Op.Shortcut, id) },
                        colors =
                            ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                        enabled = status == Status.Connected,
                        modifier = Modifier.tactile(status == Status.Connected).weight(1f),
                        shape = small,
                        contentPadding = PaddingValues(2.dp),
                    ) {
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    if (edit) {
        var target by remember { mutableStateOf(context) }
        var menu by remember { mutableStateOf(false) }
        val choices =
            remember(apps, context, profiles.toString()) {
                (listOf("Generic", context) +
                        profiles.keys().asSequence().toList() +
                        apps.map { it.exe }.filter { it.endsWith(".exe") })
                    .distinct()
                    .sorted()
            }
        var draft by
            remember(target) { mutableStateOf(profiles.optString(target, defaults(target))) }
        AlertDialog(
            onDismissRequest = { edit = false },
            title = { Text("Shortcut profiles") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Box {
                        TextButton(modifier = Modifier.tactile(), onClick = { menu = true }) {
                            Text("$target ▾")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            choices.forEach {
                                DropdownMenuItem(
                                    modifier = Modifier.tactile(),
                                    text = { Text(it) },
                                    onClick = {
                                        target = it
                                        menu = false
                                    },
                                )
                            }
                        }
                    }
                    Text("Enter up to four shortcuts in display order, one per line.")
                    OutlinedTextField(
                        draft,
                        { draft = it.take(240) },
                        label = { Text("Label = Ctrl+Key") },
                        isError = parseActions(draft) == null,
                    )
                    TextButton(
                        modifier = Modifier.tactile(),
                        onClick = { draft = defaults(target) },
                    ) {
                        Text("Restore defaults")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.tactile(parseActions(draft) != null),
                    enabled = parseActions(draft) != null,
                    onClick = {
                        val updated = JSONObject(profiles.toString()).put(target, draft)
                        vm.save(
                            Option.ShortcutProfiles,
                            JSONObject(root.toString()).put(peer?.pin.orEmpty(), updated).toString(),
                        )
                        edit = false
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(modifier = Modifier.tactile(), onClick = { edit = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
