package com.playceviri.tv

import android.app.Application
import com.playceviri.tv.util.DiagLog

class PlayCeviriApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagLog.attach(this)
    }
}
