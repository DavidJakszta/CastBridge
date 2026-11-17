package dev.castbridge.app.di

import android.content.Context
import dev.castbridge.core.capture.CaptureController
import dev.castbridge.core.casting.CastingController
import dev.castbridge.core.discovery.chromecast.ChromecastDiscovery
import dev.castbridge.core.discovery.probe.DefaultDeviceProbe
import dev.castbridge.core.discovery.repository.DeviceDiscoveryRepository
import dev.castbridge.core.discovery.roku.RokuDiscovery
import dev.castbridge.core.model.DeviceProbe

/**
 * Manual dependency container: one repository for the process lifetime, so discovery state survives
 * activity recreation. A DI framework can replace this without touching feature code.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val deviceRepository: DeviceDiscoveryRepository by lazy {
        DeviceDiscoveryRepository(
            discoveries = listOf(
                ChromecastDiscovery(appContext),
                RokuDiscovery(appContext),
            ),
        )
    }

    val deviceProbe: DeviceProbe by lazy { DefaultDeviceProbe() }

    val captureController: CaptureController by lazy { CaptureController(appContext) }

    val castingController: CastingController by lazy { CastingController() }
}
