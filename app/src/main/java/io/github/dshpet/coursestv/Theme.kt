package io.github.dshpet.coursestv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ProvideTextStyle
import androidx.tv.material3.darkColorScheme

/** The icon's charcoal and gold; gold only for progress and the current tab. */
object Palette {
    val background = Color(0xFF0D1015)
    val surface = Color(0xFF1A1E25)
    val panel = Color(0xF0121519)
    val text = Color.White
    val dim = Color(0xFFB4BAC4)
    val faint = Color(0xFF7D8590)
    val line = Color(0x40FFFFFF)
    val accent = Color(0xFFE2B254)
    val focus = Color.White
    val onFocus = Color(0xFF0D1015)
}

object Fonts {
    val heading = FontFamily(Font(R.font.dm_sans_semibold, FontWeight.SemiBold), Font(R.font.dm_sans_bold, FontWeight.Bold))
    val text = FontFamily(
        Font(R.font.figtree_regular, FontWeight.Normal),
        Font(R.font.figtree_medium, FontWeight.Medium),
        Font(R.font.figtree_semibold, FontWeight.SemiBold),
        Font(R.font.figtree_bold, FontWeight.Bold),
    )
}

object Type {
    val display = TextStyle(fontFamily = Fonts.heading, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp)
    val heading = TextStyle(fontFamily = Fonts.heading, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 26.sp)
    val body = TextStyle(fontFamily = Fonts.text, fontSize = 15.sp, lineHeight = 22.sp)
    val label = TextStyle(fontFamily = Fonts.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    val small = TextStyle(fontFamily = Fonts.text, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
}

/** [screenH]: the TV-safe side margin, 5% of 960 dp. */
object Dimens {
    val screenH = 48.dp
    val cardWidth = 196.dp
    val cardHeight = 110.dp
    val tallWidth = 136.dp
    val tallHeight = 181.dp
    val gap = 14.dp
}

@Composable
fun TvTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Palette.focus,
        onPrimary = Palette.onFocus,
        background = Palette.background,
        onBackground = Palette.text,
        surface = Palette.surface,
        onSurface = Palette.text,
        surfaceVariant = Palette.surface,
        onSurfaceVariant = Palette.text,
        border = Palette.focus,
    ),
) {
    CompositionLocalProvider(LocalContentColor provides Palette.text) {
        ProvideTextStyle(Type.body, content)
    }
}

/** Material Design icon paths. */
object Icons {
    val play = icon("M8,5v14l11,-7z")
    val pause = icon("M6,19h4V5H6v14zm8,-14v14h4V5h-4z")
    val search = icon(
        "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16" +
            "c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z",
    )
    val add = icon("M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
    val check = icon("M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z")
    val person = icon("M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zm0,2c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z")
    val next = icon("M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
    val list = icon("M3,13h2v-2H3v2zm0,4h2v-2H3v2zm0,-8h2V7H3v2zm4,4h14v-2H7v2zm0,4h14v-2H7v2zM7,7v2h14V7H7z")
    val book = icon(
        "M21,5c-1.11,-0.35 -2.33,-0.5 -3.5,-0.5c-1.95,0 -4.05,0.4 -5.5,1.5c-1.45,-1.1 -3.55,-1.5 -5.5,-1.5S2.45,4.9 1,6v14.65" +
            "c0,0.25 0.25,0.5 0.5,0.5c0.1,0 0.15,-0.05 0.25,-0.05C3.1,20.45 5.05,20 6.5,20c1.95,0 4.05,0.4 5.5,1.5c1.35,-0.85 3.8,-1.5 5.5,-1.5" +
            "c1.65,0 3.35,0.3 4.75,1.05c0.1,0.05 0.15,0.05 0.25,0.05c0.25,0 0.5,-0.25 0.5,-0.5V6c-0.6,-0.45 -1.25,-0.75 -2,-1z" +
            "M21,18.5c-1.1,-0.35 -2.3,-0.5 -3.5,-0.5c-1.7,0 -4.15,0.65 -5.5,1.5V8c1.35,-0.85 3.8,-1.5 5.5,-1.5c1.2,0 2.4,0.15 3.5,0.5v11.5z",
    )
    val captions = icon(
        "M19,4H5c-1.11,0 -2,0.9 -2,2v12c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2zm-8,7H9.5v-0.5h-2v3h2V13H11v1" +
            "c0,0.55 -0.45,1 -1,1H7c-0.55,0 -1,-0.45 -1,-1v-4c0,-0.55 0.45,-1 1,-1h3c0.55,0 1,0.45 1,1v1zm7,0h-1.5v-0.5h-2v3h2V13H18v1" +
            "c0,0.55 -0.45,1 -1,1h-3c-0.55,0 -1,-0.45 -1,-1v-4c0,-0.55 0.45,-1 1,-1h3c0.55,0 1,0.45 1,1v1z",
    )
    val settings = icon(
        "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58c0.18,-0.14 0.23,-0.41 0.12,-0.61" +
            "l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41" +
            "h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87" +
            "C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61" +
            "l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54c0.05,0.24 0.24,0.41 0.48,0.41h3.84" +
            "c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32" +
            "c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z",
    )

    private fun icon(path: String) = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White))
        .build()
}
