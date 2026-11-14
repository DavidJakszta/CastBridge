package dev.castbridge.core.discovery.mdns

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.ArrayDeque

/**
 * NsdManager only supports one active resolve call. This queue serialises resolutions and retries a
 * failed service, which smooths over transient `FAILURE_ALREADY_ACTIVE` errors when several
 * discoveries (Chromecast, Roku fallback) share the system resolver.
 */
@Suppress("DEPRECATION")
internal class ServiceResolutionQueue(
    private val nsdManager: NsdManager,
    private val onResolved: (NsdServiceInfo) -> Unit,
) : NsdManager.ResolveListener {
    private val lock = Any()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private val seen = mutableSetOf<String>()
    private val attempts = mutableMapOf<String, Int>()

    private var resolving = false
    private var currentServiceName: String? = null
    private var stopped = false

    fun enqueue(serviceInfo: NsdServiceInfo) {
        synchronized(lock) {
            if (stopped) return
            if (!seen.add(serviceInfo.serviceName)) return
            pending.addLast(serviceInfo)
            resolveNextLocked()
        }
    }

    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
        val deliver = synchronized(lock) { !stopped }
        if (deliver) {
            runCatching { onResolved(serviceInfo) }
                .onFailure { Log.d(TAG, "Could not map resolved service: ${it.message}") }
        }
        finishCurrent()
    }

    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
        val willRetry = synchronized(lock) {
            if (stopped) return

            val attempt = attempts.getOrDefault(serviceInfo.serviceName, 0) + 1
            attempts[serviceInfo.serviceName] = attempt
            if (attempt < MAX_ATTEMPTS) {
                pending.addLast(serviceInfo)
                true
            } else {
                false
            }
        }

        Log.d(
            TAG,
            "Resolve failed for ${serviceInfo.serviceName} (error $errorCode), retry=$willRetry",
        )
        finishCurrent()
    }

    fun stop() {
        synchronized(lock) {
            stopped = true
            pending.clear()
            seen.clear()
        }
    }

    private fun finishCurrent() {
        synchronized(lock) {
            resolving = false
            currentServiceName = null
            resolveNextLocked()
        }
    }

    private fun resolveNextLocked() {
        if (stopped || resolving) return
        val next = pending.pollFirst() ?: return

        resolving = true
        currentServiceName = next.serviceName
        try {
            nsdManager.resolveService(next, this)
        } catch (e: Exception) {
            Log.d(TAG, "resolveService failed for ${next.serviceName}: ${e.message}")
            resolving = false
            currentServiceName = null

            val attempt = attempts.getOrDefault(next.serviceName, 0) + 1
            attempts[next.serviceName] = attempt
            if (attempt < MAX_ATTEMPTS) {
                pending.addLast(next)
            }
            resolveNextLocked()
        }
    }

    private companion object {
        const val TAG = "CastResolver"
        const val MAX_ATTEMPTS = 3
    }
}
