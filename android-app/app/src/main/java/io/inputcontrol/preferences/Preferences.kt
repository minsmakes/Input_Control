package io.inputcontrol.preferences

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("input_control")

enum class Option(val initial: String) {
    AutoKeyboard("On"),
    WordBackspace("On"),
    FocusDelay("350 ms"),
    Language("English"),
    Numpad("On"),
    Functions("On"),
    Single("Left click"),
    Two("Right click"),
    Scroll("Scroll"),
    Drag("On"),
    Pinch("On"),
    ZoomSpeed("1.0"),
    PointerSpeed("1.0"),
    ScrollSpeed("1.0"),
    Natural("On"),
    Haptic("On"),
    Transport("Wi-Fi"),
    Reconnect("Off"),
    Dark("System"),
    Theme("modern-light"),
    Decorations("On"),
    LastDevice(""),
    DashboardPins("{}"),
    ShortcutProfiles("{}"),
}

class Config(private val values: Map<Option, String> = emptyMap()) {
    val loaded
        get() = values.isNotEmpty()

    operator fun get(key: Option) = values[key] ?: key.initial

    fun on(key: Option) = get(key) == "On"

    fun speed(key: Option) = get(key).toFloatOrNull()?.coerceIn(0.25f, 3f) ?: 1f
}

class Preferences(private val context: Context) {
    val flow =
        context.store.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .map { stored ->
                Config(
                    Option.entries.associateWith { option ->
                        stored[stringPreferencesKey(option.name)]
                            ?: if (
                                option == Option.Theme &&
                                    stored[stringPreferencesKey(Option.Dark.name)] != null
                            )
                                "legacy"
                            else option.initial
                    }
                )
            }

    suspend fun put(key: Option, value: String) {
        context.store.edit {
            it[stringPreferencesKey(key.name)] = value
            if (key == Option.Dark) it[stringPreferencesKey(Option.Theme.name)] = "legacy"
        }
    }
}
