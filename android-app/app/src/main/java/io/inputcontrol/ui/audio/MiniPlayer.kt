package io.inputcontrol.ui.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.inputcontrol.RemoteModel
import io.inputcontrol.connection.Status
import io.inputcontrol.input.Input
import io.inputcontrol.input.Op
import io.inputcontrol.ui.components.Glyph
import io.inputcontrol.ui.components.tactile

@Composable
fun MiniPlayer(
    vm: RemoteModel,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    feedback: () -> Unit,
) {
    val peer by vm.devices.active.collectAsState()
    val status by vm.devices.status.collectAsState()
    val media by vm.devices.media.collectAsState()
    var fallbackPlaying by rememberSaveable(peer?.pin, status) { mutableStateOf(false) }
    val playing = media.playing ?: fallbackPlaying
    IconButton(
        onClick = { onOpenChange(true) },
        modifier = Modifier.tactile().size(38.dp).semantics { contentDescription = "Media" },
    ) {
        Glyph("volume", 20.dp)
    }
    DropdownMenu(
        expanded = open,
        onDismissRequest = { onOpenChange(false) },
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        val state by vm.devices.audio.collectAsState()
        val connected = status == Status.Connected
        val volume = state?.volume.takeIf { connected }
        val muted = state?.muted == true

        fun command(id: Int) {
            if (connected) {
                Input.send(Op.Media, id)
                feedback()
            }
        }

        Column(
            Modifier.width(280.dp).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Media", style = MaterialTheme.typography.titleMedium)
                PlayerButton(
                    if (muted) "Unmute" else "Mute",
                    if (muted) "mute" else "volume",
                    enabled = volume != null,
                ) {
                    command(if (muted) 6 else 7)
                }
            }
            val playback = connected && state != null
            Row(
                Modifier.fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        RoundedCornerShape(18.dp),
                    )
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerButton("Previous track", "previous", playback) { command(3) }
                FilledIconButton(
                    onClick = {
                        command(2)
                        fallbackPlaying = !playing
                    },
                    modifier =
                        Modifier.tactile(playback).size(56.dp).semantics {
                            contentDescription = if (playing) "Pause" else "Play"
                        },
                    enabled = playback,
                    shape = CircleShape,
                    colors =
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                ) {
                    Glyph(if (playing) "pause" else "playOnly", size = 26.dp)
                }
                PlayerButton("Next track", "next", playback) { command(4) }
            }
            key(peer?.pin, status) {
                VolumeSlider(volume, onVolume = { command(100 + it) }) { percent ->
                    Text(
                        when {
                            percent != null -> if (muted) "$percent% · Muted" else "$percent%"
                            !connected -> "Connect to a Windows PC."
                            state == null -> "Audio state unavailable"
                            else -> "No playback device"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerButton(label: String, icon: String, enabled: Boolean, action: () -> Unit) {
    IconButton(
        onClick = action,
        enabled = enabled,
        modifier = Modifier.tactile(enabled).semantics { contentDescription = label },
    ) {
        Glyph(icon)
    }
}
