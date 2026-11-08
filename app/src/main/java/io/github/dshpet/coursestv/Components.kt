package io.github.dshpet.coursestv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    class Failed(val error: Throwable, val retry: () -> Unit) : Load<Nothing>
}

/** Bumped each time the screen comes back to the front, to read personal data again. */
val LocalResumes = compositionLocalOf { 0 }

/** How much of each course has been watched, by course id; see [TvActivity.show]. */
val LocalMarks = compositionLocalOf { emptyMap<Int, Mark>() }

/**
 * Runs [block] whenever [keys] change. The last result stays on screen while it reloads and if a
 * reload fails, unless the session has ended or [keepOnFailure] is false (results that answer the
 * keys, such as a search's).
 */
@Composable
fun <T> load(vararg keys: Any?, keepOnFailure: Boolean = true, block: suspend () -> T): Load<T> = loading(keys, keepOnFailure, null, block)

/** [load] that shows what the TV kept from last time at once, while the screen has nothing yet. */
@Composable
fun <T> loadKept(vararg keys: Any?, block: suspend (Source) -> T): Load<T> = loading(keys, true, { block(Source.Kept) }) { block(Source.Fresh) }

@Composable
private fun <T> loading(keys: Array<out Any?>, keepOnFailure: Boolean, kept: (suspend () -> T)?, fresh: suspend () -> T): Load<T> {
    var tries by remember { mutableIntStateOf(0) }
    return produceState<Load<T>>(Load.Loading, *keys, tries) {
        val answer = async { attempt { fresh() } }
        // Shown only if it comes first: the fresh answer may already be in memory.
        val early = if (kept != null && value !is Load.Ready) launch { attempt { kept() }.onSuccess { if (!answer.isCompleted) value = Load.Ready(it) } } else null
        val result = answer.await()
        early?.cancel()
        value = result.fold({ Load.Ready(it) }) { e ->
            (value as? Load.Ready).takeIf { keepOnFailure && e !is SignedOutException } ?: Load.Failed(e) { tries++ }
        }
    }.value
}

/** [value] once it has held for a moment, so the hero doesn't flicker while scrolling. */
@Composable
fun <T> settled(value: T, millis: Long = 180): T {
    var shown by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        delay(millis)
        shown = value
    }
    return shown
}

/** Scrolls the focused child to [leading] from the start. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PivotScroll(leading: Dp, content: @Composable () -> Unit) {
    val px = with(LocalDensity.current) { leading.toPx() }
    val spec = remember(px) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - px
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

@Composable
fun ItemCard(item: Item, tall: Boolean, onFocus: (Item) -> Unit, onClick: (Item) -> Unit, modifier: Modifier = Modifier, onLongClick: ((Item) -> Unit)? = null) {
    val shape = RoundedCornerShape(6.dp)
    Card(
        onClick = { onClick(item) },
        onLongClick = onLongClick?.let { { it(item) } },
        modifier = modifier
            .size(if (tall) Dimens.tallWidth else Dimens.cardWidth, if (tall) Dimens.tallHeight else Dimens.cardHeight)
            .onFocusChanged { if (it.isFocused) onFocus(item) },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(containerColor = Palette.surface),
        scale = CardDefaults.scale(focusedScale = 1.05f),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Palette.focus), shape = shape)),
        glow = CardDefaults.glow(),
    ) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(model = item.image, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            // Lecture pictures have no title of their own.
            if (item.lecture > 0) Caption(item.title)
            // Continue Watching shows its lecture's progress; other course cards, the course's.
            val mark = LocalMarks.current[item.courseId]?.takeIf { !item.plays && item.lecture == 0 && item.subject.isEmpty() }
            when {
                mark?.finished == true -> WatchedBadge(Modifier.align(Alignment.TopEnd))
                mark != null -> ProgressLine(mark.fraction, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                item.progress > 0f -> ProgressLine(item.progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            }
        }
    }
}

// Dark enough to hide a course title in the picture.
@Composable
private fun BoxScope.Caption(text: String) {
    Box(
        Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color(0xF0000000), 1f to Color.Black))
            .padding(start = 10.dp, end = 10.dp, top = 22.dp, bottom = 12.dp),
    ) {
        Text(text, style = Type.small, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun WatchedBadge(modifier: Modifier) {
    Box(modifier.padding(6.dp).size(24.dp).background(Palette.background.copy(alpha = 0.8f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.check, contentDescription = "Watched", tint = Palette.text, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).background(Color(0x4DFFFFFF))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(Palette.accent))
    }
}

/** Rows of cards; the focused row docks under the hero. */
@Composable
fun RowList(
    rows: List<Row>,
    onFocus: (Item) -> Unit,
    onClick: (Item) -> Unit,
    modifier: Modifier = Modifier,
    firstCard: FocusRequester? = null,
    onLongClick: ((Item) -> Unit)? = null,
) {
    val state = rememberLazyListState()
    // At its start, the list stays there when a row is put in front (it would follow its first row).
    remember(rows) { (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0).also { if (it) state.requestScrollToItem(0) } }
    PivotScroll(leading = 34.dp) {
        LazyColumn(modifier, state, contentPadding = PaddingValues(top = 4.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            itemsIndexed(rows, key = { _, row -> row.title }) { index, row ->
                ItemRow(row, onFocus, onClick, if (index == 0) firstCard else null, onLongClick)
            }
        }
    }
}

/** [onLongClick] applies to cards that play. */
@Composable
fun ItemRow(row: Row, onFocus: (Item) -> Unit, onClick: (Item) -> Unit, firstCard: FocusRequester? = null, onLongClick: ((Item) -> Unit)? = null) {
    val state = rememberLazyListState()
    val start = firstCard ?: remember { FocusRequester() }
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var atStart by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    remember(row.items) { keepPlace(row.items, focusedKey, atStart, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) { state.requestScrollToItem(it) } }
    // At the start of the row, the focus stays at the start, on a newer card put in front.
    LaunchedEffect(row.items) {
        if (focused && atStart && row.items.firstOrNull()?.key != focusedKey) start.requestFocus()
    }
    Column {
        Text(row.title, style = Type.heading, modifier = Modifier.padding(start = Dimens.screenH, bottom = 8.dp))
        PivotScroll(leading = Dimens.screenH) {
            LazyRow(
                state = state,
                modifier = Modifier.focusRestorer().onFocusChanged { focused = it.hasFocus },
                contentPadding = PaddingValues(horizontal = Dimens.screenH, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(Dimens.gap),
            ) {
                itemsIndexed(row.items, key = { _, item -> item.key }) { index, item ->
                    val focus = if (index == 0) Modifier.focusRequester(start) else Modifier
                    val onCardFocus = { card: Item ->
                        focusedKey = card.key
                        atStart = index == 0
                        onFocus(card)
                    }
                    ItemCard(item, row.tall, onCardFocus, onClick, focus, onLongClick.takeIf { item.plays })
                }
            }
        }
    }
}

/**
 * Where a reloaded list stays: on the focused card, or it loses the focus; at its start if it was
 * there (keyed lists otherwise follow their first card, hiding a newer one put in front).
 */
private fun keepPlace(items: List<Item>, focusedKey: String?, atStart: Boolean, first: Int, offset: Int, scrollTo: (Int) -> Unit): Int {
    val index = items.indexOfFirst { it.key == focusedKey }
    when {
        atStart || (first == 0 && offset == 0) -> scrollTo(0)
        index >= 0 -> scrollTo(index)
    }
    return index
}

@Composable
fun ItemGrid(
    items: List<Item>,
    onFocus: (Item) -> Unit,
    onClick: (Item) -> Unit,
    modifier: Modifier = Modifier,
    first: FocusRequester? = null,
) {
    val state = rememberLazyGridState()
    var focusedKey by remember { mutableStateOf<String?>(null) }
    remember(items) { keepPlace(items, focusedKey, false, state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) { state.requestScrollToItem(it) } }
    PivotScroll(leading = 12.dp) {
        LazyVerticalGrid(
            columns = GridCells.FixedSize(Dimens.cardWidth),
            state = state,
            modifier = modifier.focusRestorer(),
            contentPadding = PaddingValues(start = Dimens.screenH, end = Dimens.screenH, top = 8.dp, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(Dimens.gap),
            verticalArrangement = Arrangement.spacedBy(Dimens.gap + 4.dp),
        ) {
            itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                ItemCard(item, tall = false, { focusedKey = it.key; onFocus(it) }, onClick, if (index == 0 && first != null) Modifier.focusRequester(first) else Modifier)
            }
        }
    }
}

/** The focused title's picture, top right, fading into the background. */
@Composable
fun Backdrop(item: Item?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        Crossfade(
            targetState = item?.let { it.backdrop to it.image },
            animationSpec = tween(450),
            modifier = Modifier.align(Alignment.TopEnd).fillMaxWidth(0.7f).fillMaxHeight(0.78f),
            label = "backdrop",
        ) { pictures ->
            if (pictures != null) {
                // The card's picture only if the backdrop is missing.
                var failed by remember(pictures) { mutableStateOf(false) }
                AsyncImage(
                    model = if (failed) pictures.second else pictures.first,
                    contentDescription = null,
                    onError = { failed = true },
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize().scrim(),
                )
            }
        }
    }
}

/** Fades a picture into the background at its left and bottom; darkens the top for the menu. */
fun Modifier.scrim(): Modifier = drawWithContent {
    drawContent()
    drawRect(Brush.horizontalGradient(0f to Palette.background, 0.45f to Palette.background.copy(alpha = 0.55f), 1f to Color.Transparent))
    drawRect(
        Brush.verticalGradient(
            0f to Palette.background.copy(alpha = 0.95f),
            0.1f to Palette.background.copy(alpha = 0.85f),
            0.22f to Palette.background.copy(alpha = 0.4f),
            0.34f to Color.Transparent,
            0.45f to Color.Transparent,
            1f to Palette.background,
        ),
    )
}

@Composable
fun HeroInfo(item: Item?, modifier: Modifier = Modifier) {
    Column(modifier.padding(start = Dimens.screenH).width(540.dp)) {
        if (item == null) return@Column
        Text(item.title, style = Type.display, fontSize = 30.sp, lineHeight = 34.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (item.subtitle.isNotEmpty()) {
            Text(item.subtitle, style = Type.label, color = Palette.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        }
        if (item.description.isNotEmpty()) {
            Text(item.description, color = Palette.text.copy(alpha = 0.8f), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** An outlined button; white when focused. */
@Composable
fun TvButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val shape = RoundedCornerShape(50)
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Palette.text,
            focusedContainerColor = Palette.focus,
            focusedContentColor = Palette.onFocus,
        ),
        border = ButtonDefaults.border(
            border = Border(BorderStroke(1.5.dp, Palette.text.copy(alpha = 0.6f)), shape = shape),
            focusedBorder = Border(BorderStroke(1.5.dp, Palette.focus), shape = shape),
        ),
        scale = ButtonDefaults.scale(focusedScale = 1.04f),
        glow = ButtonDefaults.glow(),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = Type.label)
    }
}

/** A full-screen message with actions; the first action takes focus. */
@Composable
fun Message(title: String, text: String, vararg actions: Pair<String, () -> Unit>) {
    val first = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(horizontal = 160.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = Type.display, fontSize = 28.sp, textAlign = TextAlign.Center)
        Text(text, style = Type.body, fontSize = 17.sp, color = Palette.dim, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp, bottom = 28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            actions.forEachIndexed { index, (label, action) ->
                TvButton(label, action, if (index == 0) Modifier.focusRequester(first) else Modifier)
            }
        }
    }
    if (actions.isNotEmpty()) {
        LaunchedEffect(Unit) {
            withFrameNanos { } // once laid out
            first.requestFocus()
        }
    }
}

@Composable
fun LoadError(failed: Load.Failed, signIn: () -> Unit) = if (failed.error is SignedOutException) {
    Message("Signed out", "Your session has ended. Sign in again to continue.", "Sign in" to signIn)
} else {
    Message("Can’t connect", "The courses didn’t load. Check the TV’s internet connection and try again.", "Try again" to failed.retry)
}

@Composable
fun SkeletonRows(modifier: Modifier = Modifier) {
    val shade = Palette.surface
    Column(modifier.padding(start = Dimens.screenH), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        repeat(2) {
            Column {
                Box(Modifier.size(180.dp, 16.dp).background(shade, RoundedCornerShape(4.dp)))
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(Dimens.gap)) {
                    repeat(5) { Box(Modifier.size(Dimens.cardWidth, Dimens.cardHeight).background(shade, RoundedCornerShape(6.dp))) }
                }
            }
        }
    }
}

/** A menu over the screen; Back closes it. */
@Composable
fun OptionsDialog(title: String, detail: String, actions: List<Pair<String, () -> Unit>>, note: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val first = remember { FocusRequester() }
        Column(Modifier.width(460.dp).background(Palette.surface, RoundedCornerShape(10.dp)).padding(28.dp)) {
            Text(title, style = Type.heading, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, color = Palette.dim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                actions.forEachIndexed { index, (label, action) ->
                    TvButton(label, action, Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(first) else Modifier))
                }
            }
            if (note.isNotEmpty()) Text(note, color = Palette.accent, modifier = Modifier.padding(top = 14.dp))
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            first.requestFocus()
        }
    }
}
