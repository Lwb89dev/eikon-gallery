package app.eikon.gallery.feature.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.eikon.gallery.data.edit.EditRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Every edit, by photo id, for thumbnails and the viewer all over the app (provided once at the root). */
@HiltViewModel
class EditRecipesViewModel @Inject constructor(repository: EditRepository) : ViewModel() {
    val texts: StateFlow<Map<Long, String>> = repository.recipeTexts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
}
