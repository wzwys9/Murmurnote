package app.murmurnote.android.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.murmurnote.android.R

@Composable
internal fun AppLanguageSetting() {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val currentLanguage = currentAppUiLanguage()

    ListItem(
        leadingContent = {
            Icon(
                imageVector = Icons.Filled.Language,
                contentDescription = null,
            )
        },
        headlineContent = { Text(stringResource(R.string.settings_app_language)) },
        supportingContent = {
            Text(stringResource(R.string.settings_app_language_description))
        },
        trailingContent = {
            Text(
                currentLanguage.displayName(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        modifier = Modifier.clickable(
            role = Role.Button,
            onClick = { showPicker = true },
        ),
    )

    if (showPicker) {
        AppLanguagePickerDialog(
            currentLanguage = currentLanguage,
            onLanguageSelected = { language ->
                showPicker = false
                setAppUiLanguage(language)
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun AppLanguagePickerDialog(
    currentLanguage: AppUiLanguage,
    onLanguageSelected: (AppUiLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_app_language_dialog_title)) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                AppUiLanguage.entries.forEach { language ->
                    val selected = language == currentLanguage
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { onLanguageSelected(language) },
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = null,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = language.displayName(),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun AppUiLanguage.displayName(): String = stringResource(
    when (this) {
        AppUiLanguage.SYSTEM -> R.string.settings_app_language_system
        AppUiLanguage.CHINESE -> R.string.settings_app_language_chinese
        AppUiLanguage.ENGLISH -> R.string.settings_app_language_english
    },
)
