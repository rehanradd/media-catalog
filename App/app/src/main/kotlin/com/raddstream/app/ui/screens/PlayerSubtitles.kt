package com.raddstream.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.raddstream.app.R
import com.raddstream.app.player.PlayerUiState
import com.raddstream.app.player.SubtitleColor
import com.raddstream.app.player.SubtitleEdge
import com.raddstream.app.player.SubtitleFont
import com.raddstream.app.player.SubtitleUiSettings
import com.raddstream.app.player.subtitle.AssAlignment
import com.raddstream.app.player.subtitle.OverlayCue
import com.raddstream.app.player.subtitle.assColorIsReadable
import com.raddstream.app.ui.theme.RaddCaptionPlate
import com.raddstream.app.ui.theme.brandSp
import com.raddstream.app.ui.theme.paint
import com.raddstream.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay


// ── Subtitle overlay (custom path: external SRT/VTT/ASS, delay honoured) ───────

private fun SubtitleFont.toFontFamily(): FontFamily = when (this) {
    SubtitleFont.SERIF -> FontFamily.Serif
    SubtitleFont.MONOSPACE -> FontFamily.Monospace
    SubtitleFont.DEFAULT -> FontFamily.Default
}

private fun AssAlignment.toComposeAlignment(): Alignment = when (this) {
    AssAlignment.BOTTOM_LEFT -> Alignment.BottomStart
    AssAlignment.BOTTOM_CENTER -> Alignment.BottomCenter
    AssAlignment.BOTTOM_RIGHT -> Alignment.BottomEnd
    AssAlignment.MIDDLE_LEFT -> Alignment.CenterStart
    AssAlignment.MIDDLE_CENTER -> Alignment.Center
    AssAlignment.MIDDLE_RIGHT -> Alignment.CenterEnd
    AssAlignment.TOP_LEFT -> Alignment.TopStart
    AssAlignment.TOP_CENTER -> Alignment.TopCenter
    AssAlignment.TOP_RIGHT -> Alignment.TopEnd
}

/**
 * One cue as styled text. A run's own colour wins when the file gave one; a file colour too
 * dark to read (the Robo run-4 rule) falls back to the viewer's colour instead of rendering
 * invisible text; a cue with no file colour at all (SRT/VTT) is the viewer's colour outright.
 * Size is the viewer's size, scaled by the cue style's size relative to the 20 pt default
 * and by any `\fs` on the run — so a fansub's big title line stays big, and the viewer's
 * size slider still moves everything. Weight and decoration follow the same rule as the
 * embedded-styles toggle: the file's style is the look when the file has one, and the
 * viewer's bold only fills in where the file says nothing (SRT/VTT).
 */
private fun OverlayCue.toAnnotatedString(viewer: SubtitleUiSettings): AnnotatedString {
    val viewerColor = Color(viewer.colour.argb)
    val builder = AnnotatedString.Builder()
    val sizeScale = (styleFontSizePt / 20f).coerceIn(0.25f, 4f)
    runs.forEach { run ->
        val fileColor = run.colorArgb ?: stylePrimaryArgb
        val color = when {
            fileColor == null -> viewerColor
            assColorIsReadable(fileColor) -> Color(fileColor)
            else -> viewerColor
        }
        val scale = sizeScale * (run.fontSizeScale ?: 1f)
        // Weight/decoration: the run's override wins, then the file style's, then — only
        // when the file says nothing at all (SRT/VTT) — the viewer's own bold.
        val bold = run.bold ?: styleBold ?: viewer.bold
        val italic = run.italic ?: styleItalic ?: false
        val underline = run.underline ?: styleUnderline ?: false
        val strike = run.strike ?: styleStrike ?: false
        builder.pushStyle(
            SpanStyle(
                color = color,
                fontSize = brandSp(16f * viewer.sizePercent / 100f * scale),
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                textDecoration = when {
                    underline && strike ->
                        TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                    underline -> TextDecoration.Underline
                    strike -> TextDecoration.LineThrough
                    else -> TextDecoration.None
                },
            ),
        )
        builder.append(run.text)
        builder.pop()
    }
    return builder.toAnnotatedString()
}

/**
 * The outline approximation, honestly labelled: Compose Text has no stroke, so the outline
 * is the same text drawn behind with a stroke `drawStyle` in solid black, and the filled
 * text drawn over it. The stroke is centred on the glyph edge, so the fill covers its inner
 * half and ~1.5 px of black shows outside — a readable edge on any frame, not a true
 * outline, and the sheet never claims otherwise.
 */
private fun outlinedAnnotated(annotated: AnnotatedString, outline: Color): AnnotatedString =
    AnnotatedString(
        text = annotated.text,
        spanStyles = annotated.spanStyles.map { range ->
            AnnotatedString.Range(range.item.copy(color = outline), range.start, range.end)
        },
        paragraphStyles = annotated.paragraphStyles,
    )

@Composable
internal fun SubtitleOverlay(state: PlayerUiState, vm: PlayerViewModel) {
    if (state.customSubtitleLoaded == null) return
    val cues = vm.overlayCues(state.positionMs)
    if (cues.isEmpty()) return
    val s = state.subtitle
    val fontFamily = s.font.toFontFamily()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The scope receiver is lost inside the forEach below, so the pane size is
        // captured here, once, and the cue loop reads the plain values.
        val paneWidth = maxWidth
        val paneHeight = maxHeight
        // \pos is absolute on the PlayRes canvas: the offset is measured from the cue's own
        // alignment anchor, so a bottom-centre line at \pos(192,240) sits with its bottom
        // centre at that point, exactly as libass places it.
        val anchorX = { a: AssAlignment ->
            when (a) {
                AssAlignment.BOTTOM_LEFT, AssAlignment.MIDDLE_LEFT, AssAlignment.TOP_LEFT -> 0f
                AssAlignment.BOTTOM_CENTER, AssAlignment.MIDDLE_CENTER, AssAlignment.TOP_CENTER -> 0.5f
                else -> 1f
            }
        }
        val anchorY = { a: AssAlignment ->
            when (a) {
                AssAlignment.TOP_LEFT, AssAlignment.TOP_CENTER, AssAlignment.TOP_RIGHT -> 0f
                AssAlignment.MIDDLE_LEFT, AssAlignment.MIDDLE_CENTER, AssAlignment.MIDDLE_RIGHT -> 0.5f
                else -> 1f
            }
        }
        cues.forEach { cue ->
            val isTop = cue.alignment in setOf(AssAlignment.TOP_LEFT, AssAlignment.TOP_CENTER, AssAlignment.TOP_RIGHT)
            val isMiddle = cue.alignment in setOf(AssAlignment.MIDDLE_LEFT, AssAlignment.MIDDLE_CENTER, AssAlignment.MIDDLE_RIGHT)
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = cue.alignment.toComposeAlignment(),
            ) {
                val annotated = cue.toAnnotatedString(s)
                val textAlign = when (cue.alignment) {
                    AssAlignment.BOTTOM_LEFT, AssAlignment.MIDDLE_LEFT, AssAlignment.TOP_LEFT -> TextAlign.Start
                    AssAlignment.BOTTOM_RIGHT, AssAlignment.MIDDLE_RIGHT, AssAlignment.TOP_RIGHT -> TextAlign.End
                    else -> TextAlign.Center
                }
                // The seat: the viewer's bottom fraction, plus the style's own margin. A
                // top-aligned cue is pushed down from the top instead, and a middle cue
                // keeps its centre (a margin on a centred line has nowhere honest to go).
                val seatPadding = paneHeight * (s.bottomFraction + cue.marginVFraction)
                val posOffset = cue.position?.let { pos ->
                    DpOffset(
                        x = paneWidth * (pos.xFraction - anchorX(cue.alignment)),
                        y = paneHeight * (pos.yFraction - anchorY(cue.alignment)),
                    )
                }
                val plate = if (s.textBackground) {
                    Modifier
                        .background(RaddCaptionPlate, MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                } else {
                    Modifier
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(
                            bottom = if (!isTop && !isMiddle) (72.dp + seatPadding) else 0.dp,
                            top = if (isTop) (72.dp + seatPadding) else 0.dp,
                        )
                        .then(if (posOffset != null) Modifier.offset(posOffset.x, posOffset.y) else Modifier),
                    contentAlignment = cue.alignment.toComposeAlignment(),
                ) {
                    if (s.edge == SubtitleEdge.OUTLINE) {
                        // No plate here: the main text below draws it once. Drawing the
                        // plate twice would stack its alpha and darken it.
                        Text(
                            outlinedAnnotated(annotated, Color.Black),
                            style = TextStyle(
                                fontFamily = fontFamily,
                                drawStyle = Stroke(width = 3f),
                            ),
                            textAlign = textAlign,
                        )
                    }
                    Text(
                        annotated,
                        fontFamily = fontFamily,
                        style = if (s.edge == SubtitleEdge.SHADOW) {
                            TextStyle(shadow = Shadow(color = Color.Black, offset = Offset(2f, 2f), blurRadius = 2f))
                        } else {
                            TextStyle.Default
                        },
                        textAlign = textAlign,
                        modifier = plate,
                    )
                }
            }
        }
    }
}

// ── Subtitle colour swatch ────────────────────────────────────────────────────

/**
 * One caption colour as a tappable dot: the dot is the colour, the halo says it is chosen, and
 * the press is named for what it does — a swatch that only a sighted viewer can tell the
 * difference between is a preference half of the audience cannot set.
 */
@Composable
internal fun SubtitleColourSwatch(colour: SubtitleColor, selected: Boolean, onSelect: () -> Unit) {
    val paint = colour.paint()
    val selectedSuffix = if (selected) stringResource(R.string.player_selected_suffix) else ""
    val spoken = stringResource(R.string.player_subtitles_for_colour, colour.label) + selectedSuffix
    ControlTarget(label = spoken, onClick = onSelect) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(if (selected) paint.copy(alpha = 0.26f) else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(paint, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = if (paint.luminance() > 0.5f) Color.Black else Color.White,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}
