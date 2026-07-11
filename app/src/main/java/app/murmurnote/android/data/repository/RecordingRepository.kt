package app.murmurnote.android.data.repository

import androidx.room.withTransaction
import app.murmurnote.android.data.local.MurmurnoteDatabase
import app.murmurnote.android.data.local.dao.RecordingDao
import app.murmurnote.android.data.local.entity.ProcessingStatus
import app.murmurnote.android.data.local.entity.Recording
import app.murmurnote.android.data.local.entity.RecordingSegment
import app.murmurnote.android.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingRepository @Inject constructor(
    private val recordingDao: RecordingDao,
    private val database: MurmurnoteDatabase,
    private val recordingFileStore: RecordingFileStore,
    private val logger: Logger
) {
    private val audioLifecycleMutex = Mutex()

    fun observeAll(): Flow<List<Recording>> = recordingDao.observeAll()
    fun observe(id: String): Flow<Recording?> = recordingDao.observeById(id)
    suspend fun get(id: String): Recording? = recordingDao.getById(id)

    suspend fun insert(recording: Recording) = audioLifecycleMutex.withLock {
        recordingDao.insert(recording)
    }

    /**
     * Validates and removes owned audio before committing the Room cascade. If validation or file
     * I/O fails, the recording and every transcript stay available. If the process dies after file
     * cleanup, retrying is safe because missing files count as success.
     */
    suspend fun delete(id: String): AudioDeletionReport = audioLifecycleMutex.withLock {
        deleteLocked(id)
    }

    private suspend fun deleteLocked(id: String): AudioDeletionReport {
        val audioFiles = database.withTransaction {
            val recording = recordingDao.getById(id) ?: return@withTransaction null
            val segmentPaths = recordingDao.getRecordingSegments(id).map { it.filePath }
            val otherOwners = getOtherAvailableAudioOwners(id)
            RecordingAudioFiles(
                recordingId = recording.id,
                originalFilePath = recording.originalFilePath,
                segmentFilePaths = segmentPaths,
                otherAvailableOwners = otherOwners
            )
        } ?: return AudioDeletionReport.nothingToDelete()

        val firstAttempt = withContext(NonCancellable + Dispatchers.IO) {
            recordingFileStore.delete(audioFiles)
        }
        val report = if (
            !firstAttempt.isSuccess &&
            firstAttempt.failures.all { it.reason == AudioDeletionFailureReason.IO_FAILURE }
        ) {
            withContext(NonCancellable + Dispatchers.IO) { recordingFileStore.delete(audioFiles) }
        } else {
            firstAttempt
        }
        if (!report.isSuccess) {
            logger.w(
                "AudioRetention",
                "manual recording delete retained database row after audio cleanup failure",
                fields = mapOf(
                    "attempted" to report.attemptedPaths,
                    "deleted" to report.deletedPaths,
                    "retainedShared" to report.retainedSharedPaths,
                    "failures" to report.failures.size
                )
            )
            return report
        }

        withContext(NonCancellable) {
            database.withTransaction {
                if (recordingDao.getById(id) != null) recordingDao.deleteById(id)
            }
        }
        return report
    }
    suspend fun updateTags(id: String, tags: List<String>) =
        recordingDao.updateTags(id, tags.toTagString())
    suspend fun updateArchived(id: String, archived: Boolean) =
        recordingDao.updateArchived(id, archived)
    suspend fun setStatus(id: String, status: ProcessingStatus, error: String? = null) =
        audioLifecycleMutex.withLock { recordingDao.updateStatus(id, status, error) }
    suspend fun markInterruptedProcessingFailed(): Int = audioLifecycleMutex.withLock {
        recordingDao.markInterruptedProcessingFailed("上次处理被中断，可手动重试")
    }
    suspend fun markProcessingFailedIfInProgress(id: String, error: String): Boolean =
        audioLifecycleMutex.withLock {
            recordingDao.markProcessingFailedIfInProgress(id, error) == 1
        }
    suspend fun updateDuration(id: String, durationMs: Long): Boolean =
        recordingDao.updateDuration(id, durationMs) == 1

    suspend fun deleteRecordingSegments(recordingId: String) =
        recordingDao.deleteRecordingSegmentsForRecording(recordingId)
    fun observeRecordingSegments(recordingId: String): Flow<List<RecordingSegment>> =
        recordingDao.observeRecordingSegments(recordingId)
    fun search(query: String): Flow<List<Recording>> = recordingDao.searchRecordings(query)
    fun searchFiltered(
        query: String,
        fromMs: Long?,
        toMs: Long?,
        searchSummary: Boolean,
        searchTranscript: Boolean
    ): Flow<List<Recording>> = recordingDao.searchRecordingsFiltered(
        query = query,
        fromMs = fromMs,
        toMs = toMs,
        searchSummary = searchSummary,
        searchTranscript = searchTranscript
    )
    fun observeTotalCount(): Flow<Int> = recordingDao.countAll()
    fun observeCountSince(since: Long): Flow<Int> = recordingDao.countSince(since)

    suspend fun setKeepAudio(id: String, keepAudio: Boolean): Boolean =
        audioLifecycleMutex.withLock { recordingDao.setKeepAudio(id, keepAudio) > 0 }

    /**
     * Expires audio only. Recording text, revisions, rules, summaries, and extracted items remain.
     * Missing files count as a successful cleanup, making a retry after a database failure safe.
     */
    suspend fun expireAudio(nowMs: Long = System.currentTimeMillis()): AudioExpiryReport =
        audioLifecycleMutex.withLock { expireAudioLocked(nowMs) }

    private suspend fun expireAudioLocked(nowMs: Long): AudioExpiryReport {
        val candidates = recordingDao.getAudioExpiryCandidates(nowMs)
            .filter { recording ->
                AudioRetentionPolicy.isEligible(
                    audioAvailable = recording.audioAvailable,
                    keepAudio = recording.keepAudio,
                    audioExpiresAt = recording.audioExpiresAt,
                    processingStatus = recording.processingStatus,
                    nowMs = nowMs
                )
            }
        var expiredRecordings = 0
        var fileFailureRecordings = 0
        var databaseFailureRecordings = 0
        var deletedPaths = 0
        var missingPaths = 0
        var retainedSharedPaths = 0

        candidates.forEach { recording ->
            val audioFiles = RecordingAudioFiles(
                recordingId = recording.id,
                originalFilePath = recording.originalFilePath,
                segmentFilePaths = recordingDao.getRecordingSegments(recording.id).map { it.filePath },
                otherAvailableOwners = getOtherAvailableAudioOwners(recording.id)
            )
            val deletion = withContext(Dispatchers.IO) { recordingFileStore.delete(audioFiles) }
            deletedPaths += deletion.deletedPaths
            missingPaths += deletion.missingPaths
            retainedSharedPaths += deletion.retainedSharedPaths
            if (!deletion.isSuccess) {
                fileFailureRecordings++
                return@forEach
            }

            try {
                database.withTransaction {
                    recordingDao.deleteRecordingSegmentsForRecording(recording.id)
                    val marked = recordingDao.markAudioUnavailableIfStillEligible(recording.id, nowMs)
                    check(marked == 1 || recordingDao.getById(recording.id) == null) {
                        "Audio expiry eligibility changed before commit"
                    }
                }
                expiredRecordings++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The recording row and every transcript remain. A later run sees missing files as
                // success and retries this small database state transition.
                databaseFailureRecordings++
            }
        }

        return AudioExpiryReport(
            eligibleRecordings = candidates.size,
            expiredRecordings = expiredRecordings,
            fileFailureRecordings = fileFailureRecordings,
            databaseFailureRecordings = databaseFailureRecordings,
            deletedPaths = deletedPaths,
            missingPaths = missingPaths,
            retainedSharedPaths = retainedSharedPaths
        )
    }

    /** Kept for source compatibility; it no longer deletes recording rows. */
    suspend fun deleteExpired(): Int = expireAudio().expiredRecordings

    private fun List<String>.toTagString(): String =
        map { it.trim().replace(",", " ").take(24) }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")

    private suspend fun getOtherAvailableAudioOwners(recordingId: String): List<RecordingAudioOwner> {
        val segmentPathsByRecording = recordingDao.getOtherAvailableRecordingSegments(recordingId)
            .groupBy { it.recordingId }
            .mapValues { (_, segments) -> segments.map { it.filePath } }
        return recordingDao.getOtherAvailableAudioOwners(recordingId).map { owner ->
            RecordingAudioOwner(
                recordingId = owner.id,
                originalFilePath = owner.originalFilePath,
                segmentFilePaths = segmentPathsByRecording[owner.id].orEmpty()
            )
        }
    }
}

data class AudioExpiryReport(
    val eligibleRecordings: Int,
    val expiredRecordings: Int,
    val fileFailureRecordings: Int,
    val databaseFailureRecordings: Int,
    val deletedPaths: Int,
    val missingPaths: Int,
    val retainedSharedPaths: Int
) {
    val requiresRetry: Boolean
        get() = fileFailureRecordings > 0 || databaseFailureRecordings > 0
}

internal object AudioRetentionPolicy {
    fun isEligible(
        audioAvailable: Boolean,
        keepAudio: Boolean,
        audioExpiresAt: Long?,
        processingStatus: ProcessingStatus,
        nowMs: Long
    ): Boolean =
        audioAvailable &&
            !keepAudio &&
            audioExpiresAt != null &&
            audioExpiresAt <= nowMs &&
            (processingStatus == ProcessingStatus.COMPLETED || processingStatus == ProcessingStatus.FAILED)
}
