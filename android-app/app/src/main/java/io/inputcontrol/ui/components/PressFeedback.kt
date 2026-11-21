package io.inputcontrol.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

// Only drawing is transformed. Pointer coordinates and semantics bounds stay stationary.
@Composable
fun Modifier.pressFinish(pressed: Boolean): Modifier {
    val factor by
        animateFloatAsState(
            if (pressed) .97f else 1f,
            tween(if (pressed) 75 else 125),
            label = "Press feedback",
        )
    return drawWithContent { scale(factor) { this@drawWithContent.drawContent() } }
}

// Observe without consuming events. The Material component owns the gesture and ripple.
@Composable
fun Modifier.tactile(enabled: Boolean = true): Modifier {
    var pressed by remember { mutableStateOf(false) }
    return pointerInput(enabled) {
            if (enabled)
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    pressed = true
                    try {
                        waitForUpOrCancellation(PointerEventPass.Initial)
                    } finally {
                        pressed = false
                    }
                }
        }
        .pressFinish(pressed && enabled)
}
