package app.eikon.gallery.feature.backup

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.eikon.gallery.R
import app.eikon.gallery.feature.settings.GroupTitle

/**
 * In the `standard` build the backup is not there, and the app says so and says why, instead of showing controls that would do nothing. This build asks Android for no
 * network access at all; the `backup` build is the same app with the backup added, and can be installed over this one without losing anything.
 */
@Composable
fun BackupSettingsSection() {
    GroupTitle(R.string.backup_title)
    Text(
        text = stringResource(R.string.backup_not_in_this_build),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}
