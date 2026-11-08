package io.github.dshpet.coursestv

import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Where a read comes from: [Kept], the answer the TV kept from last time; [Fresh], the service's, or
 * the kept one when the service can't be reached; [Exact], the service's only, for decisions that
 * stale data would get wrong (where to resume, whether My List holds a course).
 */
enum class Source { Kept, Fresh, Exact }

/** The TV has kept nothing for this read yet. */
class NotKeptException : IOException("not kept")

/**
 * Everything the screens show, from the service's JSON API. The catalog is
 * cached for 30 min and personal data for 10 s, each loaded once for concurrent callers; answers are
 * also kept on the TV ([Stash]) for the [Source] reads. JSON is mapped off the main thread.
 */
class Catalog(private val api: Api, private val scope: CoroutineScope, private val counts: SharedPreferences, private val stash: Stash) {

    val isSignedIn: Boolean get() = WebViewCookies.token != null

    // Key → (expiry, load).
    private val cache = HashMap<String, Pair<Long, Deferred<Any>>>()

    // The last progress save; progress reads wait for it, so no screen shows a stale position.
    @Volatile private var saving: Job? = null

    /** The service's row order, with Continue Watching, Recently Watched and My List filled in. */
    suspend fun home(source: Source = Source.Fresh): List<Row> = work {
        val order = async { read("order", "${Hosts.API}/rest/V1/traysorder/plus/home", source) { it } }
        val trays = async { homeTrays(source) }
        val watching = async { optional(Watching(emptyList(), emptyList())) { watching(source) } }
        val myList = async { optional(emptyList()) { myList(source) } }
        Parse.home(order.await(), trays.await(), watching.await(), myList.await())
    }

    /** Starts reading Home, kept and fresh, while the app's first screen is still being set up. */
    fun prepareHome() {
        scope.launch(Dispatchers.Default) {
            if (!isSignedIn) return@launch
            launch { attempt { home(Source.Kept) } }
            attempt { home() }
        }
    }

    /**
     * Continue Watching and Recently Watched. Candidate courses are read a batch at a time until
     * there are [MAX_CARDS] cards; finished courses give none.
     */
    private suspend fun watching(source: Source): Watching = work {
        val shown = async { progress("shown", "group_by=course&suppress=1&limit=200&fields=items[course_id]", source, Parse::courseIds) }
        val courses = Parse.watched(allProgress(source))
        val next = mutableListOf<Item>()
        for (batch in Parse.continueWatching(courses, shown.await(), Instant.now()).chunked(BATCH)) {
            if (next.size >= MAX_CARDS) break
            next += batch.map { watched -> async { orNull { Parse.nextUp(details(watched.courseId, source).withProgress(watched.progress)) } } }
                .awaitAll()
                .filterNotNull()
        }
        Watching(next.take(MAX_CARDS), Parse.recentlyWatched(courses, LocalDate.now(), ZoneId.systemDefault()))
    }

    /** Removes a course from Continue Watching, here and on the website; its progress stays. */
    suspend fun hideFromContinueWatching(courseId: Int) {
        api.post("${Hosts.API}/rest/V1/lectureprogress/mine/suppression", JSONObject().put("course_id", courseId.toString()))
        forget(PROGRESS)
    }

    /** Each watched course's [Mark], by course id; a course whose size can't be read is left out. */
    suspend fun marks(source: Source = Source.Fresh): Map<Int, Mark> = work {
        if (!isSignedIn) return@work emptyMap()
        val requests = Semaphore(AT_ONCE)
        allProgress(source).filter { it.courseId > 0 }.groupBy { it.courseId }
            .map { (id, records) -> async { orNull { Parse.mark(records, lectureCount(id, source, requests)) }?.let { id to it } } }
            .awaitAll()
            .filterNotNull()
            .toMap()
    }

    // A course's lecture count doesn't change, so it is kept for good.
    private suspend fun lectureCount(id: Int, source: Source, requests: Semaphore): Int =
        counts.getInt(id.toString(), 0).takeIf { it > 0 }
            ?: requests.withPermit { summary(id, source) }.lectures.also { if (it > 0) counts.edit { putInt(id.toString(), it) } }

    private suspend fun summary(id: Int, source: Source): Summary =
        read("summary/$id", "${Hosts.API}/rest/V1/dlo_products/$id?fields=$SUMMARY_FIELDS", source, parse = Parse::summary)

    /** A course and its lectures, without progress. */
    suspend fun details(id: Int, source: Source = Source.Fresh): Course =
        read("course/$id", "${Hosts.API}/rest/V1/dlo_products/$id?fields=$COURSE_FIELDS", source, parse = Parse::course)

    suspend fun course(id: Int, source: Source = Source.Fresh): Course = work {
        val progress = async {
            when {
                !isSignedIn -> emptyList()
                // All the progress the TV kept stands in until the course's own arrives.
                source == Source.Kept -> allProgress(source).filter { it.courseId == id }
                else -> progress("course/$id", "course_id=$id&fields=$PROGRESS_FIELDS", source, Parse::progress)
            }
        }
        details(id, if (source == Source.Exact) Source.Fresh else source).withProgress(progress.await())
    }

    /** A row for each of [course]'s professors: their other courses. */
    suspend fun moreFrom(course: Course, source: Source = Source.Fresh): List<Row> = work {
        val requests = Semaphore(AT_ONCE)
        course.professors.distinct().map { id -> async { orNull { professor(id, source) } } }.awaitAll().filterNotNull()
            .map { Row("More from ${it.name.ifBlank { course.professor }}", cards(it.courses.filter { id -> id != course.id }, source, requests)) }
            .filter { it.items.isNotEmpty() }
            .distinctBy { it.title } // a row's title is its key on screen
    }

    /** Each professor with a row of their courses. */
    suspend fun professors(ids: List<Int>, source: Source = Source.Fresh): List<Pair<Professor, Row>> = work {
        val requests = Semaphore(AT_ONCE)
        val professors = ids.distinct().map { id -> async { professor(id, source) } }.awaitAll()
        professors.map { professor ->
            async { professor to Row(if (professors.size > 1) "Courses by ${professor.name}" else "Courses", cards(professor.courses, source, requests)) }
        }.awaitAll()
    }

    // A course that can't be read (some are retired) is left out.
    private suspend fun cards(ids: List<Int>, source: Source, requests: Semaphore): List<Item> = coroutineScope {
        ids.distinct().map { id -> async { orNull { requests.withPermit { Parse.card(summary(id, source)) } } } }
            .awaitAll()
            .filterNotNull()
            .filter { it.title.isNotBlank() }
    }

    private suspend fun professor(id: Int, source: Source): Professor =
        read("professor/$id", "${Hosts.API}/rest/all/V1/professors/$id?fields=$PROFESSOR_FIELDS", source, parse = Parse::professor)

    suspend fun myList(source: Source = Source.Fresh): List<Item> = work { Parse.myList(myListJson(source)) }

    suspend fun subjects(source: Source = Source.Fresh): List<Item> = work { Parse.subjects(homeTrays(source).values) }

    suspend fun subject(route: String, source: Source = Source.Fresh): List<Row> =
        read("subject/$route", "${Hosts.API}/rest/V1/trays/plus/$route/subject/us?fields=$TRAY_FIELDS", source, parse = Parse::rows)

    suspend fun search(query: String): List<Item> = work {
        if (query.isBlank()) return@work emptyList()
        Parse.search(searchIndex(), query)
    }

    /** Loads the search index while the user starts typing. */
    suspend fun prepareSearch() {
        searchIndex()
    }

    /** The course's own My List entry (not one of its lectures'), if any. */
    suspend fun listEntry(courseId: Int): ListEntry? = work {
        Parse.listEntries(myListJson(Source.Exact)).firstOrNull { it.courseId == courseId && it.lecture == 0 }
    }

    suspend fun addToList(course: Course) {
        // Empty once the website's own session has lapsed.
        val customerId = customer().text("id").ifEmpty { throw IOException("no customer id") }
        api.post(
            "${Hosts.SITE}/rest/V1/watchlist/mine/items",
            JSONObject().put("watchlist", JSONObject().put("customer_id", customerId).put("product_id", course.productId)),
        )
        forget(MY_LIST)
    }

    suspend fun removeFromList(entry: ListEntry) {
        api.delete("${Hosts.SITE}/rest/V1/watchlist/mine/items/${entry.itemId}")
        forget(MY_LIST)
    }

    suspend fun account(): Account = work {
        val subscribed = async { canPlay() }
        val customer = customer()
        Account(name = customer.text("firstname").ifEmpty { customer.text("fullname") }, subscribed = subscribed.await())
    }

    /** The subscription check before playing; a "no" is asked again next time. */
    suspend fun canPlay(): Boolean =
        shared("entitlement") { JSONObject(api.get("${Hosts.API}/rest/V1/entitlement/mine")).optBoolean("plus") }.also { if (!it) forget("entitlement") }

    /** Saves a lecture's position in the app's scope (leaving the player doesn't cancel it), in order. */
    fun saveProgress(course: Course, lecture: Lecture, seconds: Int) {
        val previous = saving
        saving = scope.launch {
            previous?.join()
            attempt { postProgress(course, lecture, seconds) }.onFailure { Log.w(TAG, "Progress not saved: ${it.message}") }
        }
        // Reads from now on wait for this save.
        forget(PROGRESS)
    }

    /** Marks [lecture] as watched to its end, or not watched at all, after any save in flight. */
    suspend fun markWatched(course: Course, lecture: Lecture, watched: Boolean) {
        saving?.join()
        postProgress(course, lecture, if (watched) lecture.seconds else 0)
        forget(PROGRESS)
    }

    private suspend fun postProgress(course: Course, lecture: Lecture, seconds: Int) {
        api.post(
            "${Hosts.API}/rest/V1/lectureprogress/mine",
            JSONObject().put("progress", JSONObject().put("course_id", course.id.toString()).put("lecture_id", lecture.sku).put("progress", seconds)),
        )
    }

    /** Forgets everything, also what the TV kept, so the next account sees nothing of this one's. */
    fun clearCache() {
        synchronized(cache) { cache.clear() }
        stash.clear()
    }

    private fun forget(prefix: String) = synchronized(cache) { cache.keys.removeAll { it.startsWith(prefix) } }

    private suspend fun allProgress(source: Source): List<Progress> =
        progress("all", "sort_attr=updated_at&sort_dir=desc&limit=1000&fields=$PROGRESS_FIELDS", source, Parse::progress)

    private suspend fun homeTrays(source: Source) =
        read("home", "${Hosts.API}/rest/V1/trays/plus/home/home/US?fields=$TRAY_FIELDS", source, parse = Parse::trays)

    private suspend fun searchIndex() =
        read("index", "${Hosts.API}/rest/V1/trays/plus/allsubjects/subject/us?fields=$TRAY_FIELDS", Source.Fresh, parse = Parse::searchIndex)

    private suspend fun myListJson(source: Source) =
        read(MY_LIST, "${Hosts.SITE}/rest/all/V2/watchlist/mine/items?fields=$MY_LIST_FIELDS", source, PERSONAL_MILLIS) { it }

    // The website's customer record: the first name, and the id the watchlist needs. Never kept on
    // the TV: it holds a sign-in token.
    private suspend fun customer(): JSONObject =
        JSONObject(api.get("${Hosts.SITE}/customer/section/load/?sections=customer")).optJSONObject("customer") ?: JSONObject()

    // Progress reads wait for the last save, so no screen shows a stale position.
    private suspend fun <T : Any> progress(key: String, query: String, source: Source, parse: (JSONObject) -> T): T =
        read("$PROGRESS/$key", "${Hosts.API}/rest/V1/lectureprogress/mine?$query", source, PERSONAL_MILLIS, waitForSave = true, parse)

    private suspend fun <T : Any> read(
        key: String,
        url: String,
        source: Source,
        millis: Long = CACHE_MILLIS,
        waitForSave: Boolean = false,
        parse: (JSONObject) -> T,
    ): T {
        // Kept reads are shared briefly too, so one started early (prepareHome) serves the screen.
        if (source == Source.Kept) return shared("kept/$key", KEPT_MILLIS) { kept(url, parse) ?: throw NotKeptException() }
        return try {
            shared(key, millis) {
                if (waitForSave) saving?.join()
                parse(fetch(url))
            }
        } catch (e: IOException) {
            if (source == Source.Exact || e is SignedOutException) throw e
            kept(url, parse) ?: throw e
        }
    }

    private suspend fun <T> kept(url: String, parse: (JSONObject) -> T): T? = withContext(Dispatchers.IO) { stash.read(url)?.let(parse) }

    private suspend fun fetch(url: String): JSONObject {
        val generation = stash.generation
        val text = api.get(url)
        scope.launch(Dispatchers.IO) { stash.write(url, text, generation) }
        return JSONObject(text)
    }

    /** A personal row that fails to load is left out ([empty]); a lapsed session, or nothing kept, fails the screen. */
    private suspend fun <T> optional(empty: T, load: suspend () -> T): T = attempt { load() }.getOrElse {
        if (it is SignedOutException || it is NotKeptException) throw it
        Log.w(TAG, "Row not loaded: ${it.message}")
        empty
    }

    /** Null if [load] fails; a lapsed session still fails the screen. */
    private suspend fun <T> orNull(load: suspend () -> T): T? = attempt { load() }.getOrElse { if (it is SignedOutException) throw it else null }

    private suspend fun <T> work(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.Default, block)

    /** Loads [key] once for all callers, in the app's scope, and keeps it for [millis]; a failed load is retried. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> shared(key: String, millis: Long = CACHE_MILLIS, load: suspend () -> T): T {
        val now = SystemClock.elapsedRealtime()
        val entry = synchronized(cache) {
            cache.values.removeAll { now >= it.first }
            cache[key]?.takeIf { !it.second.isCancelled } // a failed load counts as cancelled
                ?: (now + millis to scope.async(Dispatchers.Default) { load() as Any }).also { cache[key] = it }
        }
        return entry.second.await() as T
    }

    private companion object {
        const val TAG = "Catalog"
        const val CACHE_MILLIS = 30 * 60 * 1000L
        const val PERSONAL_MILLIS = 10_000L
        const val KEPT_MILLIS = 5_000L
        const val PROGRESS = "progress"
        const val MY_LIST = "mylist"
        const val AT_ONCE = 6
        const val MAX_CARDS = 10
        const val BATCH = 5

        // Only what the screens use: about half of each answer.
        const val TRAY_FIELDS = "trays[widget_id,widget_title,widget_image_type,products[product_type,product_sku,product_name," +
            "product_short_description,product_image_name,course_id,course_name,course_image_name,lecture_number]]"
        const val COURSE_FIELDS = "course_id,product_magento_id,course_name,course_professor_name,course_professor_product_id_list," +
            "course_duration_hours,course_description,wondrium_description,course_guidebook_path,lectures[lecture_number,lecture_sku," +
            "lecture_name,lecture_description,time_in_seconds,lecture_image_filename,lecture_video_filename]"
        const val SUMMARY_FIELDS = "course_id,course_name,course_lecture_count,course_professor_name,course_duration_hours,course_description,wondrium_description"
        const val PROFESSOR_FIELDS = "professor_name,professor_courses,professor_image,professor_institution,professor_qualification," +
            "professor_almamater,professor_biography"
        const val PROGRESS_FIELDS = "items[course_id,lecture_id,progress,time_in_seconds,updated_at,course_name]"
        const val MY_LIST_FIELDS = "items[item_id,course_id,product_sku,course_name,course_image_filename,course_description,lecture_name," +
            "lecture_image_filename,lecture_description]"
    }
}

/** Answers kept on the TV by URL, so a screen can show the last one at once. */
class Stash(private val dir: File) {

    /** Bumped by [clear]: an answer asked for before it isn't kept. */
    @Volatile var generation = 0
        private set

    fun read(url: String): JSONObject? = try {
        JSONObject(file(url).readText())
    } catch (e: IOException) {
        null
    } catch (e: JSONException) {
        null
    }

    fun write(url: String, text: String, generation: Int) {
        synchronized(this) {
            if (generation != this.generation) return
            try {
                dir.mkdirs()
                val part = File.createTempFile("answer", null, dir)
                part.writeText(text)
                if (!part.renameTo(file(url))) part.delete()
            } catch (e: IOException) {
                // Not kept this time.
            }
        }
    }

    fun clear() {
        synchronized(this) {
            generation++
            dir.deleteRecursively()
        }
    }

    private fun file(url: String) = File(dir, MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) })
}
