package app.eikon.gallery.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.eikon.gallery.core.permissions.MediaAccessChecker
import app.eikon.gallery.data.db.encryption.DatabaseProtectionState
import app.eikon.gallery.data.db.encryption.ProtectionStatus
import app.eikon.gallery.data.indexing.AnalysisStatus
import app.eikon.gallery.data.indexing.AnalysisStatusRepository
import app.eikon.gallery.data.indexing.IndexingScheduler
import app.eikon.gallery.data.settings.AnalysisSettings
import app.eikon.gallery.data.settings.AppSettings
import app.eikon.gallery.data.settings.SettingsRepository
import app.eikon.gallery.data.settings.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    statusRepository: AnalysisStatusRepository,
    private val scheduler: IndexingScheduler,
    private val access: MediaAccessChecker,
    protection: DatabaseProtectionState,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = repository.state
        .filterNotNull()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), repository.state.value ?: AppSettings())

    val analysis: StateFlow<AnalysisStatus?> = statusRepository.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** How the library's database is protected; null until it has been opened. */
    val storage: StateFlow<ProtectionStatus?> = protection.status

    private val locationAllowed = MutableStateFlow(access.canReadLocation())

    /** Whether Android currently lets eikon read where photos were taken. */
    val canReadLocation: StateFlow<Boolean> = locationAllowed.asStateFlow()

    /** Permission can change in system settings or by the dialog; re-check whenever the screen is shown. */
    fun refreshLocationPermission() {
        locationAllowed.value = access.canReadLocation()
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun setLockHidden(enabled: Boolean) {
        viewModelScope.launch { repository.setLockHidden(enabled) }
    }

    fun setLockTrash(enabled: Boolean) {
        viewModelScope.launch { repository.setLockTrash(enabled) }
    }

    fun setShowHidden(enabled: Boolean) {
        viewModelScope.launch { repository.setShowHidden(enabled) }
    }

    fun setSecureScreens(enabled: Boolean) {
        viewModelScope.launch { repository.setSecureScreens(enabled) }
    }

    fun setAnalysis(change: (AnalysisSettings) -> AnalysisSettings) {
        viewModelScope.launch { repository.setAnalysis(change) }
    }

    fun analyzeNow() = scheduler.analyzeNow()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
