package com.fmswitch

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.fmswitch.model.AppViewModel
import com.fmswitch.ui.RootScreen

class MainActivity : ComponentActivity() {
    private val model: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        leaveUsbDeviceClosed(intent)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            LaunchedEffect(model.isWriting, model.playingFirmware) {
                if (model.isWriting || model.playingFirmware != null) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            RootScreen(model)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        leaveUsbDeviceClosed(intent)
        setIntent(intent)
        if (!model.isWriting) model.scan(force = false)
    }

    /**
     * The plug-in prompt includes the UsbDevice. Leave it closed.
     * UsbManager.openDevice takes the port from the MIDI service, the FM-1
     * drops USB, and the prompt comes back.
     */
    private fun leaveUsbDeviceClosed(intent: Intent?) {
        intent?.removeExtra(UsbManager.EXTRA_DEVICE)
    }

    override fun onResume() {
        super.onResume()
        if (!model.isWriting) model.scan(force = false)
    }
}
