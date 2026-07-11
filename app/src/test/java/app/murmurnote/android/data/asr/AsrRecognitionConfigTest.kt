package app.murmurnote.android.data.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrRecognitionConfigTest {

    @Test
    fun senseVoiceOptionsRequireSenseVoiceEngine() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalAsrSessionConfig(
                engineType = AsrEngineType.LOCAL_QWEN3_ASR,
                modelId = "sensevoice-small-int8",
                options = LocalModelOptions.SenseVoice(language = "zh", useItn = true),
                vadPresetVersion = "silero-v5.1"
            )
        }
    }

    @Test
    fun qwenOptionsRequireQwenEngine() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalAsrSessionConfig(
                engineType = AsrEngineType.LOCAL_SENSE_VOICE,
                modelId = "qwen3-asr-0.6b-int8",
                options = LocalModelOptions.Qwen3Asr(hotwordSnapshot = null),
                vadPresetVersion = "silero-v5.1"
            )
        }
    }

    @Test
    fun localSessionConfigRejectsCloudEngine() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalAsrSessionConfig(
                engineType = AsrEngineType.CLOUD_GLM,
                modelId = "glm-asr",
                options = LocalModelOptions.SenseVoice(language = "zh", useItn = true),
                vadPresetVersion = "silero-v5.1"
            )
        }
    }

    @Test
    fun senseVoiceSnapshotHasCanonicalFieldOrderAndSha256Fingerprint() {
        val config = LocalAsrSessionConfig(
            engineType = AsrEngineType.LOCAL_SENSE_VOICE,
            modelId = "sensevoice-small-int8",
            options = LocalModelOptions.SenseVoice(language = "zh", useItn = true),
            vadPresetVersion = "silero-v5.1"
        )

        assertEquals(
            """{"schemaVersion":1,"engineType":"LOCAL_SENSE_VOICE","modelId":"sensevoice-small-int8","recognizerConcurrency":1,"options":{"type":"SENSE_VOICE","language":"zh","useItn":true},"vadPresetVersion":"silero-v5.1"}""",
            config.configSnapshotJson
        )
        assertEquals(
            "a8540d4a3c738aa18e29a3221bbc631cf3ac6ce332d707d380f3825050a4d065",
            config.configFingerprint
        )
    }

    @Test
    fun qwenSnapshotDistinguishesMissingAndPresentHotwords() {
        val withoutHotwords = LocalAsrSessionConfig(
            engineType = AsrEngineType.LOCAL_QWEN3_ASR,
            modelId = "qwen3-asr-0.6b-int8",
            options = LocalModelOptions.Qwen3Asr(hotwordSnapshot = null),
            vadPresetVersion = "silero-v5.1"
        )
        val withHotwords = withoutHotwords.copy(
            options = LocalModelOptions.Qwen3Asr(
                hotwordSnapshot = HotwordSnapshot(listOf("Murmurnote", "声纹"))
            )
        )

        assertEquals(
            """{"schemaVersion":1,"engineType":"LOCAL_QWEN3_ASR","modelId":"qwen3-asr-0.6b-int8","recognizerConcurrency":1,"options":{"type":"QWEN3_ASR","hotwordSnapshot":null},"vadPresetVersion":"silero-v5.1"}""",
            withoutHotwords.configSnapshotJson
        )
        assertEquals(
            """{"schemaVersion":1,"engineType":"LOCAL_QWEN3_ASR","modelId":"qwen3-asr-0.6b-int8","recognizerConcurrency":1,"options":{"type":"QWEN3_ASR","hotwordSnapshot":{"hotwords":["Murmurnote","声纹"]}},"vadPresetVersion":"silero-v5.1"}""",
            withHotwords.configSnapshotJson
        )
        assertNotEquals(withoutHotwords.configFingerprint, withHotwords.configFingerprint)
    }

    @Test
    fun fingerprintIsStableForEqualConfigsAndChangesForBehaviorFields() {
        val baseline = LocalAsrSessionConfig(
            engineType = AsrEngineType.LOCAL_SENSE_VOICE,
            modelId = "sensevoice-small-int8",
            options = LocalModelOptions.SenseVoice(language = "zh", useItn = true),
            vadPresetVersion = "silero-v5.1"
        )
        val equalConfig = baseline.copy()
        val behaviorChanges = listOf(
            baseline.copy(modelId = "sensevoice-small-fp16"),
            baseline.copy(options = LocalModelOptions.SenseVoice(language = "en", useItn = true)),
            baseline.copy(options = LocalModelOptions.SenseVoice(language = "zh", useItn = false)),
            baseline.copy(vadPresetVersion = "silero-v5.2")
        )

        assertEquals(baseline.configSnapshotJson, equalConfig.configSnapshotJson)
        assertEquals(baseline.configFingerprint, equalConfig.configFingerprint)
        assertTrue(behaviorChanges.all { it.configFingerprint != baseline.configFingerprint })
        assertTrue(baseline.configFingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun hotwordSnapshotDefensivelyCopiesItsInput() {
        val source = mutableListOf("Murmurnote")
        val snapshot = HotwordSnapshot(source)
        val config = LocalAsrSessionConfig(
            engineType = AsrEngineType.LOCAL_QWEN3_ASR,
            modelId = "qwen3-asr-0.6b-int8",
            options = LocalModelOptions.Qwen3Asr(snapshot),
            vadPresetVersion = "silero-v5.1"
        )
        val originalJson = config.configSnapshotJson
        val originalFingerprint = config.configFingerprint

        source += "new term"

        assertEquals(listOf("Murmurnote"), snapshot.hotwords)
        assertEquals(originalJson, config.configSnapshotJson)
        assertEquals(originalFingerprint, config.configFingerprint)
    }

    @Test
    fun snapshotEscapesJsonStringsDeterministically() {
        val config = LocalAsrSessionConfig(
            engineType = AsrEngineType.LOCAL_SENSE_VOICE,
            modelId = "model\"\\\n",
            options = LocalModelOptions.SenseVoice(language = "zh\tCN", useItn = false),
            vadPresetVersion = "vad\r1"
        )

        assertEquals(
            """{"schemaVersion":1,"engineType":"LOCAL_SENSE_VOICE","modelId":"model\"\\\n","recognizerConcurrency":1,"options":{"type":"SENSE_VOICE","language":"zh\tCN","useItn":false},"vadPresetVersion":"vad\r1"}""",
            config.configSnapshotJson
        )
    }

    @Test
    fun provenanceCopiesTheFrozenConfigIdentity() {
        val config = LocalAsrSessionConfig(
            engineType = AsrEngineType.LOCAL_QWEN3_ASR,
            modelId = "qwen3-asr-0.6b-int8",
            options = LocalModelOptions.Qwen3Asr(null),
            vadPresetVersion = "silero-v5.1"
        )

        assertEquals(
            AsrProvenance(
                engineType = AsrEngineType.LOCAL_QWEN3_ASR,
                modelId = "qwen3-asr-0.6b-int8",
                configFingerprint = config.configFingerprint,
                configSnapshotJson = config.configSnapshotJson,
                vadPresetVersion = "silero-v5.1"
            ),
            config.toProvenance()
        )
    }

    @Test
    fun cloudSnapshotIsCanonicalStableAndContainsNoCredential() {
        val config = CloudAsrSessionConfig.fromBaseUrl(
            modelId = "glm-asr-2512",
            vadPresetVersion = "silero-v5.1",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4/"
        )

        assertEquals(
            """{"schemaVersion":1,"engineType":"CLOUD_GLM","modelId":"glm-asr-2512","endpointFingerprint":"01466612b7ca82b51dedceae43a791f41ed88b493388654d352d4e37203eaad1","vadPresetVersion":"silero-v5.1"}""",
            config.configSnapshotJson
        )
        assertEquals(config.configFingerprint, config.copy().configFingerprint)
        assertTrue(config.configFingerprint.matches(Regex("[0-9a-f]{64}")))
        assertTrue("apiKey" !in config.configSnapshotJson)
        assertEquals(AsrEngineType.CLOUD_GLM, config.toProvenance().engineType)
    }

    @Test
    fun cloudFingerprintChangesWithModelOrVadAndRejectsBlankIdentity() {
        val baseline = CloudAsrSessionConfig.fromBaseUrl(
            modelId = "glm-asr-2512",
            vadPresetVersion = "silero-v5.1",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4/"
        )

        assertNotEquals(
            baseline.configFingerprint,
            baseline.copy(modelId = "glm-asr-next").configFingerprint
        )
        assertNotEquals(
            baseline.configFingerprint,
            baseline.copy(vadPresetVersion = "silero-v5.2").configFingerprint
        )
        assertThrows(IllegalArgumentException::class.java) {
            CloudAsrSessionConfig.fromBaseUrl(
                modelId = "",
                vadPresetVersion = "silero-v5.1",
                baseUrl = "https://example.com"
            )
        }
    }

    @Test
    fun cloudRequestFreezesEndpointAndCredentialWithoutExposingEither() {
        val session = CloudAsrSessionConfig.fromBaseUrl(
            modelId = "glm-asr-2512",
            vadPresetVersion = "silero-v5.1",
            baseUrl = "https://private-proxy.example/v4"
        )
        val request = CloudAsrRequestConfig(
            sessionConfig = session,
            baseUrl = "https://private-proxy.example/v4",
            apiKey = "top-secret"
        )

        assertFalse(request.toString().contains("private-proxy"))
        assertFalse(request.toString().contains("top-secret"))
        assertNotEquals(
            session.configFingerprint,
            CloudAsrSessionConfig.fromBaseUrl(
                modelId = session.modelId,
                vadPresetVersion = session.vadPresetVersion,
                baseUrl = "https://another.example/v4"
            ).configFingerprint
        )
    }
}
