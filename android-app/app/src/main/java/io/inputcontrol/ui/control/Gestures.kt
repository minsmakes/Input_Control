package io.inputcontrol.ui.control

import androidx.compose.foundation.gestures.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import io.inputcontrol.input.*
import io.inputcontrol.preferences.*
import kotlinx.coroutines.*

private enum class TwoFingerMode {
    Pending,
    Scroll,
    Zoom,
}

fun Modifier.trackpad(
    cfg: Config,
    doubleTapSlop: Int,
    session: io.inputcontrol.connection.Status,
    feedback: () -> Unit,
    contact: (Boolean) -> Unit,
) =
    pointerInput(cfg, session) {
        coroutineScope {
            var pending: Job? = null
            var firstPosition = Offset.Zero
            var firstUp = -1000L
            fun click(action: String) {
                val button =
                    when (action) {
                        "Left click" -> 1
                        "Right click" -> 2
                        else -> return
                    }
                Input.send(Op.Down, button)
                Input.send(Op.Up, button)
                feedback()
            }
            var held = false
            try {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    contact(true)
                    down.consume()
                    val close =
                        down.uptimeMillis - firstUp in
                            0..viewConfiguration.doubleTapTimeoutMillis &&
                            (down.position - firstPosition).getDistance() < doubleTapSlop
                    val second = close && pending?.isActive == true
                    if (pending?.isActive == true) {
                        pending?.cancel()
                        if (!second) click(cfg[Option.Single])
                    }
                    var fingers = 1
                    var two = TwoFingerMode.Pending
                    var initialSpan = 0f
                    var zoomRemainder = 0f
                    var lastSpan = 0f
                    fun startDrag() {
                        Input.send(Op.Down, 1)
                        held = true
                        feedback()
                    }
                    var moved = false
                    var distance = Offset.Zero
                    var lastTime = down.uptimeMillis
                    var cancelled = false
                    do {
                        val e =
                            if (second && fingers == 1 && !held && !moved && cfg.on(Option.Drag)) {
                                val remaining =
                                    viewConfiguration.longPressTimeoutMillis -
                                        (lastTime - down.uptimeMillis)
                                val timed =
                                    withTimeoutOrNull(remaining.coerceAtLeast(1L)) {
                                        awaitPointerEvent()
                                    }
                                if (timed != null) timed
                                else {
                                    startDrag()
                                    moved = true
                                    awaitPointerEvent()
                                }
                            } else awaitPointerEvent()
                        if (e.changes.any { it.isConsumed }) {
                            cancelled = true
                            break
                        }
                        val count = e.changes.count { it.pressed }
                        if (count > fingers) {
                            if (count == 2) {
                                val points = e.changes.filter { it.pressed }
                                initialSpan =
                                    (points[0].position - points[1].position).getDistance()
                                lastSpan = initialSpan
                            }
                            fingers = count
                            distance = Offset.Zero
                            if (held) {
                                Input.send(Op.Up, 1)
                                held = false
                            }
                        }
                        lastTime = e.changes.first().uptimeMillis
                        if (
                            count == fingers && e.changes.all { it.pressed && it.previousPressed }
                        ) {
                            val delta =
                                e.changes.fold(Offset.Zero) { a, p -> a + p.positionChange() } /
                                    fingers.toFloat()
                            distance += delta
                            if (fingers == 2) {
                                val span =
                                    (e.changes[0].position - e.changes[1].position).getDistance()
                                val spread = span - initialSpan
                                val slop = viewConfiguration.touchSlop
                                if (two == TwoFingerMode.Pending) {
                                    if (
                                        kotlin.math.abs(spread) > 2 * slop &&
                                            kotlin.math.abs(spread) > distance.getDistance() * 1.5f
                                    )
                                        two = TwoFingerMode.Zoom
                                    else if (distance.getDistance() > slop)
                                        two = TwoFingerMode.Scroll
                                }
                                if (two != TwoFingerMode.Pending) moved = true
                                if (two == TwoFingerMode.Zoom && cfg.on(Option.Pinch)) {
                                    zoomRemainder +=
                                        (span - lastSpan) * cfg.speed(Option.ZoomSpeed) /
                                            (slop * 4f)
                                    val steps = zoomRemainder.toInt().coerceIn(-8, 8)
                                    if (steps != 0) {
                                        Input.send(Op.Zoom, steps)
                                        zoomRemainder -= steps
                                    }
                                }
                                if (
                                    two == TwoFingerMode.Scroll &&
                                        cfg[Option.Scroll] == "Scroll" &&
                                        delta.y != 0f
                                )
                                    Input.send(
                                        Op.Scroll,
                                        dy =
                                            delta.y *
                                                cfg.speed(Option.ScrollSpeed) *
                                                (if (cfg.on(Option.Natural)) 1 else -1),
                                    )
                                if (two != TwoFingerMode.Pending) lastSpan = span
                            }
                            if (fingers == 1) {
                                val wasMoving = moved
                                if (distance.getDistance() > viewConfiguration.touchSlop)
                                    moved = true
                                if (moved) {
                                    if (second && cfg.on(Option.Drag) && !held) startDrag()
                                    val step = if (wasMoving) delta else distance
                                    val speed = cfg.speed(Option.PointerSpeed)
                                    if (step != Offset.Zero)
                                        Input.send(
                                            Op.Move,
                                            dx = step.x * speed,
                                            dy = step.y * speed,
                                        )
                                }
                            }
                        }
                        e.changes.forEach { it.consume() }
                    } while (e.changes.any { it.pressed })
                    contact(false)
                    if (held) {
                        Input.send(Op.Up, 1)
                        held = false
                    }
                    if (
                        !cancelled &&
                            !moved &&
                            lastTime - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis
                    ) {
                        if (fingers == 2) click(cfg[Option.Two])
                        if (fingers == 1) {
                            if (second) {
                                click(cfg[Option.Single])
                                click(cfg[Option.Single])
                            } else if (cfg.on(Option.Drag)) {
                                if (pending?.isActive == true) {
                                    pending?.cancel()
                                    click(cfg[Option.Single])
                                }
                                firstPosition = down.position
                                firstUp = lastTime
                                pending = launch {
                                    delay(viewConfiguration.doubleTapTimeoutMillis)
                                    click(cfg[Option.Single])
                                }
                            } else click(cfg[Option.Single])
                        }
                    }
                }
            } finally {
                contact(false)
                pending?.cancel()
                if (held) Input.send(Op.Up, 1)
            }
        }
    }
