package app.eikon.gallery.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.eikon.gallery.core.permissions.MediaAccessChecker
import app.eikon.gallery.data.backup.BackupSettingsRepository
import app.eikon.gallery.data.settings.SettingsRepository
import app.eikon.gallery.data.sync.LibrarySyncCoordinator
import app.eikon.gallery.domain.MediaAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The first-run screens: what the permissions the app asks for stand at, the consent to use the network (which is the app's own switch, since Android grants the internet permission at
 * install and never asks), and the record that the screens have been seen.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val backup: BackupSettingsRepository,
    private val checker: MediaAccessChecker,
    private val coordinator: LibrarySyncCoordinator,
) : ViewModel() {
    private val mediaAccess = MutableStateFlow(checker.current())
    private val locationAllowed = MutableStateFlow(checker.canReadLocation())

    /** What eikon may read now (all, a hand-picked selection, or nothing). */
    val access: StateFlow<MediaAccess> = mediaAccess.asStateFlow()

    /** Whether Android lets eikon read where photos were taken. */
    val canReadLocation: StateFlow<Boolean> = locationAllowed.asStateFlow()

    val networkAllowed: StateFlow<Boolean> = backup.settings.map { it.networkAllowed }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /** Permissions can change behind the app's back (the system settings, the dialogs), so this is asked again whenever the screen is shown. */
    fun refresh() {
        mediaAccess.value = checker.current()
        locationAllowed.value = checker.canReadLocation()
    }

    /** The photo-access dialog closed: what it changed decides what is visible, so the index is rebuilt. */
    fun onMediaDialogClosed() {
        refresh()
        coordinator.requestSync(force = true)
    }

    fun setNetworkAllowed(allowed: Boolean) {
        viewModelScope.launch { backup.update { it.copy(networkAllowed = allowed) } }
    }

    /** The screens have been seen: from now on the library opens directly. */
    fun finish() {
        viewModelScope.launch { settings.setOnboardingCompleted(true) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
