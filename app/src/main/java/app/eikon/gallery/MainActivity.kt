package app.eikon.gallery

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.ViewModel
import app.eikon.gallery.core.security.AreaLocks
import app.eikon.gallery.data.settings.AppSettings
import app.eikon.gallery.data.settings.SettingsRepository
import app.eikon.gallery.data.sync.LibrarySyncCoordinator
import app.eikon.gallery.feature.viewer.ExternalView
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AppViewModel @Inject constructor(settingsRepository: SettingsRepository) : ViewModel() {
    val settings: StateFlow<AppSettings?> = settingsRepository.state
}

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject
    lateinit var syncCoordinator: LibrarySyncCoordinator

    @Inject
    lateinit var areaLocks: AreaLocks

    private val appViewModel: AppViewModel by viewModels()

    /** A picture another app asked eikon to show ("Open with"); when set, that is all this window shows. */
    private var externalImage: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Hold the splash until the persisted theme/grid settings are read, so the first frame is final.
        splash.setKeepOnScreenCondition { appViewModel.settings.value == null }
        enableEdgeToEdge()
        externalImage = imageToView(intent)

        // Keep the index fresh only while the app is visible; nothing runs in the background. Showing one picture for another app needs none of it.
        if (externalImage == null) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) { syncCoordinator.keepFresh() }
            }
        }
        setContent {
            val settings by appViewModel.settings.collectAsState()
            settings?.let { EikonApp(it, externalImage, onCloseExternal = ::finish) }
        }
    }

    private fun imageToView(intent: Intent?): Uri? {
        val data = intent?.data ?: return null
        return data.takeIf { ExternalView.isImage(intent.action, it.scheme, intent.type) }
    }

    /** Protected areas (Hidden, Recently deleted) lock again as soon as the app leaves the screen. */
    override fun onStop() {
        areaLocks.lockAll()
        super.onStop()
    }
}
