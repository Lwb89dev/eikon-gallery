package app.eikon.gallery.feature.backup

import android.Manifest
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eikon.gallery.R
import app.eikon.gallery.data.backup.BackupRunSummary
import app.eikon.gallery.data.backup.BackupSettings
import app.eikon.gallery.data.backup.BackupStop
import app.eikon.gallery.data.backup.ServerKind
import app.eikon.gallery.data.backup.Tls
import app.eikon.gallery.data.db.BackupCounts
import app.eikon.gallery.feature.settings.GroupTitle
import app.eikon.gallery.feature.settings.SwitchRow
import java.text.DateFormat
import java.util.Date

/**
 * The backup in Settings. First the consent to use the network at all (off until the user gives it, here or in the first-run screens), and only when it is on, the rest: which
 * server, how to reach it, what to send and when, and how far it has got. Nothing leaves the phone until the network is allowed **and** the backup is switched on.
 */
@Composable
fun BackupSettingsSection(viewModel: BackupSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    GroupTitle(R.string.backup_title)
    Text(stringResource(R.string.backup_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SwitchRow(R.string.network_allow, settings.networkAllowed, viewModel::setNetworkAllowed)
    Text(stringResource(R.string.network_allow_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (settings.networkAllowed) BackupControls(settings, viewModel)
}

@Composable
private fun BackupControls(settings: BackupSettings, viewModel: BackupSettingsViewModel) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val lastRun by viewModel.lastRun.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val hasCredential by viewModel.hasCredential.collectAsStateWithLifecycle()
    val canReadLocation by viewModel.canReadLocation.collectAsStateWithLifecycle()
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refreshPermission() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshPermission() }

    ServerForm(form, hasCredential, viewModel)
    ConnectionResult(connection, viewModel)
    val ready = hasCredential && BackupSettings.normalizedUrl(settings.serverUrl).isNotEmpty()
    BackupSwitches(settings, ready, counts, viewModel)
    if (settings.enabled) {
        if (!canReadLocation) LocationNote { locationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) }
        BackupStatus(counts, lastRun, running, onNow = viewModel::backUpNow, onRetry = viewModel::retryRefused)
    }
}

@Composable
private fun ServerForm(form: BackupForm, hasCredential: Boolean, viewModel: BackupSettingsViewModel) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ServerKind.entries.forEach { kind -> KindOption(kind, selected = form.kind == kind) { viewModel.edit { it.copy(kind = kind) } } }
        Field(R.string.backup_address, form.url, KeyboardType.Uri) { v -> viewModel.edit { it.copy(url = v) } }
        if (form.kind == ServerKind.NEXTCLOUD) {
            Field(R.string.backup_username, form.username, KeyboardType.Text) { v -> viewModel.edit { it.copy(username = v) } }
            Field(R.string.backup_folder, form.folder, KeyboardType.Text) { v -> viewModel.edit { it.copy(folder = v) } }
        }
        val secretLabel = if (form.kind == ServerKind.IMMICH) R.string.backup_api_key else R.string.backup_app_password
        OutlinedTextField(
            value = form.credential,
            onValueChange = { v -> viewModel.edit { it.copy(credential = v) } },
            label = { Text(stringResource(secretLabel)) },
            placeholder = { if (hasCredential) Text(stringResource(R.string.backup_secret_saved)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(if (form.kind == ServerKind.IMMICH) R.string.backup_help_immich else R.string.backup_help_nextcloud),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = viewModel::saveAndTest, enabled = form.url.isNotBlank() && (hasCredential || form.credential.isNotBlank())) { Text(stringResource(R.string.backup_save_test)) }
    }
}

@Composable
private fun KindOption(kind: ServerKind, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect, role = Role.RadioButton).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(stringResource(if (kind == ServerKind.IMMICH) R.string.backup_kind_immich else R.string.backup_kind_nextcloud), Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun Field(label: Int, value: String, keyboard: KeyboardType, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ConnectionResult(state: ConnectionState, viewModel: BackupSettingsViewModel) {
    when (state) {
        ConnectionState.Untested -> Unit
        ConnectionState.Testing -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        ConnectionState.Declined -> Note(stringResource(R.string.backup_certificate_declined), error = true)
        is ConnectionState.Failed -> Note(stringResource(R.string.backup_failed, state.message), error = true)
        is ConnectionState.Connected -> {
            val version = state.check.version?.let { " $it" }.orEmpty()
            Note(stringResource(R.string.backup_connected, state.check.product + version), error = false)
            state.check.warnings.forEach { Note(it, error = true) }
        }
        is ConnectionState.NeedsTrust -> CertificateCard(state, viewModel)
    }
}

@Composable
private fun CertificateCard(state: ConnectionState.NeedsTrust, viewModel: BackupSettingsViewModel) {
    val certificate = state.certificate
    Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.backup_certificate_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.backup_certificate_body, state.host), style = MaterialTheme.typography.bodyMedium)
            Text(Tls.display(certificate.fingerprint), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
            Text(stringResource(R.string.backup_certificate_subject, certificate.subject), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.backup_certificate_expires, DateFormat.getDateInstance().format(Date(certificate.notAfter))), style = MaterialTheme.typography.bodySmall)
            if (certificate.selfSigned) Text(stringResource(R.string.backup_certificate_self_signed), style = MaterialTheme.typography.bodySmall)
            CertificateChoices(viewModel)
        }
    }
}

@Composable
private fun CertificateChoices(viewModel: BackupSettingsViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = viewModel::trustCertificate) { Text(stringResource(R.string.backup_certificate_trust)) }
        TextButton(onClick = viewModel::refuseCertificate) { Text(stringResource(R.string.action_cancel)) }
    }
}

@Composable
private fun Note(text: String, error: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun BackupSwitches(settings: BackupSettings, ready: Boolean, counts: BackupCounts, viewModel: BackupSettingsViewModel) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(top = 8.dp)) {
        SwitchRow(R.string.backup_enable, settings.enabled && ready) { on -> if (on && !settings.enabled) confirming = true else viewModel.setEnabled(false) }
        if (!ready) Text(stringResource(R.string.backup_test_first), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SwitchRow(R.string.backup_wifi_only, settings.wifiOnly, viewModel::setWifiOnly)
        SwitchRow(R.string.backup_only_charging, settings.chargingOnly, viewModel::setChargingOnly)
        SwitchRow(R.string.backup_videos, settings.includeVideos, viewModel::setIncludeVideos)
        SwitchRow(R.string.backup_hidden, settings.includeHidden, viewModel::setIncludeHidden)
        if (settings.includeHidden) Text(stringResource(R.string.backup_hidden_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.backup_confirm_title)) },
            text = { Text(pluralStringResource(R.plurals.backup_confirm_body, counts.total, counts.total, settings.serverUrl.removePrefix("https://"))) },
            confirmButton = { TextButton(onClick = { confirming = false; viewModel.setEnabled(true) }) { Text(stringResource(R.string.backup_confirm_start)) } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun LocationNote(onAllow: () -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(stringResource(R.string.backup_location_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onAllow) { Text(stringResource(R.string.analysis_allow_locations)) }
    }
}

@Composable
private fun BackupStatus(counts: BackupCounts, lastRun: BackupRunSummary?, running: Boolean, onNow: () -> Unit, onRetry: () -> Unit) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.backup_progress, counts.done, counts.total), style = MaterialTheme.typography.bodyMedium)
        if (counts.total > 0) LinearProgressIndicator(progress = { counts.done.toFloat() / counts.total }, modifier = Modifier.fillMaxWidth())
        if (counts.failed > 0) Text(pluralStringResource(R.plurals.backup_refused, counts.failed, counts.failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Text(
            stringResource(if (running) R.string.backup_running else R.string.backup_idle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        lastRun?.let { LastRun(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onNow, enabled = !running) { Text(stringResource(R.string.backup_now)) }
            if (counts.failed > 0) TextButton(onClick = onRetry) { Text(stringResource(R.string.backup_retry_refused)) }
        }
    }
}

@Composable
private fun LastRun(run: BackupRunSummary) {
    val whenText = DateUtils.getRelativeTimeSpanString(run.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    Text(stringResource(R.string.backup_last_run, whenText, run.sent, run.alreadyThere, run.refused), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val reason = stopReason(run.stoppedBy) ?: return
    Text(stringResource(R.string.backup_stopped, stringResource(reason)) + run.message?.let { " ($it)" }.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

private fun stopReason(stop: BackupStop?): Int? = when (stop) {
    null -> null
    BackupStop.TIME_UP -> null
    BackupStop.DISABLED -> null
    BackupStop.POWER_SAVE -> R.string.backup_stop_power_save
    BackupStop.THERMAL -> R.string.backup_stop_thermal
    BackupStop.UNREACHABLE -> R.string.backup_stop_unreachable
    BackupStop.UNAUTHORIZED -> R.string.backup_stop_unauthorized
    BackupStop.UNTRUSTED -> R.string.backup_stop_untrusted
    BackupStop.MISCONFIGURED -> R.string.backup_stop_misconfigured
    BackupStop.TOO_MANY_REFUSED -> R.string.backup_stop_refusals
}
