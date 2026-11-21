package io.inputcontrol.ui.control

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import io.inputcontrol.RemoteModel
import io.inputcontrol.connection.*
import io.inputcontrol.input.*
import io.inputcontrol.preferences.*
import io.inputcontrol.ui.components.*
import org.json.*

@Composable
private fun Picture(data: String, fallback: String, size: Dp) {
    val bitmap =
        remember(data) {
            if (data.isEmpty() || data.length > 24000) null
            else
                runCatching {
                        val bytes = Base64.decode(data, Base64.DEFAULT)
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        if (bounds.outWidth in 1..256 && bounds.outHeight in 1..256)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        else null
                    }
                    .getOrNull()
        }
    if (bitmap != null) Image(bitmap, null, Modifier.size(size)) else Glyph(fallback, size)
}

@Composable
internal fun Dashboard(
    vm: RemoteModel,
    cfg: Config,
    mediaOpen: Boolean,
    onMediaOpen: (Boolean) -> Unit,
    feedback: () -> Unit,
) {
    val apps by vm.devices.apps.collectAsState()
    val peer by vm.devices.active.collectAsState()
    val audio by vm.devices.audio.collectAsState()
    val media by vm.devices.media.collectAsState()
    val connected by vm.devices.status.collectAsState()
    val pin = peer?.pin.orEmpty()
    val saved = remember(cfg) { JSONObject(cfg[Option.DashboardPins]) }
    val ids =
        remember(saved, apps, pin) {
            val list = saved.optJSONArray(pin)
            if (list != null) (0 until list.length()).map { list.getInt(it) }
            else
                apps
                    .filter {
                        it.name.lowercase() in
                            setOf(
                                "google chrome",
                                "file explorer",
                                "spotify",
                                "notion",
                                "notepad",
                                "calculator",
                            )
                    }
                    .take(5)
                    .map { it.id }
        }
    var editing by remember(pin) { mutableStateOf(false) }
    val enabled = connected == Status.Connected
    Row(
        Modifier.fillMaxWidth().fillMaxHeight(.30f),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            Modifier.weight(1.28f).fillMaxHeight(),
            shape = rounded,
            border = BorderStroke(1.dp, LocalFinish.current.border),
            shadowElevation = LocalFinish.current.elevation,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                Modifier.background(LocalFinish.current.cardGradient).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Glyph("rocket", 17.dp)
                    Text(
                        if (enabled) "PC App Launcher" else "PC App Launcher · Disconnected",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                }
                repeat(2) { r ->
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(3) { c ->
                            val i = r * 3 + c
                            val app = apps.find { it.id == ids.getOrNull(i) }
                            val edit = i == 5
                            val interaction = remember { MutableInteractionSource() }
                            val pressed by interaction.collectIsPressedAsState()
                            Surface(
                                onClick = {
                                    if (edit || app == null) editing = true
                                    else Input.send(Op.Launch, app.id)
                                },
                                enabled = enabled,
                                interactionSource = interaction,
                                modifier = Modifier.weight(1f).fillMaxHeight().pressFinish(pressed),
                                shape = small,
                                color =
                                    if (enabled) LocalFinish.current.surface
                                    else LocalFinish.current.disabled,
                                shadowElevation =
                                    if (pressed) 0.dp else LocalFinish.current.elevation,
                                contentColor =
                                    if (enabled) LocalFinish.current.ink
                                    else LocalFinish.current.muted,
                                border =
                                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    if (edit) Glyph("settings", 18.dp)
                                    else if (app != null) Picture(app.icon, "grid", 20.dp)
                                    Text(
                                        if (edit) "Edit…" else app?.name ?: "＋",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style =
                                            if (!edit && app == null)
                                                MaterialTheme.typography.titleLarge
                                            else MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Surface(
            Modifier.weight(1f).fillMaxHeight(),
            shape = rounded,
            border = BorderStroke(1.dp, LocalFinish.current.border),
            shadowElevation = LocalFinish.current.elevation,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Box(Modifier.fillMaxSize().background(LocalFinish.current.cardGradient)) {
                Scenery(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.60f))
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Picture(media.art, "music", 32.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                media.title.ifBlank {
                                    when {
                                        !enabled -> "Disconnected"
                                        !media.available -> "Media unavailable"
                                        media.playing != null -> "Media playback"
                                        else -> "No active media"
                                    }
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            if (media.artist.isNotBlank())
                                Text(
                                    media.artist,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                )
                        }
                        Box {
                            io.inputcontrol.ui.audio.MiniPlayer(
                                vm,
                                mediaOpen,
                                onMediaOpen,
                                feedback,
                            )
                        }
                    }
                    if (media.duration > 0 && media.position >= 0) {
                        LinearProgressIndicator(
                            progress = { media.position.toFloat() / media.duration },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.onSurface,
                            trackColor = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            listOf(media.position, media.duration).forEach {
                                Text(
                                    "%d:%02d".format(it / 60, it % 60),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf(
                                Triple("Previous track", "previous", 3),
                                Triple(
                                    if (media.playing == true) "Pause" else "Play",
                                    if (media.playing == true) "pause" else "playOnly",
                                    2,
                                ),
                                Triple("Next track", "next", 4),
                            )
                            .forEach { (label, icon, cmd) ->
                                IconButton(
                                    onClick = { Input.send(Op.Media, cmd) },
                                    enabled = enabled && audio != null,
                                    modifier =
                                        Modifier.tactile(enabled && audio != null)
                                            .size(40.dp)
                                            .semantics { contentDescription = label },
                                ) {
                                    if (cmd == 2)
                                        Surface(
                                            shape = androidx.compose.foundation.shape.CircleShape,
                                            color = LocalFinish.current.primary,
                                            contentColor = LocalFinish.current.onPrimary,
                                        ) {
                                            Box(
                                                Modifier.size(34.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Glyph(icon, 19.dp)
                                            }
                                        }
                                    else
                                        CompositionLocalProvider(
                                            LocalContentColor provides LocalFinish.current.muted
                                        ) {
                                            Glyph(icon, 20.dp)
                                        }
                                }
                            }
                    }
                }
            }
        }
    }
    if (editing && enabled) {
        var draft by remember { mutableStateOf(ids) }
        var search by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Pinned PC apps") },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    itemsIndexed(draft) { i, id ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                apps.find { it.id == id }?.name ?: "Unavailable app",
                                Modifier.weight(1f),
                                maxLines = 1,
                            )
                            TextButton(
                                modifier = Modifier.tactile(i > 0),
                                enabled = i > 0,
                                onClick = {
                                    draft = draft.toMutableList().apply { add(i - 1, removeAt(i)) }
                                },
                            ) {
                                Text("↑")
                            }
                            TextButton(
                                modifier = Modifier.tactile(),
                                onClick = { draft = draft - id },
                            ) {
                                Text("Remove")
                            }
                        }
                    }
                    item {
                        OutlinedTextField(
                            search,
                            { search = it },
                            label = { Text("Find installed apps") },
                            singleLine = true,
                        )
                    }
                    items(
                        apps.filter { it.id !in draft && it.name.contains(search, true) },
                        key = { it.id },
                    ) { app ->
                        TextButton(
                            modifier = Modifier.tactile(draft.size < 5),
                            enabled = draft.size < 5,
                            onClick = { draft = draft + app.id },
                        ) {
                            Text(app.name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    modifier = Modifier.tactile(),
                    onClick = {
                        vm.save(
                            Option.DashboardPins,
                            JSONObject(saved.toString()).put(pin, JSONArray(draft)).toString(),
                        )
                        editing = false
                    },
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(modifier = Modifier.tactile(), onClick = { editing = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
