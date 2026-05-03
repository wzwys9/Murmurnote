package app.murmurnote.android.domain.pipeline

sealed class PipelineStage {
    data object Idle : PipelineStage()
    data class Recording(val durationMs: Long) : PipelineStage()
    data class Converting(val progress: Float) : PipelineStage()
    data class Splitting(val segmentCount: Int) : PipelineStage()
    data class Transcribing(val segmentIndex: Int, val totalSegments: Int, val partialText: String) : PipelineStage()
    data class Extracting(val transcriptLength: Int) : PipelineStage()
    data class Saving(val recordingId: String) : PipelineStage()
    data class Completed(val recordingId: String) : PipelineStage()
    data class Failed(val stage: String, val errorMessage: String) : PipelineStage()
}
