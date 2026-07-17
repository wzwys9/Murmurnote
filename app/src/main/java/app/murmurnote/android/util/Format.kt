package app.murmurnote.android.util

import java.text.DateFormat
import java.util.Date
import java.util.Locale

fun formatDurationMs(ms: Long): String {
    val s = ms / 1000
    val hh = s / 3600
    val mm = (s % 3600) / 60
    val ss = s % 60
    return when {
        hh > 0 -> String.format(Locale.US, "%d:%02d:%02d", hh, mm, ss)
        mm > 0 -> String.format(Locale.US, "%d:%02d", mm, ss)
        else -> String.format(Locale.US, "0:%02d", ss)
    }
}

/** 录音时间点完整格式（年月日时分秒）。详情页 / 待办列表 / 搜索结果右上角小字用这个。 */
fun formatTimestampFull(
    epochMs: Long,
    locale: Locale = Locale.getDefault(),
): String = DateFormat.getDateTimeInstance(
    DateFormat.MEDIUM,
    DateFormat.MEDIUM,
    locale,
).format(Date(epochMs))
