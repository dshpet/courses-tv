package io.github.dshpet.coursestv

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/** A screen. Coming back to it bumps [LocalResumes], so it reads personal data again. */
abstract class TvActivity : ComponentActivity() {

    val catalog: Catalog get() = app.catalog

    private var resumes by mutableIntStateOf(0)
    private var started = false
    private var launching = false
    private var signingIn = false

    private val player = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        PlayerActivity.reached(result.data)?.let(::played)
    }

    /** [marks]: course cards show how much of each course has been watched. */
    fun show(marks: Boolean = false, content: @Composable () -> Unit) = setContent {
        TvTheme {
            CompositionLocalProvider(LocalResumes provides resumes) {
                val watched = if (marks) (loadKept(resumes) { catalog.marks(it) } as? Load.Ready)?.value.orEmpty() else emptyMap()
                CompositionLocalProvider(LocalMarks provides watched) {
                    Box(Modifier.fillMaxSize().background(Palette.background)) { content() }
                }
            }
        }
    }

    /**
     * [item] with its course's teacher, size and description where its row gave none. Reading the
     * course also has it ready if the user opens it.
     */
    @Composable
    fun described(item: Item?): Item? {
        val id = item?.takeIf { it.lecture == 0 && it.subject.isEmpty() && (it.subtitle.isEmpty() || it.description.isEmpty()) }?.courseId ?: 0
        val course by produceState<Course?>(null, id) {
            if (id <= 0) return@produceState
            attempt { catalog.details(id, Source.Kept) }.onSuccess { value = it }
            attempt { catalog.details(id) }.onSuccess { value = it }
        }
        val known = course?.takeIf { it.id == id } ?: return item
        return item?.copy(subtitle = item.subtitle.ifEmpty { known.facts }, description = item.description.ifEmpty { known.description })
    }

    override fun onResume() {
        super.onResume()
        launching = false
        // Back from signing in: rebuild, so everything is read with the new session.
        if (signingIn) {
            signingIn = false
            if (catalog.isSignedIn) return recreate()
        }
        if (started) resumes++ else started = true
    }

    fun open(item: Item) = when {
        item.subject.isNotEmpty() -> launch(SubjectActivity.intent(this, item.subject, item.title))
        item.plays -> play(item.courseId, item.lecture)
        else -> openCourse(item.courseId, item.lecture)
    }

    fun play(courseId: Int, lecture: Int, fromStart: Boolean = false) {
        if (claimLaunch()) player.launch(PlayerActivity.intent(this, courseId, lecture, fromStart))
    }

    /** Back from the player, which had reached [lecture]. */
    protected open fun played(lecture: Int) = Unit

    fun openCourse(courseId: Int, lecture: Int = 0) = launch(CourseActivity.intent(this, courseId, lecture))

    fun signIn() {
        signingIn = true
        launch(Intent(this, SignInActivity::class.java))
    }

    /** Ends the website session; [then] runs once its cookies are gone. */
    fun signOut(then: () -> Unit) = WebViewCookies.clear {
        catalog.clearCache()
        then()
    }

    /** The API refused the session: sign in afresh. */
    fun signInAgain() = signOut(::signIn)

    protected fun launch(intent: Intent) {
        if (claimLaunch()) startActivity(intent)
    }

    /** One screen per Select, however fast the presses. */
    private fun claimLaunch(): Boolean {
        if (launching) return false
        launching = true
        return true
    }
}
