package app.murmurnote.android.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class SileroVadAssetTest {

    @Test
    fun bundledModelExistsWithReviewedSizeAndDigest() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val digest = MessageDigest.getInstance("SHA-256")
        var byteCount = 0L

        assets.open(SileroVadDetector.MODEL_ASSET_PATH).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
                byteCount += read
            }
        }

        assertEquals(SileroVadDetector.MODEL_SIZE_BYTES, byteCount)
        assertEquals(EXPECTED_SHA256, digest.digest().toHex())
        assertTrue(
            assets.open("vad_models/silero_vad_v5/LICENSE").bufferedReader().use { license ->
                license.readText().contains("MIT License")
            },
        )
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(radix = 16).padStart(2, '0')
    }

    private companion object {
        const val EXPECTED_SHA256 = "6b99cbfd39246b6706f98ec13c7c50c6b299181f2474fa05cbc8046acc274396"
    }
}
