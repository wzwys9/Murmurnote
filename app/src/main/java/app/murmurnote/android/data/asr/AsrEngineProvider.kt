package app.murmurnote.android.data.asr

import app.murmurnote.android.data.preference.AppPreferences
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * 按用户偏好返回当前要用的 AsrEngine。每次 transcribe 前调一次 current()，
 * 不缓存切换状态 —— 让用户在设置里改完引擎类型立刻生效。
 *
 * Local 引擎用 javax.inject.Provider 延迟注入，避免应用启动就构造 ONNX runtime（200MB+ 内存）。
 */
@Singleton
class AsrEngineProvider @Inject constructor(
    private val appPreferences: AppPreferences,
    private val cloud: CloudAsrEngine,
    private val localProvider: Provider<LocalAsrEngine>
) {

    /**
     * 返回当前选中的引擎；如果选了本地但未就绪，回 NotReady 让 Pipeline 抛友好错误，
     * 由 UI 引导用户回设置页下载/切换云端。
     *
     * 本地"未就绪"区分两种：
     *   - 模型文件没下：让用户去设置页下
     *   - 模型文件 OK 但 sherpa-onnx 原生库 AAR 缺失：开发者需要在构建时放进 app/libs/
     */
    suspend fun current(): Selection {
        val type = AsrEngineType.parse(appPreferences.asrEngineType.first())
        return when (type) {
            AsrEngineType.CLOUD_GLM -> {
                if (cloud.isReady()) Selection.Active(cloud)
                else Selection.NotReady(type, "云端引擎未就绪：请在设置页填写智谱 GLM API Key")
            }
            AsrEngineType.LOCAL_FIRE_RED_ASR -> {
                val local = localProvider.get()
                val modelOk = local.modelReady()
                val nativeOk = local.nativeLibReady()
                when {
                    modelOk && nativeOk -> Selection.Active(local)
                    !nativeOk -> Selection.NotReady(
                        type,
                        "sherpa-onnx 原生库未集成：本 APK 编译时未在 app/libs/ 放置 sherpa-onnx AAR。请联系开发者重新构建，或临时在设置页切换为云端引擎。"
                    )
                    else -> Selection.NotReady(
                        type,
                        "本地模型未就绪，请到设置页下载，或临时切换到云端"
                    )
                }
            }
        }
    }

    sealed class Selection {
        data class Active(val engine: AsrEngine) : Selection()
        data class NotReady(val type: AsrEngineType, val reason: String) : Selection()
    }
}
