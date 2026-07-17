package app.murmurnote.android.ui.screen.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmurnote.android.R
import app.murmurnote.android.domain.pipeline.PipelineStage
import app.murmurnote.android.domain.pipeline.ProcessingQueueEntry
import app.murmurnote.android.domain.pipeline.ProcessingQueueStatus

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onOpenSearch: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val hasActiveProcessingQueue = state.processingQueue.any {
        it.status == ProcessingQueueStatus.WAITING || it.status == ProcessingQueueStatus.RUNNING
    }

    val recordPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startRecording()
        else viewModel.reportPermissionDenied()
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* 不阻塞录音 */ }

    val pickAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.importAudio(uri)
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val onPrimaryClick = {
        if (state.isRecording) {
            viewModel.stopRecording()
        } else {
            val hasPerm = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPerm) viewModel.startRecording()
            else recordPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val recognitionContentScrollState = rememberScrollState()
    LaunchedEffect(state.isRecording) {
        recognitionContentScrollState.scrollTo(0)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val recordingControlTop = upperGoldenSectionTop(
            containerHeight = maxHeight.value,
            elementHeight = RECORDING_CONTROL_SIZE.value,
        ).dp
        val recognitionContentTop = recordingControlTop + RECORDING_OPERATION_HEIGHT

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = recognitionContentTop,
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp,
                )
                .verticalScroll(recognitionContentScrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.isRecording) {
                RealtimeTranscriptCard(
                    active = shouldAnimateLivePreview(
                        active = state.liveTranscriptionActive,
                        isPaused = state.isPaused,
                    ),
                    message = state.liveTranscriptionMessage,
                    segments = state.liveTranscriptSegments,
                    onRetryFailedSegment = viewModel::retryLiveSegment
                )
            }

            // ===== Pipeline 状态卡片：实时显示在干什么 =====
            if (state.pipelineStage !is PipelineStage.Idle) {
                Spacer(Modifier.height(20.dp))
                PipelineProgressCard(
                    stage = state.pipelineStage,
                    onDismiss = { viewModel.dismissPipelineStatus() }
                )
            }

            if (hasActiveProcessingQueue) {
                Spacer(Modifier.height(12.dp))
                ProcessingQueueCard(
                    entries = state.processingQueue,
                    onCancelCurrent = { viewModel.cancelCurrentProcessing(context) }
                )
            }

            state.errorMessage?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Text(
                    msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Column(
            // Keep this visual anchor unchanged while the recorder moves independently.
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp, start = 64.dp, end = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    R.string.home_recording_counts,
                    state.todayCount,
                    state.totalCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .offset(y = recordingControlTop)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrimaryRecordingControl(
                isRecording = state.isRecording,
                isPaused = state.isPaused,
                amplitudeDb = state.amplitudeDb,
                onClick = onPrimaryClick,
            )
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.height(28.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = if (state.isRecording) {
                        val tag = stringResource(
                            if (state.isPaused) R.string.home_paused else R.string.home_recording
                        )
                        "$tag ${formatElapsed(state.elapsedMs)}"
                    } else stringResource(R.string.home_tap_to_record),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (state.isRecording) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (state.isRecording) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { viewModel.togglePause() }) {
                            Icon(if (state.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(
                                    if (state.isPaused) R.string.action_continue
                                    else R.string.action_pause
                                )
                            )
                        }
                        TextButton(onClick = { viewModel.stopRecording() }) {
                            Icon(
                                Icons.Filled.Stop,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.action_stop), color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = { viewModel.cancelRecording() }) {
                            Icon(Icons.Filled.Close, null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                } else {
                    FilledTonalButton(onClick = { pickAudioLauncher.launch(arrayOf("audio/*")) }) {
                        Icon(Icons.Filled.FileUpload, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.home_import_audio))
                    }
                }
            }
        }

        IconButton(
            onClick = onOpenSearch,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp),
        ) {
            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.home_search))
        }
    }
}

internal fun shouldAnimateLivePreview(active: Boolean, isPaused: Boolean): Boolean =
    active && !isPaused

internal fun upperGoldenSectionTop(containerHeight: Float, elementHeight: Float): Float {
    require(containerHeight >= 0f) { "Container height must not be negative" }
    require(elementHeight >= 0f) { "Element height must not be negative" }
    return (containerHeight * UPPER_GOLDEN_SECTION_RATIO - elementHeight / 2f)
        .coerceAtLeast(0f)
}

@Composable
private fun PrimaryRecordingControl(
    isRecording: Boolean,
    isPaused: Boolean,
    amplitudeDb: Int,
    onClick: () -> Unit,
) {
    val contextDescription = stringResource(
        if (isRecording) R.string.home_stop_recording else R.string.home_start_recording
    )
    if (isRecording) {
        Surface(
            modifier = Modifier
                .size(RECORDING_CONTROL_SIZE)
                .semantics { contentDescription = contextDescription },
            color = Color.Transparent,
            shape = MaterialTheme.shapes.large,
            shadowElevation = 0.dp,
            onClick = onClick,
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                RecordingAmplitudeIndicator(
                    amplitudeDb = amplitudeDb,
                    isPaused = isPaused,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.width(124.dp).height(56.dp),
                )
            }
        }
    } else {
        Surface(
            modifier = Modifier
                .size(RECORDING_CONTROL_SIZE)
                .semantics { contentDescription = contextDescription },
            color = MaterialTheme.colorScheme.primary,
            shape = CircleShape,
            shadowElevation = 6.dp,
            onClick = onClick,
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

internal fun recordingAmplitudeLevel(amplitudeDb: Int): Float =
    ((amplitudeDb - RECORDING_NOISE_FLOOR_DB) /
        (RECORDING_PEAK_DB - RECORDING_NOISE_FLOOR_DB))
        .coerceIn(0f, 1f)

@Composable
private fun RecordingAmplitudeIndicator(
    amplitudeDb: Int,
    isPaused: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val targetLevel = if (isPaused) 0f else recordingAmplitudeLevel(amplitudeDb)
    val animatedLevel by animateFloatAsState(
        targetValue = targetLevel,
        animationSpec = tween(durationMillis = 90, easing = LinearEasing),
        label = "recording-amplitude",
    )

    Canvas(modifier = modifier) {
        val barCount = RECORDING_BAR_GAINS.size
        val gap = size.width * 0.055f
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        val minimumHeight = 6.dp.toPx()
        val heightRange = (size.height - minimumHeight).coerceAtLeast(0f)

        RECORDING_BAR_GAINS.forEachIndexed { index, gain ->
            val barHeight = minimumHeight + heightRange * animatedLevel * gain
            drawRoundRect(
                color = color.copy(alpha = 0.9f),
                topLeft = Offset(
                    x = index * (barWidth + gap),
                    y = (size.height - barHeight) / 2f,
                ),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

private const val RECORDING_NOISE_FLOOR_DB = 35f
private const val RECORDING_PEAK_DB = 90f
private const val UPPER_GOLDEN_SECTION_RATIO = 0.38196601125f
private val RECORDING_CONTROL_SIZE = 160.dp
private val RECORDING_OPERATION_HEIGHT = 268.dp
private val RECORDING_BAR_GAINS = floatArrayOf(0.45f, 0.72f, 1f, 0.82f, 1f, 0.72f, 0.45f)

@Composable
private fun ProcessingQueueCard(
    entries: List<ProcessingQueueEntry>,
    onCancelCurrent: () -> Unit
) {
    val running = entries.firstOrNull { it.status == ProcessingQueueStatus.RUNNING }
    val waiting = entries.count { it.status == ProcessingQueueStatus.WAITING }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.home_processing_queue),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (running != null) {
                    TextButton(onClick = onCancelCurrent) {
                        Text(stringResource(R.string.home_cancel_current))
                    }
                }
            }
            running?.let {
                Text(
                    "${it.fileName} · ${it.detail}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (waiting > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.home_waiting_count, waiting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            entries
                .filter {
                    it.status == ProcessingQueueStatus.FAILED ||
                        it.status == ProcessingQueueStatus.CANCELLED ||
                        it.status == ProcessingQueueStatus.COMPLETED
                }
                .takeLast(2)
                .forEach {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${queueStatusLabel(it.status)} · ${it.fileName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (it.status == ProcessingQueueStatus.FAILED) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
        }
    }
}

@Composable
private fun RealtimeTranscriptCard(
    active: Boolean,
    message: String?,
    segments: List<HomeViewModel.LiveTranscriptSegment>,
    onRetryFailedSegment: (Int) -> Unit
) {
    if (message == null && segments.isEmpty()) return
    val transcriptScrollState = rememberScrollState()
    LaunchedEffect(segments.size, segments.lastOrNull()?.text, segments.lastOrNull()?.status) {
        transcriptScrollState.animateScrollTo(transcriptScrollState.maxValue)
    }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    stringResource(R.string.home_live_preview),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            message?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(transcriptScrollState)
            ) {
                segments.forEach { segment ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        liveSegmentLabel(segment),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    when (segment.status) {
                        HomeViewModel.LiveTranscriptStatus.TRANSCRIBING -> {
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { segment.progress ?: 0f },
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HomeViewModel.LiveTranscriptStatus.TRANSCRIBED -> {
                            Text(
                                segment.text.ifBlank { stringResource(R.string.home_empty_recognition) },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HomeViewModel.LiveTranscriptStatus.FAILED -> {
                            Text(
                                stringResource(R.string.home_transcription_failed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            TextButton(onClick = { onRetryFailedSegment(segment.sequence) }) {
                                Text(stringResource(R.string.home_retry_segment))
                            }
                        }
                        HomeViewModel.LiveTranscriptStatus.WAITING -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun queueStatusLabel(status: ProcessingQueueStatus): String = when (status) {
    ProcessingQueueStatus.WAITING -> stringResource(R.string.status_waiting)
    ProcessingQueueStatus.RUNNING -> stringResource(R.string.status_processing)
    ProcessingQueueStatus.COMPLETED -> stringResource(R.string.status_completed)
    ProcessingQueueStatus.FAILED -> stringResource(R.string.status_failed)
    ProcessingQueueStatus.CANCELLED -> stringResource(R.string.status_cancelled)
}

@Composable
private fun PipelineProgressCard(stage: PipelineStage, onDismiss: () -> Unit) {
    val (title, detail, fraction, isError, isDone) = describe(stage)

    val containerColor = when {
        isError -> MaterialTheme.colorScheme.errorContainer
        isDone -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val onColor = when {
        isError -> MaterialTheme.colorScheme.onErrorContainer
        isDone -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isError && !isDone) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = onColor)
                    Spacer(Modifier.width(10.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, color = onColor)
                Spacer(Modifier.fillMaxWidth().weight(1f))
                if (isError || isDone) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            stringResource(R.string.action_close),
                            tint = onColor,
                        )
                    }
                }
            }
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = onColor,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (fraction != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = onColor
                )
            } else if (!isError && !isDone) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = onColor)
            }
        }
    }
}

private data class StageDescription(
    val title: String,
    val detail: String,
    val fraction: Float?,
    val isError: Boolean,
    val isDone: Boolean
)

@Composable
private fun describe(s: PipelineStage): StageDescription = when (s) {
    is PipelineStage.Idle -> StageDescription(
        stringResource(R.string.home_pipeline_idle),
        "",
        null,
        false,
        false,
    )
    is PipelineStage.Recording -> StageDescription(
        stringResource(R.string.home_pipeline_recording),
        formatElapsed(s.durationMs),
        null,
        false,
        false,
    )
    is PipelineStage.Converting -> StageDescription(
        stringResource(R.string.home_pipeline_converting_title),
        stringResource(R.string.home_pipeline_converting_detail),
        s.progress.takeIf { it > 0 }, false, false
    )
    is PipelineStage.Splitting -> StageDescription(
        stringResource(R.string.home_pipeline_splitting_title),
        if (s.segmentCount == 0) {
            stringResource(R.string.home_pipeline_detecting_speech)
        } else {
            stringResource(R.string.home_pipeline_segments_ready, s.segmentCount)
        },
        null, false, false
    )
    is PipelineStage.Transcribing -> StageDescription(
        stringResource(R.string.home_pipeline_transcribing_title),
        stringResource(
            R.string.home_pipeline_transcribing_detail,
            s.segmentIndex + 1,
            s.totalSegments,
            s.recognizedChars,
        ),
        if (s.totalSegments > 0) (s.segmentIndex + 1).toFloat() / s.totalSegments else null,
        false, false
    )
    is PipelineStage.Extracting -> StageDescription(
        stringResource(R.string.home_pipeline_extracting_title),
        stringResource(R.string.home_pipeline_extracting_detail, s.transcriptLength),
        null, false, false
    )
    is PipelineStage.Saving -> StageDescription(
        stringResource(R.string.home_pipeline_saving_title),
        stringResource(R.string.home_pipeline_saving_detail),
        null,
        false,
        false,
    )
    is PipelineStage.Completed -> StageDescription(
        stringResource(R.string.home_pipeline_completed_title),
        stringResource(R.string.home_pipeline_completed_detail),
        1f, false, true
    )
    is PipelineStage.Failed -> StageDescription(
        stringResource(R.string.home_pipeline_failed_title),
        stringResource(R.string.home_pipeline_failed_detail, s.stage),
        null, true, false
    )
}

@Composable
private fun liveSegmentLabel(segment: HomeViewModel.LiveTranscriptSegment): String {
    val status = when (segment.status) {
        HomeViewModel.LiveTranscriptStatus.WAITING -> stringResource(R.string.status_waiting)
        HomeViewModel.LiveTranscriptStatus.TRANSCRIBING -> stringResource(R.string.home_status_transcribing)
        HomeViewModel.LiveTranscriptStatus.TRANSCRIBED -> stringResource(R.string.home_status_transcribed)
        HomeViewModel.LiveTranscriptStatus.FAILED -> stringResource(R.string.status_failed)
    }
    return stringResource(
        R.string.home_segment_label,
        segment.sequence + 1,
        formatElapsed(segment.startMs),
        formatElapsed(segment.endMs),
        status,
    )
}

private fun formatElapsed(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val mm = ((s % 3600) / 60).toString().padStart(2, '0')
    val ss = (s % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:$mm:$ss" else "$mm:$ss"
}
