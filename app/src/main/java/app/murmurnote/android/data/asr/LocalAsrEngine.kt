package app.murmurnote.android.data.asr

import android.content.Context
import app.murmurnote.android.data.preference.AppPreferences
import app.murmurnote.android.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates immutable ASR pools without knowing anything about JNI resources.
 * Same-key leases share a pool; a different key waits until every old lease has returned.
 */
internal class FrozenAsrPoolCoordinator<T>(
    private val releasePayload: (T) -> Unit
) {
    internal data class Key(
        val configFingerprint: String,
        val concurrency: Int
    )

    internal class PoolState<T>(
        val key: Key,
        val payload: T,
        var activeLeases: Int = 0,
        var idleSignal: CompletableDeferred<Unit> = CompletableDeferred(),
        var retireWhenIdle: Boolean = false
    )

    internal class Lease<T> internal constructor(
        internal val state: PoolState<T>
    ) {
        val payload: T
            get() = state.payload

        internal var released: Boolean = false
    }

    private val lock = Any()
    private var current: PoolState<T>? = null

    suspend fun acquire(key: Key, createPayload: () -> T): Lease<T> {
        while (true) {
            var granted: Lease<T>? = null
            var waitForIdle: CompletableDeferred<Unit>? = null
            synchronized(lock) {
                val active = current
                when {
                    active == null -> {
                        val installed = PoolState(key = key, payload = createPayload())
                        current = installed
                        granted = grantLocked(installed)
                    }

                    active.key == key && !active.retireWhenIdle -> {
                        granted = grantLocked(active)
                    }

                    active.activeLeases == 0 -> {
                        retireLocked(active)
                        val installed = PoolState(key = key, payload = createPayload())
                        current = installed
                        granted = grantLocked(installed)
                    }

                    else -> waitForIdle = active.idleSignal
                }
            }
            granted?.let { return it }
            checkNotNull(waitForIdle).await()
        }
    }

    fun release(lease: Lease<T>) {
        synchronized(lock) {
            check(!lease.released) { "Frozen ASR pool lease was already released" }
            val state = lease.state
            check(state.activeLeases > 0) { "Frozen ASR pool has no active lease" }
            lease.released = true
            state.activeLeases -= 1
            if (state.activeLeases == 0) {
                state.idleSignal.complete(Unit)
                if (state.retireWhenIdle && current === state) {
                    retireLocked(state)
                }
            }
        }
    }

    /** Non-blocking release request: active native resources retire only after their final lease. */
    fun retireCurrent() {
        synchronized(lock) {
            current?.let { state ->
                state.retireWhenIdle = true
                if (state.activeLeases == 0) retireLocked(state)
            }
        }
    }

    private fun grantLocked(state: PoolState<T>): Lease<T> {
        if (state.activeLeases == 0) state.idleSignal = CompletableDeferred()
        state.activeLeases += 1
        return Lease(state)
    }

    private fun retireLocked(state: PoolState<T>) {
        check(state.activeLeases == 0) { "Cannot retire an active frozen ASR pool" }
        releasePayload(state.payload)
        if (current === state) current = null
        state.idleSignal.complete(Unit)
    }
}

/**
 * 本地 ASR 引擎：sherpa-onnx + SenseVoiceSmall int8。
 *
 * 与 sherpa-onnx 类的耦合走反射，目的是让 sherpa-onnx 的 AAR 不在 app/libs/ 时，整个 app 仍能编译运行；
 * 只有"用户实际选了本地引擎并触发转写"那一刻才会感知到反射失败，由 UI 引导其放置 AAR。
 *
 * 并发：同一冻结配置下 SenseVoice 最多三路、Qwen 固定一路；配置切换等待旧识别全部结束。
 */
@Singleton
class LocalAsrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: AsrModelManager,
    private val appPreferences: AppPreferences,
    private val logger: Logger
) : AsrEngine {

    override val engineType: AsrEngineType
        get() = if (modelManager.selectedModel().id == AsrModelUrls.QWEN3_ASR_ID) {
            AsrEngineType.LOCAL_QWEN3_ASR
        } else {
            AsrEngineType.LOCAL_SENSE_VOICE
        }

    private class BridgeSlot(
        val bridge: SherpaBridge,
        var inUse: Boolean
    )

    private class BridgePool(
        val config: LocalAsrSessionConfig,
        val concurrency: Int
    ) {
        val semaphore = Semaphore(concurrency)
        val bridges = mutableListOf<BridgeSlot>()
    }

    private val bridgeLock = Any()
    private val poolCoordinator = FrozenAsrPoolCoordinator<BridgePool>(::releaseBridgePool)

    override suspend fun isReady(): Boolean = modelReady() && nativeLibReady()

    suspend fun isReady(config: LocalAsrSessionConfig): Boolean =
        modelReady(config) && nativeLibReady()

    fun modelReady(): Boolean = modelManager.isModelReady()

    fun modelReady(config: LocalAsrSessionConfig): Boolean =
        modelManager.isModelReady(config.modelId)

    fun nativeLibReady(): Boolean = nativeLibAvailable()

    override suspend fun transcribe(
        wav: File,
        onProgress: suspend (Float) -> Unit
    ): Result<AsrResult> {
        val config = snapshotLegacyConfig()
        return transcribe(wav, config, onProgress)
    }

    suspend fun transcribe(
        wav: File,
        config: LocalAsrSessionConfig,
        onProgress: suspend (Float) -> Unit = {}
    ): Result<AsrResult> {
        return withContext(Dispatchers.IO) {
            runCatching {
                if (!modelManager.isModelReady(config.modelId)) {
                    throw LocalAsrError.ModelMissing("${config.modelId} 模型未下载或文件不完整")
                }
                val allowedConcurrency = if (config.engineType == AsrEngineType.LOCAL_QWEN3_ASR) {
                    1
                } else {
                    config.recognizerConcurrency
                }
                val lease = poolCoordinator.acquire(
                    key = FrozenAsrPoolCoordinator.Key(
                        configFingerprint = config.configFingerprint,
                        concurrency = allowedConcurrency
                    )
                ) {
                    BridgePool(config = config, concurrency = allowedConcurrency)
                }
                try {
                    onProgress(0.1f)
                    val samples = WavReader.readMono16kPcm(wav)
                    onProgress(0.3f)
                    val text = lease.payload.semaphore.withPermit {
                        val slot = checkoutBridge(lease.payload)
                        try {
                            slot.bridge.decode(samples)
                        } finally {
                            checkinBridge(slot)
                        }
                    }
                    onProgress(1f)
                    AsrResult(text = text, durationMs = (samples.size * 1000L / WavReader.TARGET_SR))
                } finally {
                    poolCoordinator.release(lease)
                }
            }.onFailure {
                if (it is CancellationException) throw it
                logger.e(
                    "LocalAsr",
                    "transcribe failed type=${it.javaClass.simpleName} " +
                        "fingerprint=${config.configFingerprint.take(FINGERPRINT_LOG_PREFIX_LENGTH)}",
                    it
                )
            }
        }
    }

    override fun release() {
        poolCoordinator.retireCurrent()
    }

    /**
     * Checks out one recognizer exclusively. The per-pool semaphore guarantees that either an
     * idle bridge exists or the pool still has room to create one.
     */
    private fun checkoutBridge(pool: BridgePool): BridgeSlot = synchronized(bridgeLock) {
        pool.bridges.firstOrNull { !it.inUse }?.let { available ->
            available.inUse = true
            return@synchronized available
        }
        check(pool.bridges.size < pool.concurrency) {
            "No local ASR bridge is available despite holding a pool permit"
        }
        val modelDir = modelManager.modelDirFor(pool.config.modelId)
        val created = try {
            SherpaBridge.create(
                modelDir = modelDir,
                config = pool.config,
                numThreads = pool.concurrency,
                logger = logger
            )
        } catch (e: ClassNotFoundException) {
            throw LocalAsrError.NativeLibMissing(e)
        } catch (e: NoClassDefFoundError) {
            throw LocalAsrError.NativeLibMissing(RuntimeException(e))
        } catch (e: Throwable) {
            throw LocalAsrError.DecodeFailed("初始化 sherpa-onnx 失败：${e.message}", e)
        }
        BridgeSlot(bridge = created, inUse = true).also { slot ->
            pool.bridges.add(slot)
        }
    }

    private fun checkinBridge(slot: BridgeSlot) = synchronized(bridgeLock) {
        check(slot.inUse) { "Local ASR bridge was already checked in" }
        slot.inUse = false
    }

    private fun releaseBridgePool(pool: BridgePool) = synchronized(bridgeLock) {
        check(pool.bridges.none(BridgeSlot::inUse)) {
            "Cannot release a local ASR bridge that is still decoding"
        }
        pool.bridges.forEach { slot -> runCatching { slot.bridge.release() } }
        pool.bridges.clear()
    }

    private suspend fun snapshotLegacyConfig(): LocalAsrSessionConfig {
        val snapshot = combine(
            combine(
            appPreferences.asrEngineType,
            appPreferences.asrLocalModelId,
            appPreferences.asrLanguageMode,
            appPreferences.asrManualLanguage,
            appPreferences.asrSenseVoiceUseItn
        ) { engineType, modelId, languageMode, manualLanguage, useItn ->
            AsrAttemptPreferenceSnapshot(
                engineType = AsrEngineType.parse(engineType),
                requestedModelId = modelId,
                languageMode = languageMode,
                manualLanguage = manualLanguage,
                senseVoiceUseItn = useItn
            )
            },
            appPreferences.asrLocalConcurrency
        ) { preferences, concurrency ->
            preferences.copy(localConcurrency = concurrency.coerceIn(1, 3))
        }.first()
        return AsrAttemptConfigResolver.resolve(
            preferenceSnapshot = snapshot,
            vadPresetVersion = LEGACY_VAD_PRESET_VERSION,
            systemLocale = Locale.getDefault()
        )
    }

    private fun nativeLibAvailable(): Boolean = runCatching {
        Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
        true
    }.getOrDefault(false)

    private companion object {
        const val LEGACY_VAD_PRESET_VERSION = "legacy-unspecified"
        const val FINGERPRINT_LOG_PREFIX_LENGTH = 12
    }
}
