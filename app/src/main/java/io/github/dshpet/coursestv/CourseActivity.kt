package io.github.dshpet.coursestv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

class CourseActivity : TvActivity() {

    /** The lecture to focus (0: the Play button); [played]: back from the player. */
    private data class Target(val lecture: Int, val request: Int = 0, val played: Boolean = false)

    private var target by mutableStateOf(Target(0))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(EXTRA_COURSE, 0).takeIf { it > 0 } ?: return finish()
        target = Target(intent.getIntExtra(EXTRA_LECTURE, 0))
        show(marks = true) { CourseScreen(id) }
    }

    // The player's Lectures button.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val sameCourse = intent.getIntExtra(EXTRA_COURSE, 0) == this.intent.getIntExtra(EXTRA_COURSE, 0)
        setIntent(intent)
        if (sameCourse) target = Target(intent.getIntExtra(EXTRA_LECTURE, 0), target.request + 1) else recreate()
    }

    override fun played(lecture: Int) {
        target = Target(lecture, target.request + 1, played = true)
    }

    @Composable
    private fun CourseScreen(id: Int) {
        var reloads by remember { mutableIntStateOf(0) }
        when (val course = loadKept(reloads, LocalResumes.current) { catalog.course(id, it) }) {
            Load.Loading -> Backdrop(Item("", Images.course(id), backdrop = Images.backdrop(id)))
            is Load.Failed -> LoadError(course, ::signInAgain)
            is Load.Ready -> Details(course.value, reload = { reloads++ })
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun Details(course: Course, reload: () -> Unit) {
        // Null while the buttons have the focus.
        var focused by remember { mutableStateOf<Lecture?>(null) }
        // A card in the professor's rows.
        var card by remember { mutableStateOf<Item?>(null) }
        var options by remember { mutableStateOf<Lecture?>(null) }
        var pageFocused by remember { mutableStateOf(false) }
        val next = course.nextUp
        val picture = settled(card ?: (focused ?: next)?.let { Item(it.title, it.image) } ?: Item(course.title, course.image, backdrop = Images.backdrop(course.id)))
        val more = (loadKept(course.id) { catalog.moreFrom(course, it) } as? Load.Ready)?.value.orEmpty()
        val list = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val play = remember { FocusRequester() }
        val numbers = course.lectures.map { it.number }
        val rows = remember(numbers) { numbers.associateWith { FocusRequester() } }

        // Darker behind the lecture list, from the moment a lecture has the focus.
        val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
        val shade by animateFloatAsState(if (scrolled || focused != null) 0.72f else 0f, tween(300), label = "shade")

        Box(Modifier.fillMaxSize()) {
            Backdrop(picture)
            Box(Modifier.fillMaxSize().background(Palette.background.copy(alpha = shade)))
            CompositionLocalProvider(LocalBringIntoViewSpec provides ComfortZone) {
                LazyColumn(Modifier.fillMaxSize().onFocusChanged { pageFocused = it.hasFocus }, state = list, contentPadding = PaddingValues(bottom = 60.dp)) {
                    item {
                        Header(
                            course,
                            next,
                            play,
                            Modifier
                                .onFocusChanged {
                                    if (!it.hasFocus) return@onFocusChanged
                                    focused = null
                                    card = null
                                    scope.launch { list.animateScrollToItem(0) }
                                }
                                // Down goes to the lecture Play plays, not to lecture 1.
                                .onPreviewKeyEvent { event ->
                                    val number = next?.number ?: return@onPreviewKeyEvent false
                                    if (event.key != Key.DirectionDown) return@onPreviewKeyEvent false
                                    if (event.type == KeyEventType.KeyDown) scope.launch { focusLecture(course, number, list, rows) }
                                    true
                                },
                        )
                    }
                    item {
                        Text("Lectures", style = Type.heading, modifier = Modifier.padding(start = Dimens.screenH, top = 8.dp, bottom = 6.dp))
                    }
                    items(course.lectures, key = { it.number }) { lecture ->
                        val first = lecture == course.lectures.first()
                        LectureRow(
                            lecture,
                            onFocus = {
                                focused = lecture
                                card = null
                            },
                            onClick = { play(course.id, lecture.number) },
                            onLongClick = { options = lecture },
                            modifier = Modifier
                                .then(rows[lecture.number]?.let { Modifier.focusRequester(it) } ?: Modifier)
                                // Up from the first lecture goes to Play, not to the nearest button.
                                .onPreviewKeyEvent { event ->
                                    if (!first || event.key != Key.DirectionUp) return@onPreviewKeyEvent false
                                    if (event.type == KeyEventType.KeyDown) {
                                        scope.launch {
                                            list.scrollToItem(0)
                                            withFrameNanos { }
                                            play.requestFocus()
                                        }
                                    }
                                    true
                                },
                        )
                    }
                    items(more, key = { it.title }) { row ->
                        Box(Modifier.padding(top = 30.dp)) { ItemRow(row, onFocus = { card = it }, onClick = ::open) }
                    }
                }
            }
        }
        LaunchedEffect(target) {
            // Back from playing: the focus stays on Play, or on the lecture if it is the one reached.
            if (target.played && pageFocused && focused.let { it == null || it.number == target.lecture }) return@LaunchedEffect
            if (!focusLecture(course, target.lecture, list, rows)) play.requestFocus()
        }
        // A change that ends after its menu closed doesn't close another lecture's menu.
        options?.let { lecture -> LectureOptions(course, lecture, reload) { if (options == lecture) options = null } }
    }

    /** Select held on a lecture. */
    @Composable
    private fun LectureOptions(course: Course, lecture: Lecture, onChanged: () -> Unit, onClose: () -> Unit) {
        var busy by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        // In the screen's scope, so it completes and reloads the course even if Back closes the menu.
        fun mark(watched: Boolean) {
            if (busy) return
            busy = true
            lifecycleScope.launch {
                failed = attempt { catalog.markWatched(course, lecture, watched) }.isFailure
                busy = false
                if (failed) return@launch
                onChanged()
                onClose()
            }
        }
        val actions = buildList<Pair<String, () -> Unit>> {
            add((if (lecture.isStarted) "Resume" else "Play") to {
                onClose()
                play(course.id, lecture.number)
            })
            if (lecture.isStarted) {
                add("Play from the beginning" to {
                    onClose()
                    play(course.id, lecture.number, fromStart = true)
                })
            }
            if (lecture.seconds > 0) add(if (lecture.isFinished) "Mark as not watched" to { mark(false) } else "Mark as watched" to { mark(true) })
        }
        OptionsDialog(
            lecture.numberedTitle,
            facts(lecture),
            actions,
            note = if (failed) "Couldn’t save that. Check the TV’s connection, then try again." else "",
            onDismiss = onClose,
        )
    }

    /** False if the course has no lecture [number]. */
    private suspend fun focusLecture(course: Course, number: Int, list: LazyListState, rows: Map<Int, FocusRequester>): Boolean {
        val index = course.lectures.indexOfFirst { it.number == number }
        if (index < 0) return false
        // Keeps the item before it (a lecture, or the "Lectures" title) in view.
        list.scrollToItem(index + 1)
        withFrameNanos { }
        return rows[number]?.requestFocus() == true
    }

    @Composable
    private fun Header(course: Course, next: Lecture?, play: FocusRequester, modifier: Modifier) {
        val scope = rememberCoroutineScope()
        // Null while unknown; a failed read is kept and Select reads it again. Each change is
        // confirmed by reading the list again.
        var entry by remember { mutableStateOf<Result<ListEntry?>?>(null) }
        var busy by remember { mutableStateOf(false) }
        var changeFailed by remember { mutableStateOf(false) }
        val resumes = LocalResumes.current
        LaunchedEffect(resumes) { if (!busy) entry = readEntry(course.id) }
        val finished = course.lectures.count { it.isFinished }

        val all = finished == course.lectures.size
        val watched = course.watchedSeconds
        Column(modifier.padding(start = Dimens.screenH, top = 56.dp, bottom = 18.dp)) {
            if (course.professor.isNotEmpty()) Text(course.professor, style = Type.label, color = Palette.dim)
            Text(course.title, style = Type.display, fontSize = 34.sp, lineHeight = 40.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp).width(600.dp))
            Text(
                listOfNotNull(
                    lecturesLabel(course.lectures.size),
                    course.hours.takeIf { it > 0 }?.let { if (watched > 0 && !all) watchedLabel(watched, it) else hoursLabel(it) },
                    finished.takeIf { it > 0 }?.let { if (all) "All finished" else "$it finished" },
                ).joinToString("  ·  "),
                color = Palette.dim,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(course.description, color = Palette.text.copy(alpha = 0.8f), maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp).width(600.dp))
            Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                val target = next ?: course.lectures.firstOrNull()
                if (target != null) {
                    val label = when {
                        next == null -> "Watch again"
                        next.isStarted -> "Resume lecture ${next.number}"
                        finished > 0 || course.lectures.any { it.isStarted } -> "Play lecture ${next.number}"
                        else -> "Play"
                    }
                    TvButton(label, { play(course.id, target.number) }, Modifier.focusRequester(play), Icons.play)
                }
                val current = entry?.getOrNull()
                TvButton("My List", {
                    val known = entry
                    if (busy || known == null) return@TvButton
                    busy = true
                    changeFailed = false
                    scope.launch {
                        if (known.isSuccess) {
                            changeFailed = attempt { if (current != null) catalog.removeFromList(current) else catalog.addToList(course) }.isFailure
                        }
                        entry = readEntry(course.id)
                        busy = false
                    }
                }, icon = if (current != null) Icons.check else Icons.add)
                if (course.guidebook.isNotEmpty()) {
                    TvButton("Guidebook", { launch(GuidebookActivity.intent(this@CourseActivity, course)) }, icon = Icons.book)
                }
                if (course.professors.isNotEmpty()) {
                    val label = if (course.professors.distinct().size > 1) "Professors" else course.professor.ifEmpty { "Professor" }
                    TvButton(label, { launch(ProfessorActivity.intent(this@CourseActivity, course.professors)) }, icon = Icons.person)
                }
            }
            if (changeFailed) {
                Text("My List couldn’t be changed. Check the TV’s connection, then try again.", color = Palette.accent, modifier = Modifier.padding(top = 12.dp))
            }
            if (next != null && next.isStarted) {
                Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProgressLine(next.fraction, Modifier.width(180.dp))
                    Text(minutesLeft(next), style = Type.small, color = Palette.dim, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }

    @Composable
    private fun LectureRow(lecture: Lecture, onFocus: () -> Unit, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier) {
        val shape = RoundedCornerShape(8.dp)
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier.padding(horizontal = 34.dp, vertical = 3.dp).fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() },
            shape = ClickableSurfaceDefaults.shape(shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = Palette.text,
                focusedContainerColor = Color(0x1AFFFFFF),
                focusedContentColor = Palette.text,
            ),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Palette.focus), shape = shape)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(168.dp, 94.dp).clip(RoundedCornerShape(4.dp)).background(Palette.surface)) {
                    AsyncImage(lecture.image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (lecture.isStarted || lecture.isFinished) ProgressLine(lecture.fraction, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                }
                Column(Modifier.padding(start = 20.dp).weight(1f)) {
                    Text(lecture.numberedTitle, style = Type.label, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        facts(lecture),
                        style = Type.small,
                        color = Palette.dim,
                        modifier = Modifier.padding(top = 3.dp, bottom = 5.dp),
                    )
                    Text(lecture.description, style = Type.small, fontSize = 14.sp, lineHeight = 19.sp, color = Palette.text.copy(alpha = 0.7f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    private suspend fun readEntry(courseId: Int): Result<ListEntry?> = attempt { catalog.listEntry(courseId) }

    private fun facts(lecture: Lecture) = listOfNotNull(
        "${(lecture.seconds + 30) / 60} min",
        when {
            lecture.isFinished -> "Watched"
            lecture.isStarted -> minutesLeft(lecture)
            else -> null
        },
    ).joinToString("  ·  ")

    private fun minutesLeft(lecture: Lecture) = "${((lecture.seconds - lecture.watched) / 60).coerceAtLeast(1)} min left"

    /** Scrolls only when the focused lecture leaves the middle of the screen, back to a third of the way down. */
    @OptIn(ExperimentalFoundationApi::class)
    private object ComfortZone : BringIntoViewSpec {
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
            val top = containerSize * 0.2f
            val bottom = containerSize * 0.9f
            return when {
                offset < top -> offset - top
                offset + size > bottom -> offset - containerSize * 0.33f
                else -> 0f
            }
        }
    }

    companion object {
        private const val EXTRA_COURSE = "course"
        private const val EXTRA_LECTURE = "lecture"

        fun intent(context: Context, courseId: Int, lecture: Int = 0): Intent =
            Intent(context, CourseActivity::class.java).putExtra(EXTRA_COURSE, courseId).putExtra(EXTRA_LECTURE, lecture)
    }
}
