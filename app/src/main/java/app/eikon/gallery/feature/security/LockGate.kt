package app.eikon.gallery.feature.security

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eikon.gallery.R
import app.eikon.gallery.core.security.AreaLocks
import app.eikon.gallery.core.security.AuthAvailability
import app.eikon.gallery.core.security.BiometricAuthenticator
import app.eikon.gallery.core.security.LockedArea
import app.eikon.gallery.core.ui.findActivity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class LockViewModel @Inject constructor(
    val locks: AreaLocks,
    val authenticator: BiometricAuthenticator,
) : ViewModel()

/**
 * Shows [content] only after the user authenticated with the system prompt (fingerprint, face, or
 * screen-lock PIN). [enabled] false (the user turned the lock off) opens straight away.
 *
 * Honest limits, also stated in the docs: this gates the *screen*. It does not encrypt files, and on a
 * phone with no screen lock at all there is nothing to check against, so the area opens and says so
 * ([content] gets `protected = false`). Leaving the screen locks it again.
 */
@Composable
fun LockGate(
    area: LockedArea,
    enabled: Boolean,
    @StringRes title: Int,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LockViewModel = hiltViewModel(),
    content: @Composable (isProtected: Boolean) -> Unit,
) {
    val unlocked by viewModel.locks.unlocked.collectAsStateWithLifecycle()
    DisposableEffect(area) { onDispose { viewModel.locks.lock(area) } }

    val availability = remember { viewModel.authenticator.availability() }
    val open = !enabled || area in unlocked || availability == AuthAvailability.NO_DEVICE_LOCK
    if (open) {
        content(enabled && availability != AuthAvailability.NO_DEVICE_LOCK)
        return
    }
    LockedScreen(area, title, availability, onCancel, modifier, viewModel)
}

@Composable
private fun LockedScreen(
    area: LockedArea,
    @StringRes title: Int,
    availability: AuthAvailability,
    onCancel: () -> Unit,
    modifier: Modifier,
    viewModel: LockViewModel,
) {
    val activity = LocalContext.current.findActivity() as? FragmentActivity
    val scope = rememberCoroutineScope()
    val promptTitle = stringResource(title)
    val promptSubtitle = stringResource(R.string.auth_subtitle)
    var failed by remember { mutableStateOf(false) }
    val unlock = {
        if (activity != null) {
            scope.launch {
                val ok = viewModel.authenticator.authenticate(activity, promptTitle, promptSubtitle)
                if (ok) viewModel.locks.unlock(area) else failed = true
            }
        }
    }
    // Ask right away on arrival; the button is for trying again after a cancel.
    LaunchedEffect(Unit) { if (availability == AuthAvailability.AVAILABLE) unlock() }

    Column(
        modifier = modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.locked_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        val message = if (availability == AuthAvailability.UNAVAILABLE) R.string.auth_unavailable else R.string.locked_body
        Text(
            stringResource(message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = { unlock() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.locked_unlock)) }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_back)) }
        if (failed) Spacer(Modifier.height(4.dp))
    }
}

/** Banner shown above an open-but-unprotected area (no screen lock on the phone). */
@Composable
fun UnprotectedNotice(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.no_device_lock_notice),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
