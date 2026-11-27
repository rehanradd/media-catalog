package com.raddstream.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.raddstream.app.ui.theme.RaddHeroPlaceholderHigh
import com.raddstream.app.ui.theme.RaddHeroPlaceholderLow
import com.raddstream.app.ui.theme.RaddStreamMark
import kotlin.math.min

/**
 * Video audit issue 5 (Nov 2026): the bundled welcome poster — the branded
 * art every rail shows the moment the app is installed, before any poster
 * has arrived from the server. As each real poster loads it crossfades in
 * over this (the [CatalogImage] loading slot); a poster that never loads
 * keeps this instead of a dark monogram box.
 *
 * Brand/generic art only, never fake content: a jade gradient, the Radd
 * mark at 48dp, and two faint arcs echoing the mark's rings. No title, no
 * initials, no text of any kind — a placeholder must never pretend to be a
 * title. Code-drawn, so the APK does not grow by a single bitmap.
 *
 * The loading slot uses this whenever the caller has no accent tint yet
 * (see [CatalogImage]); the error and no-URL states keep
 * [ImageFallbackCard], which is the honest "this artwork failed" surface.
 */
@Composable
fun WelcomePoster(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(RaddHeroPlaceholderHigh, RaddHeroPlaceholderLow),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Two faint arcs around the mark — the mark's own rings, enlarged
        // and thinned to a texture. Alpha stays low on purpose: this is a
        // placeholder, not artwork, and it must never compete with the
        // poster that lands on top of it.
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val base = min(size.width, size.height)
            val arcColor = Color.White.copy(alpha = 0.05f)
            val stroke = 2.dp.toPx()
            val rOuter = base * 0.44f
            drawArc(
                color = arcColor,
                startAngle = -70f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(cx - rOuter, cy - rOuter),
                size = Size(rOuter * 2f, rOuter * 2f),
                style = Stroke(width = stroke),
            )
            val rInner = base * 0.34f
            drawArc(
                color = arcColor,
                startAngle = 110f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(cx - rInner, cy - rInner),
                size = Size(rInner * 2f, rInner * 2f),
                style = Stroke(width = stroke),
            )
        }
        RaddStreamMark(
            diameter = 48.dp,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
