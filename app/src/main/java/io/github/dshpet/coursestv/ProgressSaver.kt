package io.github.dshpet.coursestv

import kotlin.math.abs

/**
 * When and what to save of the position in a course's lectures (seconds): every 30 s of playback,
 * on pause, on leaving and on moving to another lecture. The first two minutes are not saved, a
 * finished lecture stays finished, and reaching the closing 10% saves it as watched to its end.
 */
class ProgressSaver(lecture: Lecture, start: Int, private val send: (Lecture, Int) -> Unit) {

    var lecture = lecture
        private set

    private var position = start

    // The service already has the position playback starts from.
    private var saved = start

    // This session's saves, which the course as loaded doesn't know.
    private val finished = mutableSetOf<Int>()
    private val positions = mutableMapOf<Int, Int>()

    private val isFinished: Boolean get() = lecture.isFinished || lecture.number in finished

    /** Saves once playback is 30 s away from the last save, either way. */
    fun playing(seconds: Int) {
        position = seconds
        if (abs(seconds - saved) >= EVERY_SECONDS) save(seconds)
    }

    fun save(seconds: Int) {
        position = seconds
        val value = when {
            lecture.finishes(seconds) -> lecture.seconds.also { finished += lecture.number }
            seconds < STARTED_SECONDS || isFinished -> return
            else -> seconds
        }
        if (value == saved) return
        saved = value
        positions[lecture.number] = value
        send(lecture, value)
    }

    fun end() = save(lecture.seconds)

    /** A lecture that [ended] counts as watched to its end; a skipped one keeps its position. Returns where [next] starts. */
    fun moveTo(next: Lecture, ended: Boolean): Int {
        if (ended) end() else save(position)
        lecture = next
        val start = if (next.number in finished) 0 else positions[next.number] ?: next.startSeconds
        position = start
        saved = start
        return start
    }

    private companion object {
        const val EVERY_SECONDS = 30
    }
}
