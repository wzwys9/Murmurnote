package app.murmurnote.android.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse

private val CHECKBOX_TOUCH_TARGET_SIZE = 48.dp

internal data class FirstLineCheckboxOffsets(
    val controlTopPadding: Dp,
    val contentTopPadding: Dp,
)

internal fun calculateFirstLineCheckboxOffsets(
    controlHeight: Dp,
    firstLineHeight: Dp,
): FirstLineCheckboxOffsets {
    val halfDifference = (controlHeight - firstLineHeight) / 2
    return if (halfDifference >= 0.dp) {
        FirstLineCheckboxOffsets(
            controlTopPadding = 0.dp,
            contentTopPadding = halfDifference,
        )
    } else {
        FirstLineCheckboxOffsets(
            controlTopPadding = -halfDifference,
            contentTopPadding = 0.dp,
        )
    }
}

@Composable
internal fun FirstLineAlignedCheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: String,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    textDecoration: TextDecoration? = null,
    horizontalSpacing: Dp = 0.dp,
    supportingContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val firstLineHeight = with(density) {
        textStyle.lineHeight.takeOrElse { textStyle.fontSize }.toDp()
    }
    val offsets = calculateFirstLineCheckboxOffsets(
        controlHeight = CHECKBOX_TOUCH_TARGET_SIZE,
        firstLineHeight = firstLineHeight,
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .padding(top = offsets.controlTopPadding)
                .size(CHECKBOX_TOUCH_TARGET_SIZE),
        )
        if (horizontalSpacing > 0.dp) {
            Spacer(Modifier.width(horizontalSpacing))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = offsets.contentTopPadding),
        ) {
            Text(
                text = text,
                style = textStyle,
                textDecoration = textDecoration,
            )
            supportingContent?.invoke(this)
        }
    }
}
