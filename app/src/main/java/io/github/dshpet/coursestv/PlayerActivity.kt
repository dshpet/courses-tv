package io.github.dshpet.coursestv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.CaptioningManager
import androidx.annotation.OptIn
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays a course's lectures from the chosen one on, after the subscription check. Resumes where the
 * user left off and saves progress to the service, so other devices continue from here.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : TvActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var settings: PlayerSettings
    private var progress: ProgressFeed? = null
    private var released = false
    private var restored = false
    private var resumeAt = 0

    // Lectures started by themselves since the remote was last used; after a few, playback waits.
    private var autoStarts = 0
    private var asking by mutableStateOf(false)
    private lateinit var captionLook: CaptionLook

    // Only while on screen: in the background, a remote's Play would start sound with no picture.
    private var session: MediaSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val courseId = intent.getIntExtra(EXTRA_COURSE, 0).takeIf { it > 0 } ?: return finish()
        // Rebuilt after process death: the lecture playback had reached, paused.
        val reached = savedInstanceState?.getInt(STATE_LECTURE) ?: 0
        restored = reached > 0
        resumeAt = savedInstanceState?.getInt(STATE_SECONDS) ?: 0
        val lecture = if (restored) reached else intent.getIntExtra(EXTRA_LECTURE, 0)
        // The stream links answer the platform's own user agent.
        val http = OkHttpDataSource.Factory(app.http).setUserAgent(System.getProperty("http.agent"))
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        captionLook = CaptionLook(this)
        settings = PlayerSettings(this, captionLook.enabled)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage("en")
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !settings.captions)
            .withQuality(settings.quality)
            .build()
        player.setPlaybackSpeed(settings.speed)
        lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                progress?.tick()
            }
        }
        show { PlayerScreen(courseId, lecture) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        progress?.let {
            outState.putInt(STATE_LECTURE, it.lecture.number)
            outState.putInt(STATE_SECONDS, (player.currentPosition / 1000).toInt())
        }
    }

    override fun onStart() {
        super.onStart()
        if (!::player.isInitialized || released || session != null) return
        // A new player can start before the last one is destroyed; session IDs must differ.
        session = MediaSession.Builder(this, player).setId("player-${System.identityHashCode(this)}").build()
    }

    // Saved before the screen behind resumes and reads progress back.
    override fun onPause() {
        progress?.save()
        if (isFinishing) release()
        super.onPause()
    }

    override fun onStop() {
        if (::player.isInitialized && !released) player.pause()
        session?.release()
        session = null
        super.onStop()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        if (!::player.isInitialized || released) return
        released = true
        progress?.save()
        session?.release()
        session = null
        player.release()
    }

    private fun start(course: Course, requested: Int) {
        // Once, and not after release: the course may finish loading after the player is gone.
        if (progress != null || released || isFinishing) return
        val first = course.lectures.firstOrNull { it.number == requested } ?: course.nextUp ?: course.lectures.firstOrNull() ?: return finish()
        val from = when {
            // Exactly where it was, also in a part the service doesn't keep (first 2 min, last 10%).
            restored -> if (first.number == requested) resumeAt else first.startSeconds
            intent.getBooleanExtra(EXTRA_FROM_START, false) -> 0
            else -> first.startSeconds
        }
        val queue = course.lectures.dropWhile { it.number != first.number }
        progress = ProgressFeed(course, first, from).also(player::addListener)
        answer(first)
        player.setMediaItems(queue.map { mediaItem(course, it) }, 0, from * 1000L)
        player.prepare()
        // The user may have left while it loaded.
        player.playWhenReady = !restored && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    private fun mediaItem(course: Course, lecture: Lecture) = MediaItem.Builder()
        .setMediaId(lecture.number.toString())
        .setUri(lecture.stream)
        .setMimeType(MimeTypes.APPLICATION_M3U8)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(lecture.title)
                .setArtist(course.title)
                .setTrackNumber(lecture.number)
                .setArtworkUri(lecture.image.toUri())
                .build(),
        )
        .build()

    private fun Course.lecture(item: MediaItem?): Lecture? = lectures.firstOrNull { it.number.toString() == item?.mediaId }

    // The course page behind focuses the lecture playback reached.
    private fun answer(lecture: Lecture) = setResult(RESULT_OK, Intent().putExtra(EXTRA_LECTURE, lecture.number))

    /** The course page at the lecture playing: the one behind the player, or a new one. */
    private fun openLectures(course: Course) {
        val lecture = progress?.lecture?.number ?: 0
        setResult(RESULT_CANCELED) // the lecture goes in the new intent
        startActivity(CourseActivity.intent(this, course.id, lecture).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun setQuality(height: Int) {
        settings.quality = height
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().withQuality(height).build()
        // Drops the video buffered at the old quality.
        player.seekTo(player.currentPosition)
    }

    private fun setSpeed(speed: Float) {
        settings.speed = speed
        player.setPlaybackSpeed(speed)
    }

    private fun stillHere() {
        autoStarts = 0
        asking = false
    }

    private fun setCaptions(on: Boolean) {
        settings.captions = on
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on).build()
    }

    /** The player's position and events, fed to a [ProgressSaver]. */
    private inner class ProgressFeed(private val course: Course, first: Lecture, start: Int) : Player.Listener {
        private val saver = ProgressSaver(first, start) { lecture, seconds -> catalog.saveProgress(course, lecture, seconds) }
        private val seconds: Int get() = (player.currentPosition / 1000).toInt()

        val lecture: Lecture get() = saver.lecture

        fun tick() {
            if (player.isPlaying) saver.playing(seconds)
        }

        fun save() = saver.save(seconds)

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying && player.playbackState == Player.STATE_READY) save()
        }

        // The queue's last lecture ended; the ones before it end in onMediaItemTransition.
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) saver.end()
        }

        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            val next = course.lecture(item) ?: return
            if (next == saver.lecture) return
            val auto = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
            val start = saver.moveTo(next, ended = auto)
            if (start > 0) player.seekTo(start * 1000L)
            answer(next)
            // Asleep in front of the TV, the course would be saved as watched to its end.
            if (auto && ++autoStarts >= ASK_AFTER) {
                player.pause()
                asking = true
            }
        }

        // Play from the remote, the phone or the Assistant.
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) stillHere()
        }
    }

    @Composable
    private fun PlayerScreen(courseId: Int, lecture: Int) {
        val setup = load {
            coroutineScope {
                val allowed = async { catalog.canPlay() }
                catalog.course(courseId, Source.Exact) to allowed.await()
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (setup) {
                Load.Loading -> Spinner(Modifier.align(Alignment.Center))
                is Load.Failed -> LoadError(setup, ::signInAgain)
                is Load.Ready -> {
                    val (course, allowed) = setup.value
                    if (allowed) {
                        LaunchedEffect(course) { start(course, lecture) }
                        Playback(course)
                    } else {
                        Message(
                            "Not included in your plan",
                            "Watching lectures needs an active subscription on this account.",
                            "Back" to ::finish,
                        )
                    }
                }
            }
        }
    }

    /** Where the remote's keys go. */
    private enum class Zone { Bar, Buttons, Panel }

    private class Button(val label: String, val icon: ImageVector, val action: () -> Unit)

    /** A settings panel line: a section's title, or a choice in it. */
    private class Line(val label: String, val selected: Boolean = false, val choose: (() -> Unit)? = null)

    @Composable
    private fun Playback(course: Course) {
        val state = remember { PlayState(player) }
        DisposableEffect(state) {
            player.addListener(state)
            onDispose { player.removeListener(state) }
        }
        val times = rememberProgressStateWithTickInterval(player, tickIntervalMs = 500)
        val time = Times(times.currentPositionMs, times.bufferedPositionMs, times.durationMs.takeIf { it != C.TIME_UNSET } ?: 0L)
        var controls by remember { mutableStateOf(true) }
        var zone by remember { mutableStateOf(Zone.Bar) }
        var button by remember { mutableIntStateOf(0) }
        var line by remember { mutableIntStateOf(0) }
        var inputs by remember { mutableIntStateOf(0) }
        var captions by remember { mutableStateOf(settings.captions) }
        var quality by remember { mutableIntStateOf(settings.quality) }
        var speed by remember { mutableFloatStateOf(settings.speed) }
        var captionSize by remember { mutableFloatStateOf(settings.captionSize) }
        val focus = remember { FocusRequester() }

        val current = course.lecture(state.item)
        val next = current?.let { now -> course.lectures.firstOrNull { it.number > now.number } }
        val remaining = ((time.duration - time.position) / speed).toLong()
        val upNext = next != null && time.duration > 0 && remaining in 1..UP_NEXT_MILLIS && !controls

        // A height this lecture lacks plays as the highest one under it, or its lowest.
        val chosen = if (quality == 0) 0 else state.heights.firstOrNull { it <= quality } ?: state.heights.lastOrNull() ?: quality
        val lines = buildList {
            add(Line("Quality"))
            add(Line(if (quality == 0 && state.height > 0) "Auto (${state.height}p)" else "Auto", quality == 0) { quality = 0; setQuality(0) })
            state.heights.forEach { height -> add(Line("${height}p", chosen == height) { quality = height; setQuality(height) }) }
            add(Line("Speed"))
            SPEEDS.forEach { value -> add(Line(if (value == 1f) "Normal" else "${speedLabel(value)}×", speed == value) { speed = value; setSpeed(value) }) }
            if (captions) {
                add(Line("Caption size"))
                CAPTION_SIZES.forEach { (label, value) -> add(Line(label, captionSize == value) { captionSize = value; settings.captionSize = value }) }
            }
        }
        val choices = lines.filter { it.choose != null }

        val buttons = buildList {
            add(Button("Lectures", Icons.list) { openLectures(course) })
            add(Button(if (captions) "Captions on" else "Captions off", Icons.captions) { captions = !captions; setCaptions(captions) })
            add(Button("Settings", Icons.settings) {
                zone = Zone.Panel
                line = choices.indexOfFirst { it.selected }.coerceAtLeast(0)
            })
            if (next != null) add(Button("Next lecture", Icons.next) { progress?.save(); player.seekToNextMediaItem() })
        }
        val selected = button.coerceAtMost(buttons.lastIndex)

        fun onKey(event: KeyEvent): Boolean {
            if (state.error != null || asking) return false // the card's buttons take the keys
            val handled = event.key in KEYS
            if (event.type != KeyEventType.KeyDown || !handled) return handled
            val repeat = event.nativeKeyEvent.repeatCount
            inputs++
            autoStarts = 0
            val wasShown = controls
            controls = true
            val select = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
            // From the state, not this composition: keys can come faster than frames.
            val at = button.coerceAtMost(buttons.lastIndex)
            when {
                zone == Zone.Panel -> when {
                    select -> if (repeat == 0) choices.getOrNull(line)?.takeIf { !it.selected }?.choose?.invoke()
                    event.key == Key.DirectionUp -> line = (line - 1).coerceAtLeast(0)
                    event.key == Key.DirectionDown -> line = (line + 1).coerceAtMost(choices.lastIndex)
                    event.key == Key.DirectionLeft -> zone = Zone.Buttons
                }
                select -> if (repeat == 0) when {
                    upNext && !wasShown -> player.seekToNextMediaItem()
                    zone == Zone.Buttons -> buttons[at].action()
                    // Not isPlaying: that is false while buffering.
                    else -> if (player.playWhenReady) player.pause() else player.play()
                }
                event.key == Key.DirectionLeft || event.key == Key.DirectionRight -> {
                    val forward = event.key == Key.DirectionRight
                    if (zone == Zone.Buttons) {
                        button = (at + if (forward) 1 else -1).coerceIn(0, buttons.lastIndex)
                    } else {
                        val step = if (repeat > 8) 30_000L else 10_000L
                        val target = player.currentPosition + if (forward) step else -step
                        val end = if (player.duration == C.TIME_UNSET) Long.MAX_VALUE else (player.duration - 1000).coerceAtLeast(0L)
                        player.seekTo(target.coerceIn(0L, end))
                    }
                }
                event.key == Key.DirectionDown -> if (wasShown) zone = Zone.Buttons
                event.key == Key.DirectionUp -> zone = Zone.Bar
            }
            return true
        }

        // Closes the settings, then hides the controls; then Back leaves.
        BackHandler(enabled = controls && state.error == null && !asking) {
            autoStarts = 0
            if (zone == Zone.Panel) {
                zone = Zone.Buttons
            } else {
                controls = false
                zone = Zone.Bar
            }
        }
        LaunchedEffect(controls, inputs, state.playing, zone) {
            if (controls && state.playing && zone != Zone.Panel) {
                delay(HIDE_AFTER_MILLIS)
                controls = false
                zone = Zone.Bar
            }
        }
        LaunchedEffect(state.playing) { if (!state.playing && !state.buffering) controls = true }
        LaunchedEffect(state.ended) { if (state.ended) finish() }
        // Also when a card closes without its buttons, as when Play comes from the phone.
        LaunchedEffect(asking, state.error) { if (!asking && state.error == null) focus.requestFocus() }
        val view = LocalView.current
        DisposableEffect(state.playing) {
            view.keepScreenOn = state.playing
            onDispose { view.keepScreenOn = false }
        }

        Box(Modifier.fillMaxSize().focusRequester(focus).focusable().onKeyEvent(::onKey)) {
            ContentFrame(player, Modifier.fillMaxSize())
            // Beside the settings panel while it is open, so a new size can be seen.
            val panel = if (controls && zone == Zone.Panel) PANEL_WIDTH else 0.dp
            Captions(state.cues, captionSize, lifted = controls, modifier = Modifier.align(Alignment.BottomCenter).padding(end = panel))
            if (state.buffering && state.error == null) Spinner(Modifier.align(Alignment.Center))
            AnimatedVisibility(controls, enter = fadeIn(tween(150)), exit = fadeOut(tween(400))) {
                Controls(course, current, state.playWhenReady, time, speed, zone, buttons, selected)
            }
            AnimatedVisibility(controls && zone == Zone.Panel, Modifier.align(Alignment.CenterEnd), enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
                SettingsPanel(lines, choices.getOrNull(line))
            }
            AnimatedVisibility(upNext, Modifier.align(Alignment.BottomEnd), enter = fadeIn(), exit = fadeOut()) {
                if (next != null) UpNext(next, (remaining / 1000).toInt())
            }
            if (asking && state.error == null) {
                Box(Modifier.fillMaxSize().background(Palette.background.copy(alpha = 0.92f))) {
                    Message(
                        "Are you still watching?",
                        current?.let { "Next: ${it.numberedTitle}" }.orEmpty(),
                        "Keep watching" to {
                            stillHere()
                            player.play()
                        },
                        "Back" to ::finish,
                    )
                }
            }
            state.error?.let {
                Box(Modifier.fillMaxSize().background(Palette.background.copy(alpha = 0.92f))) {
                    Message(
                        "This lecture can’t play",
                        "Check the TV’s internet connection and try again.",
                        "Try again" to {
                            stillHere()
                            player.prepare()
                            player.play()
                        },
                        "Back" to ::finish,
                    )
                }
            }
        }
    }

    @Composable
    private fun Controls(course: Course, lecture: Lecture?, playWhenReady: Boolean, time: Times, speed: Float, zone: Zone, buttons: List<Button>, selected: Int) {
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
                    SeekBar(time, active = zone == Zone.Bar, modifier = Modifier.weight(1f))
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
                        val on = zone != Zone.Bar && index == selected
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
    private fun SettingsPanel(lines: List<Line>, at: Line?) {
        Column(Modifier.fillMaxHeight().width(PANEL_WIDTH).background(Palette.panel).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 36.dp)) {
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
    private fun SeekBar(time: Times, active: Boolean, modifier: Modifier) {
        Box(modifier.height(20.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0x40FFFFFF)))
            Box(Modifier.fillMaxWidth(time.fraction(time.buffered)).height(4.dp).background(Color(0x66FFFFFF)))
            Box(Modifier.fillMaxWidth(time.fraction(time.position)).height(4.dp).background(Palette.accent))
            Box(Modifier.fillMaxWidth(time.fraction(time.position)), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.offset(x = 7.dp).size(if (active) 16.dp else 10.dp).background(if (active) Palette.text else Palette.accent, CircleShape))
            }
        }
    }

    /** In the TV's caption style (Settings → Accessibility → Captions), at its text size times [size]. */
    @Composable
    private fun Captions(cues: List<String>, size: Float, lifted: Boolean, modifier: Modifier) {
        if (cues.isEmpty()) return
        val look = captionLook
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
    private fun UpNext(next: Lecture, seconds: Int) {
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
    private fun Spinner(modifier: Modifier = Modifier) {
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

    /** Milliseconds; the duration is 0 until known. */
    private data class Times(val position: Long, val buffered: Long, val duration: Long) {
        fun fraction(millis: Long): Float = if (duration > 0) (millis.toFloat() / duration).coerceIn(0f, 1f) else 0f
    }

    private class PlayState(player: Player) : Player.Listener {
        var playing by mutableStateOf(player.isPlaying)
        var playWhenReady by mutableStateOf(player.playWhenReady)
        var buffering by mutableStateOf(true)
        var ended by mutableStateOf(false)
        var item by mutableStateOf(player.currentMediaItem)
        var cues by mutableStateOf(emptyList<String>())
        var error by mutableStateOf<PlaybackException?>(null)

        /** The lecture's video heights, highest first, and the one playing. */
        var heights by mutableStateOf(emptyList<Int>())
        var height by mutableIntStateOf(0)

        override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { this.playWhenReady = playWhenReady }
        override fun onPlaybackStateChanged(state: Int) {
            buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
            ended = state == Player.STATE_ENDED
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { item = mediaItem }
        override fun onCues(cueGroup: CueGroup) { cues = cueGroup.cues.mapNotNull { it.text?.toString()?.trim()?.takeIf(String::isNotEmpty) } }
        // Also cleared when playback recovers.
        override fun onPlayerErrorChanged(error: PlaybackException?) { this.error = error }
        override fun onVideoSizeChanged(videoSize: VideoSize) { height = videoSize.height }
        override fun onTracksChanged(tracks: Tracks) {
            heights = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                .flatMap { group -> (0 until group.length).filter(group::isTrackSupported).map { group.getTrackFormat(it).height } }
                .filter { it > 0 }
                .distinct()
                .sortedDescending()
        }
    }

    companion object {
        private const val EXTRA_COURSE = "course"
        private const val EXTRA_LECTURE = "lecture"
        private const val EXTRA_FROM_START = "fromStart"
        private const val STATE_LECTURE = "lecture"
        private const val STATE_SECONDS = "seconds"
        private const val HIDE_AFTER_MILLIS = 5_000L
        private const val UP_NEXT_MILLIS = 20_000L
        private const val ASK_AFTER = 3
        private val PANEL_WIDTH = 300.dp
        private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        private val CAPTION_SIZES = listOf("Small" to 0.8f, "Normal" to 1f, "Large" to 1.3f, "Extra large" to 1.6f)
        private val KEYS = setOf(
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter,
            Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown,
        )

        /** [lecture] 0 plays the course's next lecture. */
        fun intent(context: Context, courseId: Int, lecture: Int, fromStart: Boolean = false): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_COURSE, courseId)
                .putExtra(EXTRA_LECTURE, lecture)
                .putExtra(EXTRA_FROM_START, fromStart)

        fun reached(result: Intent?): Int? = result?.getIntExtra(EXTRA_LECTURE, 0)?.takeIf { it > 0 }

        private fun clock(millis: Long): String {
            val total = (millis.coerceAtLeast(0) / 1000).toInt()
            val h = total / 3600
            val m = total / 60 % 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }

        private fun speedLabel(speed: Float) = if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()

        /** Only [height] (0: any). */
        private fun TrackSelectionParameters.Builder.withQuality(height: Int): TrackSelectionParameters.Builder =
            if (height > 0) setMaxVideoSize(Int.MAX_VALUE, height).setMinVideoSize(0, height) else clearVideoSizeConstraints().setMinVideoSize(0, 0)
    }
}

/** Remembered between sessions; quality is a video height, 0 for Auto. Captions start as the TV has them. */
private class PlayerSettings(context: Context, private val captionsByDefault: Boolean) {
    private val prefs = context.getSharedPreferences("player", Context.MODE_PRIVATE)

    var captions: Boolean
        get() = prefs.getBoolean("captions", captionsByDefault)
        set(value) = prefs.edit { putBoolean("captions", value) }

    var captionSize: Float
        get() = prefs.getFloat("captionSize", 1f)
        set(value) = prefs.edit { putFloat("captionSize", value) }

    var quality: Int
        get() = prefs.getInt("quality", 0)
        set(value) = prefs.edit { putInt("quality", value) }

    var speed: Float
        get() = prefs.getFloat("speed", 1f)
        set(value) = prefs.edit { putFloat("speed", value) }
}

/** The TV's caption settings, read when the player opens. */
private class CaptionLook(context: Context) {
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
