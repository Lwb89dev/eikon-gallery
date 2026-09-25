package app.eikon.gallery.core.security

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Parts of the app that can ask for authentication before opening. */
enum class LockedArea { HIDDEN, TRASH }

/**
 * Which protected areas are currently unlocked. Deliberately in memory only: a new process, leaving
 * the area, or the app going to the background all lock everything again (see [lockAll]).
 */
@Singleton
class AreaLocks @Inject constructor() {
    private val unlockedAreas = MutableStateFlow<Set<LockedArea>>(emptySet())
    val unlocked: StateFlow<Set<LockedArea>> = unlockedAreas.asStateFlow()

    fun isUnlocked(area: LockedArea): Boolean = area in unlockedAreas.value

    fun unlock(area: LockedArea) = unlockedAreas.update { it + area }

    fun lock(area: LockedArea) = unlockedAreas.update { it - area }

    fun lockAll() {
        unlockedAreas.value = emptySet()
    }
}
