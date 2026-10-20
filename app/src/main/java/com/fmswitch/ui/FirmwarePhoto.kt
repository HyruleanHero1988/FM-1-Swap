package com.fmswitch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.fmswitch.theme.LocalPhosphor
import com.fmswitch.theme.pixelText

@Composable
fun FirmwarePhoto(
    url: String?,
    alt: String?,
    modifier: Modifier = Modifier,
    placeholder: String = "NO SHOT",
) {
    val theme = LocalPhosphor.current
    Box(
        modifier
            .background(theme.surface)
            .border(1.dp, theme.muted),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            BasicText(
                placeholder.uppercase(),
                style = pixelText(8.sp, bold = true, color = theme.muted),
                modifier = Modifier.padding(4.dp),
            )
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = alt,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    BasicText(
                        "…",
                        style = pixelText(10.sp, bold = true, color = theme.muted),
                    )
                },
                error = {
                    BasicText(
                        placeholder.uppercase(),
                        style = pixelText(8.sp, bold = true, color = theme.muted),
                        modifier = Modifier.padding(4.dp),
                    )
                },
            )
        }
    }
}
