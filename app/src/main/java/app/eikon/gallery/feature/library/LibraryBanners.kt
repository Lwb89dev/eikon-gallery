package app.eikon.gallery.feature.library

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.eikon.gallery.R
import app.eikon.gallery.data.sync.SyncStatus

/** Shown on Android 14+ when the user granted access to selected photos only. */
@Composable
fun LimitedAccessBanner(onSelectMore: () -> Unit, onAllowAll: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.limited_banner),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onAllowAll) { Text(stringResource(R.string.limited_allow_all)) }
            TextButton(onClick = onSelectMore) { Text(stringResource(R.string.limited_select_more)) }
        }
    }
}

/** Progress of a long sync (first launch on a big library) or a retry prompt after a failure. */
@Composable
fun SyncStatusLine(status: SyncStatus, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    when (status) {
        is SyncStatus.Running -> if (status.total >= SYNC_BANNER_MIN_ROWS) SyncProgress(status, modifier)
        SyncStatus.Failed -> SyncFailed(onRetry, modifier)
        SyncStatus.Idle, SyncStatus.NoAccess -> Unit
    }
}

@Composable
private fun SyncProgress(status: SyncStatus.Running, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(
            text = stringResource(R.string.sync_progress, status.done, status.total),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { if (status.total == 0) 0f else status.done.toFloat() / status.total },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SyncFailed(onRetry: () -> Unit, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.sync_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.sync_retry)) }
    }
}

/** Centered message for a grid with nothing to show, with an optional way out (e.g. clear the filters). */
@Composable
fun EmptyLibrary(
    @StringRes message: Int,
    modifier: Modifier = Modifier,
    @StringRes actionLabel: Int? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null) {
            TextButton(onClick = onAction) { Text(stringResource(actionLabel)) }
        }
    }
}

/** Below this many rows a sync finishes in a blink and a progress bar would only flicker. */
private const val SYNC_BANNER_MIN_ROWS = 1_000
