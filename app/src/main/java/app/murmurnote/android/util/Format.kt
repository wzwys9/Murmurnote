package app.murmurnote.android.util

import java.text.SimpleDateFormat
import java.util.Calendar
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
        else -> "${ss}秒"
    }
}

fun formatRelativeTime(timestampMs: Long, now: Long = System.currentTimeMillis()): String {
    val diff = now - timestampMs
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3600_000 -> "${diff / 60_000}分钟前"
        isSameDay(timestampMs, now) -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))
        diff < 7L * 24 * 3600 * 1000 -> "${diff / (24L * 3600 * 1000)}天前"
        else -> SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(timestampMs))
    }
}

private fun isSameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
        ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

/** 录音时间点完整格式（年月日时分秒）。详情页 / 待办列表 / 搜索结果右上角小字用这个。 */
fun formatTimestampFull(epochMs: Long): String =
    SimpleDateFormat("yyyy年MM月dd日 HH时mm分ss秒", Locale.US).format(Date(epochMs))
