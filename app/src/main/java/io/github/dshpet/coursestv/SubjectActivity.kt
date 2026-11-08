package io.github.dshpet.coursestv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text

class SubjectActivity : TvActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val route = intent.getStringExtra(EXTRA_ROUTE) ?: return finish()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        show(marks = true) { Subject(route, title) }
    }

    @Composable
    private fun Subject(route: String, title: String) {
        var hero by remember { mutableStateOf<Item?>(null) }
        val rows = loadKept { catalog.subject(route, it) }
        val shown = settled(hero)
        Box(Modifier.fillMaxSize()) {
            Backdrop(shown)
            Column(Modifier.fillMaxSize()) {
                Text(title, style = Type.label, color = Palette.dim, modifier = Modifier.padding(start = Dimens.screenH, top = 26.dp))
                HeroInfo(described(shown), Modifier.height(190.dp).padding(top = 10.dp))
                when (rows) {
                    Load.Loading -> SkeletonRows()
                    is Load.Failed -> LoadError(rows, ::signInAgain)
                    is Load.Ready -> {
                        val first = remember { FocusRequester() }
                        RowList(rows.value, { hero = it }, ::open, Modifier.weight(1f), first)
                        LaunchedEffect(Unit) { first.requestFocus() }
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_ROUTE = "route"
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context, route: String, title: String): Intent =
            Intent(context, SubjectActivity::class.java).putExtra(EXTRA_ROUTE, route).putExtra(EXTRA_TITLE, title)
    }
}
