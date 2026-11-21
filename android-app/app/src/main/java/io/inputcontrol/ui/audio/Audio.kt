package io.inputcontrol.ui.audio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.inputcontrol.RemoteModel
import io.inputcontrol.connection.Status
import io.inputcontrol.input.*
import io.inputcontrol.ui.components.*
import kotlin.math.roundToInt

@Composable
fun Audio(vm: RemoteModel) {
    val peer by vm.devices.active.collectAsState()
    val status by vm.devices.status.collectAsState()
    val state by vm.devices.audio.collectAsState()
    val level = state?.volume
    val mute = state?.muted == true
    val enabled = status == Status.Connected && level != null
    val outputs = state?.outputs.orEmpty()
    fun command(id: Int) {
        if (status == Status.Connected) Input.send(Op.Media, id)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Audio Control", style = MaterialTheme.typography.titleLarge)
        Text(if (status == Status.Connected) "Connected to " + peer?.displayName else status.name)
        outputs.firstOrNull { it.isDefault }?.let { Text("Output · " + it.name) }
        Group("System volume", "volume") {
            key(peer?.pin, status) {
                VolumeSlider(level.takeIf { enabled }, onVolume = { command(100 + it) }) { volume ->
                    Text(
                        if (volume != null) "$volume% · " + (if (mute) "Muted" else "Unmuted")
                        else if (status != Status.Connected) "Connect to a Windows PC."
                        else if (state == null)
                            "Waiting for audio state. Both devices require the audio update."
                        else "No default playback endpoint."
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                        "Decrease" to 1,
                        "Increase" to 0,
                        (if (mute) "Unmute" else "Mute") to (if (mute) 6 else 7),
                    )
                    .forEach { (name, id) ->
                        OutlinedButton(
                            modifier = Modifier.tactile(enabled),
                            onClick = { command(id) },
                            enabled = enabled,
                        ) {
                            Text(name)
                        }
                    }
            }
        }
        Group("Playback", "play") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                listOf("Previous" to 3, "Play / pause" to 2, "Next" to 4).forEach { (name, id) ->
                    TextButton(
                        modifier = Modifier.tactile(status == Status.Connected && state != null),
                        onClick = { command(id) },
                        enabled = status == Status.Connected && state != null,
                    ) {
                        Text(name)
                    }
                }
            }
        }
        Group("Playback devices", "volume") {
            outputs.forEach { Text(it.name + (if (it.isDefault) " · Default" else "")) }
            Text(
                "Choose the default output in Windows Sound settings.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun VolumeSlider(
    volume: Int?,
    onVolume: (Int) -> Unit,
    label: @Composable (Int?) -> Unit,
) {
    var drag by remember(volume != null) { mutableStateOf<Float?>(null) }
    val shown = drag ?: (volume ?: 0).toFloat()
    label(shown.roundToInt().takeIf { volume != null })
    Slider(
        value = shown,
        valueRange = 0f..100f,
        enabled = volume != null,
        onValueChange = { drag = it },
        onValueChangeFinished = {
            drag?.let { onVolume(it.roundToInt()) }
            drag = null
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
