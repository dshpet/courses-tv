package io.github.dshpet.coursestv

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressSaverTest {

    private fun lecture(number: Int, watched: Int = 0) =
        Lecture(number, "ZV1001-L0$number", "Lecture $number", "", seconds = 1800, image = "", video = "", watched = watched)

    private val sent = mutableListOf<Pair<Int, Int>>()

    private fun saver(first: Lecture, start: Int = first.startSeconds) =
        ProgressSaver(first, start) { lecture, seconds -> sent += lecture.number to seconds }

    @Test
    fun `playback is saved every 30 s from where it started, never twice the same`() {
        val saver = saver(lecture(1, watched = 600))
        saver.playing(610)
        saver.playing(629)
        assertEquals("the service already has the start", emptyList<Pair<Int, Int>>(), sent)
        saver.playing(630)
        saver.save(630)
        saver.playing(659)
        saver.playing(660)
        assertEquals(listOf(1 to 630, 1 to 660), sent)
    }

    @Test
    fun `a seek back is saved too, a pause saves where it is`() {
        val saver = saver(lecture(1, watched = 600))
        saver.playing(300)
        saver.save(310)
        assertEquals(listOf(1 to 300, 1 to 310), sent)
    }

    @Test
    fun `the first two minutes are never saved, so a lecture opened by mistake changes nothing`() {
        val saver = saver(lecture(3))
        saver.playing(30)
        saver.playing(90)
        saver.save(100)
        saver.save(0)
        assertEquals(emptyList<Pair<Int, Int>>(), sent)
        saver.playing(125)
        assertEquals(listOf(3 to 125), sent)
    }

    @Test
    fun `a lecture played from the beginning keeps its old position until two minutes in`() {
        val saver = saver(lecture(1, watched = 1200), start = 0)
        saver.playing(60)
        saver.save(90)
        assertEquals("a quick look at the start changes nothing", emptyList<Pair<Int, Int>>(), sent)
        saver.playing(130)
        assertEquals(listOf(1 to 130), sent)
    }

    @Test
    fun `a finished lecture played again stays finished`() {
        val saver = saver(lecture(2, watched = 1790))
        saver.playing(30)
        saver.playing(600)
        saver.save(900)
        assertEquals("nothing that would undo finished", emptyList<Pair<Int, Int>>(), sent)
        saver.save(1700)
        assertEquals("played to its closing minutes again: finished again", listOf(2 to 1800), sent)
    }

    @Test
    fun `leaving in the closing 10 percent saves the lecture as finished`() {
        val saver = saver(lecture(1, watched = 600))
        saver.save(1650)
        assertEquals(listOf(1 to 1800), sent)
        saver.save(1660)
        assertEquals("already finished: nothing new", listOf(1 to 1800), sent)
    }

    @Test
    fun `a lecture finished in this session stays finished when played back or returned to`() {
        val saver = saver(lecture(1, watched = 600))
        saver.save(1650)
        saver.playing(1000) // seeked back
        saver.save(1000)
        assertEquals(listOf(1 to 1800), sent)
        saver.moveTo(lecture(2), ended = false)
        assertEquals("back to it (media previous): starts over, saves nothing lower", 0, saver.moveTo(lecture(1, watched = 600), ended = false))
        saver.playing(300)
        saver.save(400)
        assertEquals(listOf(1 to 1800), sent)
    }

    @Test
    fun `a lecture that ends counts as watched to its end, the next continues where it was left`() {
        val saver = saver(lecture(1, watched = 600))
        saver.playing(1500)
        assertEquals(900, saver.moveTo(lecture(2, watched = 900), ended = true))
        assertEquals(listOf(1 to 1500, 1 to 1800), sent)
        assertEquals(2, saver.lecture.number)
        saver.playing(905)
        assertEquals("no save just for starting there", 1 to 1800, sent.last())
    }

    @Test
    fun `the course's last lecture played to its end stays finished when the player stops short of it`() {
        val saver = saver(lecture(1, watched = 600))
        saver.playing(1500)
        saver.end()
        saver.save(1712) // leaving: the stream ended short of the listed length
        assertEquals(listOf(1 to 1500, 1 to 1800), sent)
    }

    @Test
    fun `a lecture returned to in the same session continues where this session left it`() {
        val saver = saver(lecture(5))
        saver.playing(1200)
        saver.moveTo(lecture(6), ended = false)
        assertEquals("not 0, the position the course had when the player opened", 1200, saver.moveTo(lecture(5), ended = false))
        assertEquals(listOf(5 to 1200), sent)
    }

    @Test
    fun `a skipped lecture keeps its position, a finished next one starts over`() {
        val saver = saver(lecture(1))
        saver.playing(400)
        assertEquals(0, saver.moveTo(lecture(2, watched = 1790), ended = false))
        assertEquals("already saved: nothing new", listOf(1 to 400), sent)
    }
}
