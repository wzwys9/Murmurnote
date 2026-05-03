package app.murmurnote.android.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmurnote.android.domain.usecase.SearchResult
import app.murmurnote.android.domain.usecase.SearchUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchUseCase: SearchUseCase
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    val result = _query
        .debounce(300)
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(SearchResult(emptyList(), emptyList()))
            else searchUseCase(q)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SearchResult(emptyList(), emptyList()))

    fun setQuery(q: String) { _query.value = q }
}
