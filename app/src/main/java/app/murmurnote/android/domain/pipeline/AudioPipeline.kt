package app.murmurnote.android.domain.pipeline

import android.content.Context
import app.murmurnote.android.audio.AudioConverter
import app.murmurnote.android.audio.AudioFileInspector
import app.murmurnote.android.audio.AudioSplitter
import app.murmurnote.android.data.asr.AsrEngine
import app.murmurnote.android.data.asr.AsrEngineProvider
import app.murmurnote.android.data.local.entity.ExtractedItem
import app.murmurnote.android.data.local.entity.ItemType
import app.murmurnote.android.data.local.entity.ProcessingStatus
import app.murmurnote.android.data.local.entity.Recording
import app.murmurnote.android.data.local.entity.RecordingSource
import app.murmurnote.android.data.local.entity.TranscriptSegment
import app.murmurnote.android.data.remote.ollama.OllamaClient
import app.murmurnote.android.data.remote.ollama.dto.ExtractionResult
import app.murmurnote.android.data.repository.ItemRepository
import app.murmurnote.android.data.repository.RecordingRepository
import app.murmurnote.android.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    private val llmClient: OllamaClient,
    private val recordingRepository: RecordingRepository,
    private val itemRepository: ItemRepository,
    private val logger: Logger
) {

    companion object {
        const val MAX_DURATION_MS = 30L * 60 * 1000   // 单录音上限 30 分钟
        const val ASR_CONCURRENCY = 3
    }

    /**
     * 端到端处理。
     * @param existingRecordingId 非 null 时表示"重跑"已有 Recording：复用同一行，清空旧的
     *                            transcript_segments / extracted_items / summary，避免新增重复行。
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
        val workDir = File(context.getExternalFilesDir(null), "pipeline/$recordingId").apply { mkdirs() }

        var recording: Recording
        if (existingRecordingId != null) {
            // 复用现有行：把旧的 transcript / items 清掉，重置状态与摘要，避免脏数据残留。
            // 通过 RecordingRepository 暴露的方法操作，DAO 这层不直接触达。
            val existing = recordingRepository.get(existingRecordingId)
                ?: error("待重跑的 Recording 不存在：$existingRecordingId")
            recordingRepository.deleteSegments(existingRecordingId)
            itemRepository.deleteForRecording(existingRecordingId)
            recording = existing.copy(
                processingStatus = ProcessingStatus.PENDING,
                errorMessage = null,
                summary = null,
                rawTranscript = null
            )
            recordingRepository.update(recording)
            logger.i("Pipe", "reprocess id=$existingRecordingId — cleared old segments/items")
        } else {
            recording = Recording(
                id = recordingId,
                title = "录音 $tsPretty",
                originalFilePath = audioFile.absolutePath,
                durationMs = 0,
                createdAt = now,
                source = source,
                processingStatus = ProcessingStatus.PENDING,
                expirationDate = now + 30L * 24 * 3600 * 1000
            )
            recordingRepository.insert(recording)
        }
        // 标题与每个待办都要带"录音时间点"，重跑沿用原 createdAt，新录音用 now。
        val createdAtPretty = formatPretty(recording.createdAt)

        // channelFlow + 显式追踪 stageName：失败日志直接写出真实阶段，
        // 不再依赖 Throwable.stackTrace[0].methodName 这种"看运气"的反射。
        var stageName = "init"
        try {
            logger.i("Pipe", "start id=$recordingId src=${audioFile.absolutePath} size=${audioFile.length()}")
            stageName = "convert"
            send(PipelineStage.Converting(0f))
            recordingRepository.setStatus(recordingId, ProcessingStatus.CONVERTING)
            val monoWav = audioConverter.convertToMonoWav(audioFile, workDir)
            logger.i("Pipe", "converted → ${monoWav.name} size=${monoWav.length()}")

            val durationMs = audioInspector.durationMs(monoWav)
            if (durationMs > MAX_DURATION_MS) error("录音超过 30 分钟限制")
            recording = recording.copy(durationMs = durationMs)
            recordingRepository.update(recording)

            stageName = "split"
            send(PipelineStage.Splitting(0))
            recordingRepository.setStatus(recordingId, ProcessingStatus.SPLITTING)
            val slices = audioSplitter.split(monoWav, File(workDir, "segments"))
            logger.i("Pipe", "split → ${slices.size} segments, durationMs=$durationMs")
            send(PipelineStage.Splitting(slices.size))

            // channelFlow 的 send() 是线程安全的，所以 transcribeAll 内部 async 子协程
            // 经由 onProgress 回调跨协程调用 send() 不会再触发 Flow invariant 违例。
            stageName = "transcribe"
            recordingRepository.setStatus(recordingId, ProcessingStatus.TRANSCRIBING)
            // 选 ASR 引擎（云端 / 本地）。NotReady 直接抛友好错误，由 catch 走 Failed 分支：
            // 用户在详情页看到 errorMessage，就知道要回设置页修。
            val engine: AsrEngine = when (val sel = asrEngineProvider.current()) {
                is AsrEngineProvider.Selection.Active -> sel.engine
                is AsrEngineProvider.Selection.NotReady -> error(sel.reason)
            }
            logger.i("Pipe", "asr engine = ${engine.engineType}")
            val transcripts = try {
                transcribeAll(slices, engine) { idx, total, partial ->
                    send(PipelineStage.Transcribing(idx, total, partial))
                }
            } finally {
                // 本地引擎释放 OfflineRecognizer 的 200MB+ 内存；云端是 no-op。
                runCatching { engine.release() }
            }
            val fullText = transcripts.joinToString("\n") { it.text }

            // 入 transcript_segments
            val transcriptEntities = transcripts.mapIndexed { idx, t ->
                TranscriptSegment(
                    recordingId = recordingId,
                    text = t.text,
                    startMs = t.startMs,
                    endMs = t.endMs,
                    sequence = idx
                )
            }
            recordingRepository.insertSegments(transcriptEntities)

            stageName = "extract"
            send(PipelineStage.Extracting(fullText.length))
            recordingRepository.setStatus(recordingId, ProcessingStatus.EXTRACTING)
            val extraction: ExtractionResult = if (fullText.isBlank()) {
                ExtractionResult("（识别为空）", emptyList())
            } else {
                // 长转写自动走 map-reduce 分块抽取并合并摘要;短文本透传到单次 extractItems。
                llmClient.extractItemsAuto(fullText).getOrElse { e ->
                    // 提取失败不致命：保留转写
                    ExtractionResult("（提取失败：${e.message?.take(40)}）", emptyList())
                }
            }

            stageName = "save"
            send(PipelineStage.Saving(recordingId))
            val items = extraction.items.map { dto ->
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
            itemRepository.insertAll(items)

            // summary 现在是多条 bullet（"• 主题：...\n• 背景：..."），第一条就是录音主题。
            // 取第一条作为标题：去掉 "• " 前缀，再去掉 "主题：" 标签（中英文冒号都可），截断到 30 字。
            val titleFromSummary = extraction.summary
                .lineSequence()
                .map { it.trim().removePrefix("•").trim() }
                .map {
                    it.removePrefix("主题：")
                        .removePrefix("主题:")
                        .trim()
                }
                .firstOrNull { it.isNotBlank() }
                ?.take(30)
            val finalTitle = if (titleFromSummary != null) {
                "$titleFromSummary · $createdAtPretty"
            } else {
                "录音 $createdAtPretty"
            }
            recording = recording.copy(
                title = finalTitle,
                summary = extraction.summary,
                rawTranscript = fullText,
                processingStatus = ProcessingStatus.COMPLETED
            )
            recordingRepository.update(recording)
            logger.i("Pipe", "completed id=$recordingId items=${items.size}")
            send(PipelineStage.Completed(recordingId))
        } catch (t: Throwable) {
            logger.e("Pipe", "failed at $stageName", t)
            // DB 与 send 都包 runCatching：万一 catch 触发的根因来自 send 自己（例如下游已 cancel），
            // 这里再 throw 一次会顶替原始异常，让 runtime.log 失去真正的根因。
            runCatching { recordingRepository.setStatus(recordingId, ProcessingStatus.FAILED, t.message) }
            runCatching { send(PipelineStage.Failed(stageName, t.message ?: t.javaClass.simpleName)) }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun transcribeAll(
        slices: List<AudioSplitter.Slice>,
        engine: AsrEngine,
        onProgress: suspend (Int, Int, String) -> Unit
    ): List<TranscriptOf> = coroutineScope {
        // 本地引擎单实例 ~200MB 内存，并行解码会 OOM；云端 GLM 仍按原 ASR_CONCURRENCY 跑。
        val concurrency = when (engine.engineType) {
            app.murmurnote.android.data.asr.AsrEngineType.LOCAL_FIRE_RED_ASR -> 1
            else -> ASR_CONCURRENCY
        }
        val sem = Semaphore(concurrency)
        val total = slices.size
        val batchStart = System.currentTimeMillis()
        val deferreds = slices.mapIndexed { index, slice ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val segStart = System.currentTimeMillis()
                    val result = engine.transcribe(slice.file) { _ ->
                        // 段内细粒度进度只对单段实时 UI 有用，Pipeline 这层按段汇总即可，
                        // 段中间继续 emit 一次 partial="" 让 PipelineStage.Transcribing 的"已识别 N 段"刷新。
                        onProgress(index, total, "")
                    }
                    val text = result.getOrElse { e ->
                        error("ASR 段 ${index + 1}/$total 失败：${e.message?.take(160) ?: e.javaClass.simpleName}")
                    }.text
                    onProgress(index, total, text)
                    logger.i(
                        "Pipe",
                        "seg ${index + 1}/$total transcribed chars=${text.length} elapsed=${System.currentTimeMillis() - segStart}ms"
                    )
                    TranscriptOf(
                        index = index,
                        text = text,
                        startMs = slice.startMs,
                        endMs = slice.endMs
                    )
                }
            }
        }
        val results = deferreds.awaitAll().sortedBy { it.index }
        logger.i(
            "Pipe",
            "transcribe done total=$total chars=${results.sumOf { it.text.length }} elapsed=${System.currentTimeMillis() - batchStart}ms"
        )
        results
    }

    private fun parseDeadline(s: String): Long? = try {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(s)?.time
    } catch (_: Exception) { null }

    /** 录音时间点统一格式：年月日 + 时分秒。详情页与列表页都基于这个串展示。 */
    private fun formatPretty(epochMs: Long): String =
        SimpleDateFormat("yyyy年MM月dd日 HH时mm分ss秒", Locale.US).format(Date(epochMs))

    private fun app.murmurnote.android.data.remote.ollama.dto.ExtractedItemDto.toItemType(): ItemType =
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
}
