package io.inputcontrol

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.inputcontrol.connection.*
import io.inputcontrol.input.*
import io.inputcontrol.preferences.*
import io.inputcontrol.ui.Root
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model by viewModels<RemoteModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Root(model) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) model.releaseHolds()
    }

    override fun onPause() {
        model.releaseHolds()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        model.devices.resume()
    }

    override fun onStop() {
        if (!isChangingConfigurations) model.devices.pause()
        super.onStop()
    }
}

enum class ShiftMode {
    OFF,
    ONE_SHOT,
    CAPS_LOCK,
}

class RemoteModel(app: Application) : AndroidViewModel(app) {
    private val store = Preferences(app)
    val config = MutableStateFlow(Config())
    val keyboard = MutableStateFlow(false)
    var handledFocus = -1L

    fun toggleKeyboard() {
        release()
        handledFocus = devices.focus.value
        keyboard.value = !keyboard.value
    }

    val korean = MutableStateFlow(false)
    val modifiers = MutableStateFlow(emptySet<Int>())
    val devices =
        WifiDevices(app, viewModelScope, ::release) { pin -> save(Option.LastDevice, pin) }

    init {
        viewModelScope.launch {
            var previous: String? = null
            store.flow.collect { settings ->
                config.value = settings
                io.inputcontrol.nativebridge.Native.wantFocus(settings.on(Option.AutoKeyboard))
                val lang = settings[Option.Language]
                if (lang != previous) setLanguage(lang == "한국어")
                devices.auto = settings.on(Option.Reconnect)
                devices.last = settings[Option.LastDevice]
                if (previous == null) devices.restore()
                previous = lang
            }
        }
    }

    fun save(key: Option, value: String) {
        viewModelScope.launch { store.put(key, value) }
    }

    fun setLanguage(on: Boolean) {
        stopRepeat()
        Input.send(Op.Language, if (on) 1 else 0)
        korean.value = on
    }

    val shift = MutableStateFlow(ShiftMode.OFF)
    private var lastShift = 0L
    private val shiftedKeys = mutableSetOf<Int>()

    private var repeatingKey: Int? = null
    private var repeatJob: Job? = null
    private var selectionShift = false

    private fun stopRepeat() {
        repeatJob?.cancel()
        repeatJob = null
        repeatingKey = null
        if (selectionShift) endShiftOwner(REPEAT_SHIFT_OWNER)
        selectionShift = false
    }

    private fun beginShiftOwner(owner: Int) {
        if (shiftedKeys.add(owner) && shiftedKeys.size == 1) Input.send(Op.KeyDown, 225)
    }

    private fun endShiftOwner(owner: Int) {
        if (shiftedKeys.remove(owner) && shiftedKeys.isEmpty()) Input.send(Op.KeyUp, 225)
    }

    private fun startRepeat(id: Int) {
        repeatingKey = id
        val session = Input.wifiSession
        val started = android.os.SystemClock.uptimeMillis()
        selectionShift = shift.value == ShiftMode.ONE_SHOT
        if (selectionShift) {
            beginShiftOwner(REPEAT_SHIFT_OWNER)
            shift.value = ShiftMode.OFF
        }
        fun stroke(word: Boolean) {
            val borrowCtrl = word && 224 !in modifiers.value
            if (borrowCtrl) Input.send(Op.KeyDown, 224)
            Input.send(Op.KeyDown, id)
            if (id == 42 && modifiers.value.isEmpty() && !borrowCtrl) Input.send(Op.Backspace)
            Input.send(Op.KeyUp, id)
            if (borrowCtrl) Input.send(Op.KeyUp, 224)
        }
        stroke(false)
        repeatJob =
            viewModelScope.launch {
                delay(400)
                while (repeatingKey == id && Input.wifiSession == session) {
                    val word =
                        id == 42 &&
                            !selectionShift &&
                            modifiers.value.isEmpty() &&
                            config.value.on(Option.WordBackspace) &&
                            android.os.SystemClock.uptimeMillis() - started >= 1350
                    stroke(word)
                    delay(if (word) 240L else 85L)
                }
            }
    }

    private companion object {
        const val REPEAT_SHIFT_OWNER = -1
    }

    fun tapShift(interval: Long, now: Long = android.os.SystemClock.uptimeMillis()) {
        stopRepeat()
        shift.value =
            when (shift.value) {
                ShiftMode.OFF -> ShiftMode.ONE_SHOT
                ShiftMode.ONE_SHOT ->
                    if (now - lastShift <= interval) ShiftMode.CAPS_LOCK else ShiftMode.OFF
                ShiftMode.CAPS_LOCK -> ShiftMode.OFF
            }
        lastShift = now
    }

    fun pressKey(id: Int, chars: String) {
        stopRepeat()
        if (id == 42 || id in 79..82) {
            startRepeat(id)
            return
        }
        val chord = modifiers.value.isNotEmpty()
        val shifted =
            shift.value == ShiftMode.ONE_SHOT || shift.value == ShiftMode.CAPS_LOCK && id in 4..29
        if (chord || chars.isEmpty() && id !in listOf(42, 40, 88)) Input.send(Op.Commit)
        if (shifted && (chord || chars.isEmpty())) beginShiftOwner(id)
        Input.send(Op.KeyDown, id)
        if (!chord)
            when {
                id == 40 || id == 88 -> Input.send(Op.Text, 10)
                chars.isNotEmpty() ->
                    Input.send(Op.Text, (if (shifted) chars.last() else chars.first()).code)
            }
        if (shift.value == ShiftMode.ONE_SHOT && (chord || id != 44)) shift.value = ShiftMode.OFF
    }

    fun endKey(id: Int) {
        if (id == 42 || id in 79..82) {
            if (repeatingKey == id) stopRepeat()
            return
        }
        Input.send(Op.KeyUp, id)
        endShiftOwner(id)
    }

    fun modifier(id: Int) {
        stopRepeat()
        val held = modifiers.value
        Input.send(if (id in held) Op.KeyUp else Op.KeyDown, id)
        modifiers.value = if (id in held) held - id else held + id
    }

    fun release() {
        Input.send(Op.Commit)
        releaseHolds()
    }

    fun releaseHolds() {
        stopRepeat()
        Input.send(Op.ReleaseHolds)
        shiftedKeys.clear()
        shift.value = ShiftMode.OFF
        modifiers.value = emptySet()
    }
}
