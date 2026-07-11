package app.murmurnote.android.data.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SherpaBridgeParametersTest {

    @Test
    fun `SenseVoice parameters preserve frozen language and ITN`() = withModelDirectory { modelDir ->
        File(modelDir, "model.int8.onnx").writeText("model")
        File(modelDir, "tokens.txt").writeText("tokens")
        val config = LocalAsrSessionConfig(
            engineType = AsrEngineType.LOCAL_SENSE_VOICE,
            modelId = AsrModelUrls.SENSE_VOICE_ID,
            options = LocalModelOptions.SenseVoice(language = "yue", useItn = false),
            vadPresetVersion = "vad-v1"
        )

        val parameters = SherpaBridgeParameterResolver.resolve(modelDir, config, requestedThreads = 3)

        assertEquals(SherpaModelKind.SENSE_VOICE, parameters.modelKind)
        assertEquals("yue", parameters.modelArguments["language"])
        assertEquals(false, parameters.modelArguments["useInverseTextNormalization"])
        assertEquals(3, parameters.offlineModelArguments["numThreads"])
    }

    @Test
    fun `Qwen parameters use empty hotwords for null snapshot`() = withModelDirectory { modelDir ->
        createQwenLayout(modelDir)
        val config = qwenConfig(hotwordSnapshot = null)

        val parameters = SherpaBridgeParameterResolver.resolve(modelDir, config, requestedThreads = 3)

        assertEquals(SherpaModelKind.QWEN3_ASR, parameters.modelKind)
        assertEquals("", parameters.modelArguments["hotwords"])
        assertEquals(1, parameters.offlineModelArguments["numThreads"])
    }

    @Test
    fun `Qwen hotword snapshot uses documented deterministic comma serialization`() =
        withModelDirectory { modelDir ->
            createQwenLayout(modelDir)
            val config = qwenConfig(
                hotwordSnapshot = HotwordSnapshot(listOf("Murmurnote", "会议纪要"))
            )

            val parameters = SherpaBridgeParameterResolver.resolve(modelDir, config, requestedThreads = 1)

            assertEquals("Murmurnote,会议纪要", parameters.modelArguments["hotwords"])
            assertFalse(parameters.metadataForLogging.contains("Murmurnote"))
            assertFalse(parameters.metadataForLogging.contains("会议纪要"))
        }

    @Test
    fun `Qwen hotword containing delimiter fails closed`() = withModelDirectory { modelDir ->
        createQwenLayout(modelDir)
        val config = qwenConfig(HotwordSnapshot(listOf("alpha,beta")))

        assertThrows(IllegalArgumentException::class.java) {
            SherpaBridgeParameterResolver.resolve(modelDir, config, requestedThreads = 1)
        }
    }

    @Test
    fun `engine and model mismatch fails before JNI loading`() = withModelDirectory { modelDir ->
        createQwenLayout(modelDir)
        val mismatched = LocalAsrSessionConfig(
            engineType = AsrEngineType.LOCAL_QWEN3_ASR,
            modelId = AsrModelUrls.SENSE_VOICE_ID,
            options = LocalModelOptions.Qwen3Asr(hotwordSnapshot = null),
            vadPresetVersion = "vad-v1"
        )

        assertThrows(IllegalArgumentException::class.java) {
            SherpaBridgeParameterResolver.resolve(modelDir, mismatched, requestedThreads = 1)
        }
    }

    private fun qwenConfig(hotwordSnapshot: HotwordSnapshot?): LocalAsrSessionConfig =
        LocalAsrSessionConfig(
            engineType = AsrEngineType.LOCAL_QWEN3_ASR,
            modelId = AsrModelUrls.QWEN3_ASR_ID,
            options = LocalModelOptions.Qwen3Asr(hotwordSnapshot),
            vadPresetVersion = "vad-v1"
        )

    private fun createQwenLayout(modelDir: File) {
        File(modelDir, "conv_frontend.onnx").writeText("conv")
        File(modelDir, "encoder.int8.onnx").writeText("encoder")
        File(modelDir, "decoder.int8.onnx").writeText("decoder")
        File(modelDir, "tokenizer").mkdirs()
        File(modelDir, "tokenizer/vocab.json").writeText("{}")
    }

    private inline fun withModelDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("sherpa-parameters-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
