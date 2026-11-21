package io.inputcontrol.connection

import android.content.Context
import android.net.nsd.*
import android.net.wifi.WifiManager
import android.os.*
import java.net.Inet4Address

@Suppress("DEPRECATION")
internal class Discovery(
    private val context: Context,
    private val found: (String, Device?) -> Unit,
    private val ended: (String) -> Unit,
) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var listener: NsdManager.DiscoveryListener? = null
    private var lock: WifiManager.MulticastLock? = null
    private var epoch = 0
    private val pending = ArrayDeque<NsdServiceInfo>()
    private val present = mutableSetOf<String>()
    private var resolving = false
    private val timeout = Runnable {
        stop()
        ended("")
    }

    fun start() {
        stop()
        val generation = epoch
        val callbacks =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String) {}

                override fun onDiscoveryStopped(t: String) {}

                override fun onStopDiscoveryFailed(t: String, e: Int) {}

                override fun onStartDiscoveryFailed(t: String, e: Int) {
                    main.post {
                        if (epoch == generation) {
                            stop()
                            ended("Discovery failed ($e). Check Wi-Fi and local network access.")
                        }
                    }
                }

                override fun onServiceFound(info: NsdServiceInfo) {
                    main.post {
                        if (
                            epoch == generation &&
                                info.serviceType.trimEnd('.') == "_inputcontrol._tcp" &&
                                present.size < 64
                        ) {
                            present.add(info.serviceName)
                            pending.removeAll { it.serviceName == info.serviceName }
                            pending.addLast(info)
                            resolve()
                        }
                    }
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    main.post {
                        if (epoch == generation) {
                            present.remove(info.serviceName)
                            pending.removeAll { it.serviceName == info.serviceName }
                            found(info.serviceName, null)
                        }
                    }
                }
            }
        try {
            if (
                Build.VERSION.SDK_INT < 33 ||
                    (Build.VERSION.SDK_INT == 33 &&
                        android.os.ext.SdkExtensions.getExtensionVersion(
                            Build.VERSION_CODES.TIRAMISU
                        ) < 7)
            ) {
                lock =
                    context.applicationContext
                        .getSystemService(WifiManager::class.java)
                        .createMulticastLock("InputControl.Discovery")
                        .apply { acquire() }
            }
            listener = callbacks
            nsd.discoverServices("_inputcontrol._tcp", NsdManager.PROTOCOL_DNS_SD, callbacks)
            main.postDelayed(timeout, 12000)
        } catch (e: Exception) {
            stop()
            ended("Local discovery unavailable: ${e.message}")
        }
    }

    private fun resolve() {
        if (resolving || pending.isEmpty()) return
        val info = pending.removeFirst()
        val generation = epoch
        resolving = true
        fun finish(device: Device?) {
            main.post {
                resolving = false
                if (generation == epoch && info.serviceName in present && device != null)
                    found(info.serviceName, device)
                resolve()
            }
        }
        try {
            nsd.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, e: Int) {
                        finish(null)
                    }

                    override fun onServiceResolved(i: NsdServiceInfo) {
                        val ips =
                            if (Build.VERSION.SDK_INT >= 34) i.hostAddresses
                            else listOfNotNull(i.host)
                        val ip =
                            ips.filterIsInstance<Inet4Address>().firstOrNull {
                                it.isSiteLocalAddress || it.isLinkLocalAddress
                            }
                        finish(
                            if (ip != null && i.port in 1..65535)
                                Device(i.serviceName, ip.hostAddress!!, i.port)
                            else null
                        )
                    }
                },
            )
        } catch (e: Exception) {
            finish(null)
        }
    }

    fun stop() {
        epoch++
        main.removeCallbacks(timeout)
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        lock?.let { if (it.isHeld) it.release() }
        lock = null
        pending.clear()
        present.clear()
    }
}
