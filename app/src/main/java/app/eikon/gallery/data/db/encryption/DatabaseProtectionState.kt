package app.eikon.gallery.data.db.encryption

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the library's database ended up protected, for Settings to tell the user. Null until the database has been opened. */
data class ProtectionStatus(val protection: Protection, val failure: String?)

/** Holds [ProtectionStatus] for the app, and whether the user still has to be told that the database had to be started over. */
@Singleton
class DatabaseProtectionState @Inject constructor(@ApplicationContext private val context: Context) {
    private val _status = MutableStateFlow<ProtectionStatus?>(null)
    val status: StateFlow<ProtectionStatus?> = _status.asStateFlow()

    private val _resetNotice = MutableStateFlow(false)

    /** True from the start that lost its key until the user has read the notice about it (it survives restarts). */
    val resetNotice: StateFlow<Boolean> = _resetNotice.asStateFlow()

    /** Called once, from the thread that opened the database. */
    fun report(opened: Opened<*>) {
        val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (opened.protection == Protection.RESET) preferences.edit(commit = true) { putBoolean(RESET_PENDING, true) }
        _resetNotice.value = preferences.getBoolean(RESET_PENDING, false)
        _status.value = ProtectionStatus(opened.protection, opened.failure)
    }

    fun dismissResetNotice() {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean(RESET_PENDING, false) }
        _resetNotice.value = false
    }

    private companion object {
        const val FILE = "database_state"
        const val RESET_PENDING = "reset_pending"
    }
}
