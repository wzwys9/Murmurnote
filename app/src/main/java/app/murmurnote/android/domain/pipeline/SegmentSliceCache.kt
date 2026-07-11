package app.murmurnote.android.domain.pipeline

import app.murmurnote.android.audio.AudioSplitter
import app.murmurnote.android.audio.HardCutBoundaryProbePolicy
import app.murmurnote.android.audio.NeuralVadSegmentPlanner
import java.io.File
import java.util.Properties

internal object SegmentSliceCache {
    private const val VERSION = "2"

    fun read(metaFile: File, outputDir: File, input: File): List<AudioSplitter.Slice>? =
        runCatching {
            if (!metaFile.exists()) return null
            val props = Properties().apply {
                metaFile.inputStream().use { load(it) }
            }
            if (props.getProperty("version") != VERSION) return null
            if (props.getProperty("inputPath") != input.absolutePath) return null
            if (props.getProperty("inputLength") != input.length().toString()) return null
            if (props.getProperty("inputLastModified") != input.lastModified().toString()) return null
            if (
                props.getProperty("vadPresetVersion") !=
                HardCutBoundaryProbePolicy.canonicalVadVersion
            ) return null
            if (props.getProperty("maxSegmentMs") != AudioSplitter.MAX_SEGMENT_MS.toString()) return null

            val count = props.getProperty("count")?.toIntOrNull() ?: return null
            if (count < 0) return null
            (0 until count).map { index ->
                val prefix = "slice.$index."
                val name = props.getProperty("${prefix}fileName") ?: return null
                if (name.isBlank() || File(name).name != name) return null
                val file = File(outputDir, name)
                val expectedLength = props.getProperty("${prefix}fileLength")?.toLongOrNull() ?: return null
                val startMs = props.getProperty("${prefix}startMs")?.toLongOrNull() ?: return null
                val endMs = props.getProperty("${prefix}endMs")?.toLongOrNull() ?: return null
                val overlapBeforeMs =
                    props.getProperty("${prefix}overlapBeforeMs")?.toLongOrNull() ?: return null
                val cutReasonValue = props.getProperty("${prefix}cutReason") ?: return null
                val cutReason = when (cutReasonValue) {
                    NO_CUT_REASON -> null
                    else -> NeuralVadSegmentPlanner.CutReason.entries
                        .firstOrNull { it.name == cutReasonValue }
                        ?: return null
                }
                if (!file.exists() || file.length() <= 0L || file.length() != expectedLength) return null
                if (startMs < 0L || endMs <= startMs || endMs - startMs > AudioSplitter.MAX_SEGMENT_MS) {
                    return null
                }
                if (overlapBeforeMs < 0L ||
                    overlapBeforeMs > NeuralVadSegmentPlanner.PRESET.hardCutOverlapMs.toLong()
                ) return null
                AudioSplitter.Slice(
                    file = file,
                    startMs = startMs,
                    endMs = endMs,
                    cutReason = cutReason,
                    overlapBeforeMs = overlapBeforeMs
                )
            }
        }.getOrNull()

    fun write(metaFile: File, input: File, slices: List<AudioSplitter.Slice>) {
        metaFile.parentFile?.mkdirs()
        Properties().apply {
            setProperty("version", VERSION)
            setProperty("inputPath", input.absolutePath)
            setProperty("inputLength", input.length().toString())
            setProperty("inputLastModified", input.lastModified().toString())
            setProperty("vadPresetVersion", HardCutBoundaryProbePolicy.canonicalVadVersion)
            setProperty("maxSegmentMs", AudioSplitter.MAX_SEGMENT_MS.toString())
            setProperty("count", slices.size.toString())
            slices.forEachIndexed { index, slice ->
                val prefix = "slice.$index."
                setProperty("${prefix}fileName", slice.file.name)
                setProperty("${prefix}fileLength", slice.file.length().toString())
                setProperty("${prefix}startMs", slice.startMs.toString())
                setProperty("${prefix}endMs", slice.endMs.toString())
                setProperty("${prefix}cutReason", slice.cutReason?.name ?: NO_CUT_REASON)
                setProperty("${prefix}overlapBeforeMs", slice.overlapBeforeMs.toString())
            }
        }.store(metaFile.outputStream(), "Murmurnote segment slice cache")
    }

    private const val NO_CUT_REASON = "NONE"
}
