package com.fmswitch.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.fmswitch.catalogue.CatalogueEntry
import com.fmswitch.catalogue.FirmwareEmulator
import com.fmswitch.theme.LocalPhosphor
import com.fmswitch.theme.MonoText
import com.fmswitch.theme.PixelButton
import com.fmswitch.theme.pixelText

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EmulatorScreen(entry: CatalogueEntry, onBack: () -> Unit) {
    val theme = LocalPhosphor.current
    val context = LocalContext.current
    val launch = remember(entry.id, entry.links) { FirmwareEmulator.launch(entry) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        onDispose {
            previous?.let { activity.requestedOrientation = it }
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(theme.background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PixelButton("◂ BACK", compact = true, onClick = onBack)
            PixelButton("BROWSER", compact = true) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(launch.url)))
            }
        }
        BasicText(entry.name.uppercase(), style = pixelText(16.sp, bold = true, color = theme.bright, glow = true))
        MonoText(launch.hint, size = 13)
        MonoText(launch.credit, size = 12, dim = true)
        error?.let { MonoText(it, size = 13) }
        if (loading && error == null) MonoText("LOADING THE EMULATOR…", size = 13, dim = true)
        Box(Modifier.weight(1f).fillMaxWidth().background(theme.surface)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        isNestedScrollingEnabled = true
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                            }
                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                resourceError: WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true) {
                                    loading = false
                                    error = resourceError?.description?.toString()
                                        ?: "The emulator page failed to load. Use BROWSER if this WebView can't run it."
                                }
                            }
                        }
                        loadUrl(launch.url)
                    }
                },
                onRelease = { web ->
                    web.stopLoading()
                    web.loadUrl("about:blank")
                    (web.parent as? ViewGroup)?.removeView(web)
                    web.destroy()
                },
            )
        }
    }
}
