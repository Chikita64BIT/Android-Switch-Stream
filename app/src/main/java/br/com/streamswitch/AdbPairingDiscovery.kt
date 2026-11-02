package br.com.streamswitch

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Finds this phone's short-lived ADB pairing port while its pairing-code screen is open. */
internal object AdbPairingDiscovery {
    fun findOwnPort(context: Context, localAddresses: List<InetAddress>): Int? {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val foundPort = AtomicReference<Int?>(null)
        val resolving = AtomicBoolean(false)
        val finished = CountDownLatch(1)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceName.startsWith("adb-", ignoreCase = true) ||
                    !resolving.compareAndSet(false, true)
                ) return
                try {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val host = resolved.host
                            if (host != null && localAddresses.any { it == host } && resolved.port in 1..65535) {
                                foundPort.set(resolved.port)
                                finished.countDown()
                            } else {
                                resolving.set(false)
                            }
                        }

                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            resolving.set(false)
                        }
                    })
                } catch (_: Exception) {
                    resolving.set(false)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                finished.countDown()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        try {
            nsd.discoverServices("_adb-tls-pairing._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            finished.await(4, TimeUnit.SECONDS)
        } finally {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (_: Exception) {
                // Discovery may already have stopped after a framework error.
            }
        }
        return foundPort.get()
    }
}
