package io.github.dshpet.coursestv

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : TvActivity() {

    private enum class Tab(val label: String) { Search("Search"), Home("Home"), Subjects("Subjects"), MyList("My List"), Account("Account") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) catalog.prepareHome()
        show(marks = true) { Main() }
    }

    @Composable
    private fun Main() {
        val resumes = LocalResumes.current
        val signedIn = remember(resumes) { catalog.isSignedIn }
        if (!signedIn) {
            return Message("Courses TV", "Sign in to watch your courses on this TV.", "Sign in" to ::signIn)
        }

        var tab by rememberSaveable { mutableStateOf(Tab.Home) }
        var hero by remember { mutableStateOf<Item?>(null) }
        var navFocused by remember { mutableStateOf(false) }
        val navItems = remember { Tab.entries.associateWith { FocusRequester() } }
        // Home's first card takes the focus once, when the app opens; after that the menu keeps it.
        var opening by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) { navItems.getValue(Tab.Home).requestFocus() }
        LaunchedEffect(tab) {
            hero = null
            if (tab != Tab.Home) opening = false
        }
        // A reload that removes the focused card drops the focus; put it back in the content, or the
        // next key lands on the menu and switches tabs.
        val content = remember { FocusRequester() }
        var contentFocused by remember { mutableStateOf(false) }
        LaunchedEffect(navFocused, contentFocused, opening) {
            if (!navFocused && !contentFocused && !opening) {
                delay(150)
                content.requestFocus()
            }
        }

        // Back from the content goes up to the menu; from the menu, to Home; from Home, out.
        BackHandler(enabled = !navFocused || tab != Tab.Home) {
            if (navFocused) tab = Tab.Home
            navItems.getValue(tab).requestFocus()
        }

        Box(Modifier.fillMaxSize()) {
            Backdrop(settled(hero))
            Column(Modifier.fillMaxSize()) {
                NavBar(tab, { tab = it }, navItems, navFocused, Modifier.onFocusChanged { navFocused = it.hasFocus })
                Box(Modifier.weight(1f).onFocusChanged { contentFocused = it.hasFocus }.focusRequester(content).focusRestorer()) {
                    val onFocus = { item: Item -> hero = item }
                    when (tab) {
                        Tab.Home -> HomeTab(hero, onFocus, takeFocus = opening) { opening = false }
                        Tab.Search -> SearchTab()
                        Tab.Subjects -> SubjectsTab()
                        Tab.MyList -> MyListTab(hero, onFocus)
                        // Rebuilt rather than recomposed in place, which left the Sign in button's focus
                        // undrawn; Home, so the next session doesn't open on the saved Account tab.
                        Tab.Account -> AccountTab(onSignOut = { signOut { tab = Tab.Home; recreate() } })
                    }
                }
            }
        }
    }

    @Composable
    private fun NavBar(tab: Tab, onTab: (Tab) -> Unit, items: Map<Tab, FocusRequester>, navFocused: Boolean, modifier: Modifier) {
        Row(
            modifier.fillMaxWidth().focusRestorer(items.getValue(tab)).padding(start = 34.dp, end = 34.dp, top = 18.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (entry in Tab.entries) {
                if (entry == Tab.Account) Spacer(Modifier.weight(1f))
                NavItem(entry, selected = entry == tab, navFocused, onTab, items.getValue(tab), items.getValue(entry))
            }
        }
    }

    @Composable
    private fun NavItem(entry: Tab, selected: Boolean, navFocused: Boolean, onTab: (Tab) -> Unit, current: FocusRequester, focus: FocusRequester) {
        Surface(
            onClick = { onTab(entry) },
            // Moving along the menu switches tabs; focus arriving from outside goes to the current tab.
            modifier = Modifier.focusRequester(focus).onFocusChanged {
                if (!it.isFocused || selected) return@onFocusChanged
                if (navFocused) onTab(entry) else current.requestFocus()
            },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = if (selected) Palette.text else Palette.dim,
                focusedContainerColor = Palette.focus,
                focusedContentColor = Palette.onFocus,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (entry == Tab.Search) {
                    Icon(Icons.search, contentDescription = entry.label, modifier = Modifier.size(22.dp))
                } else {
                    Text(entry.label, style = Type.label, fontSize = 17.sp)
                }
                Box(Modifier.padding(top = 4.dp).size(22.dp, 2.dp).background(if (selected) Palette.accent else Color.Transparent))
            }
        }
    }

    @Composable
    private fun HomeTab(hero: Item?, onFocus: (Item) -> Unit, takeFocus: Boolean, onFocusTaken: () -> Unit) {
        var reloads by remember { mutableIntStateOf(0) }
        var options by remember { mutableStateOf<Item?>(null) }
        val home = loadKept(reloads, LocalResumes.current) { catalog.home(it) }
        Column(Modifier.fillMaxSize()) {
            HeroInfo(described(settled(hero)), Modifier.height(HERO_HEIGHT).padding(top = 10.dp))
            when (home) {
                Load.Loading -> SkeletonRows()
                is Load.Failed -> LoadError(home, ::signInAgain)
                is Load.Ready -> {
                    val first = remember { FocusRequester() }
                    RowList(home.value, onFocus, ::open, Modifier.weight(1f), first, onLongClick = { options = it })
                    LaunchedEffect(Unit) {
                        reportFullyDrawn()
                        if (hero == null) home.value.firstOrNull()?.items?.firstOrNull()?.let(onFocus)
                        if (takeFocus) first.requestFocus()
                        onFocusTaken()
                    }
                    // The hero shows the focused card as read again.
                    LaunchedEffect(home.value) {
                        val shown = hero ?: return@LaunchedEffect
                        home.value.firstNotNullOfOrNull { row -> row.items.firstOrNull { it.key == shown.key } }?.let(onFocus)
                    }
                }
            }
        }
        options?.let { item ->
            WatchingOptions(item) { removed ->
                options = null
                if (removed) reloads++
            }
        }
    }

    /** Select held on a Continue Watching card. Remove can't be undone, so it takes a second Select. */
    @Composable
    private fun WatchingOptions(item: Item, onClose: (removed: Boolean) -> Unit) {
        val scope = rememberCoroutineScope()
        var confirming by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        OptionsDialog(
            item.title,
            item.subtitle,
            buildList {
                add((if (item.progress > 0f) "Resume lecture ${item.lecture}" else "Play lecture ${item.lecture}") to {
                    onClose(false)
                    play(item.courseId, item.lecture)
                })
                if (item.progress > 0f) {
                    add("Play from the beginning" to {
                        onClose(false)
                        play(item.courseId, item.lecture, fromStart = true)
                    })
                }
                add("Go to course" to {
                    onClose(false)
                    openCourse(item.courseId, item.lecture)
                })
                add("Remove from Continue Watching" to remove@{
                    if (busy) return@remove
                    if (!confirming) {
                        confirming = true
                        return@remove
                    }
                    busy = true
                    scope.launch {
                        failed = attempt { catalog.hideFromContinueWatching(item.courseId) }.isFailure
                        busy = false
                        if (!failed) onClose(true)
                    }
                })
            },
            note = when {
                failed -> "Couldn’t remove it. Check the TV’s connection, then try again."
                confirming -> "It leaves Continue Watching on the website too; its progress stays. Select again to remove it."
                else -> ""
            },
            onDismiss = { onClose(false) },
        )
    }

    @Composable
    private fun SearchTab() {
        var query by rememberSaveable { mutableStateOf("") }
        val first = remember { FocusRequester() }
        val results = load(query, keepOnFailure = false) {
            if (query.isNotBlank()) delay(250) // once typing pauses
            catalog.search(query)
        }
        LaunchedEffect(Unit) { attempt { catalog.prepareSearch() } }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = Dimens.screenH, top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                // Down goes to the first result, not to the one under the field.
                SearchField(query, { query = it }, toResults = { (results as? Load.Ready)?.value?.isNotEmpty() == true && first.requestFocus() })
                Text(
                    when {
                        query.isBlank() || results is Load.Failed -> ""
                        results is Load.Ready && results.value.isEmpty() -> "No courses match “${query.trim()}”"
                        results is Load.Ready -> if (results.value.size == 1) "1 course" else "${results.value.size} courses"
                        else -> "Searching…"
                    },
                    color = Palette.dim,
                    modifier = Modifier.padding(start = 24.dp),
                )
            }
            // A new grid for each query, so its first result is at the top.
            if (results is Load.Ready) key(query.trim()) { ItemGrid(results.value, onFocus = {}, onClick = ::open, modifier = Modifier.weight(1f), first = first) }
            if (results is Load.Failed) LoadError(results, ::signInAgain)
        }
    }

    @Composable
    private fun SearchField(query: String, onQuery: (String) -> Unit, toResults: () -> Boolean) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focusManager = LocalFocusManager.current
        var focused by remember { mutableStateOf(false) }
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = Type.body.copy(color = Palette.text, fontSize = 20.sp),
            cursorBrush = SolidColor(Palette.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, showKeyboardOnFocus = false),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                if (!toResults()) focusManager.moveFocus(FocusDirection.Down)
            }),
            modifier = Modifier
                .width(420.dp)
                .onFocusChanged { focused = it.isFocused }
                // The TV keyboard opens on focus whatever showKeyboardOnFocus says; once Back closes
                // it, Select reopens it. Up and Down leave the field.
                .onPreviewKeyEvent { event ->
                    val down = event.type == KeyEventType.KeyDown
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter -> { if (!down) keyboard?.show(); true }
                        Key.DirectionDown -> { if (down && !toResults()) focusManager.moveFocus(FocusDirection.Down); true }
                        Key.DirectionUp -> { if (down) focusManager.moveFocus(FocusDirection.Up); true }
                        else -> false
                    }
                },
            decorationBox = { field ->
                Row(
                    Modifier
                        .background(Palette.surface, RoundedCornerShape(50))
                        .border(1.5.dp, if (focused) Palette.focus else Palette.line, RoundedCornerShape(50))
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.search, contentDescription = null, tint = Palette.dim, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Box {
                        if (query.isEmpty()) Text("Search courses", style = Type.body, fontSize = 20.sp, color = Palette.faint)
                        field()
                    }
                }
            },
        )
    }

    @Composable
    private fun SubjectsTab() {
        Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
            when (val subjects = loadKept { catalog.subjects(it) }) {
                Load.Loading -> SkeletonRows()
                is Load.Failed -> LoadError(subjects, ::signInAgain)
                is Load.Ready -> ItemGrid(subjects.value, onFocus = {}, onClick = ::open, Modifier.weight(1f))
            }
        }
    }

    @Composable
    private fun MyListTab(hero: Item?, onFocus: (Item) -> Unit) {
        val list = loadKept(LocalResumes.current) { catalog.myList(it) }
        Column(Modifier.fillMaxSize()) {
            HeroInfo(described(settled(hero)), Modifier.height(HERO_HEIGHT).padding(top = 10.dp))
            when (list) {
                Load.Loading -> SkeletonRows()
                is Load.Failed -> LoadError(list, ::signInAgain)
                is Load.Ready -> if (list.value.isEmpty()) {
                    Text("Courses you add to My List appear here.", fontSize = 18.sp, color = Palette.dim, modifier = Modifier.padding(start = Dimens.screenH))
                } else {
                    ItemGrid(list.value, onFocus, ::open, Modifier.weight(1f))
                    LaunchedEffect(Unit) { if (hero == null) onFocus(list.value.first()) }
                }
            }
        }
    }

    @Composable
    private fun AccountTab(onSignOut: () -> Unit) {
        when (val account = load { catalog.account() }) {
            Load.Loading -> Unit
            is Load.Failed -> LoadError(account, ::signInAgain)
            is Load.Ready -> Column(Modifier.fillMaxSize().padding(start = Dimens.screenH, top = 40.dp)) {
                Text(account.value.name.ifEmpty { "Your account" }, style = Type.display)
                Text(
                    if (account.value.subscribed) "The subscription is active." else "This account has no active subscription.",
                    style = Type.body,
                    fontSize = 17.sp,
                    color = Palette.dim,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(32.dp))
                TvButton("Sign out", onClick = onSignOut)
                Text(
                    "Courses TV ${packageManager.getPackageInfo(packageName, 0).versionName} · unofficial, not affiliated with The Teaching Company",
                    style = Type.small,
                    color = Palette.faint,
                    modifier = Modifier.padding(top = 40.dp),
                )
            }
        }
    }

    private companion object {
        val HERO_HEIGHT = 178.dp
    }
}
