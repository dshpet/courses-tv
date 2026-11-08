package io.github.dshpet.coursestv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StashTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val url = "https://m2api.thegreatcourses.com/rest/V1/dlo_products/1001?fields=course_id"

    @Test
    fun `an answer is read back by its url`() {
        val stash = Stash(folder.root.resolve("api"))
        stash.write(url, """{"course_id":1001}""", stash.generation)
        assertEquals(1001, stash.read(url)!!.getInt("course_id"))
        assertNull(stash.read("$url,course_name"))
    }

    @Test
    fun `a newer answer replaces the kept one`() {
        val stash = Stash(folder.root)
        stash.write(url, """{"course_id":1}""", stash.generation)
        stash.write(url, """{"course_id":2}""", stash.generation)
        assertEquals(2, stash.read(url)!!.getInt("course_id"))
    }

    @Test
    fun `a damaged copy reads as none`() {
        val stash = Stash(folder.root)
        stash.write(url, """{"course_id":""", stash.generation)
        assertNull(stash.read(url))
    }

    @Test
    fun `clearing forgets everything, and an answer asked for before it isn't kept`() {
        val stash = Stash(folder.root)
        val before = stash.generation
        stash.write(url, """{"course_id":1}""", before)
        stash.clear()
        assertNull(stash.read(url))
        stash.write(url, """{"course_id":1}""", before)
        assertNull(stash.read(url))
        stash.write(url, """{"course_id":1}""", stash.generation)
        assertNotNull(stash.read(url))
    }
}
