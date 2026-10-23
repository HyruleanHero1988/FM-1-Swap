package com.fmswitch

import android.app.Application
import android.content.Context
import com.fmswitch.icon.AppIconApplier
import com.fmswitch.model.AppSettings

class FMSwitchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val settings = AppSettings(getSharedPreferences("fmswitch", Context.MODE_PRIVATE))
        AppIconApplier.apply(this, settings.appIcon)
    }
}
