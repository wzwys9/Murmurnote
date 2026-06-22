package app.murmurnote.android.data.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrEngineTypeTest {

    @Test
    fun parsePreservesQwen3AsrAsLocalEngineType() {
        val type = AsrEngineType.parse("LOCAL_QWEN3_ASR")

        assertEquals(AsrEngineType.LOCAL_QWEN3_ASR, type)
        assertTrue(type.isLocal())
    }

    @Test
    fun parseMapsLegacyFireRedAsrToLocalSenseVoice() {
        val type = AsrEngineType.parse("LOCAL_FIRE_RED_ASR")

        assertEquals(AsrEngineType.LOCAL_SENSE_VOICE, type)
        assertTrue(type.isLocal())
    }
}
