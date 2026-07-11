package app.murmurnote.android.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedStreamsTest {

    @Test
    fun copyAcceptsExactlyTheLimit() {
        val bytes = ByteArray(32) { it.toByte() }
        val output = ByteArrayOutputStream()

        val copied = BoundedStreams.copy(ByteArrayInputStream(bytes), output, bytes.size.toLong())

        assertEquals(bytes.size.toLong(), copied)
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test
    fun copyRejectsTheFirstBytePastTheLimitWithoutWritingPastIt() {
        val output = ByteArrayOutputStream()

        assertThrows(SizeLimitExceededException::class.java) {
            BoundedStreams.copy(ByteArrayInputStream(ByteArray(33)), output, 32)
        }

        assertTrue(output.size() <= 32)
    }

    @Test
    fun utf8ReaderUsesTheSameByteLimit() {
        assertEquals(
            "声记",
            BoundedStreams.readUtf8(ByteArrayInputStream("声记".toByteArray()), 6),
        )
        assertThrows(SizeLimitExceededException::class.java) {
            BoundedStreams.readUtf8(ByteArrayInputStream("声记".toByteArray()), 5)
        }
    }

    @Test
    fun copyRejectsAStreamThatNeverMakesProgress() {
        val emptyReader = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }

        assertThrows(IOException::class.java) {
            BoundedStreams.copy(emptyReader, ByteArrayOutputStream(), 32)
        }
    }
}
