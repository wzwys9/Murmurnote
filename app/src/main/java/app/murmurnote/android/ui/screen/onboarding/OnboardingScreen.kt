package app.murmurnote.android.ui.screen.onboarding

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmurnote.android.R
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel = hiltViewModel(),
    onComplete: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()

    Scaffold { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> CapabilitiesPage()
                    2 -> ApiKeyConfigPage(
                        state = state,
                        onGlmKeyChange = viewModel::updateGlmApiKey,
                        onLlmKeyChange = viewModel::updateLlmApiKey,
                        onTest = viewModel::testConfiguredConnections
                    )
                    3 -> PermissionPage()
                }
            }

            // dots
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                repeat(4) { index ->
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(if (pagerState.currentPage == index) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (pagerState.currentPage == index) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            )
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (pagerState.currentPage in 1..2) {
                    TextButton(onClick = {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                    }) { Text(stringResource(R.string.onboarding_previous)) }
                } else Spacer(Modifier.width(1.dp))

                Button(
                    onClick = {
                        if (pagerState.currentPage < 3) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        } else {
                            viewModel.completeOnboarding(onComplete)
                        }
                    },
                    enabled = !state.testing
                ) {
                    Text(
                        stringResource(
                            if (pagerState.currentPage == 3) {
                                R.string.onboarding_get_started
                            } else {
                                R.string.action_continue
                            }
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(48.dp))
        Text(
            stringResource(R.string.onboarding_tagline_line_one),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Text(
            stringResource(R.string.onboarding_tagline_line_two),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.onboarding_tagline_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun CapabilitiesPage() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(R.string.onboarding_capabilities_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        listOf(
            stringResource(R.string.onboarding_capability_record_title) to
                stringResource(R.string.onboarding_capability_record_description),
            stringResource(R.string.onboarding_capability_local_title) to
                stringResource(R.string.onboarding_capability_local_description),
            stringResource(R.string.onboarding_capability_cloud_title) to
                stringResource(R.string.onboarding_capability_cloud_description),
            stringResource(R.string.onboarding_capability_storage_title) to
                stringResource(R.string.onboarding_capability_storage_description)
        ).forEach { (title, desc) ->
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(desc, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ApiKeyConfigPage(
    state: OnboardingViewModel.UiState,
    onGlmKeyChange: (String) -> Unit,
    onLlmKeyChange: (String) -> Unit,
    onTest: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.onboarding_cloud_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.onboarding_cloud_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = state.glmApiKey,
            onValueChange = onGlmKeyChange,
            label = { Text(stringResource(R.string.onboarding_glm_key_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, "https://bigmodel.cn/usercenter/apikeys".toUri()))
        }) { Text(stringResource(R.string.onboarding_get_api_key)) }
        OutlinedTextField(
            value = state.llmApiKey,
            onValueChange = onLlmKeyChange,
            label = { Text(stringResource(R.string.onboarding_deepseek_key_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, "https://platform.deepseek.com/api_keys".toUri()))
        }) { Text(stringResource(R.string.onboarding_get_api_key)) }
        Button(
            onClick = onTest,
            enabled = (state.glmApiKey.isNotBlank() || state.llmApiKey.isNotBlank()) && !state.testing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(
                    if (state.testing) R.string.onboarding_testing
                    else R.string.onboarding_test_connections
                )
            )
        }

        state.testResult?.let {
            Text(
                it,
                color = if (state.testSuccess == true) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun PermissionPage() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(R.string.onboarding_permissions_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.onboarding_permissions_description),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(12.dp))
        listOf(
            stringResource(R.string.onboarding_permission_microphone_title) to
                stringResource(R.string.onboarding_permission_microphone_description),
            stringResource(R.string.onboarding_permission_notification_title) to
                stringResource(R.string.onboarding_permission_notification_description),
            stringResource(R.string.onboarding_permission_media_title) to
                stringResource(R.string.onboarding_permission_media_description)
        ).forEach { (t, d) ->
            Text(t, style = MaterialTheme.typography.titleSmall)
            Text(d, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
    }
}
