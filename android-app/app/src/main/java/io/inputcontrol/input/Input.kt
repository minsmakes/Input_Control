package io.inputcontrol.input

import io.inputcontrol.nativebridge.Native
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

enum class Op(val wire: Int) {
    Move(1),
    Scroll(2),
    Down(3),
    Up(4),
    KeyDown(5),
    KeyUp(6),
    Text(7),
    Backspace(8),
    Language(9),
    Media(10),
    Shortcut(11),
    Cancel(12),
    Commit(13),
    Zoom(14),
    ReleaseHolds(15),
    Launch(16),
}

// Process-wide FIFO matches the native session lifetime. One consumer owns JNI.
object Input {
    @Volatile var wifiSession = 0L
    private const val capacity = 256

    private class Packet {
        var op = Op.Cancel
        var id = 0
        var x = 0f
        var y = 0f
        var time = 0L

        var session = 0L

        fun set(o: Op, i: Int, dx: Float, dy: Float, t: Long, route: Long = wifiSession) {
            session = route
            op = o
            id = i
            x = dx
            y = dy
            time = t
        }
    }

    private val ring = Array(capacity) { Packet() }
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var head = 0
    private var depth = 0
    private var highWater = 0
    private var merged = 0L
    private var resets = 0L
    private val lock = Any()
    val composing = MutableStateFlow("")
    val error = MutableStateFlow("")
    val diagnostics = MutableStateFlow("")
    @Volatile var inspect = false
    private var language = 0

    init {
        CoroutineScope(Dispatchers.Default).launch {
            val p = Packet()
            val samples = LongArray(256)
            var activeScalar = 0
            var count = 0L
            var wait = 0L
            var boundary = 0L
            var rust = 0L
            try {
                for (ignored in signal) {
                    while (
                        synchronized(lock) {
                            if (depth == 0) false
                            else {
                                val s = ring[head]
                                p.set(s.op, s.id, s.x, s.y, s.time, s.session)
                                s.id = 0
                                head = (head + 1) % capacity
                                depth--
                                true
                            }
                        }
                    ) {
                        val start = System.nanoTime()
                        wait = start - p.time
                        rust = Native.dispatch(p.op.wire, p.id, p.x, p.y, p.session)
                        if (
                            p.op != Op.Move &&
                                p.op != Op.Scroll &&
                                p.op != Op.Up &&
                                p.op != Op.KeyUp &&
                                p.op != Op.Zoom
                        ) {
                            val scalar = Native.composing()
                            if (scalar != activeScalar) {
                                activeScalar = scalar
                                composing.value =
                                    if (scalar == 0) "" else scalar.toChar().toString()
                            }
                        }
                        p.id = 0
                        boundary = System.nanoTime() - start
                        check(rust >= 0) { "Rust rejected input" }
                        samples[(count++ % samples.size).toInt()] = wait + boundary
                    }
                    if (inspect) {
                        val snapshot = Native.snapshot()
                        val stats =
                            synchronized(lock) {
                                "Queue $depth / peak $highWater · merged $merged · resets $resets"
                            }
                        val p95 =
                            if (count >= samples.size) "${samples.sorted()[243]/1000} µs"
                            else "collecting"
                        diagnostics.value =
                            "$snapshot\n$stats\nDispatch ${wait/1000} µs · JNI ${boundary/1000} µs · Rust $rust ns\nLocal p95 $p95"
                    }
                }
            } catch (e: LinkageError) {
                error.value = "Native engine unavailable: ${e.message}"
            } catch (e: Exception) {
                error.value = "Input engine stopped: ${e.message}"
            }
        }
    }

    fun refresh() {
        signal.trySend(Unit)
    }

    fun send(op: Op, id: Int = 0, dx: Float = 0f, dy: Float = 0f) {
        val now = System.nanoTime()
        synchronized(lock) {
            val tail = ring[(head + depth + capacity - 1) % capacity]
            if (op == Op.Move && depth > 0 && tail.op == op && tail.session == wifiSession) {
                tail.x += dx
                tail.y += dy
                merged++
                return
            }
            if (op == Op.Language) language = id
            if (depth == capacity) {
                // Explicit overload recovery releases every previously dispatched hold.
                head = 0
                depth = 2
                resets++
                ring[0].set(Op.Cancel, 0, 0f, 0f, now)
                ring[1].set(Op.Language, language, 0f, 0f, now)
                error.value = "Input queue reset. Held inputs released."
            }
            ring[(head + depth) % capacity].set(op, id, dx, dy, now)
            depth++
            highWater = maxOf(highWater, depth)
        }
        signal.trySend(Unit)
    }
}
