package app.murmurnote.android.data.repository

import app.murmurnote.android.data.preference.AppPreferences
import app.murmurnote.android.data.remote.llm.LlmClient
import app.murmurnote.android.domain.correction.ContextualCorrectionRuntimePolicy
import app.murmurnote.android.domain.correction.PersonalLearningReviewRequest
import app.murmurnote.android.domain.correction.PinyinRelationClassifier
import app.murmurnote.android.util.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class PersonalCorrectionService @Inject constructor(
    private val repository: PersonalCorrectionRepository,
    private val llmClient: LlmClient,
    private val pinyinTranscriber: AndroidPinyinTranscriber,
    private val appPreferences: AppPreferences,
    private val logger: Logger,
) {
    private val reviewMutex = Mutex()

    suspend fun reviewPendingIfEnabled(): Int = reviewMutex.withLock {
        reviewPendingBatchIfEnabled()
    }

    private suspend fun reviewPendingBatchIfEnabled(): Int {
        return try {
            if (!runtimePolicy().canReviewLearning) return 0
            var completed = 0
            repository.getPendingObservations(MAX_PENDING_REVIEWS).forEach { observation ->
                if (!runtimePolicy().canReviewLearning) return completed
                val observedSyllables = pinyinTranscriber.syllables(observation.observedText)
                val replacementSyllables = pinyinTranscriber.syllables(observation.replacementText)
                val relation = PinyinRelationClassifier.classify(
                    observedSyllables,
                    replacementSyllables,
                )
                val decision = llmClient.reviewPersonalLearning(
                    PersonalLearningReviewRequest(
                        observationId = observation.eventId,
                        observedText = observation.observedText,
                        replacementText = observation.replacementText,
                        leftContext = observation.leftContext,
                        rightContext = observation.rightContext,
                        pinyinRelation = relation,
                    ),
                ).getOrNull() ?: return@forEach
                if (!runtimePolicy().canReviewLearning) return completed
                if (
                    repository.completeReview(
                        observationId = observation.eventId,
                        observedPinyin = observedSyllables?.joinToString(" "),
                        replacementPinyin = replacementSyllables?.joinToString(" "),
                        pinyinRelation = relation,
                        decision = decision,
                    )
                ) {
                    completed++
                }
            }
            completed
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.w(
                "Correction",
                "personal learning deferred type=${error.javaClass.simpleName}",
            )
            0
        }
    }

    suspend fun correctRecordingIfEnabled(recordingId: String): Boolean {
        return try {
            var policy = runtimePolicy()
            if (!policy.canReviewCandidates) return false
            if (policy.canReviewLearning) reviewPendingIfEnabled()
            policy = runtimePolicy()
            if (!policy.canReviewCandidates) return false
            val snapshot = repository.prepareSnapshot(
                recordingId = recordingId,
                includeUserDefinedRules = policy.includeUserDefinedRules,
                includePersonalLearningRules = policy.includePersonalLearningRules,
            )
            if (snapshot.candidates.isEmpty()) return false
            val approved = llmClient.reviewPersonalCorrectionCandidates(snapshot.candidates)
                .getOrNull() ?: return false
            policy = runtimePolicy()
            val stillEnabled = approved.filter { candidate ->
                policy.includes(candidate.ruleOrigin)
            }
            if (stillEnabled.isEmpty()) return false
            repository.applyApproved(snapshot, stillEnabled)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.w(
                "Correction",
                "personal correction skipped type=${error.javaClass.simpleName}",
            )
            false
        }
    }

    private suspend fun runtimePolicy(): ContextualCorrectionRuntimePolicy =
        ContextualCorrectionRuntimePolicy.resolve(
            customDictionaryEnabled = appPreferences.safeLexiconEnabled.first(),
            personalLearningEnabled = appPreferences.personalCorrectionEnabled.first(),
            llmConfigured = appPreferences.llmApiKey.first().isNotBlank(),
        )

    private companion object {
        const val MAX_PENDING_REVIEWS = 3
    }
}
