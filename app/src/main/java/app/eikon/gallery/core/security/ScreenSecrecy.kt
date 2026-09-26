package app.eikon.gallery.core.security

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Knows whether a screen with protected content (Hidden, Recently deleted) is open. While one is, the window is marked secure: Android then blocks screenshots and shows a blank card
 * in the recent-apps list, so a protected photo cannot leak out through either. Counted, not a flag, because a second such screen can open before the first is gone.
 */
@Singleton
class ScreenSecrecy @Inject constructor() {
    private val open = MutableStateFlow(0)

    val protectedScreenOpen: Flow<Boolean> = open.map { it > 0 }.distinctUntilChanged()

    fun enter() = open.update { it + 1 }

    fun leave() = open.update { (it - 1).coerceAtLeast(0) }
}
