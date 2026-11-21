package io.inputcontrol.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.inputcontrol.*
import io.inputcontrol.connection.Status
import io.inputcontrol.input.Input
import io.inputcontrol.preferences.Option
import io.inputcontrol.ui.components.*
import io.inputcontrol.ui.control.Control
import io.inputcontrol.ui.devices.Devices
import io.inputcontrol.ui.settings.Settings
import io.inputcontrol.ui.shortcuts.Shortcuts

@Composable
fun Root(vm: RemoteModel) {
    val cfg by vm.config.collectAsState()
    val host = androidx.compose.ui.platform.LocalView.current
    if (!cfg.loaded) {
        // Let Android keep its starting window until the stored palette is available.
        DisposableEffect(host) {
            val waiting = android.view.ViewTreeObserver.OnPreDrawListener { vm.config.value.loaded }
            host.viewTreeObserver.addOnPreDrawListener(waiting)
            onDispose { host.viewTreeObserver.removeOnPreDrawListener(waiting) }
        }
        return
    }
    val connection by vm.devices.status.collectAsState()
    val scanning by vm.devices.scanning.collectAsState()
    val target by vm.devices.active.collectAsState()
    val active = target.takeIf { connection == Status.Connected }
    var screen by rememberSaveable { mutableStateOf("Control") }
    var menu by remember { mutableStateOf(false) }
    val error by Input.error.collectAsState()
    var appearance by rememberSaveable { mutableStateOf(false) }
    val legacyDark =
        cfg[Option.Dark] == "Dark" || cfg[Option.Dark] == "System" && isSystemInDarkTheme()
    val themeId =
        if (cfg[Option.Theme] == "legacy" && legacyDark) "dark-focus" else cfg[Option.Theme]
    val finish = finishes.find { it.id == themeId } ?: finishes.first()
    SideEffect {
        (host.context as? android.app.Activity)?.window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, host).apply {
                isAppearanceLightStatusBars = !finish.dark
                isAppearanceLightNavigationBars = !finish.dark
            }
        }
    }
    val background by
        androidx.compose.animation.animateColorAsState(
            finish.background,
            androidx.compose.animation.core.tween(180),
            label = "Dashboard background",
        )
    val colors = finish.scheme
    MaterialTheme(colorScheme = colors) {
        Surface(color = background, modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(
                        if (screen == "Control")
                            WindowInsets.systemBars.union(WindowInsets.displayCutout)
                        else WindowInsets.safeDrawing
                    )
            ) {
                val system = LocalDensity.current
                // Keep the approved tablet proportions on large, low-density landscape displays.
                val zoom = minOf(maxWidth / 920.dp, maxHeight / 460.dp).coerceIn(.85f, 1.7f)
                CompositionLocalProvider(
                    LocalFinish provides finish,
                    LocalDecorations provides cfg.on(Option.Decorations),
                    LocalDensity provides Density(system.density * zoom, system.fontScale),
                ) {
                    if (appearance)
                        io.inputcontrol.ui.settings.AppearancePicker(vm, cfg, finish) {
                            appearance = false
                        }
                    if (screen == "Control") {
                        Scenery(
                            Modifier.align(Alignment.TopEnd)
                                .padding(end = 80.dp)
                                .fillMaxWidth(.42f)
                                .height(56.dp),
                            art = finish.header,
                        )
                        Scenery(
                            Modifier.align(Alignment.BottomEnd)
                                .fillMaxWidth(.36f)
                                .height(25.dp)
                                .horizon(),
                            art = finish.header,
                        )
                    }
                    Column(
                        Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().height(56.dp).padding(start = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Glyph("pc")
                            Text(
                                "Input Control",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.weight(.32f))
                            VerticalDivider(Modifier.height(18.dp))
                            Row(
                                Modifier.background(finish.card, CircleShape)
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.size(7.dp)
                                        .background(
                                            if (active != null) Color(0xFF488269)
                                            else finish.primary,
                                            CircleShape,
                                        )
                                )
                                Text(
                                    if (scanning && connection == Status.Disconnected) "Discovering"
                                    else connection.name,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            if (active != null) {
                                VerticalDivider(Modifier.height(18.dp))
                                Glyph("pc", 18.dp)
                                Text(
                                    "${active.displayName} · Wi-Fi",
                                    Modifier.widthIn(max = 160.dp),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            if (screen == "Control") {
                                val keyboard by vm.keyboard.collectAsState()
                                OutlinedIconButton(
                                    onClick = vm::toggleKeyboard,
                                    modifier =
                                        Modifier.tactile().semantics {
                                            contentDescription =
                                                if (keyboard) "Switch to touchpad"
                                                else "Switch to keyboard"
                                        },
                                ) {
                                    Glyph(if (keyboard) "mouse" else "keyboard")
                                }
                            }
                            Box {
                                IconButton(
                                    onClick = { menu = true },
                                    modifier =
                                        Modifier.tactile().semantics {
                                            contentDescription = "Navigation menu"
                                        },
                                ) {
                                    Glyph("dots")
                                }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(
                                        modifier = Modifier.tactile(),
                                        text = { Text("Themes & Appearance") },
                                        leadingIcon = { Glyph("grid") },
                                        onClick = {
                                            menu = false
                                            appearance = true
                                        },
                                    )
                                    listOf(
                                            "Control" to "mouse",
                                            "Devices" to "pc",
                                            "Audio" to "volume",
                                            "Shortcuts" to "grid",
                                            "Settings" to "settings",
                                        )
                                        .forEach { (name, icon) ->
                                            DropdownMenuItem(
                                                modifier = Modifier.tactile(),
                                                text = { Text(name) },
                                                leadingIcon = { Glyph(icon) },
                                                trailingIcon = { if (name == screen) Text("✓") },
                                                onClick = {
                                                    vm.release()
                                                    screen = name
                                                    menu = false
                                                },
                                            )
                                        }
                                }
                            }
                        }
                        if (error.isNotEmpty()) Text(error, color = colors.error)
                        when (screen) {
                            "Control" -> Control(vm, cfg)
                            "Audio" -> io.inputcontrol.ui.audio.Audio(vm)
                            "Devices" -> Devices(vm)
                            "Shortcuts" -> Shortcuts()
                            "Settings" -> Settings(vm, cfg) { appearance = true }
                        }
                    }
                }
            }
        }
    }
}
