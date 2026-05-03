package app.murmurnote.android.domain.usecase

import app.murmurnote.android.data.local.entity.RecordingSource
import app.murmurnote.android.domain.pipeline.AudioPipeline
import app.murmurnote.android.domain.pipeline.PipelineStage
import kotlinx.coroutines.flow.Flow
import java.io.File
import javax.inject.Inject

class ProcessRecordingUseCase @Inject constructor(
    private val pipeline: AudioPipeline
) {
    operator fun invoke(
        file: File,
        source: RecordingSource,
        existingRecordingId: String? = null
    ): Flow<PipelineStage> = pipeline.process(file, source, existingRecordingId)
}
