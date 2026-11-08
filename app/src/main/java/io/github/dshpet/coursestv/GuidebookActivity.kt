package io.github.dshpet.coursestv

import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException

/** A course's guidebook, a page at a time: Up/Down scroll, Left/Right turn pages, Select enlarges. */
class GuidebookActivity : TvActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val courseId = intent.getIntExtra(EXTRA_COURSE, 0)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (courseId <= 0 || url.isEmpty()) return finish()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        show { Guidebook(courseId, title, url) }
    }

    @Composable
    private fun Guidebook(courseId: Int, title: String, url: String) {
        var percent by remember { mutableIntStateOf(0) }
        when (val book = load { Pdf.open(download(courseId, url) { percent = it }) }) {
            Load.Loading -> Message(title, if (percent > 0) "Loading the guidebook… $percent%" else "Loading the guidebook…")
            is Load.Failed -> Message("Can’t open the guidebook", "Check the TV’s internet connection and try again.", "Try again" to book.retry, "Back" to ::finish)
            is Load.Ready -> {
                Pages(courseId, title, book.value)
                DisposableEffect(book.value) { onDispose { app.scope.launch { book.value.close() } } }
            }
        }
    }

    @Composable
    private fun Pages(courseId: Int, title: String, pdf: Pdf) {
        val read = remember { getSharedPreferences("guidebooks", MODE_PRIVATE) }
        var page by remember { mutableIntStateOf(read.getInt(courseId.toString(), 0).coerceIn(0, pdf.pages - 1)) }
        var large by remember { mutableStateOf(false) }
        val width = if (large) LARGE_WIDTH else PAGE_WIDTH
        val pixels = with(LocalDensity.current) { width.roundToPx() }
        val image by produceState<ImageBitmap?>(null, page, pixels) {
            value = attempt { pdf.page(page, pixels) }.getOrNull()
            if (page + 1 < pdf.pages) attempt { pdf.page(page + 1, pixels) } // ready for Right
        }
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        LaunchedEffect(page) {
            scroll.scrollTo(0)
            read.edit { putInt(courseId.toString(), page) }
        }

        fun onKey(event: KeyEvent): Boolean {
            val handled = event.key in REMOTE_KEYS
            if (event.type != KeyEventType.KeyDown || !handled) return handled
            val first = event.nativeKeyEvent.repeatCount == 0
            val step = scroll.viewportSize * 0.6f
            when (event.key) {
                // At the page's end, Down goes on to the next.
                Key.DirectionDown -> if (scroll.value < scroll.maxValue) scope.launch { scroll.animateScrollBy(step) } else if (first) page = (page + 1).coerceAtMost(pdf.pages - 1)
                Key.DirectionUp -> scope.launch { scroll.animateScrollBy(-step) }
                Key.DirectionRight -> page = (page + 1).coerceAtMost(pdf.pages - 1)
                Key.DirectionLeft -> page = (page - 1).coerceAtLeast(0)
                else -> if (first) large = !large
            }
            return true
        }

        Box(Modifier.fillMaxSize().focusRequester(focus).focusable().onKeyEvent(::onKey)) {
            Box(
                Modifier.fillMaxHeight().verticalScroll(scroll, enabled = false)
                    .then(if (large) Modifier.align(Alignment.TopCenter) else Modifier.padding(start = PAGE_START)),
            ) {
                image?.let { Image(it, contentDescription = "Page ${page + 1}", contentScale = ContentScale.FillWidth, modifier = Modifier.width(width)) }
            }
            if (!large) {
                Column(Modifier.padding(start = Dimens.screenH, top = 32.dp).width(PAGE_START - Dimens.screenH - 24.dp)) {
                    Text(title, style = Type.label, color = Palette.dim, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Text("Page ${page + 1} of ${pdf.pages}", style = Type.small, color = Palette.dim, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
    }

    /** The PDF, from the TV's cache if it was fetched lately. */
    private suspend fun download(courseId: Int, url: String, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = cacheDir.resolve("guidebooks").apply { mkdirs() }
        val file = dir.resolve("$courseId.pdf")
        // lastModified is the fetch time, so a cached file expires; it is not touched on reads.
        if (file.length() > 0 && System.currentTimeMillis() - file.lastModified() < FRESH_MILLIS) return@withContext file
        val part = dir.resolve("$courseId.part")
        try {
            app.http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val total = response.body.contentLength()
                response.body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) progress((done * 100 / total).toInt())
                        }
                    }
                }
            }
            if (!part.renameTo(file)) throw IOException("not saved")
        } finally {
            part.delete()
        }
        dir.listFiles { it.extension == "pdf" }.orEmpty().sortedByDescending { it.lastModified() }.drop(KEPT).forEach { it.delete() }
        file
    }

    companion object {
        private const val EXTRA_COURSE = "course"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_URL = "url"
        private const val KEPT = 5
        private const val FRESH_MILLIS = 30L * 24 * 60 * 60 * 1000 // 30 days
        private val PAGE_START = 240.dp
        private val PAGE_WIDTH = 680.dp
        private val LARGE_WIDTH = 900.dp

        fun intent(context: Context, course: Course): Intent = Intent(context, GuidebookActivity::class.java)
            .putExtra(EXTRA_COURSE, course.id)
            .putExtra(EXTRA_TITLE, course.title)
            .putExtra(EXTRA_URL, course.guidebook)
    }
}

/** A PDF's pages, drawn one at a time on one thread as [PdfRenderer] requires; the last few are kept. */
private class Pdf private constructor(private val descriptor: ParcelFileDescriptor) {
    private val renderer = PdfRenderer(descriptor)
    private val thread = Dispatchers.IO.limitedParallelism(1)
    private val drawn = object : LinkedHashMap<Pair<Int, Int>, ImageBitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int, Int>, ImageBitmap>?) = size > 3
    }
    private var closed = false

    val pages: Int = renderer.pageCount.also {
        if (it == 0) {
            renderer.close()
            throw IOException("no pages")
        }
    }

    /** Page [index], [width] pixels wide. */
    suspend fun page(index: Int, width: Int): ImageBitmap = withContext(thread) {
        check(!closed) { "closed" }
        drawn.getOrPut(index to width) {
            renderer.openPage(index).use { page ->
                // A fold-out page is squeezed rather than drawn on a bitmap too large for the TV.
                val bitmap = createBitmap(width, (width.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceIn(1, width * 6))
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.asImageBitmap()
            }
        }
    }

    suspend fun close() = withContext(NonCancellable + thread) {
        if (closed) return@withContext
        closed = true
        drawn.clear()
        renderer.close()
        descriptor.close()
    }

    companion object {
        /** A damaged file is deleted, so trying again downloads it afresh. */
        suspend fun open(file: File): Pdf = withContext(Dispatchers.IO) {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                Pdf(descriptor)
            } catch (e: Exception) {
                descriptor.close()
                file.delete()
                throw if (e is IOException) e else IOException(e)
            }
        }
    }
}
