package app.murmurnote.android.ui.screen.todo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmurnote.android.data.local.entity.ExtractedItem
import app.murmurnote.android.data.repository.ItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TodoViewModel @Inject constructor(
    private val repo: ItemRepository
) : ViewModel() {
    val todos = repo.observeAllTodos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<ExtractedItem>())

    fun toggle(id: Long, done: Boolean) {
        viewModelScope.launch { repo.setCompleted(id, done) }
    }
}
