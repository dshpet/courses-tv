package io.github.dshpet.coursestv

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** A card: a course, a lecture or a subject. */
data class Item(
    val title: String,
    val image: String,
    val subtitle: String = "",
    val description: String = "",
    val backdrop: String = image,
    val courseId: Int = 0,
    val lecture: Int = 0,
    val progress: Float = 0f,
    val subject: String = "",
    /** Select plays [lecture] instead of opening the course (Continue Watching). */
    val plays: Boolean = false,
    /** Identity in a row or grid, so a card keeps its focus when the list is read again. */
    val key: String = "$courseId:$lecture:$subject",
)

data class Row(val title: String, val items: List<Item>, val tall: Boolean = false)

data class Course(
    val id: Int,
    val productId: Int,
    val title: String,
    val professor: String,
    val description: String,
    val hours: Double,
    val lectures: List<Lecture>,
    /** The PDF's address, or "". */
    val guidebook: String = "",
    val professors: List<Int> = emptyList(),
) {
    val image: String get() = Images.course(id)

    val facts: String get() = facts(professor, lectures.size, hours)

    /** Finished lectures in full, those under way to where they are. */
    val watchedSeconds: Int get() = lectures.sumOf { if (it.isFinished) it.seconds else if (it.isStarted) it.watched else 0 }

    /**
     * Past the furthest finished lecture: the latest one under way, else the first. Replaying an
     * earlier lecture doesn't move it back. Null once the course is finished.
     */
    val nextUp: Lecture? get() {
        val furthest = lectures.lastOrNull { it.isFinished }?.number ?: 0
        val ahead = lectures.filter { it.number > furthest }
        return ahead.filter { it.isStarted }.maxByOrNull { it.updatedAt } ?: ahead.firstOrNull()
    }

    fun withProgress(items: List<Progress>): Course {
        val byLecture = items.associateBy { it.lectureSku }
        return copy(lectures = lectures.map { lecture -> byLecture[lecture.sku]?.let { lecture.copy(watched = it.seconds, updatedAt = it.updatedAt) } ?: lecture })
    }
}

data class Lecture(
    val number: Int,
    val sku: String,
    val title: String,
    val description: String,
    val seconds: Int,
    val image: String,
    val video: String,
    val watched: Int = 0,
    val updatedAt: String = "",
) {
    val fraction: Float get() = if (seconds > 0) (watched.toFloat() / seconds).coerceIn(0f, 1f) else 0f

    val isFinished: Boolean get() = finishes(watched)

    val isStarted: Boolean get() = watched >= STARTED_SECONDS && !isFinished

    val startSeconds: Int get() = if (isStarted) watched else 0

    fun finishes(position: Int): Boolean = finished(position, seconds)

    val stream: String get() = "${Hosts.STREAMS}$video?manifest=m3u"

    /** "07: Title", as on the website. */
    val numberedTitle: String get() = "${number.toString().padStart(2, '0')}: $title"
}

fun hoursLabel(hours: Double): String = "%.1f hours".format(Locale.ENGLISH, hours)

fun lecturesLabel(count: Int): String = if (count == 1) "1 lecture" else "$count lectures"

/** "11.1 of 18.2 hours watched"; under an hour, in minutes. */
fun watchedLabel(seconds: Int, hours: Double): String {
    val watched = if (seconds < 3600) "${seconds / 60} min" else "%.1f".format(Locale.ENGLISH, (seconds / 3600.0).coerceAtMost(hours))
    return "$watched of ${hoursLabel(hours)} watched"
}

/** "Teacher · 24 lectures · 12.7 hours", leaving out what isn't known. */
fun facts(professor: String, lectures: Int, hours: Double): String = listOfNotNull(
    professor.ifEmpty { null },
    lectures.takeIf { it > 0 }?.let(::lecturesLabel),
    hours.takeIf { it > 0 }?.let(::hoursLabel),
).joinToString(" · ")

/** A lecture counts as started after two minutes; shorter plays are taken as opened by mistake. */
const val STARTED_SECONDS = 120

/** A lecture counts as finished at 90%: the summary and credits needn't be watched. */
fun finished(position: Int, length: Int): Boolean = length > 0 && position >= length * 0.9f

data class Progress(
    val courseId: Int,
    val lectureSku: String,
    val seconds: Int,
    val updatedAt: String,
    val length: Int = 0,
    val courseName: String = "",
) {
    val isFinished: Boolean get() = finished(seconds, length)

    val counts: Boolean get() = seconds >= STARTED_SECONDS || isFinished

    val time: Instant? get() = try {
        LocalDateTime.parse(updatedAt.replace(' ', 'T')).toInstant(ZoneOffset.UTC) // the service keeps UTC
    } catch (e: DateTimeParseException) {
        null
    }
}

/** A course the user has watched: all its progress, and the latest that [Progress.counts]. */
data class Watched(val courseId: Int, val title: String, val progress: List<Progress>, val last: Progress)

/** How much of a course its card shows as watched; [finished] once its last lecture is. */
data class Mark(val fraction: Float, val finished: Boolean)

/** A course's title, teacher and size: a few hundred bytes. */
data class Summary(val courseId: Int, val title: String, val professor: String, val lectures: Int, val hours: Double, val description: String) {
    val facts: String get() = facts(professor, lectures, hours)
}

/** [about]: where they teach and their degree. */
data class Professor(val name: String, val courses: List<Int>, val about: String = "", val bio: String = "", val image: String = "")

/** Home's personal rows from progress: Continue Watching ([next]) and Recently Watched. */
data class Watching(val next: List<Item>, val recent: List<Item>)

data class Account(val name: String, val subscribed: Boolean)

data class ListEntry(val itemId: Int, val courseId: Int, val lecture: Int)

object Images {
    fun course(id: Any, file: String = "$id.jpg") = "${Hosts.IMAGES}courses/$id/$file"
    fun poster(id: Int) = course(id, "portrait/$id.jpg")
    fun professor(file: String) = "${Hosts.IMAGES}$file"

    /** The first lecture's still: a large picture of the course without its title. */
    fun backdrop(id: Int) = course(id, "$id-L01.jpg")
}

object Parse {

    private val SPACES = Regex("\\s+")
    private val ENTITIES = mapOf(
        "nbsp" to " ", "quot" to "\"", "lt" to "<", "gt" to ">", "amp" to "&", "rsquo" to "’", "lsquo" to "‘",
        "ldquo" to "“", "rdquo" to "”", "mdash" to "—", "ndash" to "–", "hellip" to "…",
    )
    private const val ENTITY_MAX = 12
    private val SPACED_INITIALS = Regex("(?<=\\.) (?=[A-Z]\\.)")
    private val LECTURE_SKU = Regex("-L(\\d+)$")
    private val CONTINUE_WINDOW = Duration.ofDays(16 * 7)
    private const val MIN_MARK = 0.04f
    private val DAY_MONTH = DateTimeFormatter.ofPattern("MMMM d", Locale.ENGLISH)
    private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

    /** The home page's trays as rows by tray id. */
    fun trays(json: JSONObject): Map<String, Row> = json.optJSONArray("trays").objects().associate { it.text("widget_id") to row(it) }

    /** The service's row order over its [trays], with the personal rows filled in. */
    fun home(order: JSONObject, trays: Map<String, Row>, watching: Watching, myList: List<Item>): List<Row> =
        order.optJSONArray("trays").objects().flatMap { entry ->
            val title = entry.text("widget_title").trim()
            when (entry.text("widget_function")) {
                "continue-watching" -> listOf(Row(title.ifBlank { "Continue Watching" }, watching.next), Row("Recently Watched", watching.recent))
                "watchlist-program" -> listOf(Row("My List", myList))
                "", "product" -> listOfNotNull(trays[entry.text("widget_id")]?.let { if (title.isBlank()) it else it.copy(title = title) })
                else -> emptyList() // banners and recommendations aren't in this API
            }
        }.shown()

    fun rows(json: JSONObject): List<Row> = json.optJSONArray("trays").objects().map { row(it) }.shown()

    /** The subjects, then the learning paths. */
    fun subjects(trays: Collection<Row>): List<Item> = trays
        .flatMap { it.items }
        .filter { it.subject.isNotEmpty() }
        .distinctBy { it.subject }
        .sortedBy { it.subject.startsWith("learning-paths-") }

    /** Every course once, with its lowercase title and description. */
    fun searchIndex(trays: JSONObject): List<Pair<Item, String>> = trays.optJSONArray("trays").objects()
        .flatMap { products(it) }
        .filter { it.courseId > 0 && it.lecture == 0 }
        .distinctBy { it.courseId }
        .map { it to "${it.title} ${it.description}".lowercase() }

    /** Courses with every word of [query]; more of the words in the title rank first. */
    fun search(index: List<Pair<Item, String>>, query: String): List<Item> {
        val words = query.lowercase().split(SPACES).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return index.filter { (_, text) -> words.all { it in text } }
            .map { it.first }
            .sortedByDescending { item -> words.count { it in item.title.lowercase() } }
    }

    // A row's title is its key on screen.
    private fun List<Row>.shown() = filter { it.title.isNotBlank() && it.items.isNotEmpty() }.distinctBy { it.title }

    private fun products(tray: JSONObject): List<Item> = tray.optJSONArray("products").objects().mapNotNull(::product)

    fun row(tray: JSONObject): Row {
        val tall = tray.text("widget_image_type") == "portrait"
        val items = products(tray).distinctBy { it.key }
        return Row(
            title = tray.text("widget_title").trim(),
            items = if (tall) items.map { if (it.lecture == 0 && it.courseId > 0) it.copy(image = Images.poster(it.courseId)) else it } else items,
            tall = tall,
        )
    }

    fun product(p: JSONObject): Item? {
        val id = p.optInt("course_id")
        val description = plainText(p.text("product_short_description"))
        return when (p.text("product_type")) {
            "course" -> Item(
                title = p.text("course_name").ifEmpty { p.text("product_name") },
                image = Images.course(id, p.text("course_image_name").ifEmpty { "$id.jpg" }),
                description = description,
                backdrop = Images.backdrop(id),
                courseId = id,
            )
            "lecture" -> {
                val number = p.optInt("lecture_number")
                Item(
                    title = p.text("product_name"),
                    image = Images.course(id, p.text("product_image_name")),
                    subtitle = "${p.text("course_name")} · Lecture $number",
                    description = description,
                    courseId = id,
                    lecture = number,
                )
            }
            "category" -> {
                val route = p.text("product_name").substringBefore('/')
                val sku = p.text("product_sku")
                Item(title = subjectTitle(route), image = Images.course(sku, p.text("course_image_name")), subject = route)
            }
            else -> null
        }?.takeIf { it.title.isNotBlank() && (it.courseId > 0 || it.subject.isNotEmpty()) }
    }

    /** `philosophy-and-religion` → `Philosophy & Religion`; `learning-paths-u-s-history-learning-path` → `U.S. History Learning Path`. */
    fun subjectTitle(route: String): String = route.removePrefix("learning-paths-")
        .split('-').filter { it.isNotEmpty() }
        .joinToString(" ") { if (it == "and") "&" else if (it.length == 1) "${it.uppercase()}." else it.replaceFirstChar(Char::uppercaseChar) }
        .replace(SPACED_INITIALS, "")

    fun course(json: JSONObject): Course {
        val id = json.optInt("course_id")
        return Course(
            id = id,
            productId = json.optInt("product_magento_id"),
            title = json.text("course_name"),
            professor = json.text("course_professor_name"),
            description = plainText(json.text("wondrium_description").ifEmpty { json.text("course_description") }),
            hours = json.optDouble("course_duration_hours", 0.0),
            lectures = json.optJSONArray("lectures").objects()
                .filter { it.optInt("lecture_number") > 0 && it.text("lecture_video_filename").isNotEmpty() }
                .map { lecture ->
                    Lecture(
                        number = lecture.optInt("lecture_number"),
                        sku = lecture.text("lecture_sku"),
                        title = lecture.text("lecture_name"),
                        description = plainText(lecture.text("lecture_description")),
                        seconds = lecture.text("time_in_seconds").toIntOrNull() ?: 0,
                        image = Images.course(id, lecture.text("lecture_image_filename")),
                        video = lecture.text("lecture_video_filename"),
                    )
                }
                .distinctBy { it.number } // the key on screen and in the player
                .sortedBy { it.number },
            guidebook = json.text("course_guidebook_path"),
            professors = json.optJSONArray("course_professor_product_id_list").objects().map { it.optInt("professor_product_id") }.filter { it > 0 },
        )
    }

    fun professor(json: JSONObject): Professor {
        val courses = json.optJSONArray("professor_courses")
        val degree = listOf(json.text("professor_qualification"), json.text("professor_almamater")).filter { it.isNotBlank() }.joinToString(", ")
        return Professor(
            name = json.text("professor_name"),
            courses = if (courses == null) emptyList() else (0 until courses.length()).mapNotNull { courses.opt(it)?.toString()?.toIntOrNull() },
            about = listOf(json.text("professor_institution"), degree).filter { it.isNotBlank() }.joinToString(" · "),
            bio = plainText(json.text("professor_biography")),
            image = json.text("professor_image").takeIf { it.isNotBlank() }?.let(Images::professor).orEmpty(),
        )
    }

    /** A course card from its [Summary]: for rows the service doesn't give. */
    fun card(summary: Summary) = Item(
        title = summary.title,
        image = Images.course(summary.courseId),
        subtitle = summary.facts,
        description = summary.description,
        backdrop = Images.backdrop(summary.courseId),
        courseId = summary.courseId,
    )

    fun progress(json: JSONObject): List<Progress> = json.optJSONArray("items").objects().map {
        Progress(
            courseId = it.text("course_id").toIntOrNull() ?: 0,
            lectureSku = it.text("lecture_id"),
            seconds = it.optInt("progress"),
            updatedAt = it.text("updated_at"),
            length = it.text("time_in_seconds").toIntOrNull() ?: 0,
            courseName = it.text("course_name"),
        )
    }

    fun courseIds(json: JSONObject): Set<Int> = json.optJSONArray("items").objects().mapNotNull { it.text("course_id").toIntOrNull() }.toSet()

    /** Courses with progress that counts, the most recently watched first. */
    fun watched(progress: List<Progress>): List<Watched> = progress.filter { it.courseId > 0 }.groupBy { it.courseId }
        .mapNotNull { (id, records) -> records.filter { it.counts }.maxByOrNull { it.updatedAt }?.let { Watched(id, it.courseName, records, it) } }
        .sortedByDescending { it.last.updatedAt }

    /** Watched in the last 16 weeks and not removed from the row ([shown]), the latest first. */
    fun continueWatching(courses: List<Watched>, shown: Set<Int>, now: Instant): List<Watched> {
        val since = now.minus(CONTINUE_WINDOW)
        return courses.filter { it.courseId in shown && it.last.time?.isAfter(since) == true }
    }

    /** A Continue Watching card that plays the course's next lecture; none once it is finished. */
    fun nextUp(course: Course): Item? = course.nextUp?.let { lecture ->
        Item(
            title = course.title,
            image = lecture.image,
            subtitle = "Lecture ${lecture.number}: ${lecture.title}",
            description = lecture.description,
            courseId = course.id,
            lecture = lecture.number,
            progress = if (lecture.isStarted) lecture.fraction else 0f,
            plays = true,
            key = "course:${course.id}", // the same card as the lecture changes
        )
    }

    fun recentlyWatched(courses: List<Watched>, today: LocalDate, zone: ZoneId): List<Item> = courses.map { course ->
        val finished = course.progress.count { it.isFinished }
        Item(
            title = course.title,
            image = Images.course(course.courseId),
            subtitle = listOfNotNull(
                course.last.time?.let { "Last watched ${day(it.atZone(zone).toLocalDate(), today)}" },
                if (finished == 1) "1 lecture finished" else if (finished > 1) "$finished lectures finished" else null,
            ).joinToString(" · "),
            backdrop = Images.backdrop(course.courseId),
            courseId = course.courseId,
        )
    }

    /**
     * The share of a course's [lectures] watched (a lecture under way counts in part), never so
     * small that the bar can't be seen; none until something [Progress.counts].
     */
    fun mark(progress: List<Progress>, lectures: Int): Mark? {
        val counted = progress.filter { it.counts }.distinctBy { it.lectureSku }
        if (lectures <= 0 || counted.isEmpty()) return null
        val watched = counted.sumOf { if (it.isFinished) 1.0 else if (it.length > 0) it.seconds.toDouble() / it.length else 0.0 }
        val last = counted.any { it.isFinished && lectureNumber(it.lectureSku) >= lectures }
        return Mark((watched / lectures).toFloat().coerceIn(MIN_MARK, 1f), finished = last)
    }

    fun summary(json: JSONObject) = Summary(
        courseId = json.optInt("course_id"),
        title = json.text("course_name"),
        professor = json.text("course_professor_name"),
        lectures = json.optInt("course_lecture_count"),
        hours = json.optDouble("course_duration_hours", 0.0),
        description = plainText(json.text("wondrium_description").ifEmpty { json.text("course_description") }),
    )

    private fun day(date: LocalDate, today: LocalDate): String = when {
        date == today -> "today"
        date == today.minusDays(1) -> "yesterday"
        date.year == today.year -> date.format(DAY_MONTH)
        else -> date.format(MONTH_YEAR)
    }

    fun myList(json: JSONObject): List<Item> = json.optJSONArray("items").objects().mapNotNull {
        val id = it.optInt("course_id").takeIf { id -> id > 0 } ?: return@mapNotNull null
        val lecture = lectureNumber(it.text("product_sku"))
        if (lecture > 0) {
            Item(
                title = it.text("lecture_name"),
                image = Images.course(id, it.text("lecture_image_filename")),
                subtitle = "${it.text("course_name")} · Lecture $lecture",
                description = plainText(it.text("lecture_description")),
                courseId = id,
                lecture = lecture,
            )
        } else {
            Item(
                title = it.text("course_name"),
                image = Images.course(id, it.text("course_image_filename").ifEmpty { "$id.jpg" }),
                description = plainText(it.text("course_description")),
                backdrop = Images.backdrop(id),
                courseId = id,
            )
        }
    }.distinctBy { it.key }

    fun listEntries(json: JSONObject): List<ListEntry> = json.optJSONArray("items").objects().map {
        ListEntry(it.optInt("item_id"), it.optInt("course_id"), lectureNumber(it.text("product_sku")))
    }

    /** `ZV50070-L03` → 3; a course SKU → 0. */
    fun lectureNumber(sku: String): Int = LECTURE_SKU.find(sku)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** Tags dropped, entities decoded once ("&amp;lt;" stays "&lt;"), runs of spaces as one. One pass: Home has hundreds. */
    fun plainText(html: String): String {
        val out = StringBuilder(html.length)
        var space = false
        fun add(c: Char) {
            if (c == ' ' || c in '\t'..'\r') {
                space = out.isNotEmpty()
            } else {
                if (space) out.append(' ')
                space = false
                out.append(c)
            }
        }
        var i = 0
        while (i < html.length) {
            val c = html[i]
            if (c == '<') {
                val end = html.indexOf('>', i)
                if (end > 0) {
                    i = end + 1
                    continue
                }
            } else if (c == '&') {
                val end = html.indexOf(';', i)
                val decoded = if (end in i + 2..i + ENTITY_MAX) entity(html.substring(i + 1, end)) else null
                if (decoded != null) {
                    decoded.forEach(::add)
                    i = end + 1
                    continue
                }
            }
            add(c)
            i++
        }
        return out.toString().trim()
    }

    private fun entity(name: String): String? {
        ENTITIES[name]?.let { return it }
        if (!name.startsWith('#')) return null
        val hex = name.startsWith("#x")
        val digits = name.substring(if (hex) 2 else 1)
        if (digits.isEmpty() || !digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val code = digits.toIntOrNull(if (hex) 16 else 10)?.takeIf(Character::isValidCodePoint) ?: return null
        return String(Character.toChars(code))
    }
}

fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** A text field, "" when missing or null (Android's `optString` gives "null" for a JSON null). */
fun JSONObject.text(name: String): String = opt(name)?.takeIf { it != JSONObject.NULL }?.toString().orEmpty()
