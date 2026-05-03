package app.murmurnote.android.ui.screen.idea

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmurnote.android.data.local.entity.ExtractedItem
import app.murmurnote.android.data.local.entity.ItemType
import app.murmurnote.android.data.repository.ItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class IdeaViewModel @Inject constructor(
    private val repo: ItemRepository
) : ViewModel() {
    val ideas = repo.observeByType(ItemType.IDEA)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<ExtractedItem>())
}
