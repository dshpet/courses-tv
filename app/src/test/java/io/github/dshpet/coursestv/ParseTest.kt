package io.github.dshpet.coursestv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invented catalog, progress and My List records in the API's shape. */
class ParseTest {

    private fun fixture(name: String) = JSONObject(javaClass.classLoader!!.getResource("$name.json")!!.readText())

    private fun tray(title: String) = fixture("home").optJSONArray("trays").objects().first { it.optString("widget_title") == title }

    @Test
    fun `course products become course cards with the course picture`() {
        val row = Parse.row(tray("Most Popular Courses"))
        assertEquals("Most Popular Courses", row.title)
        assertFalse(row.tall)
        val item = row.items.first()
        assertTrue(item.courseId > 0)
        assertEquals(0, item.lecture)
        assertEquals("${Hosts.IMAGES}courses/${item.courseId}/${item.courseId}.jpg", item.image)
        assertEquals(Images.backdrop(item.courseId), item.backdrop)
        assertFalse("descriptions are plain text", item.description.contains('<'))
    }

    @Test
    fun `portrait rows use the posters`() {
        val row = Parse.row(tray("New Releases"))
        assertTrue(row.tall)
        row.items.forEach { assertEquals(Images.poster(it.courseId), it.image) }
    }

    @Test
    fun `lecture products keep their lecture number and course`() {
        val item = Parse.row(tray("Lectures")).items.single()
        assertTrue(item.lecture > 0)
        assertTrue(item.subtitle.endsWith("· Lecture ${item.lecture}"))
        assertTrue(item.image.startsWith("${Hosts.IMAGES}courses/${item.courseId}/"))
    }

    @Test
    fun `subject products link to their subject route`() {
        val subjects = Parse.row(tray("Browse by Subject")).items
        assertEquals("history", subjects.first().subject)
        assertEquals("History", subjects.first().title)
        assertEquals("${Hosts.IMAGES}courses/history-1/history-1.jpg", subjects.first().image)
        val path = Parse.row(tray("Learning Paths")).items.first()
        assertTrue(path.subject.startsWith("learning-paths-"))
        assertFalse(path.title.startsWith("Learning Paths"))
    }

    @Test
    fun `trays without products, such as the banners, give empty rows`() {
        val banner = fixture("home").optJSONArray("trays").objects().first { it.optString("widget_function") == "Jumbotron" }
        assertTrue(Parse.row(banner).items.isEmpty())
    }

    @Test
    fun `subject titles read naturally`() {
        assertEquals("Philosophy & Religion", Parse.subjectTitle("philosophy-and-religion"))
        assertEquals("U.S. History Learning Path", Parse.subjectTitle("learning-paths-u-s-history-learning-path"))
        assertEquals("Learning Paths", Parse.subjectTitle("learning-paths"))
    }

    @Test
    fun `a subject is a list of titled rows`() {
        val rows = Parse.rows(fixture("subject"))
        assertEquals(listOf("Ancient History", "Civilization & Culture"), rows.map { it.title })
        assertTrue(rows.all { it.items.isNotEmpty() })
    }

    @Test
    fun `a course lists its lectures without the trailer entry`() {
        val course = Parse.course(fixture("course"))
        assertEquals(1001, course.id)
        assertEquals("Markets and Lighthouses", course.title)
        assertEquals(listOf(1, 2, 3), course.lectures.map { it.number })
        val first = course.lectures.first()
        assertEquals("ZV1001-L01", first.sku)
        assertTrue(first.seconds > 0)
        assertEquals("${Hosts.STREAMS}ZV1001-L01?manifest=m3u", first.stream)
        assertEquals("${Hosts.IMAGES}courses/1001/1001-L01.jpg", first.image)
        assertEquals("https://example.com/guidebooks/1001.pdf", course.guidebook)
        assertEquals(listOf(2001), course.professors)
        assertEquals("Morgan Hale · 3 lectures · 12.7 hours", course.facts)
    }

    @Test
    fun `a professor lists the courses they teach, with their degree, biography and photo`() {
        val professor = Parse.professor(
            JSONObject(
                """{"professor_name":"Morgan Hale","professor_courses":["1016","1001",null,"x"],"professor_image":"professor/Morgan-Hale.jpg",
                "professor_institution":"","professor_qualification":"PhD","professor_almamater":"Example University",
                "professor_biography":"<strong>Morgan Hale</strong> won Example College&rsquo;s <em>Lantern</em> Prize."}""",
            ),
        )
        assertEquals("Morgan Hale", professor.name)
        assertEquals(listOf(1016, 1001), professor.courses)
        assertEquals("PhD, Example University", professor.about)
        assertEquals("Morgan Hale won Example College’s Lantern Prize.", professor.bio)
        assertEquals("${Hosts.IMAGES}professor/Morgan-Hale.jpg", professor.image)
        assertEquals("Example College · PhD", Parse.professor(JSONObject("""{"professor_institution":"Example College","professor_qualification":"PhD"}""")).about)
        val unknown = Parse.professor(JSONObject())
        assertEquals(emptyList<Int>(), unknown.courses)
        assertEquals("", unknown.about)
        assertEquals("", unknown.image)
    }

    @Test
    fun `a course card from a summary opens the course`() {
        val card = Parse.card(Summary(1016, "Economics", "Morgan Hale", 36, 18.2, "Markets"))
        assertEquals("Economics", card.title)
        assertEquals(1016, card.courseId)
        assertEquals(0, card.lecture)
        assertEquals(Images.course(1016), card.image)
        assertEquals(Images.backdrop(1016), card.backdrop)
        assertEquals("Morgan Hale · 36 lectures · 18.2 hours", card.subtitle)
        assertFalse(card.plays)
    }

    @Test
    fun `null fields read as empty, so fallbacks apply and lectures without video are left out`() {
        val json = fixture("course")
        json.put("wondrium_description", JSONObject.NULL)
        json.optJSONArray("lectures").optJSONObject(1).put("lecture_video_filename", JSONObject.NULL)
        val course = Parse.course(json)
        assertEquals(json.getString("course_description"), course.description)
        assertEquals(listOf(2, 3), course.lectures.map { it.number })
        assertFalse(course.lectures.any { it.stream.contains("null") })
    }

    @Test
    fun `a repeated lecture number is listed once`() {
        val json = fixture("course")
        json.optJSONArray("lectures").put(JSONObject(json.optJSONArray("lectures").optJSONObject(1).toString()))
        assertEquals(listOf(1, 2, 3), Parse.course(json).lectures.map { it.number })
    }

    @Test
    fun `progress marks lectures, the one under way is next up and resumes where it was left`() {
        val course = Parse.course(fixture("course")).withProgress(Parse.progress(fixture("progress")))
        val second = course.lectures.first { it.number == 2 }
        assertEquals(600, second.watched)
        assertTrue(second.isStarted)
        assertEquals(2, course.nextUp?.number)
        assertEquals(600, course.nextUp?.startSeconds)
    }

    private val course = Parse.course(fixture("course"))

    // Progress for lecture number to seconds watched, the later ones more recent.
    private fun progress(vararg watched: Pair<Int, Int>) = watched.mapIndexed { index, (n, seconds) ->
        val lecture = course.lectures.first { it.number == n }
        Progress(course.id, lecture.sku, seconds, "2026-11-0${index + 1} 10:00:00", lecture.seconds, course.title)
    }

    private fun nextUp(vararg watched: Pair<Int, Int>) = course.withProgress(progress(*watched)).nextUp

    @Test
    fun `time watched counts finished lectures in full and the one under way to where it is`() {
        assertEquals("under 2 minutes: opened by mistake", 0, course.withProgress(progress(1 to 100)).watchedSeconds)
        assertEquals(course.lectures[0].seconds + 600, course.withProgress(progress(1 to 1770, 2 to 600)).watchedSeconds)
        assertEquals("10 min of 12.7 hours watched", watchedLabel(600, 12.73))
        assertEquals("11.1 of 16.0 hours watched", watchedLabel(39_960, 16.0))
        assertEquals("never more than the course", "12.7 of 12.7 hours watched", watchedLabel(46_000, 12.73))
    }

    @Test
    fun `next up follows the furthest finished lecture, and a course finished has none`() {
        assertEquals(1, nextUp()?.number)
        assertEquals(2, nextUp(1 to 1956)?.number)
        assertEquals("starts over: nothing under way", 0, nextUp(1 to 1956)?.startSeconds)
        assertEquals("90 percent counts as finished", 2, nextUp(1 to 1770)?.number)
        assertNull(nextUp(1 to 1956, 2 to 1579, 3 to 1857))
        assertNull("finished up to the last lecture, even with one skipped", nextUp(1 to 1956, 3 to 1857))
    }

    @Test
    fun `a lecture replayed or opened by mistake behind the furthest one doesn't move next up back`() {
        assertEquals(3, nextUp(1 to 1956, 2 to 1579, 1 to 681)?.number)
        assertEquals("a few seconds ahead don't count as under way", 0, nextUp(1 to 1956, 2 to 5)?.startSeconds)
        assertEquals("of two under way, the latest", 3, nextUp(2 to 300, 3 to 400)?.number)
        assertEquals(400, nextUp(2 to 300, 3 to 400)?.startSeconds)
    }

    @Test
    fun `watched courses are those with real watching, the latest first`() {
        val records = Parse.progress(fixture("progress")) +
            Progress(1015, "ZV1015-L01", 60, "2026-11-05 10:00:00", 1949, "Understanding Ropes") // opened by mistake
        val watched = Parse.watched(records)
        assertEquals(listOf(1001, 1002), watched.map { it.courseId })
        assertEquals("Markets and Lighthouses", watched.first().title)
    }

    @Test
    fun `continue watching keeps the last 16 weeks, without courses removed from it`() {
        val watched = Parse.watched(Parse.progress(fixture("progress")))
        val now = java.time.Instant.parse("2026-11-07T12:00:00Z")
        assertEquals(listOf(1001, 1002), Parse.continueWatching(watched, setOf(1001, 1002), now).map { it.courseId })
        assertEquals(listOf(1002), Parse.continueWatching(watched, setOf(1002), now).map { it.courseId })
        assertEquals(listOf(1001), Parse.continueWatching(watched, setOf(1001, 1002), java.time.Instant.parse("2027-02-10T00:00:00Z")).map { it.courseId })
    }

    @Test
    fun `a continue watching card plays the course's next lecture`() {
        val item = Parse.nextUp(course.withProgress(progress(1 to 1956, 2 to 600)))!!
        assertEquals("one card per course, whatever its lecture", "course:1001", item.key)
        assertTrue(item.plays)
        assertEquals(2, item.lecture)
        assertEquals(600f / 1579f, item.progress, 0.001f)
        assertEquals("Lecture 2: An Allocation Game", item.subtitle)
        assertEquals("${Hosts.IMAGES}courses/1001/1001-L02.jpg", item.image)
        assertEquals("not yet started: no progress bar", 0f, Parse.nextUp(course.withProgress(progress(1 to 1956)))!!.progress, 0f)
        assertNull(Parse.nextUp(course.withProgress(progress(1 to 1956, 2 to 1579, 3 to 1857))))
    }

    @Test
    fun `recently watched lists the courses, saying when and how much`() {
        val watched = Parse.watched(Parse.progress(fixture("progress")))
        val items = Parse.recentlyWatched(watched, java.time.LocalDate.parse("2026-11-01"), java.time.ZoneOffset.UTC)
        assertEquals(listOf(1001, 1002), items.map { it.courseId })
        assertFalse("opens the course", items.first().plays)
        assertEquals("Last watched today", items[0].subtitle)
        assertEquals("Last watched October 1 · 1 lecture finished", items[1].subtitle)
        assertEquals("${Hosts.IMAGES}courses/1001/1001.jpg", items[0].image)
    }

    @Test
    fun `a course card shows the share of its lectures watched, a lecture under way in part`() {
        fun record(lecture: Int, seconds: Int) = Progress(1001, "ZV1001-L%02d".format(lecture), seconds, "2026-11-01 10:00:00", 1800)
        assertEquals(1.5f / 24, Parse.mark(listOf(record(1, 1800), record(2, 900)), 24)!!.fraction, 0.001f)
        assertEquals("a lecture finished twice counts once", 1f / 24, Parse.mark(listOf(record(1, 1800), record(1, 1700)), 24)!!.fraction, 0.001f)
        assertEquals("a little is still visible", 0.04f, Parse.mark(listOf(record(1, 200)), 24)!!.fraction, 0f)
        assertNull("opened by mistake: no mark", Parse.mark(listOf(record(1, 60)), 24))
        assertNull("lecture count unknown", Parse.mark(listOf(record(1, 1800)), 0))
        assertFalse(Parse.mark(listOf(record(23, 1800)), 24)!!.finished)
        assertTrue("finished with its last lecture, as Continue Watching counts it", Parse.mark(listOf(record(3, 1800), record(24, 1700)), 24)!!.finished)
    }

    @Test
    fun `a course summary gives the teacher, size and description`() {
        val summary = Parse.summary(
            JSONObject("""{"course_id":1001,"course_name":"Markets and Lighthouses","course_lecture_count":24,"course_professor_name":"Morgan Hale","course_duration_hours":12.73,"course_description":"<p>Markets &amp; prices</p>"}"""),
        )
        assertEquals("Markets and Lighthouses", summary.title)
        assertEquals("Morgan Hale · 24 lectures · 12.7 hours", summary.facts)
        assertEquals("Markets & prices", summary.description)
        assertEquals("1 lecture", Summary(1, "", "", 1, 0.0, "").facts)
    }

    @Test
    fun `my list holds courses and single lectures`() {
        val items = Parse.myList(fixture("mylist"))
        assertEquals(0, items[0].lecture)
        assertEquals("Markets and Lighthouses", items[0].title)
        assertEquals(2, items[1].lecture)
        assertEquals("The Debate at the Harbor", items[1].title)
        val entries = Parse.listEntries(fixture("mylist"))
        assertEquals(ListEntry(11, 1001, 0), entries[0])
        assertEquals(ListEntry(12, 1010, 2), entries[1])
    }

    @Test
    fun `home follows the service's row order, with the personal rows filled in`() {
        val next = listOf(Parse.nextUp(course)!!)
        val recent = Parse.recentlyWatched(Parse.watched(Parse.progress(fixture("progress"))), java.time.LocalDate.parse("2026-11-07"), java.time.ZoneOffset.UTC)
        val myList = Parse.myList(fixture("mylist"))
        val rows = Parse.home(fixture("traysorder"), Parse.trays(fixture("home")), Watching(next, recent), myList)
        assertEquals(
            listOf("Continue Watching", "Recently Watched", "New Releases", "Most Popular Courses", "New Pilot Lectures", "My List", "Learning Paths", "Browse by Subject"),
            rows.map { it.title },
        )
        assertEquals(next, rows[0].items)
        assertEquals(recent, rows[1].items)
        assertEquals(myList, rows.first { it.title == "My List" }.items)
        assertTrue(rows.first { it.title == "New Releases" }.tall)
    }

    @Test
    fun `home leaves out banners, recommendations, trays it doesn't order and empty personal rows`() {
        val rows = Parse.home(fixture("traysorder"), Parse.trays(fixture("home")), Watching(emptyList(), emptyList()), emptyList())
        assertEquals(listOf("New Releases", "Most Popular Courses", "New Pilot Lectures", "Learning Paths", "Browse by Subject"), rows.map { it.title })
    }

    @Test
    fun `subjects come first, then learning paths, each once`() {
        val subjects = Parse.subjects(Parse.trays(fixture("home")).values)
        assertEquals(listOf("history", "science", "philosophy-and-religion"), subjects.take(3).map { it.subject })
        assertEquals(3, subjects.drop(3).count { it.subject.startsWith("learning-paths-") })
        assertEquals(6, subjects.size)
    }

    @Test
    fun `search finds courses with every word, title matches first`() {
        val index = Parse.searchIndex(fixture("home"))
        assertEquals("each course once, no lectures or subjects", index.map { it.first.courseId }.distinct(), index.map { it.first.courseId })
        assertTrue(index.all { it.first.lecture == 0 && it.first.courseId > 0 })
        assertFalse("search results are landscape cards, never posters", index.any { it.first.image.contains("/portrait/") })

        assertEquals(listOf(1003, 1005, 1006, 1002, 1004, 1009), Parse.search(index, "history").map { it.courseId })
        assertEquals(listOf(1006), Parse.search(index, "Secret  HISTORY ").map { it.courseId })
        assertTrue(Parse.search(index, "roman").isEmpty())
        assertTrue(Parse.search(index, "  ").isEmpty())
    }

    @Test
    fun `a JSON null reads as empty text`() {
        val json = JSONObject().put("name", JSONObject.NULL).put("number", 7)
        assertEquals("", json.text("name"))
        assertEquals("", json.text("missing"))
        assertEquals("7", json.text("number"))
    }

    @Test
    fun `lecture numbers come from lecture SKUs only`() {
        assertEquals(3, Parse.lectureNumber("ZV1001-L03"))
        assertEquals(0, Parse.lectureNumber("1001"))
    }

    @Test
    fun `html descriptions become plain text`() {
        assertEquals("Who’s Who — of “giants”", Parse.plainText("<p>Who&rsquo;s Who&nbsp;&mdash; of &ldquo;<strong>giants</strong>&rdquo;</p>"))
        assertEquals("\"the unnecessary war.\" — ok", Parse.plainText("&#34;the unnecessary war.&#34; &#x2014; ok"))
        assertEquals("a < b & c", Parse.plainText("a &lt; b &amp; c"))
        assertEquals("escaped: &lt; &#39;", Parse.plainText("escaped: &amp;lt; &amp;#39;"))
        assertEquals("not a character: &#x110000; &#99999999;", Parse.plainText("not a character: &#x110000; &#99999999;"))
        assertEquals("AT&T; a d; 3 &lt 4 &#X41; &#x; &#1a;", Parse.plainText("AT&T; a < b and c > d; 3 &lt 4 &#X41; &#x; &#1a;"))
        assertEquals("a c", Parse.plainText("a <b class=\"x\">\n</b>\t c"))
        assertEquals("one two", Parse.plainText("  one&nbsp;\r\n two &#32;"))
        assertEquals("x<b>y A", Parse.plainText("x&lt;b&gt;y &#65;"))
        assertEquals("unclosed <tag", Parse.plainText("unclosed <tag"))
        assertNull(Parse.product(JSONObject().put("product_type", "unknown")))
    }
}
