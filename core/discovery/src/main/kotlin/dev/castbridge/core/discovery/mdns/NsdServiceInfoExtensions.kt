package dev.castbridge.core.discovery.mdns

import android.net.nsd.NsdServiceInfo
import android.os.Build

/** Returns the first resolved address, using the API 34+ list when available. */
@Suppress("DEPRECATION")
internal fun NsdServiceInfo.preferredHostAddress(): String? {
    val hostAddress = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        hostAddresses.firstOrNull()
    } else {
        host
    }
    return hostAddress?.hostAddress?.takeIf { it.isNotBlank() }
}
