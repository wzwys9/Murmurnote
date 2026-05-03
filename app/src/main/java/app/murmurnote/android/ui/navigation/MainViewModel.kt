package app.murmurnote.android.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmurnote.android.data.preference.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    appPreferences: AppPreferences
) : ViewModel() {
    // 初始值 null = 还没从 DataStore 读出来。UI 必须等 emit 之后再决定 startDestination，
    // 否则任何一边的默认都会让另一边在冷启动时闪一下错误的目的地。
    val onboardingCompleted: StateFlow<Boolean?> = appPreferences.onboardingCompleted
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
