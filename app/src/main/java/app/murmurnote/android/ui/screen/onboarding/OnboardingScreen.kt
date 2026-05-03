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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                modifier = Modifier.fillMaxWidth().weight(1f),
                userScrollEnabled = pagerState.currentPage != 2
            ) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> CapabilitiesPage()
                    2 -> ApiKeyConfigPage(
                        state = state,
                        onGlmKeyChange = viewModel::updateGlmApiKey,
                        onOllamaKeyChange = viewModel::updateOllamaApiKey,
                        onTest = viewModel::testBothConnections
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
                    }) { Text("上一步") }
                } else Spacer(Modifier.width(1.dp))

                if (pagerState.currentPage == 2) {
                    TextButton(onClick = { viewModel.completeOnboarding(); onComplete() }) {
                        Text("稍后配置", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Button(
                    onClick = {
                        if (pagerState.currentPage < 3) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        } else {
                            viewModel.completeOnboarding(); onComplete()
                        }
                    },
                    enabled = when (pagerState.currentPage) {
                        2 -> state.glmApiKey.isNotBlank() && state.ollamaApiKey.isNotBlank()
                        else -> true
                    }
                ) {
                    Text(if (pagerState.currentPage == 3) "开始使用" else "继续")
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
        Text("Murmurnote", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(48.dp))
        Text("随口一说", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text("AI 替你记下来", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Text(
            "把灵感、待办、想法用语音留住",
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
        Text("能做什么", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        listOf(
            "🎙 录音 / 导入" to "随手录或从其他 APP 分享音频过来",
            "📝 自动转写" to "GLM-ASR 识别中文语音为文字",
            "🤖 AI 整理" to "DeepSeek 抽出 4 类结构化信息",
            "📂 本地保存" to "全部数据离线存储，30 天自动清理音频"
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
    onOllamaKeyChange: (String) -> Unit,
    onTest: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("配置 AI 服务", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Murmurnote 使用智谱 AI 做语音识别，使用 Ollama 做文本提取。请先配置您的 API Key。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = state.glmApiKey,
            onValueChange = onGlmKeyChange,
            label = { Text("智谱 GLM API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, "https://bigmodel.cn/usercenter/apikeys".toUri()))
        }) { Text("→ 没有？点这里去申请") }
        OutlinedTextField(
            value = state.ollamaApiKey,
            onValueChange = onOllamaKeyChange,
            label = { Text("Ollama API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, "https://ollama.com/settings/keys".toUri()))
        }) { Text("→ 没有？点这里去申请") }
        Button(
            onClick = onTest,
            enabled = state.glmApiKey.isNotBlank() && state.ollamaApiKey.isNotBlank() && !state.testing,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (state.testing) "测试中..." else "测试两个连接") }

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
        Text("权限说明", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            "为了正常工作，Murmurnote 需要：",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(12.dp))
        listOf(
            "🎤 录音权限" to "用于按住录音",
            "🔔 通知权限" to "处理录音时显示进度",
            "📂 媒体读取" to "支持从其他 APP 导入音频"
        ).forEach { (t, d) ->
            Text(t, style = MaterialTheme.typography.titleSmall)
            Text(d, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
    }
}
