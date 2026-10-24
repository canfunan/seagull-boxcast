package com.seagull.boxcast

import android.app.Application
import com.seagull.boxcast.net.BoxConnection

class BoxCastApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: BoxCastApp
            private set
    }
}
