package app.eikon.gallery.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AreaLocksTest {
    private val locks = AreaLocks()

    @Test
    fun everythingStartsLocked() {
        LockedArea.entries.forEach { assertFalse(locks.isUnlocked(it)) }
    }

    @Test
    fun unlockingOneAreaLeavesTheOthersLocked() {
        locks.unlock(LockedArea.HIDDEN)
        assertTrue(locks.isUnlocked(LockedArea.HIDDEN))
        assertFalse(locks.isUnlocked(LockedArea.TRASH))
    }

    @Test
    fun lockingAnAreaRevokesOnlyThatArea() {
        locks.unlock(LockedArea.HIDDEN)
        locks.unlock(LockedArea.TRASH)
        locks.lock(LockedArea.HIDDEN)
        assertEquals(setOf(LockedArea.TRASH), locks.unlocked.value)
    }

    @Test
    fun lockAllRevokesEverything() {
        LockedArea.entries.forEach(locks::unlock)
        locks.lockAll()
        assertTrue(locks.unlocked.value.isEmpty())
    }

    @Test
    fun unlockingTwiceIsHarmless() {
        locks.unlock(LockedArea.HIDDEN)
        locks.unlock(LockedArea.HIDDEN)
        assertEquals(setOf(LockedArea.HIDDEN), locks.unlocked.value)
    }
}
