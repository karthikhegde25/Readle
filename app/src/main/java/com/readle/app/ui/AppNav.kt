package com.readle.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.readle.app.domain.AppSettings
import com.readle.app.domain.LibraryScope
import com.readle.app.domain.Locator
import com.readle.app.reader.ReaderScreen
import com.readle.app.ui.browse.CollectionsScreen
import com.readle.app.ui.browse.FolderBrowserScreen
import com.readle.app.ui.browse.FoldersScreen
import com.readle.app.ui.browse.GroupsScreen
import com.readle.app.ui.browse.NotesScreen
import com.readle.app.ui.browse.VocabularyScreen
import com.readle.app.ui.components.BackIconButton
import com.readle.app.ui.library.DetailsScreen
import com.readle.app.ui.library.LibraryScreen
import com.readle.app.ui.library.PICKER_MIME_TYPES
import com.readle.app.ui.settings.OnboardingScreen
import com.readle.app.ui.settings.SettingsScreen
import com.readle.app.ui.theme.ReadleTheme
import kotlinx.coroutines.launch

private object Route {
    const val LIBRARY = "library"
    const val RECENT = "recent"
    const val COLLECTIONS = "collections"
    const val AUTHORS = "authors"
    const val SERIES = "series"
    const val FOLDERS = "folders"
    const val NOTES = "notes"
    const val VOCABULARY = "vocabulary"
    const val DICTIONARY = "dictionary"
    const val SETTINGS = "settings"
    const val SETTINGS_THEMES = "settings/themes"
    const val SCOPE = "scope/{scope}/{title}"
    const val DETAILS = "details/{id}"
    const val READER = "reader/{id}?loc={loc}&ann={ann}"
    const val BROWSE = "browse/{id}"

    fun scope(scope: LibraryScope, title: String) = "scope/${Uri.encode(scope.encode())}/${Uri.encode(title)}"
    fun details(id: Long) = "details/$id"
    fun reader(id: Long, loc: Locator? = null, ann: Long? = null) =
        "reader/$id?loc=${Uri.encode(loc?.let { Locator.toRouteArg(it) } ?: "")}&ann=${ann ?: -1}"
    fun browse(id: Long) = "browse/$id"
}

private val TOP_LEVEL = setOf(
    Route.LIBRARY, Route.RECENT, Route.COLLECTIONS, Route.AUTHORS, Route.SERIES,
    Route.FOLDERS, Route.NOTES, Route.VOCABULARY, Route.DICTIONARY, Route.SETTINGS
)

private data class DrawerEntry(val route: String, val label: String, val icon: ImageVector)

private val DRAWER = listOf(
    DrawerEntry(Route.LIBRARY, "Library", Icons.Filled.LibraryBooks),
    DrawerEntry(Route.RECENT, "Recent", Icons.Filled.History),
    DrawerEntry(Route.COLLECTIONS, "Collections", Icons.Outlined.CollectionsBookmark),
    DrawerEntry(Route.AUTHORS, "Authors", Icons.Filled.Person),
    DrawerEntry(Route.SERIES, "Series", Icons.Filled.AutoStories),
    DrawerEntry(Route.FOLDERS, "Folders", Icons.Filled.Folder),
    DrawerEntry(Route.NOTES, "Bookmarks & notes", Icons.Filled.Bookmarks),
    DrawerEntry(Route.VOCABULARY, "Vocabulary", Icons.Filled.School),
    DrawerEntry(Route.DICTIONARY, "Dictionary", Icons.Filled.Book),
    DrawerEntry(Route.SETTINGS, "Settings", Icons.Filled.Settings)
)

/** Application root: theme, first-run onboarding, drawer and the navigation graph. */
@Composable
fun ReadleRoot(incoming: Intent?, onIncomingHandled: () -> Unit) {
    val container = LocalAppContainer.current
    // null until DataStore has delivered the first value, so onboarding doesn't flash for returning users.
    val settings by container.settings.settings.collectAsState(initial = null)
    val s = settings ?: AppSettings()

    ReadleTheme(
        appTheme = s.appTheme,
        highContrast = s.highContrast,
        largeTouchTargets = s.largeTouchTargets,
        reducedMotion = s.reducedMotion
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when {
                settings == null -> Unit
                !s.onboardingDone -> {
                    val scope = rememberCoroutineScope()
                    val files = rememberLauncherForActivityResult(com.readle.app.files.OpenDocumentsReadWrite()) { uris: List<Uri> ->
                        scope.launch {
                            if (uris.isNotEmpty()) container.libraryManager.addFiles(uris)
                            container.settings.update { it.copy(onboardingDone = true) }
                        }
                    }
                    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
                        scope.launch {
                            if (uri != null) container.libraryManager.addFolder(uri)
                            container.settings.update { it.copy(onboardingDone = true) }
                        }
                    }
                    OnboardingScreen(
                        onAddFiles = { files.launch(PICKER_MIME_TYPES) },
                        onAddFolder = { folder.launch(null) },
                        onSkip = { scope.launch { container.settings.update { it.copy(onboardingDone = true) } } }
                    )
                }
                else -> MainNav(incoming, onIncomingHandled)
            }
        }
    }
}

@Composable
private fun MainNav(incoming: Intent?, onIncomingHandled: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val nav = rememberNavController()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val topLevel = route in TOP_LEVEL

    // "Open with Readle": register the document, then open it.
    LaunchedEffect(incoming) {
        val intent = incoming ?: return@LaunchedEffect
        val uri = intent.data
        if (intent.action == Intent.ACTION_VIEW && uri != null) {
            val result = container.libraryManager.addFiles(listOf(uri))
            val id = result.firstDocId
            if (id != null) nav.navigate(Route.reader(id))
            else android.widget.Toast.makeText(context, "Readle can't open that file. It supports ${com.readle.app.domain.FormatSupport.supportedLabel()}.", android.widget.Toast.LENGTH_LONG).show()
        }
        onIncomingHandled()
    }

    val menu: @Composable () -> Unit = {
        IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Open navigation menu") }
    }
    val back: @Composable () -> Unit = { BackIconButton { nav.popBackStack() } }

    fun go(r: String) {
        nav.navigate(r) {
            popUpTo(Route.LIBRARY) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = topLevel,
        drawerContent = {
            ModalDrawerSheet {
                Text("Readle", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp))
                DRAWER.forEach { d ->
                    NavigationDrawerItem(
                        icon = { Icon(d.icon, null) },
                        label = { Text(d.label) },
                        selected = route == d.route,
                        onClick = { scope.launch { drawer.close() }; go(d.route) },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    ) {
        NavHost(nav, startDestination = Route.LIBRARY) {
            composable(Route.LIBRARY) {
                LibraryScreen(
                    scope = LibraryScope.All, title = "Library", navigation = menu,
                    onOpenBook = { nav.navigate(Route.reader(it)) },
                    onShowDetails = { nav.navigate(Route.details(it)) },
                    onOpenFolders = { go(Route.FOLDERS) },
                    onReviewUnavailable = { nav.navigate(Route.scope(LibraryScope.Smart(com.readle.app.domain.SmartList.UNAVAILABLE), "Unavailable files")) }
                )
            }
            composable(Route.RECENT) {
                LibraryScreen(
                    scope = LibraryScope.Recent, title = "Recent", navigation = menu,
                    onOpenBook = { nav.navigate(Route.reader(it)) },
                    onShowDetails = { nav.navigate(Route.details(it)) },
                    onOpenFolders = { go(Route.FOLDERS) },
                    onReviewUnavailable = { nav.navigate(Route.scope(LibraryScope.Smart(com.readle.app.domain.SmartList.UNAVAILABLE), "Unavailable files")) }
                )
            }
            composable(Route.SCOPE, arguments = listOf(navArgument("scope") { type = NavType.StringType }, navArgument("title") { type = NavType.StringType })) { e ->
                val sc = LibraryScope.decode(e.arguments?.getString("scope"))
                LibraryScreen(
                    scope = sc, title = e.arguments?.getString("title").orEmpty(), navigation = back,
                    onOpenBook = { nav.navigate(Route.reader(it)) },
                    onShowDetails = { nav.navigate(Route.details(it)) },
                    onOpenFolders = { go(Route.FOLDERS) },
                    onReviewUnavailable = { nav.navigate(Route.scope(LibraryScope.Smart(com.readle.app.domain.SmartList.UNAVAILABLE), "Unavailable files")) }
                )
            }
            composable(Route.COLLECTIONS) {
                CollectionsScreen(menu) { sc, title -> nav.navigate(Route.scope(sc, title)) }
            }
            composable(Route.AUTHORS) { GroupsScreen(series = false, navigation = menu) { sc, title -> nav.navigate(Route.scope(sc, title)) } }
            composable(Route.SERIES) { GroupsScreen(series = true, navigation = menu) { sc, title -> nav.navigate(Route.scope(sc, title)) } }
            composable(Route.FOLDERS) {
                FoldersScreen(menu, onOpenScope = { sc, title -> nav.navigate(Route.scope(sc, title)) }, onBrowse = { nav.navigate(Route.browse(it)) })
            }
            composable(Route.BROWSE, arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                FolderBrowserScreen(e.arguments!!.getLong("id"), onBack = { nav.popBackStack() }, onOpenBook = { nav.navigate(Route.reader(it)) })
            }
            composable(Route.NOTES) {
                NotesScreen(menu) { docId, loc, ann -> nav.navigate(Route.reader(docId, loc, ann)) }
            }
            composable(Route.VOCABULARY) { VocabularyScreen(menu) }
            composable(Route.DICTIONARY) { com.readle.app.ui.dictionary.DictionaryScreen(menu) }
            composable(Route.SETTINGS) { SettingsScreen(menu) }
            // Opened from the reader's Theme row; Back returns to the book.
            composable(Route.SETTINGS_THEMES) { SettingsScreen(back, scrollToThemes = true) }
            composable(Route.DETAILS, arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                val id = e.arguments!!.getLong("id")
                DetailsScreen(
                    docId = id,
                    onBack = { nav.popBackStack() },
                    onOpen = { nav.navigate(Route.reader(it)) },
                    onOpenAnnotations = { go(Route.NOTES) }
                )
            }
            composable(
                Route.READER,
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("loc") { type = NavType.StringType; defaultValue = "" },
                    navArgument("ann") { type = NavType.LongType; defaultValue = -1L }
                )
            ) { e ->
                val id = e.arguments!!.getLong("id")
                val loc = Locator.fromRouteArg(e.arguments?.getString("loc"))
                val ann = e.arguments?.getLong("ann")?.takeIf { it >= 0 }
                ReaderScreen(
                    docId = id, startAt = loc, startAnnotationId = ann,
                    onExit = { nav.popBackStack() },
                    onOpenDetails = { nav.navigate(Route.details(it)) },
                    onOpenThemes = { nav.navigate(Route.SETTINGS_THEMES) }
                )
            }
        }
    }
}

