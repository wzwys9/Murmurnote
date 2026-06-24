package app.murmurnote.android.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import app.murmurnote.android.R
import app.murmurnote.android.ui.screen.home.HomeScreen
import app.murmurnote.android.ui.screen.idea.IdeaScreen
import app.murmurnote.android.ui.screen.list.ListScreen
import app.murmurnote.android.ui.screen.settings.SettingsScreen
import app.murmurnote.android.ui.screen.todo.TodoScreen

private enum class Tab(val titleRes: Int, val icon: ImageVector) {
    Home(R.string.tab_home, Icons.Filled.Mic),
    List(R.string.tab_list, Icons.AutoMirrored.Filled.FormatListBulleted),
    Todo(R.string.tab_todo, Icons.Filled.Checklist),
    Idea(R.string.tab_idea, Icons.Filled.Lightbulb),
    Settings(R.string.tab_settings, Icons.Filled.Settings)
}

@Composable
fun MainScreen(
    onOpenDetail: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDebug: () -> Unit
) {
    var current by rememberSaveable { mutableStateOf(Tab.Home) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab,
                        onClick = { current = tab },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.titleRes)) }
                    )
                }
            }
        }
    ) { padding ->
        when (current) {
            Tab.Home -> HomeScreen(
                modifier = Modifier.padding(padding),
                onOpenSearch = onOpenSearch
            )
            Tab.List -> ListScreen(
                modifier = Modifier.padding(padding),
                onOpenDetail = onOpenDetail
            )
            Tab.Todo -> TodoScreen(
                modifier = Modifier.padding(padding),
                onOpenDetail = onOpenDetail
            )
            Tab.Idea -> IdeaScreen(
                modifier = Modifier.padding(padding),
                onOpenDetail = onOpenDetail
            )
            Tab.Settings -> SettingsScreen(
                modifier = Modifier.padding(padding),
                onNavigateToDebug = onOpenDebug
            )
        }
    }
}
