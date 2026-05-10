package app.murmurnote.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingSegmentManifestTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun readReturnsSegmentsWhenManifestAndFilesMatch() {
        val finalFile = temp.newFile("rec.wav").apply { writeBytes(ByteArray(128) { 3 }) }
        val dir = RecordingSegmentManifest.segmentDirFor(finalFile).apply { mkdirs() }
        val first = dir.resolve("segment_0000.wav").apply { writeBytes(ByteArray(64) { 1 }) }
        val second = dir.resolve("segment_0001.wav").apply { writeBytes(ByteArray(32) { 2 }) }

        RecordingSegmentManifest.write(
            finalFile = finalFile,
            sampleRateHz = 16_000,
            bitsPerSample = 16,
            segmentTargetMs = 60_000L,
            segments = listOf(
                RecordingSegmentManifest.Segment(0, first, 0L, 60_000L),
                RecordingSegmentManifest.Segment(1, second, 60_000L, 72_000L)
            )
        )

        val manifest = RecordingSegmentManifest.read(finalFile)

        assertEquals(16_000, manifest?.sampleRateHz)
        assertEquals(2, manifest?.segments?.size)
        assertEquals(60_000L, manifest?.segments?.last()?.startMs)
        assertEquals(second.absolutePath, manifest?.segments?.last()?.file?.absolutePath)
    }

    @Test
    fun readReturnsNullWhenSegmentFileIsMissing() {
        val finalFile = temp.newFile("rec.wav").apply { writeBytes(ByteArray(128) { 3 }) }
        val dir = RecordingSegmentManifest.segmentDirFor(finalFile).apply { mkdirs() }
        val segment = dir.resolve("segment_0000.wav").apply { writeBytes(ByteArray(64) { 1 }) }

        RecordingSegmentManifest.write(
            finalFile = finalFile,
            sampleRateHz = 16_000,
            bitsPerSample = 16,
            segmentTargetMs = 60_000L,
            segments = listOf(RecordingSegmentManifest.Segment(0, segment, 0L, 10_000L))
        )
        segment.delete()

        assertNull(RecordingSegmentManifest.read(finalFile))
    }
}
