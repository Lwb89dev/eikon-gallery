package app.eikon.gallery.core.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.eikon.gallery.R

/** The top-level destinations. Search joins them when it is implemented, not before. */
enum class TopLevel(@StringRes val label: Int, @DrawableRes val icon: Int) {
    LIBRARY(R.string.nav_library, R.drawable.ic_photo),
    COLLECTIONS(R.string.nav_collections, R.drawable.ic_folder),
}

@Composable
fun EikonBottomBar(current: TopLevel, onSelect: (TopLevel) -> Unit) {
    NavigationBar {
        TopLevel.entries.forEach { destination ->
            NavigationBarItem(
                selected = destination == current,
                onClick = { if (destination != current) onSelect(destination) },
                icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                label = { Text(stringResource(destination.label)) },
            )
        }
    }
}
