package app.eikon.gallery.feature.permissions

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eikon.gallery.R
import app.eikon.gallery.core.permissions.MediaAccessChecker
import app.eikon.gallery.domain.MediaAccess

/**
 * Shows [content] once eikon can read at least some media, otherwise an explanation and the request
 * button. Handles the three-way Android 14+ model: full access, selected photos only, none.
 *
 * [content] receives a callback that re-opens the system picker so the user can select more photos
 * while access is limited.
 */
@Composable
fun MediaAccessGate(
    modifier: Modifier = Modifier,
    viewModel: MediaAccessViewModel = hiltViewModel(),
    content: @Composable (access: MediaAccess, onSelectMore: () -> Unit, onOpenAppSettings: () -> Unit) -> Unit,
) {
    val access by viewModel.access.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val context = LocalContext.current
    var requestedOnce by rememberSaveable { mutableStateOf(false) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        requestedOnce = true
        // Android stops showing the dialog after repeated denials; rationale flips to false then.
        permanentlyDenied = activity != null && MediaAccessChecker.mediaPermissions().none {
            ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
        }
        viewModel.onPermissionDialogClosed()
    }
    val openAppSettings = {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        context.startActivity(intent)
    }

    if (access == MediaAccess.NONE) {
        PermissionScreen(
            denied = requestedOnce,
            permanentlyDenied = permanentlyDenied,
            onRequest = { launcher.launch(MediaAccessChecker.mediaPermissions()) },
            onOpenSettings = openAppSettings,
            modifier = modifier,
        )
    } else {
        content(access, { launcher.launch(MediaAccessChecker.mediaPermissions()) }, openAppSettings)
    }
}

@Composable
private fun PermissionScreen(
    denied: Boolean,
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.permission_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.permission_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (denied) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.permission_denied),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(32.dp))
        if (permanentlyDenied) {
            Button(onClick = onOpenSettings) { Text(stringResource(R.string.permission_open_settings)) }
        } else {
            Button(onClick = onRequest) { Text(stringResource(R.string.permission_grant)) }
        }
    }
}
