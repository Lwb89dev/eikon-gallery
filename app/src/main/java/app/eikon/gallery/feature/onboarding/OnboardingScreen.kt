package app.eikon.gallery.feature.onboarding

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eikon.gallery.R
import app.eikon.gallery.core.permissions.MediaAccessChecker
import app.eikon.gallery.domain.MediaAccess
import app.eikon.gallery.feature.settings.SwitchRow
import kotlinx.coroutines.launch

private const val PAGE_COUNT = 2
private const val PAGE_PERMISSIONS = 1

/** What the second screen shows and does, gathered so the pager's page function stays small. */
private class PermissionUi(
    val access: MediaAccess,
    val canReadLocation: Boolean,
    val networkAllowed: Boolean,
    val onAllowMedia: () -> Unit,
    val onAllowLocation: () -> Unit,
    val onNetwork: (Boolean) -> Unit,
)

/**
 * The first thing a new install shows, in two screens: what eikon is (and that it depends on nothing outside the phone), then what it asks for and why. The one thing it cannot do
 * is grant the network: Android hands out the internet permission at install without asking, so the choice made here is eikon's own switch, off unless the user turns it on.
 */
@Composable
fun OnboardingScreen(viewModel: OnboardingViewModel = hiltViewModel()) {
    val pager = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val access by viewModel.access.collectAsStateWithLifecycle()
    val canReadLocation by viewModel.canReadLocation.collectAsStateWithLifecycle()
    val networkAllowed by viewModel.networkAllowed.collectAsStateWithLifecycle()
    var finishAfterDialog by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onMediaDialogClosed()
        if (finishAfterDialog) viewModel.finish()
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refresh() }
    BackHandler(enabled = pager.currentPage > 0) { scope.launch { pager.animateScrollToPage(0) } }

    val askMedia = { mediaLauncher.launch(MediaAccessChecker.mediaPermissions()) }
    val ui = PermissionUi(access, canReadLocation, networkAllowed, askMedia, { locationLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) }, viewModel::setNetworkAllowed)
    val showPermissions: () -> Unit = { scope.launch { pager.animateScrollToPage(PAGE_PERMISSIONS) } }
    // The library is empty without photo access, so the last button asks for it first and goes on once the dialog is answered (whatever the answer, the library screen explains what is missing).
    val askThenFinish = { finishAfterDialog = true; askMedia() }
    val onContinue: () -> Unit = {
        when {
            pager.currentPage < PAGE_PERMISSIONS -> showPermissions()
            access == MediaAccess.NONE -> askThenFinish()
            else -> viewModel.finish()
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        HorizontalPager(pager, Modifier.weight(1f)) { page -> OnboardingPage(page, ui) }
        PageDots(pager)
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text(stringResource(if (pager.currentPage < PAGE_PERMISSIONS) R.string.onboarding_next else R.string.onboarding_start))
        }
    }
}

@Composable
private fun OnboardingPage(page: Int, ui: PermissionUi) {
    if (page == 0) IntroPage() else PermissionsPage(ui)
}

/** The icon, what eikon is, and the promise that it needs nothing from outside. */
@Composable
private fun IntroPage() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(R.drawable.onboarding_icon), contentDescription = null, modifier = Modifier.size(144.dp))
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.onboarding_intro_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.onboarding_intro_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Point(R.string.onboarding_point_local)
            Point(R.string.onboarding_point_independent)
            Point(R.string.onboarding_point_yours)
        }
    }
}

@Composable
private fun Point(text: Int) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
}

/** What eikon asks Android for and why, with the button for each, and the network choice. */
@Composable
private fun PermissionsPage(ui: PermissionUi) {
    val mediaGranted = ui.access != MediaAccess.NONE
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.onboarding_permissions_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.onboarding_permissions_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PermissionCard(R.string.onboarding_perm_media_title, R.string.onboarding_perm_media_body) { AllowControl(mediaGranted, enabled = true, onClick = ui.onAllowMedia) }
        PermissionCard(R.string.onboarding_perm_location_title, R.string.onboarding_perm_location_body) { AllowControl(ui.canReadLocation, enabled = mediaGranted, onClick = ui.onAllowLocation) }
        PermissionCard(R.string.onboarding_perm_network_title, R.string.onboarding_perm_network_body) { SwitchRow(R.string.network_allow, ui.networkAllowed, ui.onNetwork) }
        Promise()
    }
}

@Composable
private fun PermissionCard(title: Int, body: Int, control: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            control()
        }
    }
}

/** "Allow" while the permission is missing, a tick once it is there. */
@Composable
private fun AllowControl(granted: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (!granted) {
        FilledTonalButton(onClick = onClick, enabled = enabled) { Text(stringResource(R.string.onboarding_allow)) }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(stringResource(R.string.onboarding_allowed), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
    }
}

/** The one sentence the whole screen exists to say. */
@Composable
private fun Promise() {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Text(
            stringResource(R.string.onboarding_promise),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun PageDots(pager: PagerState) {
    val description = stringResource(R.string.onboarding_page, pager.currentPage + 1, PAGE_COUNT)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics { contentDescription = description },
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(PAGE_COUNT) { index ->
            val color = if (index == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
            Spacer(Modifier.padding(horizontal = 4.dp).size(8.dp).clip(CircleShape).background(color))
        }
    }
}
