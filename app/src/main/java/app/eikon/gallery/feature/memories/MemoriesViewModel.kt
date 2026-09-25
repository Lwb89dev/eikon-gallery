package app.eikon.gallery.feature.memories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.eikon.gallery.data.memories.MemoryCard
import app.eikon.gallery.data.memories.MemoryRepository
import app.eikon.gallery.domain.memories.MemoryId
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The memories on offer today; null while they are being worked out. */
@HiltViewModel
class MemoriesViewModel @Inject constructor(private val repository: MemoryRepository) : ViewModel(), MemoryActions {
    private val loaded = MutableStateFlow<List<MemoryCard>?>(null)
    val cards: StateFlow<List<MemoryCard>?> = loaded.asStateFlow()

    fun load() {
        viewModelScope.launch { loaded.value = repository.cards() }
    }

    override fun hide(id: MemoryId) = change { repository.hide(id) }

    override fun showFewer(id: MemoryId) = change { repository.showFewer(id.kind) }

    override fun showLessOf(personId: Long) = change { repository.showLessOf(personId) }

    fun reset() = change { repository.reset() }

    /** Applies a choice, then plans again so the memory (or its kind, or the person) goes away at once. */
    private fun change(action: suspend () -> Unit) {
        viewModelScope.launch {
            action()
            loaded.value = repository.cards()
        }
    }
}
