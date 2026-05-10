package app.murmurnote.android.ui.screen.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmurnote.android.data.local.entity.Recording
import app.murmurnote.android.data.repository.RecordingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ListViewModel @Inject constructor(
    private val repo: RecordingRepository
) : ViewModel() {
    private val allRecordings = repo.observeAll()

    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag = _selectedTag.asStateFlow()

    private val _showArchived = MutableStateFlow(false)
    val showArchived = _showArchived.asStateFlow()

    val allTags: StateFlow<List<String>> = allRecordings
        .map { recordings ->
            recordings.flatMap { it.tags.toTagList() }.distinct().sorted()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hasArchived: StateFlow<Boolean> = allRecordings
        .map { recordings -> recordings.any { it.archived } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val recordings: StateFlow<List<Recording>> = combine(
        allRecordings,
        _selectedTag,
        _showArchived
    ) { recordings, selectedTag, showArchived ->
        recordings
            .filter { showArchived || !it.archived }
            .filter { selectedTag == null || selectedTag in it.tags.toTagList() }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(id: String) {
        viewModelScope.launch { repo.delete(id) }
    }

    fun selectTag(tag: String?) {
        _selectedTag.value = tag
    }

    fun toggleShowArchived() {
        _showArchived.value = !_showArchived.value
    }

    private fun String.toTagList(): List<String> =
        split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct()
}
