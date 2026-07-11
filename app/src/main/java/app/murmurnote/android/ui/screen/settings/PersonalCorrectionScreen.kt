package app.murmurnote.android.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmurnote.android.domain.correction.PersonalCorrectionLearningState
import app.murmurnote.android.domain.correction.PersonalCorrectionProfile
import app.murmurnote.android.domain.correction.PersonalLearningConfidence
import app.murmurnote.android.domain.correction.PersonalLearningVerdict
import app.murmurnote.android.domain.correction.PinyinRelation

@Composable
internal fun PersonalCorrectionScreen(
    modifier: Modifier,
    masterEnabled: Boolean,
    onBack: () -> Unit,
    viewModel: PersonalCorrectionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item { PersonalCorrectionHeader(onBack) }
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        if (masterEnabled) "自学习纠错已开启" else "自学习纠错总开关已关闭",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "这里只保存你明确修改过的词对和学习状态。拼音用于辅助判断，" +
                            "未来是否替换仍由局部上下文决定。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        state.errorMessage?.let { message ->
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            message,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = viewModel::dismissError) { Text("知道了") }
                    }
                }
            }
        }
        if (state.profiles.isNotEmpty()) {
            item {
                OutlinedButton(
                    onClick = viewModel::requestClearAll,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("清空全部学习记录")
                }
            }
        }
        when {
            state.isLoading -> item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }
            state.profiles.isEmpty() -> item { PersonalCorrectionEmptyCard() }
            else -> items(state.profiles, key = { it.ruleId }) { profile ->
                PersonalCorrectionProfileCard(
                    profile = profile,
                    masterEnabled = masterEnabled,
                    isUpdating = profile.ruleId in state.updatingRuleIds,
                    onEnabledChange = { viewModel.setEnabled(profile, it) },
                    onDelete = { viewModel.requestDelete(profile) },
                )
            }
        }
    }

    state.pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDelete,
            title = { Text("删除这条学习记录？") },
            text = {
                Text(
                    "“${profile.observedText}” → “${profile.replacementText}”\n\n" +
                        "删除后不会再用于未来纠错，也不会改动已有转写或模型原文。",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDelete) { Text("取消") }
            },
        )
    }
    if (state.confirmClearAll) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClearAll,
            title = { Text("清空全部学习记录？") },
            text = { Text("所有个性化词对和学习样本都会删除；已有转写不会变化。") },
            confirmButton = {
                TextButton(onClick = viewModel::confirmClearAll) { Text("全部清空") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissClearAll) { Text("取消") }
            },
        )
    }
}

@Composable
private fun PersonalCorrectionHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回实验室功能")
        }
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                "个性化自学习纠错",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "查看、停用或遗忘系统学到的词对",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PersonalCorrectionEmptyCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("还没有学习记录", style = MaterialTheme.typography.titleMedium)
            Text(
                "开启功能后，在录音详情里手动修正一次转写，系统会自动判断是否值得学习。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PersonalCorrectionProfileCard(
    profile: PersonalCorrectionProfile,
    masterEnabled: Boolean,
    isUpdating: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "${profile.observedText}  →  ${profile.replacementText}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "${profile.state.displayName()} · ${profile.pinyinRelation.displayName()} · " +
                    "正向样本 ${profile.positiveEvidenceCount} 次" +
                    if (profile.negativeEvidenceCount > 0) {
                        " · 纠正反馈 ${profile.negativeEvidenceCount} 次"
                    } else {
                        ""
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            profile.reviewExplanation()?.let { explanation ->
                Text(
                    explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when {
                        profile.isEnabled && masterEnabled -> "未来命中时会先让 AI 判断上下文"
                        profile.isEnabled -> "词条已启用；总开关关闭"
                        profile.canBeEnabled -> "可以重新启用"
                        else -> "当前不会参与自动纠错"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(
                    checked = profile.isEnabled,
                    onCheckedChange = onEnabledChange,
                    enabled = !isUpdating && (profile.isEnabled || profile.canBeEnabled),
                    modifier = Modifier.semantics {
                        contentDescription =
                            "学习词条开关：${profile.observedText}改为${profile.replacementText}"
                    },
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onDelete, enabled = !isUpdating) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription =
                            "删除学习记录：${profile.observedText}改为${profile.replacementText}",
                    )
                }
            }
        }
    }
}

private fun PersonalCorrectionLearningState.displayName(): String = when (this) {
    PersonalCorrectionLearningState.PENDING_REVIEW -> "等待 AI 评估"
    PersonalCorrectionLearningState.ACTIVE -> "已学会"
    PersonalCorrectionLearningState.NEEDS_MORE_EVIDENCE -> "需要更多样本"
    PersonalCorrectionLearningState.REJECTED -> "未采用"
    PersonalCorrectionLearningState.DISABLED -> "已停用"
}

private fun PinyinRelation.displayName(): String = when (this) {
    PinyinRelation.EXACT_PINYIN -> "拼音相同"
    PinyinRelation.NEAR_PINYIN -> "拼音相近"
    PinyinRelation.NOT_PHONETIC -> "非音近"
    PinyinRelation.UNAVAILABLE -> "拼音未判定"
}

private fun PersonalCorrectionProfile.reviewExplanation(): String? {
    val verdictText = when (lastVerdict ?: return null) {
        PersonalLearningVerdict.ACTIVATE -> "建议学习"
        PersonalLearningVerdict.NEEDS_MORE_EVIDENCE -> "暂需更多样本"
        PersonalLearningVerdict.REJECT -> "不建议学习"
    }
    val confidenceText = when (lastConfidence) {
        PersonalLearningConfidence.HIGH -> "高置信"
        PersonalLearningConfidence.MEDIUM -> "中置信"
        PersonalLearningConfidence.LOW -> "低置信"
        null -> "置信度未知"
    }
    val reasonText = when (lastReasonCode) {
        "PHONETIC_ASR_ERROR" -> "音近的语音识别错误"
        "USER_TERM_FITS_CONTEXT" -> "符合你的固定用词"
        "PROPER_NOUN_FITS_CONTEXT" -> "符合当前专名语境"
        "VISUAL_SIMILARITY_ONLY" -> "只有字形相似证据"
        "NOT_AN_ASR_ERROR" -> "不像语音识别错误"
        "AMBIGUOUS_CONTEXT" -> "当前语境有歧义"
        "LOCAL_RULE_CYCLE" -> "会与已学词条形成循环替换"
        "LOCAL_ACTIVE_RULE_LIMIT" -> "启用词条已达到安全上限"
        else -> "没有可解释的固定原因"
    }
    return "最近判断：$reasonText · $confidenceText · $verdictText"
}
