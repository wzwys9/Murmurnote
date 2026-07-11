package app.murmurnote.android.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files

class RecordingFileStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var filesRoot: File
    private lateinit var externalRoot: File
    private lateinit var store: RecordingFileStore

    @Before
    fun setUp() {
        filesRoot = temp.newFolder("files")
        externalRoot = temp.newFolder("external")
        store = RecordingFileStore(filesRoot, externalRoot)
    }

    @Test
    fun deletesExplicitFilesAndOnlyTheTwoKnownGeneratedDirectories() {
        val original = file(File(externalRoot, "recordings/meeting.wav"), "audio")
        val rollingSegment = file(File(externalRoot, "recordings/meeting_segments/0001.wav"), "slice")
        val nestedRollingArtifact = file(
            File(externalRoot, "recordings/meeting_segments/cache/manifest.txt"),
            "manifest"
        )
        val pipelineArtifact = file(File(externalRoot, "pipeline/recording-1/chunks/0001.wav"), "chunk")

        val report = store.delete(
            RecordingAudioFiles(
                recordingId = "recording-1",
                originalFilePath = original.absolutePath,
                segmentFilePaths = listOf(rollingSegment.absolutePath)
            )
        )

        assertTrue(report.isSuccess)
        assertEquals(4, report.attemptedPaths)
        assertEquals(4, report.deletedPaths)
        assertFalse(original.exists())
        assertFalse(rollingSegment.exists())
        assertFalse(nestedRollingArtifact.exists())
        assertFalse(pipelineArtifact.exists())
    }

    @Test
    fun sharedOriginalSiblingAndContainedSegmentsAreRetainedForAnotherAvailableOwner() {
        val original = file(File(externalRoot, "recordings/shared.wav"), "audio")
        val rollingSegment = file(File(externalRoot, "recordings/shared_segments/0001.wav"), "slice")
        val ownPipeline = file(File(externalRoot, "pipeline/current/chunk.wav"), "chunk")

        val report = store.delete(
            RecordingAudioFiles(
                recordingId = "current",
                originalFilePath = original.absolutePath,
                segmentFilePaths = listOf(rollingSegment.absolutePath),
                otherAvailableOwners = listOf(
                    RecordingAudioOwner(
                        recordingId = "other",
                        originalFilePath = original.absolutePath,
                        segmentFilePaths = emptyList()
                    )
                )
            )
        )

        assertTrue(report.isSuccess)
        assertEquals(3, report.retainedSharedPaths)
        assertTrue(original.exists())
        assertTrue(rollingSegment.exists())
        assertFalse(ownPipeline.exists())
    }

    @Test
    fun explicitlySharedSegmentPathIsRetainedWhileOwnedArtifactsAreDeleted() {
        val original = file(File(externalRoot, "imports/current.m4a"), "audio")
        val sharedSegment = file(File(externalRoot, "recordings/shared/chunk.wav"), "shared")
        val ownPipeline = file(File(externalRoot, "pipeline/current-2/chunk.wav"), "chunk")

        val report = store.delete(
            RecordingAudioFiles(
                recordingId = "current-2",
                originalFilePath = original.absolutePath,
                segmentFilePaths = listOf(sharedSegment.absolutePath),
                otherAvailableOwners = listOf(
                    RecordingAudioOwner(
                        recordingId = "other-2",
                        originalFilePath = File(externalRoot, "imports/other.m4a").absolutePath,
                        segmentFilePaths = listOf(sharedSegment.absolutePath)
                    )
                )
            )
        )

        assertTrue(report.isSuccess)
        assertEquals(1, report.retainedSharedPaths)
        assertFalse(original.exists())
        assertTrue(sharedSegment.exists())
        assertFalse(ownPipeline.exists())
    }

    @Test
    fun unsafeOutsidePathFailsClosedBeforeDeletingAnySafeCandidate() {
        val safeOriginal = file(File(filesRoot, "recordings/safe.wav"), "safe")
        val outside = file(File(temp.root, "outside.wav"), "outside")

        val report = store.delete(
            RecordingAudioFiles(
                recordingId = "recording-2",
                originalFilePath = safeOriginal.absolutePath,
                segmentFilePaths = listOf(outside.absolutePath)
            )
        )

        assertFalse(report.isSuccess)
        assertEquals(0, report.deletedPaths)
        assertTrue(
            report.failures.all {
                it.reason == AudioDeletionFailureReason.OUTSIDE_ALLOWED_ROOT
            }
        )
        assertTrue(safeOriginal.exists())
        assertTrue(outside.exists())
    }

    @Test
    fun corruptDatabasePathCannotDeleteAnotherPrivateAppAsset() {
        val model = file(File(filesRoot, "asr_models/model.onnx"), "model")

        val report = store.delete(
            RecordingAudioFiles(
                recordingId = "corrupt-owner",
                originalFilePath = model.absolutePath,
                segmentFilePaths = emptyList()
            )
        )

        assertFalse(report.isSuccess)
        assertEquals(0, report.deletedPaths)
        assertTrue(
            report.failures.all {
                it.reason == AudioDeletionFailureReason.OUTSIDE_ALLOWED_ROOT
            }
        )
        assertTrue(model.exists())
    }

    @Test
    fun directSymlinkEscapeFailsClosedAndDoesNotTouchTarget() {
        val outsideTarget = file(File(temp.root, "private.wav"), "private")
        val link = File(externalRoot, "recordings/link.wav")
        link.parentFile?.mkdirs()
        try {
            Files.createSymbolicLink(link.toPath(), outsideTarget.toPath())
        } catch (error: Exception) {
            assumeNoException(error)
        }

        val report = store.delete(
            RecordingAudioFiles(
                recordingId = "recording-3",
                originalFilePath = link.absolutePath,
                segmentFilePaths = emptyList()
            )
        )

        assertFalse(report.isSuccess)
        assertEquals(AudioDeletionFailureReason.SYMLINK, report.failures.single().reason)
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertTrue(outsideTarget.exists())
    }

    @Test
    fun symlinkedAncestorIsRejectedEvenWhenItsTargetStaysInsideAllowedRoot() {
        val realDirectory = File(externalRoot, "recordings/real").apply { mkdirs() }
        val target = file(File(realDirectory, "target.wav"), "target")
        val alias = File(externalRoot, "recordings/alias")
        try {
            Files.createSymbolicLink(alias.toPath(), realDirectory.toPath())
        } catch (error: Exception) {
            assumeNoException(error)
        }

        val report = store.delete(
            RecordingAudioFiles(
                recordingId = "recording-ancestor-link",
                originalFilePath = File(alias, "target.wav").absolutePath,
                segmentFilePaths = emptyList()
            )
        )

        assertFalse(report.isSuccess)
        assertTrue(report.failures.all { it.reason == AudioDeletionFailureReason.SYMLINK })
        assertEquals(
            setOf(AudioFileKind.ORIGINAL, AudioFileKind.DERIVED_SEGMENT_DIRECTORY),
            report.failures.map { it.kind }.toSet()
        )
        assertTrue(target.exists())
    }

    @Test
    fun symlinkNestedInGeneratedDirectoryAlsoFailsClosed() {
        val original = file(File(externalRoot, "recordings/nested.wav"), "audio")
        val outsideTarget = file(File(temp.root, "outside-nested.wav"), "outside")
        val link = File(externalRoot, "recordings/nested_segments/escape.wav")
        link.parentFile?.mkdirs()
        try {
            Files.createSymbolicLink(link.toPath(), outsideTarget.toPath())
        } catch (error: Exception) {
            assumeNoException(error)
        }

        val report = store.delete(
            RecordingAudioFiles(
                recordingId = "recording-4",
                originalFilePath = original.absolutePath,
                segmentFilePaths = emptyList()
            )
        )

        assertFalse(report.isSuccess)
        assertEquals(0, report.deletedPaths)
        assertTrue(original.exists())
        assertTrue(outsideTarget.exists())
    }

    @Test
    fun pathTraversalInRecordingIdCannotRetargetPipelineDeletion() {
        val original = file(File(externalRoot, "recordings/traversal.wav"), "audio")
        val unrelated = file(File(externalRoot, "recordings/do-not-delete.wav"), "unrelated")

        val report = store.delete(
            RecordingAudioFiles(
                recordingId = "../recordings",
                originalFilePath = original.absolutePath,
                segmentFilePaths = emptyList()
            )
        )

        assertFalse(report.isSuccess)
        assertEquals(AudioDeletionFailureReason.PATH_TRAVERSAL, report.failures.single().reason)
        assertTrue(original.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun invalidPlatformPathFailsClosedInsteadOfThrowing() {
        val safeOriginal = file(File(filesRoot, "recordings/invalid.wav"), "safe")

        val report = store.delete(
            RecordingAudioFiles(
                recordingId = "recording-invalid",
                originalFilePath = safeOriginal.absolutePath,
                segmentFilePaths = listOf("\u0000invalid")
            )
        )

        assertFalse(report.isSuccess)
        assertEquals(AudioDeletionFailureReason.PATH_TRAVERSAL, report.failures.single().reason)
        assertTrue(safeOriginal.exists())
    }

    @Test
    fun indeterminateExistenceIsAnIoFailureNotAFalseMissingSuccess() {
        assertEquals(
            AudioPathExistence.INDETERMINATE,
            classifyAudioPathExistence(exists = false, notExists = false)
        )
        assertEquals(
            AudioPathExistence.MISSING,
            classifyAudioPathExistence(exists = false, notExists = true)
        )
        assertEquals(
            AudioPathExistence.EXISTS,
            classifyAudioPathExistence(exists = true, notExists = false)
        )
    }

    @Test
    fun lazyDirectoryWalkFailureIsClassifiedAsRetryableIo() {
        assertTrue(isAudioFileIoFailure(UncheckedIOException(IOException())))
        assertTrue(isAudioFileIoFailure(IOException()))
        assertFalse(isAudioFileIoFailure(IllegalStateException()))
    }

    @Test
    fun missingKnownPathsAreACompleteSuccessfulDeletion() {
        val missingOriginal = File(filesRoot, "imports/missing.m4a")
        val missingSegment = File(filesRoot, "imports/missing_segments/0001.wav")

        val report = store.delete(
            RecordingAudioFiles(
                recordingId = "recording-5",
                originalFilePath = missingOriginal.absolutePath,
                segmentFilePaths = listOf(missingSegment.absolutePath)
            )
        )

        assertTrue(report.isSuccess)
        assertEquals(4, report.attemptedPaths)
        assertEquals(4, report.missingPaths)
        assertEquals(0, report.deletedPaths)
    }

    @Test
    fun explicitUserSuppliedDirectoryIsNeverRecursivelyDeleted() {
        val directory = File(filesRoot, "recordings/arbitrary").apply { mkdirs() }
        val child = file(File(directory, "keep.txt"), "keep")

        val report = store.delete(
            RecordingAudioFiles(
                recordingId = "recording-6",
                originalFilePath = directory.absolutePath,
                segmentFilePaths = emptyList()
            )
        )

        assertFalse(report.isSuccess)
        assertEquals(AudioDeletionFailureReason.UNEXPECTED_DIRECTORY, report.failures.single().reason)
        assertTrue(child.exists())
    }

    private fun file(path: File, content: String): File = path.apply {
        parentFile?.mkdirs()
        writeText(content)
    }
}
