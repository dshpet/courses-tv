package io.github.dshpet.coursestv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

class ProfessorActivity : TvActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ids = intent.getIntArrayExtra(EXTRA_IDS)?.toList()?.takeIf { it.isNotEmpty() } ?: return finish()
        show(marks = true) { Professors(ids) }
    }

    @Composable
    private fun Professors(ids: List<Int>) {
        when (val pages = loadKept { catalog.professors(ids, it) }) {
            Load.Loading -> SkeletonRows(Modifier.padding(top = 56.dp))
            is Load.Failed -> LoadError(pages, ::signInAgain)
            is Load.Ready -> {
                val first = remember { FocusRequester() }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 40.dp, bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(48.dp)) {
                    itemsIndexed(pages.value) { index, (professor, courses) ->
                        Section(professor, courses, if (index == 0) first else null)
                    }
                }
                // Kept answers may have none of the courses yet.
                val ready = pages.value.firstOrNull()?.second?.items?.isNotEmpty() == true
                LaunchedEffect(ready) { if (ready) first.requestFocus() }
            }
        }
    }

    @Composable
    private fun Section(professor: Professor, courses: Row, firstCard: FocusRequester?) {
        Column {
            Row(Modifier.padding(horizontal = Dimens.screenH).height(PHOTO_HEIGHT)) {
                AsyncImage(
                    professor.image,
                    contentDescription = professor.name,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.size(PHOTO_WIDTH, PHOTO_HEIGHT).clip(RoundedCornerShape(8.dp)).background(Palette.surface),
                )
                Column(Modifier.padding(start = 28.dp).width(620.dp).fillMaxHeight()) {
                    Text(professor.name, style = Type.display, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (professor.about.isNotEmpty()) Text(professor.about, style = Type.label, color = Palette.dim, modifier = Modifier.padding(top = 6.dp))
                    if (professor.bio.isNotEmpty()) Bio(professor.bio, Modifier.padding(top = 10.dp).weight(1f))
                }
            }
            if (courses.items.isNotEmpty()) {
                Box(Modifier.padding(top = 28.dp)) { ItemRow(courses, onFocus = {}, onClick = ::open, firstCard = firstCard) }
            }
        }
    }

    /** Text that doesn't fit takes the focus when Up reaches it; Up and Down then scroll it. */
    @Composable
    private fun Bio(text: String, modifier: Modifier) {
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        val step = with(LocalDensity.current) { 66.dp.toPx() }
        var focused by remember { mutableStateOf(false) }
        Box(
            modifier
                .offset(x = (-10).dp) // the text lines up with the name; the border sits outside it
                .border(2.dp, if (focused) Palette.focus else Color.Transparent, RoundedCornerShape(6.dp))
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    val by = when (event.key) {
                        Key.DirectionDown -> step.takeIf { scroll.canScrollForward }
                        Key.DirectionUp -> (-step).takeIf { scroll.canScrollBackward }
                        else -> null
                    } ?: return@onPreviewKeyEvent false
                    if (event.type == KeyEventType.KeyDown) scope.launch { scroll.animateScrollBy(by) }
                    true
                }
                .focusable(enabled = scroll.maxValue in 1 until Int.MAX_VALUE)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(text, color = Palette.text.copy(alpha = 0.85f), modifier = Modifier.verticalScroll(scroll))
            if (scroll.canScrollBackward) Fade(Modifier.align(Alignment.TopCenter), listOf(Palette.background, Color.Transparent))
            if (scroll.canScrollForward) Fade(Modifier.align(Alignment.BottomCenter), listOf(Color.Transparent, Palette.background))
        }
    }

    @Composable
    private fun Fade(modifier: Modifier, colors: List<Color>) = Box(modifier.fillMaxWidth().height(30.dp).background(Brush.verticalGradient(colors)))

    companion object {
        private const val EXTRA_IDS = "professors"
        private val PHOTO_WIDTH = 180.dp
        private val PHOTO_HEIGHT = 250.dp

        fun intent(context: Context, ids: List<Int>): Intent =
            Intent(context, ProfessorActivity::class.java).putExtra(EXTRA_IDS, ids.toIntArray())
    }
}
