package io.inputcontrol.ui.shortcuts

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.inputcontrol.input.*
import io.inputcontrol.ui.components.*

val media =
    listOf(
        "Volume up" to "volume",
        "Volume down" to "volume",
        "Play / pause" to "play",
        "Previous" to "previous",
        "Next" to "next",
        "Mute" to "mute",
    )
private val productivity =
    listOf("Copy\nCtrl + C" to "copy", "Paste\nCtrl + V" to "paste", "Undo\nCtrl + Z" to "undo")
private val system =
    listOf(
        "Lock PC\nWin + L" to "lock",
        "Screenshot\nWin + Shift + S" to "shot",
        "Open browser" to "browser",
    )

@Composable
fun Shortcuts() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf("Media" to media, "Productivity" to productivity, "System" to system)
            .forEachIndexed { group, (title, items) ->
                Group(title) {
                    items.chunked(3).forEachIndexed { r, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEachIndexed { c, (label, icon) ->
                                KeyTile(label, Modifier.weight(1f), icon = icon) {
                                    if (group == 0) Input.send(Op.Media, r * 3 + c)
                                    else Input.send(Op.Shortcut, (group - 1) * 3 + c)
                                }
                            }
                        }
                    }
                }
            }
    }
}
