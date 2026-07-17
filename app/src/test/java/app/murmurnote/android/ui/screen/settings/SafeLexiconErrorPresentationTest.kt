package app.murmurnote.android.ui.screen.settings

import app.murmurnote.android.R
import app.murmurnote.android.domain.correction.ContextualCorrectionCapacityExceededException
import app.murmurnote.android.domain.correction.CorrectionRuleOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

class SafeLexiconErrorPresentationTest {

    @Test
    fun contextualCapacityErrorUsesTheSpecificLocalizedMessage() {
        val presentation = lexiconErrorPresentation(
            ContextualCorrectionCapacityExceededException(
                origin = CorrectionRuleOrigin.USER_DEFINED,
                maximum = 100,
            )
        )

        assertEquals(R.string.lexicon_contextual_capacity_reached, presentation.messageResource)
        assertEquals(listOf(100), presentation.formatArguments)
    }

    @Test
    fun unknownErrorUsesTheGenericLocalizedMessage() {
        val presentation = lexiconErrorPresentation(IllegalStateException("internal detail"))

        assertEquals(R.string.lexicon_operation_failed, presentation.messageResource)
        assertEquals(emptyList<Any>(), presentation.formatArguments)
    }
}
