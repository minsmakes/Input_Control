package io.inputcontrol.ui.keyboard

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import io.inputcontrol.RemoteModel
import io.inputcontrol.ShiftMode
import io.inputcontrol.input.*
import io.inputcontrol.preferences.*
import io.inputcontrol.ui.components.*

@Composable
fun Keyboard(vm: RemoteModel, cfg: Config) {
    val ko by vm.korean.collectAsState()
    val shift by vm.shift.collectAsState()
    val interval =
        androidx.compose.ui.platform.LocalViewConfiguration.current.doubleTapTimeoutMillis
    val mods by vm.modifiers.collectAsState()
    var set by rememberSaveable { mutableStateOf("QWERTY") }
    var open by remember { mutableStateOf(false) }
    val modes =
        listOfNotNull(
            "QWERTY",
            "Numpad".takeIf { cfg.on(Option.Numpad) },
            "F1–F12".takeIf { cfg.on(Option.Functions) },
        )
    val mode = set.takeIf { it in modes || it == "Symbols" } ?: "QWERTY"
    val rows =
        when (mode) {
            "Symbols" -> symbolRows
            "Numpad" -> keypad
            "F1–F12" -> functions
            else -> qwerty
        }
    val ime = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { ime?.hide() }
    val window =
        (androidx.compose.ui.platform.LocalView.current.context as? android.app.Activity)?.window
    DisposableEffect(vm, window) {
        val oldMode = window?.attributes?.softInputMode
        window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        onDispose {
            if (oldMode != null) window.setSoftInputMode(oldMode)
            vm.releaseHolds()
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val indent = maxWidth * .045f
        val compact = maxHeight < 260.dp
        val gap = if (compact) 3.dp else 6.dp
        val top = if (compact) 3.dp else 12.dp
        val available = maxHeight - top - gap * (rows.size + 1)
        val toolbar = (available / (rows.size + 1)).coerceIn(1.dp, 48.dp)
        val keyHeight = ((available - toolbar) / rows.size).coerceAtLeast(1.dp)
        Column(
            Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = top, bottom = gap),
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            Row(
                Modifier.height(toolbar),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Button(
                        modifier = Modifier.tactile(),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = LocalFinish.current.primary
                            ),
                        onClick = { open = true },
                    ) {
                        Text("$mode  ▾", color = LocalFinish.current.onPrimary)
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        modes.forEach {
                            DropdownMenuItem(
                                modifier = Modifier.tactile(),
                                text = { Text(it) },
                                onClick = {
                                    vm.release()
                                    set = it
                                    open = false
                                },
                            )
                        }
                    }
                }
                val composing by Input.composing.collectAsState()
                Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                    Text(if (ko) composing else "", maxLines = 1)
                }
                if (mode == "QWERTY" || mode == "Symbols")
                    editing.forEach { k ->
                        KeyboardKey(
                            k.name,
                            false,
                            Modifier.weight(1f).height(toolbar),
                            { vm.pressKey(k.id, "") },
                            { vm.endKey(k.id) },
                        )
                    }
            }
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                key(mode) {
                    rows.forEachIndexed { index, row ->
                        Row(
                            Modifier.fillMaxWidth()
                                .padding(
                                    horizontal =
                                        if (mode == "QWERTY" && index == 2) indent else 0.dp
                                ),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            row.forEach { key ->
                                val shifted =
                                    shift == ShiftMode.ONE_SHOT ||
                                        shift == ShiftMode.CAPS_LOCK && key.id in 4..29
                                val name =
                                    if (key.id == 225 && shift == ShiftMode.CAPS_LOCK) "⇪ Caps"
                                    else label(key, ko, shifted)
                                val active =
                                    key.id in mods ||
                                        key.id == 225 && shift != ShiftMode.OFF ||
                                        key.id == -1 && ko
                                fun press() {
                                    when (key.id) {
                                        -2 -> {
                                            vm.release()
                                            set = if (mode == "Symbols") "QWERTY" else "Symbols"
                                        }
                                        -3,
                                        -4 ->
                                            if (mods.isEmpty())
                                                Input.send(Op.Text, key.chars.first().code)
                                        -1 -> vm.setLanguage(!vm.korean.value)
                                        225 -> vm.tapShift(interval)
                                        224,
                                        226,
                                        227 -> vm.modifier(key.id)
                                        else -> vm.pressKey(key.id, key.chars)
                                    }
                                }
                                fun release() {
                                    if (key.id in 0..223) vm.endKey(key.id)
                                }
                                KeyboardKey(
                                    name,
                                    active,
                                    Modifier.weight(key.weight).height(keyHeight),
                                    ::press,
                                    ::release,
                                    accent = key.id == 40,
                                    special = key.id in listOf(225, 42),
                                    onTapOnly = key.id < 0 || key.id in 224..227,
                                    timedTap =
                                        if (key.id == 225)
                                            ({ stamp -> vm.tapShift(interval, stamp) })
                                        else null,
                                    alternate =
                                        if (
                                            mode == "QWERTY" && key.id in 30..39 && mods.isEmpty()
                                        ) {
                                            {
                                                vm.pressKey(
                                                    key.id,
                                                    key.chars.last().toString().repeat(2),
                                                )
                                                vm.endKey(key.id)
                                            }
                                        } else null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyboardKey(
    name: String,
    active: Boolean,
    modifier: Modifier,
    press: () -> Unit,
    release: () -> Unit,
    accent: Boolean = false,
    special: Boolean = false,
    onTapOnly: Boolean = false,
    timedTap: ((Long) -> Unit)? = null,
    alternate: (() -> Unit)? = null,
) {
    val down by rememberUpdatedState(press)
    val up by rememberUpdatedState(release)
    val hold by rememberUpdatedState(alternate)
    val onTimedTap by rememberUpdatedState(timedTap)
    var touched by remember { mutableStateOf(false) }
    Surface(
        modifier
            .heightIn(min = 48.dp)
            .semantics {
                role = Role.Button
                if (active) stateDescription = "Active"
                onClick {
                    down()
                    up()
                    true
                }
            }
            .pointerInput(onTapOnly, alternate != null, timedTap != null) {
                if (onTimedTap != null)
                    awaitEachGesture {
                        val first = awaitFirstDown()
                        first.consume()
                        touched = true
                        try {
                            waitForUpOrCancellation()?.let { upEvent ->
                                upEvent.consume()
                                onTimedTap?.invoke(upEvent.uptimeMillis)
                            }
                        } finally {
                            touched = false
                        }
                    }
                else if (hold != null)
                    detectTapGestures(
                        onTap = {
                            down()
                            up()
                        },
                        onLongPress = { hold?.invoke() },
                        onPress = {
                            touched = true
                            try {
                                tryAwaitRelease()
                            } finally {
                                touched = false
                            }
                        },
                    )
                else
                    detectTapGestures(
                        onPress = {
                            val end = up
                            touched = true
                            if (!onTapOnly) down()
                            try {
                                if (tryAwaitRelease() && onTapOnly) down()
                            } finally {
                                end()
                                touched = false
                            }
                        }
                    )
            }
            .pressFinish(touched),
        shape = small,
        color =
            when {
                touched -> LocalFinish.current.pressed
                active || accent -> LocalFinish.current.primary
                special -> LocalFinish.current.accent
                else -> LocalFinish.current.surface
            },
        contentColor =
            if ((active || accent) && !touched) LocalFinish.current.onPrimary
            else LocalFinish.current.ink,
        shadowElevation = if (touched) 0.dp else LocalFinish.current.elevation,
        border =
            BorderStroke(
                if (active) 2.dp else 1.dp,
                if (active) LocalFinish.current.ink else LocalFinish.current.border,
            ),
    ) {
        val plain = !touched && !active && !accent && !special
        Box(
            if (plain) Modifier.background(LocalFinish.current.keyGradient) else Modifier,
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}
