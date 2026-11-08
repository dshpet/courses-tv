package io.github.dshpet.coursestv

import android.content.Context
import android.view.accessibility.CaptioningManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage

/** Milliseconds; the duration is 0 until known. */
data class PlaybackTime(val position: Long, val buffered: Long, val duration: Long) {
    fun fraction(millis: Long): Float = if (duration > 0) (millis.toFloat() / duration).coerceIn(0f, 1f) else 0f
}

class PlayerButton(val label: String, val icon: ImageVector, val action: () -> Unit)

/** A settings panel line: a section's title, or a choice in it. */
class SettingsLine(val label: String, val selected: Boolean = false, val choose: (() -> Unit)? = null)

val SETTINGS_WIDTH = 300.dp

/** The titles, seek bar and buttons; [onBar]: the seek bar has the remote, else button [selected]. */
@Composable
fun PlayerControls(course: Course, lecture: Lecture?, playWhenReady: Boolean, time: PlaybackTime, speed: Float, onBar: Boolean, buttons: List<PlayerButton>, selected: Int) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xE6000000), Color(0x99000000), Color.Transparent)))
                .padding(start = Dimens.screenH, end = Dimens.screenH, top = 30.dp, bottom = 50.dp),
        ) {
            Text(course.title, style = Type.label, color = Palette.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (lecture != null) {
                Text(
                    lecture.numberedTitle,
                    style = Type.display,
                    fontSize = 26.sp,
                    lineHeight = 32.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000), Color(0xF2000000))))
                .padding(start = Dimens.screenH, end = Dimens.screenH, top = 60.dp, bottom = 34.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (playWhenReady) Icons.pause else Icons.play, contentDescription = null, tint = Palette.text, modifier = Modifier.size(30.dp))
                Text(clock(time.position), style = Type.label, fontSize = 15.sp, modifier = Modifier.padding(start = 14.dp, end = 14.dp))
                SeekBar(time, active = onBar, modifier = Modifier.weight(1f))
                Text(
                    listOfNotNull(
                        if (time.duration > 0) "−" + clock(time.duration - time.position) else "−–:––",
                        if (speed != 1f) "${speedLabel(speed)}×" else null,
                    ).joinToString("   "),
                    style = Type.label,
                    fontSize = 15.sp,
                    color = Palette.dim,
                    modifier = Modifier.padding(start = 14.dp),
                )
            }
            Row(Modifier.padding(top = 18.dp, start = 44.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                buttons.forEachIndexed { index, button ->
                    val on = !onBar && index == selected
                    Row(
                        Modifier.clip(RoundedCornerShape(50))
                            .background(if (on) Palette.focus else Color.Transparent)
                            .border(1.5.dp, if (on) Palette.focus else Palette.text.copy(alpha = 0.6f), RoundedCornerShape(50))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(button.icon, contentDescription = null, tint = if (on) Palette.onFocus else Palette.text, modifier = Modifier.size(20.dp))
                        Text(button.label, style = Type.label, fontSize = 15.sp, color = if (on) Palette.onFocus else Palette.text, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsPanel(lines: List<SettingsLine>, at: SettingsLine?) {
    Column(Modifier.fillMaxHeight().width(SETTINGS_WIDTH).background(Palette.panel).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 36.dp)) {
        lines.forEach { line ->
            if (line.choose == null) {
                Text(line.label, style = Type.heading, fontSize = 18.sp, modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp))
            } else {
                val on = line === at
                // Keeps the choice in view with a line around it, so a section's title shows above its first.
                val view = remember { BringIntoViewRequester() }
                val margin = with(LocalDensity.current) { 48.dp.toPx() }
                LaunchedEffect(on) { if (on) view.bringIntoView(Rect(0f, -margin, 1f, margin * 1.5f)) }
                Row(
                    Modifier.fillMaxWidth().bringIntoViewRequester(view).clip(RoundedCornerShape(6.dp)).background(if (on) Palette.focus else Color.Transparent).padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(22.dp)) {
                        if (line.selected) Icon(Icons.check, contentDescription = null, tint = if (on) Palette.onFocus else Palette.accent, modifier = Modifier.size(20.dp))
                    }
                    Text(line.label, style = Type.label, color = if (on) Palette.onFocus else Palette.text, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun SeekBar(time: PlaybackTime, active: Boolean, modifier: Modifier) {
    Box(modifier.height(20.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0x40FFFFFF)))
        Box(Modifier.fillMaxWidth(time.fraction(time.buffered)).height(4.dp).background(Color(0x66FFFFFF)))
        Box(Modifier.fillMaxWidth(time.fraction(time.position)).height(4.dp).background(Palette.accent))
        Box(Modifier.fillMaxWidth(time.fraction(time.position)), contentAlignment = Alignment.CenterEnd) {
            Box(Modifier.offset(x = 7.dp).size(if (active) 16.dp else 10.dp).background(if (active) Palette.text else Palette.accent, CircleShape))
        }
    }
}

/** In the TV's caption style ([look]), at its text size times [size]. */
@Composable
fun Captions(cues: List<String>, look: CaptionLook, size: Float, lifted: Boolean, modifier: Modifier) {
    if (cues.isEmpty()) return
    val scale = look.scale * size
    Column(modifier.padding(bottom = if (lifted) 170.dp else 48.dp, start = 100.dp, end = 100.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        cues.forEach { cue ->
            Text(
                cue,
                style = Type.body.copy(shadow = look.shadow),
                color = look.color,
                fontSize = 22.sp * scale,
                lineHeight = 30.sp * scale,
                textAlign = TextAlign.Center,
                modifier = Modifier.background(look.background, RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
fun UpNext(next: Lecture, seconds: Int) {
    Row(
        Modifier.padding(end = Dimens.screenH, bottom = 40.dp).width(440.dp)
            .background(Palette.panel, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(next.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(128.dp, 72.dp).clip(RoundedCornerShape(4.dp)))
        Column(Modifier.padding(start = 14.dp)) {
            Text("Next lecture in $seconds s", style = Type.small, color = Palette.dim)
            Text(next.numberedTitle, style = Type.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("Select to play now", style = Type.small, color = Palette.dim)
        }
    }
}

@Composable
fun Spinner(modifier: Modifier = Modifier) {
    val angle by rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "angle",
    )
    Canvas(modifier.size(56.dp)) {
        drawArc(Palette.text, startAngle = angle, sweepAngle = 270f, useCenter = false, style = Stroke(width = 4.dp.toPx()))
    }
}

fun speedLabel(speed: Float) = if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()

private fun clock(millis: Long): String {
    val total = (millis.coerceAtLeast(0) / 1000).toInt()
    val h = total / 3600
    val m = total / 60 % 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** The TV's caption settings (Settings → Accessibility → Captions), read when the player opens. */
class CaptionLook(context: Context) {
    private val manager = context.getSystemService(CaptioningManager::class.java)
    private val style = manager?.userStyle

    val enabled = manager?.isEnabled == true
    val scale = manager?.fontScale ?: 1f
    val color = style?.takeIf { it.hasForegroundColor() }?.let { Color(it.foregroundColor) } ?: Color.White
    val background = style?.takeIf { it.hasBackgroundColor() }?.let { Color(it.backgroundColor) } ?: Color(0xB3000000)

    // Outline, raised and depressed edges are drawn as a soft shadow; drop shadow, offset.
    val shadow: Shadow? = style?.takeIf { it.hasEdgeType() && it.edgeType != CaptioningManager.CaptionStyle.EDGE_TYPE_NONE }?.let {
        val edge = if (it.hasEdgeColor()) Color(it.edgeColor) else Color.Black
        val drop = it.edgeType == CaptioningManager.CaptionStyle.EDGE_TYPE_DROP_SHADOW
        Shadow(edge, if (drop) Offset(3f, 3f) else Offset.Zero, blurRadius = if (drop) 3f else 6f)
    }
}
