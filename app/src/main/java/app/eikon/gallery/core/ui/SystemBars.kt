package app.eikon.gallery.core.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * Sets the status/navigation bar icon colour. [lightIcons] means light-coloured icons, for use on
 * dark backgrounds. The previous appearance is restored when this leaves the composition, so the
 * photo viewer can force light icons over black without leaking that into the rest of the app.
 */
@Composable
fun SystemBarIcons(lightIcons: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view, lightIcons) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = !lightIcons
        controller?.isAppearanceLightNavigationBars = !lightIcons
        onDispose {
            previousStatus?.let { controller.isAppearanceLightStatusBars = it }
            previousNavigation?.let { controller.isAppearanceLightNavigationBars = it }
        }
    }
}

/** Hides the system bars (swipe from the edge shows them transiently) while [hidden] is true. */
@Composable
fun ImmersiveMode(hidden: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view, hidden) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
