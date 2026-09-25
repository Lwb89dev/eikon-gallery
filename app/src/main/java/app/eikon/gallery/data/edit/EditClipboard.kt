package app.eikon.gallery.data.edit

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.eikon.gallery.core.di.SettingsStore
import app.eikon.gallery.domain.edit.EditRecipe
import app.eikon.gallery.domain.edit.EditRecipeCodec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The edits last copied with "Copy edits", kept across restarts of the app (a few lines of text) so they can be pasted onto other photos later. */
@Singleton
class EditClipboard @Inject constructor(
    @SettingsStore private val store: DataStore<Preferences>,
) {
    val recipe: Flow<EditRecipe?> = store.data.map { prefs -> prefs[KEY]?.let(EditRecipeCodec::decode) }

    /** Copies the look of [recipe] (not its crop, turns or straightening: those belong to one picture). Copying nothing empties the clipboard. */
    suspend fun copy(recipe: EditRecipe) {
        store.edit { prefs ->
            val look = recipe.pasteable()
            if (look.isIdentity) prefs.remove(KEY) else prefs[KEY] = EditRecipeCodec.encode(look)
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("edit_clipboard")
    }
}
