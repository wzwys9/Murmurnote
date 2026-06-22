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
    private val logger: Logger
) {
    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val ROLLING_SEGMENT_MS = 15_000L
        private const val LIVE_PAUSE_SEGMENT_MS = 1_800L
        private const val LIVE_HARD_CUT_OVERLAP_MS = 600L
        private const val MIN_LIVE_SEGMENT_MS = 4_000L
        private const val MIN_LIVE_SPEECH_MS = 1_000L
        private const val SPEECH_RMS_DBFS_THRESHOLD = -55.0
        private const val CHANNEL_COUNT = 1
        private const val BITS_PER_SAMPLE = 16
        private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
        private const val BYTE_RATE = SAMPLE_RATE_HZ * CHANNEL_COUNT * BYTES_PER_SAMPLE
        private const val WAV_HEADER_BYTES = 44
        private const val MAX_UINT32 = 0xffff_ffffL
        private val SEGMENT_PCM_BYTES = msToPcmBytes(ROLLING_SEGMENT_MS)
        private val HARD_CUT_OVERLAP_BYTES = msToPcmBytes(LIVE_HARD_CUT_OVERLAP_MS).toInt()
        private val PAUSE_PCM_BYTES = msToPcmBytes(LIVE_PAUSE_SEGMENT_MS)
        private val MIN_SEGMENT_PCM_BYTES = msToPcmBytes(MIN_LIVE_SEGMENT_MS)
        private val MIN_SPEECH_PCM_BYTES = msToPcmBytes(MIN_LIVE_SPEECH_MS)

        private fun msToPcmBytes(ms: Long): Long =
            ((ms * BYTE_RATE) / 1000L).coerceAtLeast(0L)
    }

    data class RecordedSegment(
        val sequence: Int,
        val file: File,
        val startMs: Long,
        val endMs: Long
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
    private var segmentWriter: WavWriter? = null
    private val segments = mutableListOf<RecordedSegment>()
    private var currentSegmentSequence: Int = 0
    private var currentSegmentStartPcmBytes: Long = 0L
    private var currentSegmentSpeechPcmBytes: Long = 0L
    private var currentSegmentTrailingSilencePcmBytes: Long = 0L
    private var totalPcmBytes: Long = 0L
    private val hardCutOverlapBuffer = PcmOverlapBuffer(HARD_CUT_OVERLAP_BYTES)
    @Volatile private var lastAmplitude: Int = 0

    @Volatile var isRecording: Boolean = false
        private set
    @Volatile var isPaused: Boolean = false
        private set

    @SuppressLint("MissingPermission")
    fun start(target: File): File {
        check(!isRecording) { "Recorder already started" }
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuffer > 0) { "AudioRecord min buffer unavailable: $minBuffer" }
        val bufferSize = maxOf(minBuffer, BYTE_RATE / 5)

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

        val segmentsDirectory = File(target.parentFile, "${target.nameWithoutExtension}_segments")
        if (segmentsDirectory.exists()) segmentsDirectory.deleteRecursively()
        segmentsDirectory.mkdirs()

        synchronized(lock) {
            currentFile = target
            segmentDir = segmentsDirectory
            finalWriter = WavWriter(target).also { it.open() }
            segments.clear()
            hardCutOverlapBuffer.clear()
            totalPcmBytes = 0L
            currentSegmentSequence = 0
            currentSegmentStartPcmBytes = 0L
            openSegmentLocked()
        }

        try {
            record.startRecording()
        } catch (t: Throwable) {
            cleanupAfterFailedStart(record, target, segmentsDirectory)
            logger.e("Rec", "AudioRecord.startRecording failed for ${target.name}", t)
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
            { recordLoop(record, bufferSize) },
            "MurmurnoteAudioRecorder"
        ).also { it.start() }
        logger.i(
            "Rec",
            "start wav → ${target.absolutePath}; rolling=${ROLLING_SEGMENT_MS}ms " +
                "pauseCut=${LIVE_PAUSE_SEGMENT_MS}ms overlap=${LIVE_HARD_CUT_OVERLAP_MS}ms"
        )
        return target
    }

    fun pause() {
        if (!isRecording || isPaused) return
        isPaused = true
        pauseStartMs = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (finishSegmentLocked(discardIfNoSpeech = false)) {
                currentSegmentSequence += 1
            }
            currentSegmentStartPcmBytes = totalPcmBytes
        }
    }

    fun resume() {
        if (!isRecording || !isPaused) return
        synchronized(lock) {
            currentSegmentStartPcmBytes = totalPcmBytes
            if (segmentWriter == null) openSegmentLocked()
        }
        pausedAccumulatedMs += SystemClock.elapsedRealtime() - pauseStartMs
        isPaused = false
    }

    fun stop(): File? {
        if (!isRecording) return null
        isRecording = false
        isPaused = false
        runCatching { audioRecord?.stop() }
        recordingThread?.join(2_000)
        runCatching { audioRecord?.release() }

        val result = synchronized(lock) {
            finishSegmentLocked(discardIfNoSpeech = false)
            finalWriter?.close()
            writeSegmentManifestLocked()
            val segmentCount = segments.size
            val file = currentFile
            clearStateLocked()
            file to segmentCount
        }
        logger.i(
            "Rec",
            "stop wav → ${result.first?.absolutePath} segments=${result.second} size=${result.first?.length() ?: 0}"
        )
        return result.first
    }

    fun cancel() {
        val cancelledFile = currentFile?.absolutePath
        isRecording = false
        isPaused = false
        runCatching { audioRecord?.stop() }
        recordingThread?.join(2_000)
        runCatching { audioRecord?.release() }
        synchronized(lock) {
            runCatching { segmentWriter?.close() }
            runCatching { finalWriter?.close() }
            currentFile?.delete()
            segmentDir?.deleteRecursively()
            clearStateLocked()
        }
        if (cancelledFile != null) logger.d("Rec", "low-level cancel cleared $cancelledFile")
    }

    fun elapsedMs(): Long {
        if (!isRecording) return 0
        val pausedExtra = if (isPaused) SystemClock.elapsedRealtime() - pauseStartMs else 0
        return SystemClock.elapsedRealtime() - startedAtMs - pausedAccumulatedMs - pausedExtra
    }

    fun amplitudeDb(): Int = lastAmplitude

    fun recordedSegments(): List<RecordedSegment> = synchronized(lock) { segments.toList() }

    private fun recordLoop(record: AudioRecord, bufferSize: Int) {
        val buffer = ByteArray(bufferSize - (bufferSize % 2))
        while (isRecording) {
            val read = record.read(buffer, 0, buffer.size)
            if (read > 0) {
                val evenRead = read - (read % BYTES_PER_SAMPLE)
                if (evenRead <= 0) continue
                updateAmplitude(buffer, evenRead)
                if (!isPaused) {
                    synchronized(lock) {
                        finalWriter?.write(buffer, evenRead)
                        segmentWriter?.write(buffer, evenRead)
                        totalPcmBytes += evenRead
                        hardCutOverlapBuffer.write(buffer, evenRead)
                        updateSegmentSpeechStateLocked(buffer, evenRead)
                        val segmentBytes = totalPcmBytes - currentSegmentStartPcmBytes
                        val reachedMaxSegment = segmentBytes >= SEGMENT_PCM_BYTES
                        val reachedNaturalPause =
                            currentSegmentSpeechPcmBytes >= MIN_SPEECH_PCM_BYTES &&
                                segmentBytes >= MIN_SEGMENT_PCM_BYTES &&
                                currentSegmentTrailingSilencePcmBytes >= PAUSE_PCM_BYTES
                        if (reachedMaxSegment || reachedNaturalPause) {
                            val forcedHardCut = reachedMaxSegment && !reachedNaturalPause
                            if (finishSegmentLocked(discardIfNoSpeech = true)) {
                                currentSegmentSequence += 1
                            }
                            val overlap = if (forcedHardCut) hardCutOverlapBuffer.snapshot() else ByteArray(0)
                            currentSegmentStartPcmBytes = totalPcmBytes - overlap.size.toLong()
                            openSegmentLocked(overlap)
                        }
                    }
                }
            } else if (read < 0 && isRecording) {
                logger.w("Rec", "AudioRecord.read returned $read")
            }
        }
    }

    private fun openSegmentLocked(initialPcm: ByteArray = ByteArray(0)) {
        val dir = segmentDir ?: return
        val file = File(dir, "segment_${currentSegmentSequence.toString().padStart(4, '0')}.wav")
        if (file.exists()) file.delete()
        segmentWriter = WavWriter(file).also { it.open() }
        currentSegmentSpeechPcmBytes = 0L
        currentSegmentTrailingSilencePcmBytes = 0L
        if (initialPcm.isNotEmpty()) {
            segmentWriter?.write(initialPcm, initialPcm.size)
            updateSegmentSpeechStateLocked(initialPcm, initialPcm.size)
        }
    }

    private fun finishSegmentLocked(discardIfNoSpeech: Boolean): Boolean {
        val writer = segmentWriter ?: return false
        segmentWriter = null
        writer.close()
        if (writer.dataBytes <= 0L || (discardIfNoSpeech && currentSegmentSpeechPcmBytes <= 0L)) {
            writer.file.delete()
            currentSegmentSpeechPcmBytes = 0L
            currentSegmentTrailingSilencePcmBytes = 0L
            return false
        }
        segments += RecordedSegment(
            sequence = currentSegmentSequence,
            file = writer.file,
            startMs = pcmBytesToMs(currentSegmentStartPcmBytes),
            endMs = pcmBytesToMs(currentSegmentStartPcmBytes + writer.dataBytes)
        )
        return true
    }

    private fun writeSegmentManifestLocked() {
        if (segments.isEmpty()) return
        val final = currentFile ?: return
        runCatching {
            RecordingSegmentManifest.write(
                finalFile = final,
                sampleRateHz = SAMPLE_RATE_HZ,
                bitsPerSample = BITS_PER_SAMPLE,
                segmentTargetMs = ROLLING_SEGMENT_MS,
                segments = segments.map {
                    RecordingSegmentManifest.Segment(
                        sequence = it.sequence,
                        file = it.file,
                        startMs = it.startMs,
                        endMs = it.endMs
                    )
                }
            )
        }.onFailure { e ->
            logger.w("Rec", "failed to write rolling segment manifest: ${e.message}")
        }
    }

    private fun clearStateLocked() {
        audioRecord = null
        recordingThread = null
        currentFile = null
        segmentDir = null
        finalWriter = null
        segmentWriter = null
        segments.clear()
        hardCutOverlapBuffer.clear()
        totalPcmBytes = 0L
        currentSegmentSequence = 0
        currentSegmentStartPcmBytes = 0L
        currentSegmentSpeechPcmBytes = 0L
        currentSegmentTrailingSilencePcmBytes = 0L
        lastAmplitude = 0
    }

    private fun cleanupAfterFailedStart(record: AudioRecord, target: File, segmentsDirectory: File) {
        runCatching { record.release() }
        synchronized(lock) {
            runCatching { segmentWriter?.close() }
            runCatching { finalWriter?.close() }
            clearStateLocked()
        }
        target.delete()
        segmentsDirectory.deleteRecursively()
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

    private fun updateSegmentSpeechStateLocked(buffer: ByteArray, read: Int) {
        if (isSpeechFrame(buffer, read)) {
            currentSegmentSpeechPcmBytes += read
            currentSegmentTrailingSilencePcmBytes = 0L
        } else if (currentSegmentSpeechPcmBytes > 0L) {
            currentSegmentTrailingSilencePcmBytes += read
        }
    }

    private fun isSpeechFrame(buffer: ByteArray, read: Int): Boolean {
        var sumSq = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < read) {
            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xff)).toShort().toDouble()
            sumSq += sample * sample
            samples += 1
            i += 2
        }
        if (samples == 0) return false
        val rms = kotlin.math.sqrt(sumSq / samples)
        if (rms <= 0.0) return false
        val dbfs = 20.0 * log10(rms / Short.MAX_VALUE.toDouble())
        return dbfs >= SPEECH_RMS_DBFS_THRESHOLD
    }

    private fun pcmBytesToMs(bytes: Long): Long = (bytes * 1000L) / BYTE_RATE

    private class PcmOverlapBuffer(private val capacity: Int) {
        private val data = ByteArray(capacity)
        private var nextWrite = 0
        private var size = 0

        fun write(buffer: ByteArray, length: Int) {
            if (capacity <= 0 || length <= 0) return
            val copyLength = minOf(length, buffer.size)
            if (copyLength >= capacity) {
                System.arraycopy(buffer, copyLength - capacity, data, 0, capacity)
                nextWrite = 0
                size = capacity
                return
            }
            var copied = 0
            while (copied < copyLength) {
                val chunk = minOf(copyLength - copied, capacity - nextWrite)
                System.arraycopy(buffer, copied, data, nextWrite, chunk)
                nextWrite = (nextWrite + chunk) % capacity
                copied += chunk
            }
            size = minOf(capacity, size + copyLength)
        }

        fun snapshot(): ByteArray {
            if (size <= 0) return ByteArray(0)
            val result = ByteArray(size)
            val start = (nextWrite - size + capacity) % capacity
            val first = minOf(size, capacity - start)
            System.arraycopy(data, start, result, 0, first)
            if (first < size) {
                System.arraycopy(data, 0, result, first, size - first)
            }
            return result
        }

        fun clear() {
            nextWrite = 0
            size = 0
        }
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
