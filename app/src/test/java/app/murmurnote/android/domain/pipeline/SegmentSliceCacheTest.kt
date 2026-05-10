package app.murmurnote.android.domain.pipeline

import app.murmurnote.android.audio.AudioSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SegmentSliceCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun readReturnsCachedSlicesWhenInputAndFilesMatch() {
        val input = temp.newFile("mono.wav").apply {
            writeBytes(ByteArray(32) { it.toByte() })
            setLastModified(1_700_000_000_000L)
        }
        val outputDir = temp.newFolder("segments")
        val first = outputDir.resolve("mono_seg00.wav").apply { writeBytes(ByteArray(10) { 1 }) }
        val second = outputDir.resolve("mono_seg01.wav").apply { writeBytes(ByteArray(12) { 2 }) }
        val meta = outputDir.resolve("segments.properties")

        SegmentSliceCache.write(
            metaFile = meta,
            input = input,
            slices = listOf(
                AudioSplitter.Slice(first, 0L, 10_000L),
                AudioSplitter.Slice(second, 10_000L, 18_000L)
            )
        )

        val cached = SegmentSliceCache.read(meta, outputDir, input)

        assertEquals(2, cached?.size)
        assertEquals(0L, cached?.first()?.startMs)
        assertEquals(18_000L, cached?.last()?.endMs)
        assertEquals(second.absolutePath, cached?.last()?.file?.absolutePath)
    }

    @Test
    fun readReturnsNullWhenInputChanges() {
        val input = temp.newFile("mono.wav").apply {
            writeBytes(ByteArray(32) { it.toByte() })
            setLastModified(1_700_000_000_000L)
        }
        val outputDir = temp.newFolder("segments")
        val sliceFile = outputDir.resolve("mono_seg00.wav").apply { writeBytes(ByteArray(10) { 1 }) }
        val meta = outputDir.resolve("segments.properties")
        SegmentSliceCache.write(meta, input, listOf(AudioSplitter.Slice(sliceFile, 0L, 10_000L)))

        input.appendBytes(byteArrayOf(9))

        assertNull(SegmentSliceCache.read(meta, outputDir, input))
    }

    @Test
    fun readReturnsNullWhenSliceFileIsMissing() {
        val input = temp.newFile("mono.wav").apply {
            writeBytes(ByteArray(32) { it.toByte() })
            setLastModified(1_700_000_000_000L)
        }
        val outputDir = temp.newFolder("segments")
        val sliceFile = outputDir.resolve("mono_seg00.wav").apply { writeBytes(ByteArray(10) { 1 }) }
        val meta = outputDir.resolve("segments.properties")
        SegmentSliceCache.write(meta, input, listOf(AudioSplitter.Slice(sliceFile, 0L, 10_000L)))

        sliceFile.delete()

        assertNull(SegmentSliceCache.read(meta, outputDir, input))
    }
}
