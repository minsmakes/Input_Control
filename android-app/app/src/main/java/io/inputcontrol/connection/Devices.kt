package io.inputcontrol.connection

import android.content.Context
import io.inputcontrol.input.Input
import io.inputcontrol.nativebridge.Native
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

internal fun validNickname(s: String) =
    s.isNotBlank() && s.codePointCount(0, s.length) <= 40 && s.none { it.isISOControl() }

enum class Status {
    Disconnected,
    Discovering,
    Available,
    Pairing,
    Connecting,
    Connected,
    Reconnecting,
    Failed,
}

data class PlaybackOutput(val name: String, val isDefault: Boolean)

data class PcAudio(val volume: Int?, val muted: Boolean, val outputs: List<PlaybackOutput>) {
    companion object {
        fun fromStatus(lines: List<String>): PcAudio? {
            val header = lines.firstOrNull()?.split(' ') ?: return null
            val volume = header.firstOrNull()?.toIntOrNull() ?: return null
            return PcAudio(
                volume.takeIf { it in 0..100 },
                header.getOrNull(1) == "1",
                lines
                    .drop(1)
                    .filter { it.length > 1 }
                    .map { PlaybackOutput(it.drop(1), it[0] == '*') },
            )
        }
    }
}

data class Device(
    val name: String,
    val host: String,
    val port: Int = 47831,
    val pin: String = "",
    val advertised: Boolean = false,
    val nickname: String = "",
) {
    val displayName
        get() = nickname.ifEmpty { name }

    val endpoint
        get() = "$host:$port"
}

class WifiDevices(
    context: Context,
    private val scope: CoroutineScope,
    private val release: () -> Unit,
    private val selected: (String) -> Unit,
) {
    private val identity = Identity(context)
    private val nearby = linkedMapOf<String, Device>()
    private var trusted = listOf<Device>()
    private val discovery =
        Discovery(
            context,
            { key, device ->
                if (device == null) nearby.remove(key) else nearby[key] = device
                publish()
            },
            { reason ->
                scanning.value = false
                notice.value =
                    reason.ifEmpty {
                        if (nearby.isEmpty())
                            "No receivers found. Check the receiver, Wi-Fi and firewall, or enter its address."
                        else "Scan complete."
                    }
            },
        )
    val list = MutableStateFlow(listOf<Device>())
    val active = MutableStateFlow<Device?>(null)
    val status = MutableStateFlow(Status.Disconnected)
    val scanning = MutableStateFlow(false)
    val notice = MutableStateFlow("")
    val challenge = MutableStateFlow("")
    val focus = MutableStateFlow(0L)
    val apps = MutableStateFlow(emptyList<PcApp>())
    val media = MutableStateFlow(PcMedia())
    val audio = MutableStateFlow<PcAudio?>(null)
    val metrics = MutableStateFlow("")
    private var task: Job? = null
    private var ticket = 0L
    private var failures = 0
    private var foreground = false
    var auto = false
    var last = ""

    companion object {
        private val ids = AtomicLong()
    }

    init {
        scope.launch {
            try {
                trusted = withContext(Dispatchers.IO) { identity.peers() }
                publish()
                restore()
            } catch (e: Exception) {
                notice.value = "Trust store unavailable: ${e.message}"
            }
        }
    }

    private fun publish() {
        val discovered =
            nearby.values.map { d ->
                val saved =
                    trusted.firstOrNull { it.endpoint == d.endpoint }
                        ?: trusted.singleOrNull { it.name == d.name }
                d.copy(
                    pin = saved?.pin.orEmpty(),
                    advertised = true,
                    nickname = saved?.nickname.orEmpty(),
                )
            }
        list.value =
            (discovered + trusted.filter { t -> discovered.none { it.pin == t.pin } }).distinctBy {
                it.endpoint
            }
    }

    fun restore() {
        if (foreground && auto && last.isNotEmpty() && task == null)
            trusted.find { it.pin == last }?.let { connect(it, true) }
    }

    fun resume() {
        foreground = true
        restore()
    }

    fun pause() {
        foreground = false
        stopScan()
        disconnect(false)
    }

    fun scan() {
        nearby.clear()
        publish()
        scanning.value = true
        notice.value = "Searching this network…"
        discovery.start()
    }

    fun stopScan() {
        discovery.stop()
        if (scanning.value) notice.value = "Discovery stopped."
        scanning.value = false
    }

    fun confirm(code: String) {
        Native.confirmWifi(ticket, code.trim())
    }

    fun disconnect(manual: Boolean = true) {
        task?.cancel()
        task = null
        ticket = 0
        apps.value = emptyList()
        media.value = PcMedia()
        audio.value = null
        focus.value = 0L
        Input.wifiSession = 0
        Native.closeWifi()
        release()
        active.value = null
        challenge.value = ""
        status.value = Status.Disconnected
        if (manual) {
            last = ""
            selected("")
            notice.value = "Disconnected."
        }
    }

    fun rename(pin: String, value: String?) {
        val label = value?.trim().orEmpty()
        if (trusted.none { it.pin == pin } || value != null && !validNickname(label)) return
        identity.rename(pin, label)
        trusted = trusted.map { if (it.pin == pin) it.copy(nickname = label) else it }
        active.value
            ?.takeIf { it.pin == pin }
            ?.let {
                active.value = it.copy(nickname = label)
                if (status.value == Status.Connected)
                    notice.value = "Connected to " + active.value!!.displayName + " via Wi-Fi"
            }
        publish()
    }

    fun forget(device: Device) {
        disconnect()
        scope.launch {
            try {
                withContext(Dispatchers.IO) { identity.forget(device.pin) }
                trusted = trusted.filterNot { it.pin == device.pin }
                publish()
                notice.value = "Receiver forgotten."
            } catch (e: Exception) {
                notice.value = "Could not remove trust: ${e.message}"
            }
        }
    }

    fun manual(host: String, port: String) {
        val h = host.trim()
        val p = port.toIntOrNull()
        if (
            h.length !in 1..253 ||
                !h.matches(Regex("[a-zA-Z0-9.-]+")) ||
                p == null ||
                p !in 1..65535
        ) {
            notice.value = "Enter an IPv4 address or hostname and a port from 1 to 65535."
            return
        }
        connect(trusted.find { it.host == h && it.port == p } ?: Device(h, h, p))
    }

    fun connect(device: Device, reconnect: Boolean = false) {
        stopScan()
        disconnect(false)
        val id = ids.incrementAndGet()
        ticket = id
        active.value = device
        status.value = if (reconnect) Status.Reconnecting else Status.Connecting
        notice.value = "Contacting ${device.displayName}…"
        task =
            scope.launch {
                var established = false
                try {
                    val (ip, key) =
                        withContext(Dispatchers.IO) {
                            val addr =
                                InetAddress.getAllByName(device.host)
                                    .filterIsInstance<Inet4Address>()
                                    .firstOrNull { it.isSiteLocalAddress || it.isLinkLocalAddress }
                                    ?: error(
                                        "A directly reachable private IPv4 address is required."
                                    )
                            addr.hostAddress!! to identity.privateKey()
                        }
                    val expected =
                        device.pin.ifEmpty {
                            trusted
                                .firstOrNull { it.host == ip && it.port == device.port }
                                ?.pin
                                .orEmpty()
                        }
                    try {
                        check(
                            Native.openWifi(
                                id,
                                "$ip:${device.port}",
                                key,
                                expected,
                                android.os.Build.MODEL.filter {
                                        it.isLetterOrDigit() && it.code < 128 || it in " -_"
                                    }
                                    .take(64)
                                    .ifBlank { "Android tablet" },
                            ) == 0
                        ) {
                            "Native Wi-Fi session unavailable"
                        }
                    } finally {
                        key.fill(0)
                    }
                    while (isActive) {
                        val fields = withContext(Dispatchers.IO) { Native.wifiStatus().split('\n') }
                        when (fields.first()) {
                            "Pairing" -> {
                                status.value = Status.Pairing
                                challenge.value = fields[3]
                                notice.value =
                                    "Compare the verification string with the Windows companion."
                            }
                            "Connected" -> {
                                if (!established) {
                                    val peer = Device(fields[1], ip, device.port, fields[2])
                                    check(peer.pin.length == 64)
                                    withContext(Dispatchers.IO) { identity.remember(peer) }
                                    val d =
                                        peer.copy(
                                            nickname =
                                                trusted
                                                    .find { it.pin == peer.pin }
                                                    ?.nickname
                                                    .orEmpty()
                                        )
                                    trusted = trusted.filterNot { it.pin == d.pin } + d
                                    publish()
                                    active.value = d
                                    status.value = Status.Connected
                                    challenge.value = ""
                                    last = d.pin
                                    selected(d.pin)
                                    notice.value = "Connected to ${d.displayName} via Wi-Fi"
                                    established = true
                                    release() // Finish offline composition before switching the
                                    // capture route.
                                    Input.wifiSession = id
                                }
                                val (catalog, now) =
                                    withContext(Dispatchers.Default) {
                                        Native.dashboard(id, 0)?.let(::parseApps) to
                                            Native.dashboard(id, 1)?.let(::parseMedia)
                                    }
                                catalog?.let { apps.value = it }
                                now?.let { media.value = it }
                                audio.value = PcAudio.fromStatus(fields.drop(10))
                                focus.value = fields.getOrNull(9)?.toLongOrNull() ?: 0L
                                metrics.value =
                                    "Sent ${fields[5]} · received ${fields[6]} · RTT ${fields[7]} µs · receiver work ${fields[8]} ns total · failures $failures"
                            }
                            "Failed" ->
                                error(
                                    fields.getOrNull(4).orEmpty().ifEmpty {
                                        "Connection interrupted"
                                    }
                                )
                        }
                        delay(if (established) 250 else 100)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (ticket == id) {
                        apps.value = emptyList()
                        media.value = PcMedia()
                        audio.value = null
                        focus.value = 0L
                        Input.wifiSession = 0
                        Native.closeWifi()
                        release()
                        status.value = Status.Failed
                        challenge.value = ""
                        failures++
                        notice.value = e.message ?: "Connection interrupted"
                        metrics.value = "Connection failures $failures"
                        val d = active.value
                        if (established && auto && foreground && d != null) {
                            status.value = Status.Reconnecting
                            delay(1200)
                            connect(d, true)
                        }
                    }
                } finally {
                    if (ticket == id) task = null
                }
            }
    }
}
