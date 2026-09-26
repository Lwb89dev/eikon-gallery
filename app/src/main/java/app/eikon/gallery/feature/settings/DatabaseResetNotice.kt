package app.eikon.gallery.feature.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eikon.gallery.R
import app.eikon.gallery.data.db.encryption.DatabaseProtectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class DatabaseNoticeViewModel @Inject constructor(private val protection: DatabaseProtectionState) : ViewModel() {
    val resetNotice: StateFlow<Boolean> = protection.resetNotice

    fun dismiss() = protection.dismissResetNotice()
}

/**
 * Tells the user, once, that the library's database had to be started over because its key was lost (see `DatabaseEncryptor`). It stays until they have read it, even across restarts:
 * an empty library with no albums and no edits would otherwise be a puzzle.
 */
@Composable
fun DatabaseResetNotice(viewModel: DatabaseNoticeViewModel = hiltViewModel()) {
    val show by viewModel.resetNotice.collectAsStateWithLifecycle()
    if (!show) return
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text(stringResource(R.string.settings_storage_reset_title)) },
        text = { Text(stringResource(R.string.settings_storage_reset)) },
        confirmButton = { TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.settings_storage_reset_ok)) } },
    )
}
