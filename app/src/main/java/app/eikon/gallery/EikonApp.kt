package app.eikon.gallery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.eikon.gallery.core.security.LockedArea
import app.eikon.gallery.core.ui.EikonBottomBar
import app.eikon.gallery.core.ui.TopLevel
import app.eikon.gallery.core.ui.theme.EikonTheme
import app.eikon.gallery.data.settings.AppSettings
import app.eikon.gallery.domain.GridSource
import app.eikon.gallery.feature.collections.CollectionsScreen
import app.eikon.gallery.feature.collections.collectionRoute
import app.eikon.gallery.feature.library.LibraryScreen
import app.eikon.gallery.feature.library.LibraryViewModel
import app.eikon.gallery.feature.permissions.MediaAccessGate
import app.eikon.gallery.feature.security.LockGate
import app.eikon.gallery.feature.security.UnprotectedNotice
import app.eikon.gallery.feature.settings.SettingsScreen
import app.eikon.gallery.feature.trash.TrashScreen

private object Routes {
    const val LIBRARY = "library"
    const val COLLECTIONS = "collections"
    const val SEARCH = "search"
    const val GRID = "grid/{${LibraryViewModel.SOURCE_ARG}}"
    const val TRASH = "trash"
    const val SETTINGS = "settings"
}

@Composable
fun EikonApp(settings: AppSettings) {
    EikonTheme(settings.themeMode) {
        val navController = rememberNavController()
        // A Surface (not just a background) so text without an explicit colour is legible in dark mode.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            NavHost(navController, startDestination = Routes.LIBRARY) {
                composable(Routes.LIBRARY) { LibraryDestination(navController) }
                composable(Routes.COLLECTIONS) { CollectionsDestination(navController) }
                composable(
                    Routes.SEARCH,
                    arguments = listOf(
                        navArgument(LibraryViewModel.SOURCE_ARG) {
                            type = NavType.StringType
                            defaultValue = GridSource.Search.toArg()
                        },
                    ),
                ) { SearchDestination(navController) }
                composable(
                    Routes.GRID,
                    arguments = listOf(navArgument(LibraryViewModel.SOURCE_ARG) { type = NavType.StringType }),
                ) { entry ->
                    val source = GridSource.parse(entry.arguments?.getString(LibraryViewModel.SOURCE_ARG))
                    GridDestination(navController, source, settings)
                }
                composable(Routes.TRASH) { TrashDestination(navController, settings) }
                composable(Routes.SETTINGS) { SettingsScreen(onBack = { navController.popBackStack() }) }
            }
        }
    }
}

private fun NavHostController.goTopLevel(destination: TopLevel) {
    val route = when (destination) {
        TopLevel.LIBRARY -> Routes.LIBRARY
        TopLevel.COLLECTIONS -> Routes.COLLECTIONS
        TopLevel.SEARCH -> Routes.SEARCH
    }
    navigate(route) {
        popUpTo(Routes.LIBRARY) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun LibraryDestination(navController: NavHostController) {
    MediaAccessGate { access, onSelectMore, onOpenAppSettings ->
        LibraryScreen(
            access = access,
            onSelectMoreMedia = onSelectMore,
            onOpenAppSettings = onOpenAppSettings,
            onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            onBack = null,
            bottomBar = { EikonBottomBar(TopLevel.LIBRARY, navController::goTopLevel) },
        )
    }
}

@Composable
private fun SearchDestination(navController: NavHostController) {
    MediaAccessGate { access, onSelectMore, onOpenAppSettings ->
        LibraryScreen(
            access = access,
            onSelectMoreMedia = onSelectMore,
            onOpenAppSettings = onOpenAppSettings,
            onOpenSettings = null,
            onBack = null,
            bottomBar = { EikonBottomBar(TopLevel.SEARCH, navController::goTopLevel) },
        )
    }
}

@Composable
private fun CollectionsDestination(navController: NavHostController) {
    CollectionsScreen(
        onOpen = { navController.navigate(collectionRoute(it)) },
        onOpenTrash = { navController.navigate(Routes.TRASH) },
        bottomBar = { EikonBottomBar(TopLevel.COLLECTIONS, navController::goTopLevel) },
    )
}

@Composable
private fun GridDestination(navController: NavHostController, source: GridSource, settings: AppSettings) {
    val back = { navController.popBackStack(); Unit }
    MediaAccessGate { access, onSelectMore, onOpenAppSettings ->
        if (source == GridSource.Hidden) {
            LockGate(LockedArea.HIDDEN, settings.lockHidden, R.string.auth_title_hidden, onCancel = back) { isProtected ->
                Column {
                    if (settings.lockHidden && !isProtected) UnprotectedNotice()
                    Box(Modifier.fillMaxSize()) {
                        LibraryScreen(access, onSelectMore, onOpenAppSettings, onOpenSettings = null, onBack = back)
                    }
                }
            }
        } else {
            LibraryScreen(access, onSelectMore, onOpenAppSettings, onOpenSettings = null, onBack = back)
        }
    }
}

@Composable
private fun TrashDestination(navController: NavHostController, settings: AppSettings) {
    val back = { navController.popBackStack(); Unit }
    LockGate(LockedArea.TRASH, settings.lockTrash, R.string.auth_title_trash, onCancel = back) { _ ->
        TrashScreen(onBack = back)
    }
}
