package io.inputcontrol.ui.control

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import io.inputcontrol.RemoteModel
import io.inputcontrol.preferences.*
import io.inputcontrol.ui.components.*
import io.inputcontrol.ui.keyboard.Keyboard

@Composable
fun Control(vm: RemoteModel, cfg: Config) {
    val keyboard by vm.keyboard.collectAsState()
    val saved = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val focus by vm.devices.focus.collectAsState()
    val automatic = cfg.on(Option.AutoKeyboard)
    var touching by remember { mutableStateOf(false) }
    var mediaOpen by remember { mutableStateOf(false) }
    LaunchedEffect(focus, automatic, touching, mediaOpen) {
        if (touching || mediaOpen) return@LaunchedEffect
        if (!automatic || focus == 0L) {
            vm.handledFocus = -1
            return@LaunchedEffect
        }
        if (focus == vm.handledFocus) return@LaunchedEffect
        // Ignore transient focus and retain keyboard mode across loss/unknown.
        if ((focus and 255L) == 1L) {
            kotlinx.coroutines.delay(
                cfg[Option.FocusDelay].substringBefore(" ").toLongOrNull() ?: 350
            )
            if (vm.handledFocus != focus) vm.keyboard.value = true
        }
        vm.handledFocus = focus
    }
    val haptic = LocalHapticFeedback.current
    val feedback = {
        if (cfg.on(Option.Haptic)) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Dashboard(vm, cfg, mediaOpen, { mediaOpen = it }, feedback)
        val doubleTapSlop =
            android.view.ViewConfiguration.get(LocalContext.current).scaledDoubleTapSlop
        val session by vm.devices.status.collectAsState()
        if (keyboard) saved.SaveableStateProvider("keyboard") { Keyboard(vm, cfg) }
        else {
            ContextControls(vm, cfg)
            Box(
                Modifier.fillMaxSize()
                    .clip(rounded)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, rounded)
                    .testTag("touchpad")
                    .trackpad(cfg, doubleTapSlop, session, feedback) { touching = it },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Glyph("move", 40.dp)
                    Text("Touchpad", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Tap · Move · Drag · Scroll",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
