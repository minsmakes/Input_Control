package io.inputcontrol.ui.settings

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.inputcontrol.RemoteModel
import io.inputcontrol.preferences.*
import io.inputcontrol.ui.components.*

@Composable
fun AppearancePicker(vm: RemoteModel, cfg: Config, current: Finish, dismiss: () -> Unit) {
    var chosen by rememberSaveable { mutableStateOf(current.id) }
    Dialog(onDismissRequest = dismiss) {
        Surface(shape = rounded, color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.widthIn(max = 600.dp).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Themes & Appearance", style = MaterialTheme.typography.titleLarge)
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    finishes.forEach { finish ->
                        Surface(
                            selected = chosen == finish.id,
                            onClick = { chosen = finish.id },
                            modifier = Modifier.tactile().fillMaxWidth(),
                            shape = small,
                            color = finish.surface,
                            contentColor = finish.ink,
                            border =
                                BorderStroke(if (chosen == finish.id) 2.dp else 1.dp, finish.border),
                        ) {
                            Row(
                                Modifier.padding(7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Box(
                                    Modifier.size(78.dp, 38.dp)
                                        .clip(small)
                                        .background(finish.cardGradient)
                                ) {
                                    CompositionLocalProvider(LocalDecorations provides true) {
                                        Scenery(Modifier.matchParentSize(), finish)
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(finish.title, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        finish.description,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (chosen == finish.id) Text("✓", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show Decorations", Modifier.weight(1f))
                    Switch(
                        checked = cfg.on(Option.Decorations),
                        modifier =
                            Modifier.tactile().semantics {
                                contentDescription = "Show Decorations"
                            },
                        onCheckedChange = { vm.save(Option.Decorations, if (it) "On" else "Off") },
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(modifier = Modifier.tactile(), onClick = dismiss) { Text("Close") }
                    Button(
                        modifier = Modifier.tactile(),
                        onClick = {
                            vm.save(Option.Theme, chosen)
                            dismiss()
                        },
                    ) {
                        Text("Apply")
                    }
                }
            }
        }
    }
}
