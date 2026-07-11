package app.murmurnote.android.domain.pipeline

import android.content.Context
import app.murmurnote.android.audio.AudioConverter
import app.murmurnote.android.audio.AudioFileInspector
import app.murmurnote.android.audio.AudioSplitter
import app.murmurnote.android.audio.HardCutBoundaryProbePolicy
import app.murmurnote.android.audio.NeuralVadSegmentPlanner
import app.murmurnote.android.audio.Pcm16WavStreamReader
import app.murmurnote.android.data.asr.AsrEngineProvider
import app.murmurnote.android.data.asr.AsrEngineType
import app.murmurnote.android.data.asr.CloudAsrEngine
import app.murmurnote.android.data.asr.LocalAsrEngine
import app.murmurnote.android.data.local.entity.ExtractedItem
import app.murmurnote.android.data.local.entity.ItemType
import app.murmurnote.android.data.local.entity.ProcessingStatus
import app.murmurnote.android.data.preference.AppPreferences
import app.murmurnote.android.data.local.entity.Recording
import app.murmurnote.android.data.local.entity.RecordingSource
import app.murmurnote.android.data.local.entity.TranscriptSegment
import app.murmurnote.android.data.remote.llm.LlmClient
import app.murmurnote.android.data.remote.llm.dto.ExtractionResult
import app.murmurnote.android.data.repository.RecordingRepository
import app.murmurnote.android.data.repository.SummaryRepository
import app.murmurnote.android.data.repository.TranscriptRepository
import app.murmurnote.android.domain.transcript.ModelTranscriptBoundary
import app.murmurnote.android.domain.transcript.ModelSegmentCutReason
import app.murmurnote.android.domain.transcript.ModelTranscriptSegment
import app.murmurnote.android.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 端到端处理：录音/导入文件 → 转 mono WAV → 静音切 25s → 并发转写 → 拼接 → 提取 → 入库。
 * 任何阶段失败：保留 Recording 但标记 FAILED，便于详情页"重新处理"。
 */
@Singleton
class AudioPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioConverter: AudioConverter,
    private val audioSplitter: AudioSplitter,
    private val audioInspector: AudioFileInspector,
    private val asrEngineProvider: AsrEngineProvider,
    private val llmClient: LlmClient,
    private val recordingRepository: RecordingRepository,
    private val transcriptRepository: TranscriptRepository,
    private val summaryRepository: SummaryRepository,
    private val appPreferences: AppPreferences,
    private val logger: Logger
) {

    companion object {
        const val MAX_DURATION_MS = 5L * 60 * 60 * 1000   // 单录音上限 5 小时
        const val ASR_CONCURRENCY = 3
        private val SAFE_RECORDING_ID = Regex("[A-Za-z0-9_-]{1,128}")
    }

    /**
     * 端到端处理。
     * @param existingRecordingId 非 null 时表示"重跑"已有 Recording：复用同一行；如已有转写缓存，
     *                            只重跑 AI 提取，避免重复转码/切片/ASR。
     */
    fun process(
        audioFile: File,
        source: RecordingSource,
        existingRecordingId: String? = null
    ): Flow<PipelineStage> = channelFlow {
        val now = System.currentTimeMillis()
        val tsPretty = formatPretty(now)

        // 重跑：用现有 id；否则新建
        val recordingId = existingRecordingId ?: UUID.randomUUID().toString()
        var stageName = "init"
        try {
            var recording: Recording
            var cachedSegments: List<TranscriptSegment> = emptyList()
            var finalizedTranscriptText: String? = null
            if (existingRecordingId != null) {
                val existing = recordingRepository.get(existingRecordingId)
                    ?: error("待重跑的 Recording 不存在：$existingRecordingId")
                cachedSegments = transcriptRepository.getSegments(existingRecordingId)
                finalizedTranscriptText = existing.rawTranscript?.let {
                    existing.correctedTranscript ?: it
                }
                recording = existing.copy(
                    processingStatus = ProcessingStatus.PENDING,
                    errorMessage = null
                )
                recordingRepository.setStatus(recordingId, ProcessingStatus.PENDING)
                logger.i(
                    "Pipe",
                    "reprocess id=$existingRecordingId cachedSegments=${cachedSegments.size} " +
                        "finalizedTranscript=${finalizedTranscriptText != null}"
                )
            } else {
                recording = Recording(
                id = recordingId,
                title = "录音 $tsPretty",
                originalFilePath = audioFile.absolutePath,
                durationMs = 0,
                createdAt = now,
                source = source,
                processingStatus = ProcessingStatus.PENDING,
                audioExpiresAt = now + 30L * 24 * 3600 * 1000
            )
                recordingRepository.insert(recording)
            }
            // Do not create derived files until the durable owner row exists. A failure before
            // this point must not leave an unowned work directory behind.
            val workDir = pipelineWorkDir(recordingId)
            // 标题与每个待办都要带"录音时间点"，重跑沿用原 createdAt，新录音用 now。
            val createdAtPretty = formatPretty(recording.createdAt)

            // channelFlow + 显式追踪 stageName：失败日志直接写出真实阶段。
            logger.i("Pipe", "start id=$recordingId sourceBytes=${audioFile.length()}")
            val fullText: String
            if (finalizedTranscriptText != null) {
                stageName = "reuse_final_transcript"
                fullText = checkNotNull(finalizedTranscriptText)
                logger.i(
                    "Pipe",
                    "reuse finalized corrected transcript segments=${cachedSegments.size} revision=${recording.correctionRevision}"
                )
            } else {
                stageName = "convert"
                send(PipelineStage.Converting(0f))
                recordingRepository.setStatus(recordingId, ProcessingStatus.CONVERTING)
                val monoCache = convertOrReuseMonoWav(audioFile, workDir)
                val monoWav = monoCache.value
                logger.i("Pipe", "converted outputBytes=${monoWav.length()}")

                val durationMs = audioInspector.durationMs(monoWav)
                if (durationMs > MAX_DURATION_MS) error("录音超过 5 小时限制")
                recording = recording.copy(durationMs = durationMs)
                check(recordingRepository.updateDuration(recordingId, durationMs)) {
                    "Recording disappeared while updating audio duration"
                }

                stageName = "split"
                send(PipelineStage.Splitting(0))
                recordingRepository.setStatus(recordingId, ProcessingStatus.SPLITTING)
                val sliceCache = splitOrReuseSlices(monoWav, File(workDir, "segments"))
                val slices = sliceCache.value
                logger.i("Pipe", "split → ${slices.size} segments, durationMs=$durationMs")
                send(PipelineStage.Splitting(slices.size))

                val attempt = when (
                    val selection = asrEngineProvider.snapshotAttempt(
                        vadPresetVersion = HardCutBoundaryProbePolicy.canonicalVadVersion,
                        locale = Locale.getDefault()
                    )
                ) {
                    is AsrEngineProvider.AttemptSelection.Active -> selection
                    is AsrEngineProvider.AttemptSelection.NotReady -> error(selection.reason)
                }
                val plannedSegments = slices.mapIndexed { index, slice ->
                    ModelTranscriptBoundary(
                        sequence = index,
                        startMs = slice.startMs,
                        endMs = slice.endMs,
                        cutReason = slice.cutReason?.let { ModelSegmentCutReason.valueOf(it.name) },
                        overlapBeforeMs = slice.overlapBeforeMs
                    )
                }
                cachedSegments = transcriptRepository.prepareModelAttempt(
                    recordingId = recordingId,
                    provenance = attempt.provenance,
                    plannedSegments = plannedSegments,
                    allowProvisionalReuse = monoCache.reused && sliceCache.reused
                )
                val cachedBySequence = cachedSegments
                    .associateBy { it.sequence }
                    .mapValues { (_, seg) -> seg.toTranscriptOf() }
                val missingCount = slices.indices.count { cachedBySequence[it] == null }
                if (cachedBySequence.isNotEmpty()) {
                    logger.i(
                        "Pipe",
                        "segment cache matched=${cachedBySequence.size}/${slices.size}, missing=$missingCount"
                    )
                }
                try {
                    if (missingCount > 0) {
                        // channelFlow send() is safe from the async segment workers.
                        stageName = "transcribe"
                        recordingRepository.setStatus(recordingId, ProcessingStatus.TRANSCRIBING)
                        logger.i(
                            "Pipe",
                            "asr engine=${attempt.provenance.engineType} missingSegments=$missingCount " +
                                "fingerprint=${attempt.provenance.configFingerprint.take(12)}"
                        )
                        transcribeAll(recordingId, slices, cachedBySequence, attempt) { idx, total, partial, recognizedChars ->
                            send(PipelineStage.Transcribing(idx, total, partial, recognizedChars))
                        }
                    }
                    stageName = "finalize_transcript"
                    transcriptRepository.finalizeModelTranscript(
                        recordingId = recordingId,
                        provenance = attempt.provenance,
                        expectedSequences = slices.indices.toList()
                    )
                } finally {
                    attempt.engine.release()
                }
                recording = recordingRepository.get(recordingId)
                    ?: error("Recording disappeared after transcript finalization")
                fullText = recording.correctedTranscript
                    ?: error("Corrected transcript was not persisted")
            }
            val aiExtractionEnabled = appPreferences.aiExtractionEnabled.first()
            val requestedSummaryRevision = recording.correctionRevision

            val extractionCandidate: ExtractionResult? = if (!aiExtractionEnabled) {
                logger.i("Pipe", "AI extraction disabled id=$recordingId")
                null
            } else {
                stageName = "extract"
                send(PipelineStage.Extracting(fullText.length))
                recordingRepository.setStatus(recordingId, ProcessingStatus.EXTRACTING)
                if (fullText.isBlank()) {
                    ExtractionResult("（识别为空）", emptyList())
                } else {
                    // 长转写自动走 map-reduce 分块抽取并合并摘要;短文本透传到单次 extractItems。
                    llmClient.extractItemsAuto(fullText)
                        .onFailure { e ->
                            logger.w(
                                "Pipe",
                                "optional extraction failed type=${e.javaClass.simpleName}"
                            )
                        }
                        .getOrNull()
                }
            }

            stageName = "save"
            send(PipelineStage.Saving(recordingId))
            val candidateItems = extractionCandidate?.items?.map { dto ->
                    ExtractedItem(
                        recordingId = recordingId,
                        type = dto.toItemType(),
                        // 内容里不再嵌时间——UI 用 ExtractedItem.createdAt 在右上角小字渲染。
                        content = dto.content,
                        deadline = dto.deadline?.let { parseDeadline(it) },
                        sourceTimestampMs = dto.sourceTimestampMs,
                        // 沿用 recording.createdAt：重跑时仍指向"录音时刻"而非"重新提取时刻"，
                        // 这样列表 / 待办页右上角的小字始终是录音那一刻的时间点。
                        createdAt = recording.createdAt
                    )
                }
                .orEmpty()

            // summary 现在是多条 bullet（"• 主题：...\n• 背景：..."），第一条就是录音主题。
            // 取第一条作为标题：去掉 "• " 前缀，再去掉 "主题：" 标签（中英文冒号都可），截断到 30 字。
            val titleFromSummary = extractionCandidate?.summary
                ?.lineSequence()
                ?.map { it.trim().removePrefix("•").trim() }
                ?.map {
                    it.removePrefix("主题：")
                        .removePrefix("主题:")
                        .trim()
                }
                ?.firstOrNull { it.isNotBlank() }
                ?.take(30)
            val finalTitle = if (titleFromSummary != null) {
                "$titleFromSummary · $createdAtPretty"
            } else {
                recording.title
            }
            val summarySaved = if (extractionCandidate != null) {
                summaryRepository.saveForRevision(
                    recordingId = recordingId,
                    expectedRevision = requestedSummaryRevision,
                    title = finalTitle,
                    summary = extractionCandidate.summary,
                    items = candidateItems
                )
            } else {
                false
            }
            val extractionDiscardedForRevision = extractionCandidate != null && !summarySaved
            val completionError = when {
                extractionDiscardedForRevision ->
                    "转写在总结期间发生了修改，本次总结未保存，请重新生成"
                aiExtractionEnabled && extractionCandidate == null ->
                    "AI 提取失败，转写已安全保存，可稍后重试"
                else -> null
            }
            if (!summarySaved) {
                check(summaryRepository.completeWithoutNewSummary(recordingId, completionError)) {
                    "Recording disappeared before pipeline completion"
                }
                if (extractionDiscardedForRevision) {
                    logger.w(
                        "Pipe",
                        "optional extraction discarded because transcript revision changed"
                    )
                } else {
                    logger.i("Pipe", "completed without a new summary")
                }
            }
            // Summary persistence above already commits COMPLETED. Cleanup is best-effort and
            // non-cancellable: a stale derived file must never turn a completed recording into a
            // visible processing failure.
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { recordingRepository.deleteRecordingSegments(recordingId) }
                    .onFailure { failure ->
                        logger.w(
                            "Pipe",
                            "segment metadata cleanup failed type=${failure.javaClass.simpleName}"
                        )
                    }
                runCatching { cleanupDerivedWorkDir(workDir) }
                    .onFailure { failure ->
                        logger.w(
                            "Pipe",
                            "derived work cleanup failed type=${failure.javaClass.simpleName}"
                        )
                    }
                runCatching { cleanupLivePreviewSegments(audioFile) }
                    .onFailure { failure ->
                        logger.w(
                            "Pipe",
                            "live preview cleanup failed type=${failure.javaClass.simpleName}"
                        )
                    }
            }
            logger.i(
                "Pipe",
                "completed id=$recordingId items=${if (summarySaved) candidateItems.size else 0}"
            )
            send(PipelineStage.Completed(recordingId))
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                recordingRepository.markProcessingFailedIfInProgress(
                    recordingId,
                    PipelineFailurePolicy.persistedCancellation(cancelled)
                )
            }
            throw cancelled
        } catch (t: Throwable) {
            logger.e("Pipe", "failed at $stageName", t)
            val safeFailure = PipelineFailurePolicy.persistedFailure(stageName, t)
            // DB 与 send 都包 runCatching：万一 catch 触发的根因来自 send 自己（例如下游已 cancel），
            // 这里再 throw 一次会顶替原始异常，让 runtime.log 失去真正的根因。
            runCatching {
                recordingRepository.markProcessingFailedIfInProgress(
                    recordingId,
                    safeFailure
                )
            }
            runCatching { send(PipelineStage.Failed(stageName, safeFailure)) }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun transcribeAll(
        recordingId: String,
        slices: List<AudioSplitter.Slice>,
        cachedBySequence: Map<Int, TranscriptOf>,
        attempt: AsrEngineProvider.AttemptSelection.Active,
        onProgress: suspend (Int, Int, String, Int) -> Unit
    ): List<TranscriptOf> = coroutineScope {
        val concurrency = when (attempt.provenance.engineType) {
            AsrEngineType.LOCAL_SENSE_VOICE -> attempt.localConfig?.recognizerConcurrency ?: 1
            AsrEngineType.LOCAL_QWEN3_ASR -> 1
            AsrEngineType.CLOUD_GLM -> ASR_CONCURRENCY
        }
        val sem = Semaphore(concurrency)
        val total = slices.size
        val batchStart = System.currentTimeMillis()
        val cachedResults = cachedBySequence.values.toList()
        val charCountsBySequence = IntArray(total) { index ->
            cachedBySequence[index]?.text?.length ?: 0
        }
        val charCountLock = Any()

        suspend fun emitProgress(index: Int, text: String? = null) {
            val recognizedChars = synchronized(charCountLock) {
                if (text != null) charCountsBySequence[index] = text.length
                charCountsBySequence.sum()
            }
            onProgress(index, total, text.orEmpty(), recognizedChars)
        }

        val deferreds = slices.mapIndexedNotNull { index, slice ->
            if (cachedBySequence[index] != null) return@mapIndexedNotNull null
            async(Dispatchers.IO) {
                sem.withPermit {
                    val segStart = System.currentTimeMillis()
                    val result = if (attempt.localConfig != null) {
                        val local = attempt.engine as? LocalAsrEngine
                            ?: error("Frozen local ASR attempt has a non-local engine")
                        local.transcribe(slice.file, attempt.localConfig) { _ ->
                            emitProgress(index)
                        }
                    } else {
                        val cloud = attempt.engine as? CloudAsrEngine
                            ?: error("Frozen cloud ASR attempt has a non-cloud engine")
                        val cloudConfig = attempt.cloudRequestConfig
                            ?: error("Frozen cloud ASR attempt has no request config")
                        cloud.transcribe(slice.file, cloudConfig) { _ ->
                            emitProgress(index)
                        }
                    }
                    val text = result.getOrElse { e ->
                        error("ASR 段 ${index + 1}/$total 失败：${e.message?.take(160) ?: e.javaClass.simpleName}")
                    }.text
                    val transcript = TranscriptOf(
                        index = index,
                        text = text,
                        startMs = slice.startMs,
                        endMs = slice.endMs
                    )
                    transcriptRepository.cacheModelSegment(
                        recordingId = recordingId,
                        segment = ModelTranscriptSegment(
                            rawText = transcript.text,
                            startMs = transcript.startMs,
                            endMs = transcript.endMs,
                            sequence = transcript.index,
                            cutReason = slice.cutReason?.let {
                                ModelSegmentCutReason.valueOf(it.name)
                            },
                            overlapBeforeMs = slice.overlapBeforeMs
                        ),
                        provenance = attempt.provenance
                    )
                    emitProgress(index, text)
                    logger.i(
                        "Pipe",
                        "seg ${index + 1}/$total transcribed chars=${text.length} " +
                            "persisted elapsed=${System.currentTimeMillis() - segStart}ms"
                    )
                    transcript
                }
            }
        }
        val results = (cachedResults + deferreds.awaitAll()).sortedBy { it.index }
        logger.i(
            "Pipe",
            "transcribe done total=$total cached=${cachedResults.size} " +
                "new=${results.size - cachedResults.size} chars=${results.sumOf { it.text.length }} " +
                "elapsed=${System.currentTimeMillis() - batchStart}ms"
        )
        results
    }

    private suspend fun convertOrReuseMonoWav(
        audioFile: File,
        workDir: File
    ): CacheValue<File> {
        if (Pcm16WavStreamReader.isCanonicalMono16kPcmWav(audioFile)) {
            logger.i("Pipe", "skip conversion: input already mono 16k PCM16 WAV")
            return CacheValue(audioFile, reused = true)
        }

        val metaFile = File(workDir, "mono16k.properties")
        val expectedFingerprint = audioFile.sourceFingerprint()
        val cached = readMonoCache(metaFile)
        val cachedOutput = cached?.getProperty("outputName")?.let { File(workDir, it) }
        if (
            cachedOutput != null &&
            cachedOutput.exists() &&
            cachedOutput.length() > 0 &&
            cached?.getProperty("sourcePath") == expectedFingerprint.sourcePath &&
            cached.getProperty("sourceLength") == expectedFingerprint.sourceLength &&
            cached.getProperty("sourceLastModified") == expectedFingerprint.sourceLastModified
        ) {
            logger.i("Pipe", "reuse mono wav cache bytes=${cachedOutput.length()}")
            return CacheValue(cachedOutput, reused = true)
        }

        val monoWav = audioConverter.convertToMonoWav(audioFile, workDir)
        writeMonoCache(metaFile, monoWav, expectedFingerprint)
        return CacheValue(monoWav, reused = false)
    }

    private fun readMonoCache(metaFile: File): Properties? =
        runCatching {
            if (!metaFile.exists()) return null
            Properties().apply {
                metaFile.inputStream().use { load(it) }
            }
        }.getOrNull()

    private fun writeMonoCache(metaFile: File, monoWav: File, fingerprint: SourceFingerprint) {
        runCatching {
            Properties().apply {
                setProperty("sourcePath", fingerprint.sourcePath)
                setProperty("sourceLength", fingerprint.sourceLength)
                setProperty("sourceLastModified", fingerprint.sourceLastModified)
                setProperty("outputName", monoWav.name)
            }.store(metaFile.outputStream(), "Murmurnote mono wav cache")
        }.onFailure { e ->
            logger.w("Pipe", "failed to write mono cache metadata type=${e.javaClass.simpleName}")
        }
    }

    private fun File.sourceFingerprint(): SourceFingerprint =
        SourceFingerprint(
            sourcePath = absolutePath,
            sourceLength = length().toString(),
            sourceLastModified = lastModified().toString()
        )

    private suspend fun splitOrReuseSlices(
        monoWav: File,
        outputDir: File
    ): CacheValue<List<AudioSplitter.Slice>> {
        val metaFile = File(outputDir, "segments.properties")
        val cached = SegmentSliceCache.read(metaFile, outputDir, monoWav)
        if (cached != null) {
            logger.i("Pipe", "reuse segment cache count=${cached.size}")
            return CacheValue(cached, reused = true)
        }

        val slices = audioSplitter.split(monoWav, outputDir)
        runCatching {
            SegmentSliceCache.write(metaFile, monoWav, slices)
        }.onFailure { e ->
            logger.w("Pipe", "failed to write segment cache metadata type=${e.javaClass.simpleName}")
        }
        return CacheValue(slices, reused = false)
    }

    private fun TranscriptSegment.toTranscriptOf(): TranscriptOf =
        TranscriptOf(
            index = sequence,
            text = text,
            startMs = startMs,
            endMs = endMs
        )


    private fun pipelineWorkDir(recordingId: String): File {
        require(SAFE_RECORDING_ID.matches(recordingId)) { "Recording id is not safe for a work path" }
        val externalRoot = requireNotNull(context.getExternalFilesDir(null)) {
            "External app files directory is unavailable"
        }.canonicalFile
        val pipelineRoot = File(externalRoot, "pipeline").canonicalFile.apply {
            check(isDirectory || mkdirs()) { "Unable to create the pipeline work directory" }
        }
        return File(pipelineRoot, recordingId).canonicalFile.apply {
            check(parentFile == pipelineRoot) { "Pipeline work path escaped its root" }
            check(isDirectory || mkdirs()) { "Unable to create the recording work directory" }
        }
    }

    private fun cleanupDerivedWorkDir(workDir: File) {
        runCatching {
            val canonical = workDir.canonicalFile
            check(canonical.parentFile?.name == "pipeline") {
                "Refusing to clean a non-pipeline directory"
            }
            check(!canonical.exists() || canonical.deleteRecursively()) {
                "Unable to clean the derived pipeline directory"
            }
        }.onFailure { e ->
            logger.w("Pipe", "derived work cleanup failed type=${e.javaClass.simpleName}")
        }
    }

    private fun cleanupLivePreviewSegments(audioFile: File) {
        runCatching {
            val audioParent = audioFile.canonicalFile.parentFile
                ?: error("Source audio has no parent directory")
            val previewDirectory = File(
                audioParent,
                "${audioFile.nameWithoutExtension}_segments"
            )
            if (!previewDirectory.exists()) return
            check(!Files.isSymbolicLink(previewDirectory.toPath())) {
                "Refusing to clean a symbolic-link preview directory"
            }
            val canonical = previewDirectory.canonicalFile
            check(canonical.parentFile == audioParent) {
                "Live preview directory escaped the source-audio directory"
            }
            check(canonical.deleteRecursively()) {
                "Unable to clean the live preview directory"
            }
        }.onFailure { error ->
            logger.w("Pipe", "live preview cleanup failed type=${error.javaClass.simpleName}")
        }
    }

    private fun parseDeadline(s: String): Long? = try {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s)?.time
    } catch (_: Exception) { null }

    /** 录音时间点统一格式：年月日 + 时分秒。详情页与列表页都基于这个串展示。 */
    private fun formatPretty(epochMs: Long): String =
        SimpleDateFormat("yyyy年MM月dd日 HH时mm分ss秒", Locale.US).format(Date(epochMs))

    private fun app.murmurnote.android.data.remote.llm.dto.ExtractedItemDto.toItemType(): ItemType =
        when (type.lowercase()) {
            "todo" -> ItemType.TODO
            "idea" -> ItemType.IDEA
            "note" -> ItemType.NOTE
            "decision" -> ItemType.DECISION
            else -> ItemType.NOTE
        }

    private data class TranscriptOf(
        val index: Int,
        val text: String,
        val startMs: Long,
        val endMs: Long
    )

    private data class SourceFingerprint(
        val sourcePath: String,
        val sourceLength: String,
        val sourceLastModified: String
    )

    private data class CacheValue<T>(
        val value: T,
        val reused: Boolean
    )

}
