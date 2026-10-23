package com.fmswitch.update

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** Result of the installer session. Not exported: only this app's session talks to it. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val prefs = context.getSharedPreferences("fmswitch", Context.MODE_PRIVATE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmIntent(intent)
                if (confirm == null) {
                    prefs.edit().putString(PREF_INSTALL_STATUS, INSTALL_FAILED).apply()
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (_: ActivityNotFoundException) {
                    prefs.edit().putString(PREF_INSTALL_STATUS, INSTALL_FAILED).apply()
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                prefs.edit()
                    .remove(PREF_INSTALL_STATUS)
                    .remove(PREF_OFFER_VERSION)
                    .remove(PREF_OFFER_URL)
                    .remove(PREF_OFFER_SIZE)
                    .apply()
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                prefs.edit().putString(PREF_INSTALL_STATUS, INSTALL_ABORTED).apply()
            }
            else -> {
                prefs.edit().putString(PREF_INSTALL_STATUS, INSTALL_FAILED).apply()
            }
        }
    }

    companion object {
        const val ACTION = "com.fmswitch.UPDATE_INSTALL"
    }
}

private fun confirmIntent(intent: Intent): Intent? {
    return if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_INTENT)
    }
}
