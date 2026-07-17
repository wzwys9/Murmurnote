package app.murmurnote.android.ui.component

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class FirstLineCheckboxAlignmentTest {

    @Test
    fun bodyLargeTextAlignsTheCheckboxWithItsFirstLine() {
        val offsets = calculateFirstLineCheckboxOffsets(
            controlHeight = 48.dp,
            firstLineHeight = 24.dp,
        )

        assertEquals(0.dp, offsets.controlTopPadding)
        assertEquals(12.dp, offsets.contentTopPadding)
        assertEquals(
            offsets.controlTopPadding + 24.dp,
            offsets.contentTopPadding + 12.dp,
        )
    }

    @Test
    fun bodyMediumTextAlignsTheCheckboxWithItsFirstLine() {
        val offsets = calculateFirstLineCheckboxOffsets(
            controlHeight = 48.dp,
            firstLineHeight = 20.dp,
        )

        assertEquals(0.dp, offsets.controlTopPadding)
        assertEquals(14.dp, offsets.contentTopPadding)
        assertEquals(
            offsets.controlTopPadding + 24.dp,
            offsets.contentTopPadding + 10.dp,
        )
    }

    @Test
    fun largeAccessibilityTextMovesTheCheckboxDownToTheFirstLine() {
        val offsets = calculateFirstLineCheckboxOffsets(
            controlHeight = 48.dp,
            firstLineHeight = 60.dp,
        )

        assertEquals(6.dp, offsets.controlTopPadding)
        assertEquals(0.dp, offsets.contentTopPadding)
        assertEquals(
            offsets.controlTopPadding + 24.dp,
            offsets.contentTopPadding + 30.dp,
        )
    }
}
