package dev.castbridge.app

import android.app.Application
import dev.castbridge.app.di.AppContainer

class CastBridgeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
