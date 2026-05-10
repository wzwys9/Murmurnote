package app.murmurnote.android.audio

import java.io.File
import java.util.Properties

internal object RecordingSegmentManifest {
    private const val VERSION = "1"
    private const val FILE_NAME = "recording_segments.properties"

    data class Segment(
        val sequence: Int,
        val file: File,
        val startMs: Long,
        val endMs: Long
    )

    data class Manifest(
        val finalFile: File,
        val sampleRateHz: Int,
        val bitsPerSample: Int,
        val segmentTargetMs: Long,
        val segments: List<Segment>
    )

    fun segmentDirFor(finalFile: File): File =
        File(finalFile.parentFile, "${finalFile.nameWithoutExtension}_segments")

    fun manifestFileFor(finalFile: File): File =
        File(segmentDirFor(finalFile), FILE_NAME)

    fun write(
        finalFile: File,
        sampleRateHz: Int,
        bitsPerSample: Int,
        segmentTargetMs: Long,
        segments: List<Segment>
    ) {
        if (segments.isEmpty()) return
        val metaFile = manifestFileFor(finalFile)
        metaFile.parentFile?.mkdirs()
        Properties().apply {
            setProperty("version", VERSION)
            setProperty("finalFilePath", finalFile.absolutePath)
            setProperty("sampleRateHz", sampleRateHz.toString())
            setProperty("bitsPerSample", bitsPerSample.toString())
            setProperty("segmentTargetMs", segmentTargetMs.toString())
            setProperty("count", segments.size.toString())
            segments.forEach { segment ->
                val prefix = "segment.${segment.sequence}."
                setProperty("${prefix}fileName", segment.file.name)
                setProperty("${prefix}fileLength", segment.file.length().toString())
                setProperty("${prefix}startMs", segment.startMs.toString())
                setProperty("${prefix}endMs", segment.endMs.toString())
            }
        }.store(metaFile.outputStream(), "Murmurnote rolling recording segments")
    }

    fun read(finalFile: File): Manifest? =
        runCatching {
            val metaFile = manifestFileFor(finalFile)
            if (!metaFile.exists()) return null
            val props = Properties().apply {
                metaFile.inputStream().use { load(it) }
            }
            if (props.getProperty("version") != VERSION) return null
            if (props.getProperty("finalFilePath") != finalFile.absolutePath) return null
            val sampleRateHz = props.getProperty("sampleRateHz")?.toIntOrNull() ?: return null
            val bitsPerSample = props.getProperty("bitsPerSample")?.toIntOrNull() ?: return null
            val segmentTargetMs = props.getProperty("segmentTargetMs")?.toLongOrNull() ?: return null
            val count = props.getProperty("count")?.toIntOrNull() ?: return null
            if (count <= 0) return null
            val dir = metaFile.parentFile ?: return null
            val segments = (0 until count).map { index ->
                val prefix = "segment.$index."
                val name = props.getProperty("${prefix}fileName") ?: return null
                val expectedLength = props.getProperty("${prefix}fileLength")?.toLongOrNull() ?: return null
                val startMs = props.getProperty("${prefix}startMs")?.toLongOrNull() ?: return null
                val endMs = props.getProperty("${prefix}endMs")?.toLongOrNull() ?: return null
                val file = File(dir, name)
                if (!file.exists() || file.length() <= 0L || file.length() != expectedLength) return null
                if (endMs <= startMs) return null
                Segment(sequence = index, file = file, startMs = startMs, endMs = endMs)
            }
            Manifest(
                finalFile = finalFile,
                sampleRateHz = sampleRateHz,
                bitsPerSample = bitsPerSample,
                segmentTargetMs = segmentTargetMs,
                segments = segments
            )
        }.getOrNull()
}
