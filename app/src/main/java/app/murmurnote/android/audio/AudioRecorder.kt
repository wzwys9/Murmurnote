package app.murmurnote.android.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import app.murmurnote.android.util.Logger
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10

@Singleton
class AudioRecorder @Inject constructor(
    private val sileroVadDetector: SileroVadDetector,
    private val logger: Logger
) {
    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        private const val CHANNEL_COUNT = 1
        private const val BITS_PER_SAMPLE = 16
        private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
        private const val BYTE_RATE = SAMPLE_RATE_HZ * CHANNEL_COUNT * BYTES_PER_SAMPLE
        private const val CAPTURE_READ_BUFFER_BYTES = BYTE_RATE / 5
        private const val WAV_HEADER_BYTES = 44
        private const val MAX_UINT32 = 0xffff_ffffL
        private const val LIVE_VAD_QUEUE_CAPACITY = 32
        private const val LIVE_VAD_STOP_TIMEOUT_MS = 10_000L
        private const val MAX_PENDING_PREVIEW_SEGMENTS = 8

        /**
         * The canonical WAV write always happens before the best-effort preview handoff. Preview
         * rejection or failure can therefore disable only live UI and never drop source audio.
         */
        internal fun dispatchCapturedPcm(
            buffer: ByteArray,
            length: Int,
            isSessionActive: () -> Boolean = { true },
            writeLossless: (ByteArray, Int) -> Unit,
            offerLivePreview: (ByteArray, Int) -> Boolean,
            onLivePreviewFailure: (Throwable) -> Unit = {}
        ): Boolean {
            if (!isSessionActive()) return false
            writeLossless(buffer, length)
            return try {
                offerLivePreview(buffer, length)
            } catch (failure: Throwable) {
                onLivePreviewFailure(failure)
                false
            }
        }
    }

    data class RecordedSegment(
        val sequence: Int,
        val file: File,
        val startMs: Long,
        val endMs: Long,
        val cutReason: NeuralVadSegmentPlanner.CutReason,
        val overlapBeforeMs: Long,
        val vadPresetVersion: String
    )

    private val lock = Any()
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var startedAtMs: Long = 0
    private var pausedAccumulatedMs: Long = 0
    private var pauseStartMs: Long = 0
    private var currentFile: File? = null
    private var segmentDir: File? = null
    private var finalWriter: WavWriter? = null
    private var liveVadWorker: NonBlockingLiveVadWorker? = null
    private var liveVadToken: Any? = null
    private var captureToken: Any? = null
    private var lastLiveVadSnapshot = LiveVadWorkerSnapshot(LiveVadWorkerState.NEW)
    private val segments = mutableListOf<RecordedSegment>()
    @Volatile private var lastAmplitude: Int = 0

    @Volatile var isRecording: Boolean = false
        private set
    @Volatile var isPaused: Boolean = false
        private set

    @SuppressLint("MissingPermission")
    fun start(target: File): File {
        synchronized(lock) {
            check(!isRecording && currentFile == null && recordingThread == null) {
                "Recorder is already active or still stopping"
            }
        }
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuffer > 0) { "AudioRecord min buffer unavailable: $minBuffer" }
        val bufferSize = maxOf(minBuffer, CAPTURE_READ_BUFFER_BYTES)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("AudioRecord 初始化失败")
        }

        val previewDirectory = File(target.parentFile, "${target.nameWithoutExtension}_segments")
        if (previewDirectory.exists()) {
            check(previewDirectory.deleteRecursively()) { "无法清理旧的实时预览目录" }
        }
        check(previewDirectory.mkdirs()) { "无法创建实时预览目录" }
        val liveToken = Any()
        val worker = NonBlockingLiveVadWorker(
            queueCapacity = LIVE_VAD_QUEUE_CAPACITY,
            processorFactory = {
                LiveNeuralVadPcmProcessor(
                    session = sileroVadDetector.openStreamingSession(),
                    outputDirectory = previewDirectory,
                    onSegment = { segment -> acceptLiveVadSegment(liveToken, segment) }
                )
            }
        )
        synchronized(lock) {
            currentFile = target
            segmentDir = previewDirectory
            finalWriter = WavWriter(target).also { it.open() }
            liveVadWorker = worker
            liveVadToken = liveToken
            captureToken = liveToken
            lastLiveVadSnapshot = LiveVadWorkerSnapshot(LiveVadWorkerState.STARTING)
            segments.clear()
        }
        try {
            worker.start()
            record.startRecording()
        } catch (t: Throwable) {
            cleanupAfterFailedStart(record, target, previewDirectory, worker)
            logger.e("Rec", "AudioRecord.startRecording failed", t)
            throw t
        }

        audioRecord = record
        startedAtMs = SystemClock.elapsedRealtime()
        pausedAccumulatedMs = 0L
        pauseStartMs = 0L
        lastAmplitude = 0
        isRecording = true
        isPaused = false

        recordingThread = Thread(
            {
                recordLoop(
                    record = record,
                    bufferSize = CAPTURE_READ_BUFFER_BYTES,
                    token = liveToken,
                    writer = checkNotNull(finalWriter),
                    worker = worker
                )
            },
            "MurmurnoteAudioRecorder"
        ).also { it.start() }
        logger.i(
            "Rec",
            "start lossless wav capture",
            fields = mapOf(
                "sampleRateHz" to SAMPLE_RATE_HZ,
                "vadPreset" to NeuralVadSegmentPlanner.PRESET.version,
            ),
        )
        return target
    }

    fun pause() {
        if (!isRecording || isPaused) return
        isPaused = true
        pauseStartMs = SystemClock.elapsedRealtime()
    }

    fun resume() {
        if (!isRecording || !isPaused) return
        pausedAccumulatedMs += SystemClock.elapsedRealtime() - pauseStartMs
        isPaused = false
    }

    fun stop(): File? {
        if (!isRecording) return null
        isRecording = false
        isPaused = false
        runCatching { audioRecord?.stop() }
        val captureThread = recordingThread
        captureThread?.join(2_000)
        runCatching { audioRecord?.release() }
        if (captureThread?.isAlive == true) {
            captureThread.interrupt()
            captureThread.join(2_000)
        }
        val worker = synchronized(lock) { liveVadWorker }
        val liveSnapshot = finishLiveVad(worker)

        val previewSegmentCount: Int
        val file = synchronized(lock) {
            finalWriter?.close()
            val file = currentFile
            previewSegmentCount = segments.size
            lastLiveVadSnapshot = liveSnapshot
            clearStateLocked()
            file
        }
        logger.i(
            "Rec",
            "stop lossless wav",
            fields = mapOf(
                "bytes" to (file?.length() ?: 0L),
                "previewSegments" to previewSegmentCount,
                "liveVadState" to liveSnapshot.state.name,
                "droppedPreviewChunks" to liveSnapshot.droppedChunkCount,
            )
        )
        return file
    }

    fun cancel() {
        val hadCancelledFile = currentFile != null
        isRecording = false
        isPaused = false
        runCatching { audioRecord?.stop() }
        val captureThread = recordingThread
        captureThread?.join(2_000)
        runCatching { audioRecord?.release() }
        if (captureThread?.isAlive == true) {
            captureThread.interrupt()
            captureThread.join(2_000)
        }
        val worker = synchronized(lock) { liveVadWorker }
        worker?.abort()
        val workerStopped = worker?.awaitStopped(LIVE_VAD_STOP_TIMEOUT_MS) ?: true
        synchronized(lock) {
            runCatching { finalWriter?.close() }
            currentFile?.delete()
            val directory = segmentDir
            if (workerStopped) directory?.deleteRecursively()
            else if (directory != null) {
                deletePreviewDirectoryWhenWorkerStops(checkNotNull(worker), directory)
            }
            lastLiveVadSnapshot = worker?.snapshot()
                ?: LiveVadWorkerSnapshot(LiveVadWorkerState.ABORTED)
            clearStateLocked()
        }
        if (hadCancelledFile) logger.d("Rec", "low-level cancel cleared recording files")
    }

    fun elapsedMs(): Long {
        if (!isRecording) return 0
        val pausedExtra = if (isPaused) SystemClock.elapsedRealtime() - pauseStartMs else 0
        return SystemClock.elapsedRealtime() - startedAtMs - pausedAccumulatedMs - pausedExtra
    }

    fun amplitudeDb(): Int = lastAmplitude

    internal fun recordedSegments(): List<RecordedSegment> = synchronized(lock) {
        segments.toList()
    }

    internal fun discardRecordedSegment(sequence: Int) {
        val file = synchronized(lock) {
            val segment = segments.firstOrNull { it.sequence == sequence } ?: return
            segments.remove(segment)
            segment.file
        }
        runCatching { file.delete() }
    }

    internal fun liveVadSnapshot(): LiveVadWorkerSnapshot {
        val worker = synchronized(lock) { liveVadWorker }
        return worker?.snapshot() ?: synchronized(lock) { lastLiveVadSnapshot }
    }

    private fun recordLoop(
        record: AudioRecord,
        bufferSize: Int,
        token: Any,
        writer: WavWriter,
        worker: NonBlockingLiveVadWorker
    ) {
        val buffer = ByteArray(bufferSize - (bufferSize % 2))
        while (isCaptureSessionActive(token)) {
            val read = record.read(buffer, 0, buffer.size)
            if (read > 0) {
                if (!isCaptureSessionActive(token)) break
                val evenRead = read - (read % BYTES_PER_SAMPLE)
                if (evenRead <= 0) continue
                updateAmplitude(buffer, evenRead)
                if (!isPaused) {
                    dispatchCapturedPcm(
                        buffer = buffer,
                        length = evenRead,
                        isSessionActive = { isCaptureSessionActive(token) },
                        writeLossless = { bytes, length ->
                            writer.write(bytes, length)
                        },
                        offerLivePreview = { bytes, length ->
                            worker.tryOffer(bytes, length)
                        },
                        onLivePreviewFailure = { failure ->
                            worker.abort()
                            logger.w(
                                "Rec",
                                "live VAD handoff failed type=${failure.javaClass.simpleName}"
                            )
                        }
                    )
                }
            } else if (read < 0 && isCaptureSessionActive(token)) {
                logger.w("Rec", "AudioRecord.read returned $read")
            }
        }
    }

    private fun clearStateLocked() {
        audioRecord = null
        recordingThread = null
        currentFile = null
        segmentDir = null
        finalWriter = null
        liveVadWorker = null
        liveVadToken = null
        captureToken = null
        segments.clear()
        lastAmplitude = 0
    }

    private fun cleanupAfterFailedStart(
        record: AudioRecord,
        target: File,
        previewDirectory: File,
        worker: NonBlockingLiveVadWorker
    ) {
        runCatching { record.release() }
        worker.abort()
        val workerStopped = worker.awaitStopped(LIVE_VAD_STOP_TIMEOUT_MS)
        synchronized(lock) {
            runCatching { finalWriter?.close() }
            lastLiveVadSnapshot = worker.snapshot()
            clearStateLocked()
        }
        target.delete()
        if (workerStopped) previewDirectory.deleteRecursively()
        else deletePreviewDirectoryWhenWorkerStops(worker, previewDirectory)
    }

    private fun acceptLiveVadSegment(token: Any, segment: LiveVadAudioSegment) {
        var overflowWorker: NonBlockingLiveVadWorker? = null
        synchronized(lock) {
            if (liveVadToken !== token) return
            if (segments.size >= MAX_PENDING_PREVIEW_SEGMENTS) {
                overflowWorker = liveVadWorker
                return@synchronized
            }
            segments += RecordedSegment(
                sequence = segment.sequence,
                file = segment.file,
                startMs = segment.startMs,
                endMs = segment.endMs,
                cutReason = segment.cutReason,
                overlapBeforeMs = segment.overlapBeforeMs,
                vadPresetVersion = segment.vadPresetVersion
            )
        }
        if (overflowWorker != null) {
            runCatching { segment.file.delete() }
            overflowWorker?.disableForBackpressure()
        }
    }

    private fun finishLiveVad(worker: NonBlockingLiveVadWorker?): LiveVadWorkerSnapshot {
        if (worker == null) return LiveVadWorkerSnapshot(LiveVadWorkerState.ABORTED)
        worker.finish()
        if (!worker.awaitStopped(LIVE_VAD_STOP_TIMEOUT_MS)) {
            worker.abort()
            worker.awaitStopped(LIVE_VAD_STOP_TIMEOUT_MS)
        }
        return worker.snapshot()
    }

    private fun isCaptureSessionActive(token: Any): Boolean = synchronized(lock) {
        captureToken === token && isRecording
    }

    private fun deletePreviewDirectoryWhenWorkerStops(
        worker: NonBlockingLiveVadWorker,
        directory: File
    ) {
        Thread(
            {
                worker.awaitStopped(timeoutMs = 0L)
                runCatching { directory.deleteRecursively() }
            },
            "MurmurnoteLiveVadCleanup"
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun updateAmplitude(buffer: ByteArray, read: Int) {
        var peak = 0
        var i = 0
        while (i + 1 < read) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xff)).toShort()
            val abs = kotlin.math.abs(sample.toInt())
            if (abs > peak) peak = abs
            i += 2
        }
        lastAmplitude = if (peak <= 0) 0 else (20 * log10(peak.toDouble())).toInt().coerceIn(0, 100)
    }

    private class WavWriter(val file: File) {
        private var raf: RandomAccessFile? = null
        var dataBytes: Long = 0L
            private set

        fun open() {
            file.parentFile?.mkdirs()
            raf = RandomAccessFile(file, "rw").also {
                it.setLength(0L)
                it.write(ByteArray(WAV_HEADER_BYTES))
            }
        }

        fun write(buffer: ByteArray, length: Int) {
            raf?.write(buffer, 0, length)
            dataBytes += length
        }

        fun close() {
            val handle = raf ?: return
            raf = null
            writeHeader(handle, dataBytes)
            handle.close()
        }

        private fun writeHeader(handle: RandomAccessFile, dataSize: Long) {
            handle.seek(0L)
            handle.writeBytes("RIFF")
            handle.writeIntLe((36L + dataSize).coerceAtMost(MAX_UINT32).toInt())
            handle.writeBytes("WAVE")
            handle.writeBytes("fmt ")
            handle.writeIntLe(16)
            handle.writeShortLe(1)
            handle.writeShortLe(CHANNEL_COUNT)
            handle.writeIntLe(SAMPLE_RATE_HZ)
            handle.writeIntLe(BYTE_RATE)
            handle.writeShortLe(CHANNEL_COUNT * BYTES_PER_SAMPLE)
            handle.writeShortLe(BITS_PER_SAMPLE)
            handle.writeBytes("data")
            handle.writeIntLe(dataSize.coerceAtMost(MAX_UINT32).toInt())
        }

        private fun RandomAccessFile.writeIntLe(value: Int) {
            write(value and 0xff)
            write((value shr 8) and 0xff)
            write((value shr 16) and 0xff)
            write((value shr 24) and 0xff)
        }

        private fun RandomAccessFile.writeShortLe(value: Int) {
            write(value and 0xff)
            write((value shr 8) and 0xff)
        }
    }
}
