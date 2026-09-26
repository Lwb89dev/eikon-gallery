package app.eikon.gallery

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
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
import app.eikon.gallery.core.security.ScreenSecrecy
import app.eikon.gallery.data.settings.AppSettings
import app.eikon.gallery.data.settings.SettingsRepository
import app.eikon.gallery.data.sync.LibrarySyncCoordinator
import app.eikon.gallery.feature.viewer.ExternalView
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
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

    @Inject
    lateinit var secrecy: ScreenSecrecy

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
        keepWindowSecureWhenAsked()
        setContent {
            val settings by appViewModel.settings.collectAsState()
            settings?.let { EikonApp(it, externalImage, onCloseExternal = ::finish) }
        }
    }

    /** The window is secure (no screenshots, blank recent-apps card) when the user asked for it everywhere, or while a protected area is open. */
    private fun keepWindowSecureWhenAsked() {
        val asked = appViewModel.settings.filterNotNull().map { it.secureScreens }
        lifecycleScope.launch {
            combine(asked, secrecy.protectedScreenOpen) { everywhere, protectedOpen -> everywhere || protectedOpen }
                .distinctUntilChanged()
                .collect { secure ->
                    if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
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
