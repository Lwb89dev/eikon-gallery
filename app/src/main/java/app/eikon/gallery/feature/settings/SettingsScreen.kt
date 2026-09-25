package app.eikon.gallery.feature.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eikon.gallery.R
import app.eikon.gallery.data.db.IndexStage
import app.eikon.gallery.data.indexing.AnalysisStatus
import app.eikon.gallery.data.indexing.StageProgress
import app.eikon.gallery.data.settings.AnalysisSettings
import app.eikon.gallery.data.settings.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val analysis by viewModel.analysis.collectAsStateWithLifecycle()
    val canReadLocation by viewModel.canReadLocation.collectAsStateWithLifecycle()
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.refreshLocationPermission()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshLocationPermission() }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            GroupTitle(R.string.settings_appearance)
            ThemeMode.entries.forEach { mode ->
                ThemeOption(mode, selected = mode == settings.themeMode) { viewModel.setThemeMode(mode) }
            }
            Spacer(Modifier.height(24.dp))
            AnalysisSection(settings.analysis, analysis, canReadLocation, viewModel, onAllowLocations = {
                locationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
            })
            Spacer(Modifier.height(24.dp))
            GroupTitle(R.string.settings_security)
            SwitchRow(R.string.setting_lock_hidden, settings.lockHidden, viewModel::setLockHidden)
            SwitchRow(R.string.setting_lock_trash, settings.lockTrash, viewModel::setLockTrash)
            SwitchRow(R.string.setting_show_hidden, settings.showHidden, viewModel::setShowHidden)
            Text(
                text = stringResource(R.string.settings_security_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(24.dp))
            GroupTitle(R.string.settings_privacy)
            Text(
                text = stringResource(R.string.settings_privacy_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            GroupTitle(R.string.settings_about)
            Text(stringResource(R.string.settings_version, versionName()), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Progress of the background analysis and the switches that control it. */
@Composable
private fun AnalysisSection(
    settings: AnalysisSettings,
    status: AnalysisStatus?,
    canReadLocation: Boolean,
    viewModel: SettingsViewModel,
    onAllowLocations: () -> Unit,
) {
    GroupTitle(R.string.settings_analysis)
    AnalysisProgress(settings, status)
    SwitchRow(R.string.setting_pause_analysis, settings.paused) { on -> viewModel.setAnalysis { it.copy(paused = on) } }
    SwitchRow(R.string.setting_only_charging, settings.onlyWhileCharging) { on -> viewModel.setAnalysis { it.copy(onlyWhileCharging = on) } }
    SwitchRow(R.string.setting_analyze_places, settings.places) { on -> viewModel.setAnalysis { it.copy(places = on) } }
    if (settings.places && !canReadLocation) LocationPermissionPrompt(onAllowLocations)
    SwitchRow(R.string.setting_analyze_semantic, settings.semantic) { on -> viewModel.setAnalysis { it.copy(semantic = on) } }
    SwitchRow(R.string.setting_analyze_people, settings.people) { on -> viewModel.setAnalysis { it.copy(people = on) } }
    if (settings.people) NoteText(R.string.setting_analyze_people_note)
    SwitchRow(R.string.setting_analyze_duplicates, settings.duplicates) { on -> viewModel.setAnalysis { it.copy(duplicates = on) } }
    SwitchRow(R.string.setting_analyze_text, settings.text) { on -> viewModel.setAnalysis { it.copy(text = on) } }
    TextButton(onClick = viewModel::analyzeNow, enabled = !settings.paused && settings.anyEnabled) {
        Text(stringResource(R.string.analyze_now))
    }
    Text(
        text = stringResource(R.string.settings_analysis_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AnalysisProgress(settings: AnalysisSettings, status: AnalysisStatus?) {
    if (status == null) return
    ProgressLine(settings.places, R.string.analysis_places_progress, status.places)
    ProgressLine(settings.semantic, R.string.analysis_semantic_progress, status.semantic)
    ProgressLine(settings.people, R.string.analysis_people_progress, status.people)
    ProgressLine(settings.duplicates, R.string.analysis_duplicates_progress, status.duplicates)
    if (settings.people && IndexStage.FACES in status.unavailable) {
        Text(stringResource(R.string.analysis_people_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    ProgressLine(settings.text, R.string.analysis_text_progress, status.text)
    if (settings.semantic && IndexStage.EMBED in status.unavailable) {
        Text(stringResource(R.string.analysis_semantic_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    val summary = when {
        settings.paused || !settings.anyEnabled -> null
        status.running -> R.string.analysis_running
        isUpToDate(settings, status) -> R.string.analysis_up_to_date
        else -> R.string.analysis_idle
    }
    if (summary != null) {
        Text(stringResource(summary), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NoteText(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ProgressLine(enabled: Boolean, @StringRes label: Int, progress: StageProgress) {
    if (!enabled) return
    Text(stringResource(label, progress.done, progress.total), style = MaterialTheme.typography.bodyMedium)
}

private fun isUpToDate(settings: AnalysisSettings, status: AnalysisStatus): Boolean =
    (!settings.places || status.places.isComplete) &&
        (!settings.semantic || status.semantic.isComplete) &&
        (!settings.people || status.people.isComplete) &&
        (!settings.duplicates || status.duplicates.isComplete) &&
        (!settings.text || status.text.isComplete)

@Composable
private fun LocationPermissionPrompt(onAllow: () -> Unit) {
    Text(
        text = stringResource(R.string.analysis_location_permission),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = onAllow) { Text(stringResource(R.string.analysis_allow_locations)) }
}

@Composable
private fun SwitchRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(label), Modifier.weight(1f).padding(end = 16.dp), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun GroupTitle(title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun ThemeOption(mode: ThemeMode, selected: Boolean, onSelect: () -> Unit) {
    val label = when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(stringResource(label), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun versionName(): String {
    val context = LocalContext.current
    return runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
        .getOrNull()
        .orEmpty()
}
