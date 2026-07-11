package app.murmurnote.android.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import javax.inject.Inject
import javax.inject.Singleton

data class RecordingAudioFiles(
    val recordingId: String,
    val originalFilePath: String,
    val segmentFilePaths: List<String>,
    val otherAvailableOwners: List<RecordingAudioOwner> = emptyList()
)

data class RecordingAudioOwner(
    val recordingId: String,
    val originalFilePath: String,
    val segmentFilePaths: List<String>
)

enum class AudioFileKind {
    ORIGINAL,
    RECORDING_SEGMENT,
    DERIVED_SEGMENT_DIRECTORY,
    PIPELINE_DIRECTORY,
    STORAGE_ROOT
}

enum class AudioDeletionFailureReason {
    OUTSIDE_ALLOWED_ROOT,
    PATH_TRAVERSAL,
    SYMLINK,
    UNEXPECTED_DIRECTORY,
    ROOT_UNAVAILABLE,
    IO_FAILURE
}

data class AudioDeletionFailure(
    val kind: AudioFileKind,
    val reason: AudioDeletionFailureReason
)

data class AudioDeletionReport(
    val attemptedPaths: Int,
    val deletedPaths: Int,
    val missingPaths: Int,
    val failures: List<AudioDeletionFailure>,
    val retainedSharedPaths: Int = 0
) {
    val isSuccess: Boolean
        get() = failures.isEmpty()

    companion object {
        fun nothingToDelete(): AudioDeletionReport = AudioDeletionReport(
            attemptedPaths = 0,
            deletedPaths = 0,
            missingPaths = 0,
            failures = emptyList()
        )
    }
}

internal enum class AudioPathExistence { EXISTS, MISSING, INDETERMINATE }

internal fun classifyAudioPathExistence(exists: Boolean, notExists: Boolean): AudioPathExistence =
    when {
        exists -> AudioPathExistence.EXISTS
        notExists -> AudioPathExistence.MISSING
        else -> AudioPathExistence.INDETERMINATE
    }

internal fun isAudioFileIoFailure(error: Throwable): Boolean =
    error is IOException || error is UncheckedIOException || error is SecurityException

/**
 * Deletes only audio artifacts owned by the app. Every candidate is validated before the first
 * delete, so one unsafe path makes the whole operation fail closed.
 */
@Singleton
class RecordingFileStore internal constructor(
    private val filesRoot: File,
    private val externalRoot: File?
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        filesRoot = context.filesDir,
        externalRoot = context.getExternalFilesDir(null)
    )

    fun delete(files: RecordingAudioFiles): AudioDeletionReport {
        val failures = mutableListOf<AudioDeletionFailure>()
        val candidates = buildCandidates(files, failures)
        val allowedRoots = canonicalRoots(failures)
        var missingPaths = 0

        val inspectedCandidates = candidates.map { candidate ->
            val existence = classifyAudioPathExistence(
                exists = Files.exists(candidate.path, LinkOption.NOFOLLOW_LINKS),
                notExists = Files.notExists(candidate.path, LinkOption.NOFOLLOW_LINKS)
            )
            when (existence) {
                AudioPathExistence.EXISTS ->
                    validateCandidate(candidate, exists = true, allowedRoots)?.let(failures::add)

                AudioPathExistence.MISSING -> {
                    missingPaths++
                    validateCandidate(candidate, exists = false, allowedRoots)?.let(failures::add)
                }

                AudioPathExistence.INDETERMINATE -> failures += AudioDeletionFailure(
                    candidate.kind,
                    AudioDeletionFailureReason.IO_FAILURE
                )
            }
            InspectedCandidate(candidate, existence)
        }
        val protectedCandidates = files.otherAvailableOwners.flatMap { owner ->
            buildCandidates(
                RecordingAudioFiles(
                    recordingId = owner.recordingId,
                    originalFilePath = owner.originalFilePath,
                    segmentFilePaths = owner.segmentFilePaths
                ),
                failures
            )
        }
        val sharedCandidates = if (failures.isEmpty()) {
            candidates.filterTo(mutableSetOf()) { candidate ->
                protectedCandidates.any { protected ->
                    candidatesOverlap(candidate, protected, allowedRoots, failures)
                }
            }
        } else {
            emptySet()
        }

        if (failures.isNotEmpty()) {
            return AudioDeletionReport(
                attemptedPaths = candidates.size,
                deletedPaths = 0,
                missingPaths = missingPaths,
                failures = failures.toList(),
                retainedSharedPaths = 0
            )
        }

        var deletedPaths = 0
        inspectedCandidates.forEach { inspected ->
            if (inspected.existence != AudioPathExistence.EXISTS) return@forEach
            val candidate = inspected.candidate
            if (candidate in sharedCandidates) return@forEach
            try {
                if (candidate.recursive) {
                    deleteKnownDirectory(candidate.path)
                } else {
                    Files.delete(candidate.path)
                }
                deletedPaths++
            } catch (error: Exception) {
                if (!isAudioFileIoFailure(error)) throw error
                failures += AudioDeletionFailure(candidate.kind, AudioDeletionFailureReason.IO_FAILURE)
            }
        }

        return AudioDeletionReport(
            attemptedPaths = candidates.size,
            deletedPaths = deletedPaths,
            missingPaths = missingPaths,
            failures = failures.toList(),
            retainedSharedPaths = sharedCandidates.size
        )
    }

    private fun buildCandidates(
        files: RecordingAudioFiles,
        failures: MutableList<AudioDeletionFailure>
    ): List<Candidate> {
        val candidates = linkedMapOf<String, Candidate>()
        val originalPath = parsePath(files.originalFilePath, AudioFileKind.ORIGINAL, failures)
        if (originalPath != null) {
            addCandidate(candidates, Candidate(AudioFileKind.ORIGINAL, originalPath, recursive = false))
        }

        files.segmentFilePaths.forEach { rawPath ->
            parsePath(rawPath, AudioFileKind.RECORDING_SEGMENT, failures)?.let { segmentPath ->
                addCandidate(
                    candidates,
                    Candidate(AudioFileKind.RECORDING_SEGMENT, segmentPath, recursive = false)
                )
            }
        }

        if (originalPath != null && !hasTraversal(originalPath)) {
            val originalName = originalPath.fileName?.toString().orEmpty()
            val stem = originalName.substringBeforeLast('.', originalName)
            val parent = originalPath.parent
            if (parent != null && stem.isNotBlank()) {
                addCandidate(
                    candidates,
                    Candidate(
                        AudioFileKind.DERIVED_SEGMENT_DIRECTORY,
                        parent.resolve("${stem}_segments"),
                        recursive = true
                    )
                )
            }
        }

        val pipelineRoot = externalRoot?.toPath()?.resolve(PIPELINE_DIRECTORY)
        if (pipelineRoot != null) {
            if (isSafeRecordingId(files.recordingId)) {
                addCandidate(
                    candidates,
                    Candidate(
                        AudioFileKind.PIPELINE_DIRECTORY,
                        pipelineRoot.resolve(files.recordingId),
                        recursive = true
                    )
                )
            } else {
                failures += AudioDeletionFailure(
                    AudioFileKind.PIPELINE_DIRECTORY,
                    AudioDeletionFailureReason.PATH_TRAVERSAL
                )
            }
        }

        return candidates.values.toList()
    }

    private fun parsePath(
        rawPath: String,
        kind: AudioFileKind,
        failures: MutableList<AudioDeletionFailure>
    ): Path? {
        return try {
            File(rawPath).toPath()
        } catch (_: InvalidPathException) {
            failures += AudioDeletionFailure(kind, AudioDeletionFailureReason.PATH_TRAVERSAL)
            null
        }
    }

    private fun addCandidate(candidates: MutableMap<String, Candidate>, candidate: Candidate) {
        // Keep syntactically different paths separate until validation. Normalizing the map key
        // here would accidentally hide a duplicate candidate that contains `..` traversal.
        val key = candidate.path.toAbsolutePath().toString()
        candidates.putIfAbsent(key, candidate)
    }

    private fun canonicalRoots(failures: MutableList<AudioDeletionFailure>): List<AllowedRoot> {
        return listOf(filesRoot to false, externalRoot to true).mapNotNull { (root, external) ->
            if (root == null) return@mapNotNull null
            try {
                AllowedRoot(
                    configuredPath = root.toPath().toAbsolutePath().normalize(),
                    canonicalPath = root.canonicalFile.toPath(),
                    external = external
                )
            } catch (_: IOException) {
                failures += AudioDeletionFailure(
                    AudioFileKind.STORAGE_ROOT,
                    AudioDeletionFailureReason.ROOT_UNAVAILABLE
                )
                null
            } catch (_: SecurityException) {
                failures += AudioDeletionFailure(
                    AudioFileKind.STORAGE_ROOT,
                    AudioDeletionFailureReason.ROOT_UNAVAILABLE
                )
                null
            }
        }
    }

    private fun validateCandidate(
        candidate: Candidate,
        exists: Boolean,
        allowedRoots: List<AllowedRoot>
    ): AudioDeletionFailure? {
        if (hasTraversal(candidate.path)) {
            return AudioDeletionFailure(candidate.kind, AudioDeletionFailureReason.PATH_TRAVERSAL)
        }
        if (exists && Files.isSymbolicLink(candidate.path)) {
            return AudioDeletionFailure(candidate.kind, AudioDeletionFailureReason.SYMLINK)
        }

        val canonicalPath = try {
            candidate.path.toFile().canonicalFile.toPath()
        } catch (_: IOException) {
            return AudioDeletionFailure(candidate.kind, AudioDeletionFailureReason.IO_FAILURE)
        } catch (_: SecurityException) {
            return AudioDeletionFailure(candidate.kind, AudioDeletionFailureReason.IO_FAILURE)
        }
        val absolutePath = candidate.path.toAbsolutePath().normalize()
        val allowedRoot = allowedRoots.firstOrNull { root ->
            absolutePath != root.configuredPath &&
                absolutePath.startsWith(root.configuredPath) &&
                canonicalPath != root.canonicalPath &&
                canonicalPath.startsWith(root.canonicalPath)
        }
        if (allowedRoot == null) {
            return AudioDeletionFailure(candidate.kind, AudioDeletionFailureReason.OUTSIDE_ALLOWED_ROOT)
        }
        if (!isInsideKnownAudioArea(candidate, absolutePath, canonicalPath, allowedRoot)) {
            return AudioDeletionFailure(candidate.kind, AudioDeletionFailureReason.OUTSIDE_ALLOWED_ROOT)
        }
        if (hasSymlinkComponent(allowedRoot.configuredPath, absolutePath)) {
            return AudioDeletionFailure(candidate.kind, AudioDeletionFailureReason.SYMLINK)
        }

        if (exists && !candidate.recursive && Files.isDirectory(candidate.path, LinkOption.NOFOLLOW_LINKS)) {
            return AudioDeletionFailure(candidate.kind, AudioDeletionFailureReason.UNEXPECTED_DIRECTORY)
        }
        if (exists && candidate.recursive) {
            val hasSymlink = try {
                containsSymlink(candidate.path)
            } catch (error: Exception) {
                if (!isAudioFileIoFailure(error)) throw error
                return AudioDeletionFailure(candidate.kind, AudioDeletionFailureReason.IO_FAILURE)
            }
            if (hasSymlink) {
                return AudioDeletionFailure(candidate.kind, AudioDeletionFailureReason.SYMLINK)
            }
        }
        return null
    }

    private fun hasSymlinkComponent(root: Path, candidate: Path): Boolean {
        var current = root
        root.relativize(candidate).forEach { component ->
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) return true
        }
        return false
    }

    private fun candidatesOverlap(
        candidate: Candidate,
        protected: Candidate,
        allowedRoots: List<AllowedRoot>,
        failures: MutableList<AudioDeletionFailure>
    ): Boolean {
        val candidateCanonical = canonicalOwnershipPath(candidate, allowedRoots, failures) ?: return false
        val protectedCanonical = canonicalOwnershipPath(protected, allowedRoots, failures) ?: return false
        return when {
            candidate.recursive && protected.recursive ->
                candidateCanonical == protectedCanonical ||
                    candidateCanonical.startsWith(protectedCanonical) ||
                    protectedCanonical.startsWith(candidateCanonical)

            candidate.recursive ->
                protectedCanonical == candidateCanonical || protectedCanonical.startsWith(candidateCanonical)

            protected.recursive ->
                candidateCanonical == protectedCanonical || candidateCanonical.startsWith(protectedCanonical)

            else -> candidateCanonical == protectedCanonical
        }
    }

    private fun canonicalOwnershipPath(
        candidate: Candidate,
        allowedRoots: List<AllowedRoot>,
        failures: MutableList<AudioDeletionFailure>
    ): Path? {
        if (hasTraversal(candidate.path)) {
            failures += AudioDeletionFailure(candidate.kind, AudioDeletionFailureReason.PATH_TRAVERSAL)
            return null
        }
        val canonical = try {
            candidate.path.toFile().canonicalFile.toPath()
        } catch (error: Exception) {
            if (!isAudioFileIoFailure(error)) throw error
            failures += AudioDeletionFailure(candidate.kind, AudioDeletionFailureReason.IO_FAILURE)
            return null
        }
        val absolute = candidate.path.toAbsolutePath().normalize()
        return canonical.takeIf { path ->
            allowedRoots.any { root ->
                path != root.canonicalPath &&
                    path.startsWith(root.canonicalPath) &&
                    isInsideKnownAudioArea(candidate, absolute, path, root)
            }
        }
    }

    private fun isInsideKnownAudioArea(
        candidate: Candidate,
        absolutePath: Path,
        canonicalPath: Path,
        root: AllowedRoot
    ): Boolean {
        val directoryNames = when (candidate.kind) {
            AudioFileKind.PIPELINE_DIRECTORY -> {
                if (!root.external) return false
                listOf(PIPELINE_DIRECTORY)
            }

            AudioFileKind.ORIGINAL,
            AudioFileKind.RECORDING_SEGMENT,
            AudioFileKind.DERIVED_SEGMENT_DIRECTORY ->
                listOf(RECORDINGS_DIRECTORY, IMPORTS_DIRECTORY)

            AudioFileKind.STORAGE_ROOT -> return false
        }
        return directoryNames.any { name ->
            val configuredArea = root.configuredPath.resolve(name)
            val canonicalArea = root.canonicalPath.resolve(name)
            absolutePath != configuredArea &&
                absolutePath.startsWith(configuredArea) &&
                canonicalPath != canonicalArea &&
                canonicalPath.startsWith(canonicalArea)
        }
    }

    private fun containsSymlink(path: Path): Boolean {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return false
        Files.walk(path).use { paths ->
            return paths.anyMatch { Files.isSymbolicLink(it) }
        }
    }

    private fun deleteKnownDirectory(path: Path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(path)
            return
        }
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            }
        )
    }

    private fun hasTraversal(path: Path): Boolean {
        if (!path.isAbsolute) return true
        val absolute = path.toAbsolutePath()
        return absolute.normalize() != absolute
    }

    private fun isSafeRecordingId(recordingId: String): Boolean =
        recordingId.isNotBlank() &&
            recordingId != "." &&
            recordingId != ".." &&
            SAFE_RECORDING_ID.matches(recordingId)

    private data class Candidate(
        val kind: AudioFileKind,
        val path: Path,
        val recursive: Boolean
    )

    private data class InspectedCandidate(
        val candidate: Candidate,
        val existence: AudioPathExistence
    )

    private data class AllowedRoot(
        val configuredPath: Path,
        val canonicalPath: Path,
        val external: Boolean
    )

    private companion object {
        const val PIPELINE_DIRECTORY = "pipeline"
        const val RECORDINGS_DIRECTORY = "recordings"
        const val IMPORTS_DIRECTORY = "imports"
        val SAFE_RECORDING_ID = Regex("[A-Za-z0-9._-]+")
    }
}
