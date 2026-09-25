package app.eikon.gallery.feature.permissions

import androidx.lifecycle.ViewModel
import app.eikon.gallery.core.permissions.MediaAccessChecker
import app.eikon.gallery.data.sync.LibrarySyncCoordinator
import app.eikon.gallery.domain.MediaAccess
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@HiltViewModel
class MediaAccessViewModel @Inject constructor(
    private val checker: MediaAccessChecker,
    private val coordinator: LibrarySyncCoordinator,
) : ViewModel() {
    private val mutableAccess = MutableStateFlow(checker.current())
    val access: StateFlow<MediaAccess> = mutableAccess.asStateFlow()

    /** Permissions can change behind the app's back (system settings), so re-check on every resume. */
    fun refresh() {
        mutableAccess.value = checker.current()
    }

    /**
     * After the permission dialog: picking different photos under "selected photos" access changes
     * what is visible without touching any modification date, so the index must be rebuilt.
     */
    fun onPermissionDialogClosed() {
        refresh()
        coordinator.requestSync(force = true)
    }
}
