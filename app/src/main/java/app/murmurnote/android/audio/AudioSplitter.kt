package app.murmurnote.android.audio

import app.murmurnote.android.util.Logger
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 静音感知切片，单段 ≤ MAX_SEGMENT_MS（NOTES 第一节：GLM-ASR 单段硬上限 30s）。
 * 策略：
 *   1) 跑 ffmpeg silencedetect 取静音区间
 *   2) 每段不超过 MAX_SEGMENT_MS，优先在窗口（MIN_SEGMENT_MS, MAX_SEGMENT_MS）内的静音点切
 *   3) 没找到合适静音点时硬切到 MAX_SEGMENT_MS
 */
@Singleton
class AudioSplitter @Inject constructor(
    private val vadDetector: VadDetector,
    private val logger: Logger
) {

    companion object {
        const val MAX_SEGMENT_MS: Long = 25_000L
        const val MIN_SEGMENT_MS: Long = 10_000L
        // 找不到首选窗口 [MIN, MAX] 内的静音时，把窗口左侧放宽到 8s 再试一次。
        const val RELAXED_MIN_SEGMENT_MS: Long = 8_000L
        // 没找到任何静音可切时硬切的位置：留 200ms 缓冲，规避偶发 ffmpeg/编码 rounding 把段长推到 25s 上沿。
        const val HARD_CUT_GUARD_MS: Long = 200L
        const val SILENCE_NOISE_DB: Int = -35
        const val SILENCE_DURATION_S: Double = 0.3
        // 嘈杂环境下 -30dB / 0.4s 可能一段静音都识别不出，整段全部 hard-cut。
        // 长录音首检过稀时用更宽松的阈值再扫一遍。
        const val SILENCE_NOISE_DB_RELAXED: Int = -25
        const val SILENCE_DURATION_S_RELAXED: Double = 0.3
    }

    /** 单段切片的元信息：[startMs, endMs) 是该段在原录音里的真实位置。 */
    data class Slice(val file: File, val startMs: Long, val endMs: Long)

    suspend fun split(input: File, outputDir: File): List<Slice> {
        outputDir.mkdirs()
        val durationMs = probeDurationMs(input)
        if (durationMs <= 0) {
            logger.e("Split", "zero duration ${input.name} size=${input.length()}")
            error("AudioSplitter: zero duration for ${input.name}")
        }
        if (durationMs <= MAX_SEGMENT_MS) {
            val out = File(outputDir, "${input.nameWithoutExtension}_seg00.wav")
            input.copyTo(out, overwrite = true)
            logger.i("Split", "${input.name} ≤ ${MAX_SEGMENT_MS}ms → single segment ${out.name}")
            return listOf(Slice(out, 0L, durationMs))
        }

        val silences = detectSilences(input, durationMs)
        val cuts = planCuts(durationMs, silences)
        // 区分 silence-cut 与 hard-cut（命中 [MAX-2, MAX] 末尾窗的就是被迫硬切）能让 NOTES 的"为什么这一段断在奇怪的地方"
        // 问题不再凭空猜测——日志里直接看得到。
        val hardCutCount = cuts.dropLast(1).withIndex().count { (i, end) ->
            val start = if (i == 0) 0L else cuts[i - 1]
            (end - start) >= MAX_SEGMENT_MS - HARD_CUT_GUARD_MS - 50
        }
        logger.i(
            "Split",
            "${input.name} dur=${durationMs}ms silences=${silences.size} cuts=${cuts.size} hardCuts=$hardCutCount"
        )
        return sliceByCuts(input, outputDir, cuts)
    }

    private fun probeDurationMs(file: File): Long {
        val session = FFprobeKit.getMediaInformation(file.absolutePath)
        val durSec = session.mediaInformation?.duration?.toDoubleOrNull() ?: 0.0
        return (durSec * 1000).toLong()
    }

    private suspend fun detectSilences(file: File, durationMs: Long): List<SilenceRange> {
        val ffmpegPrimary = runSilenceDetect(file, SILENCE_NOISE_DB, SILENCE_DURATION_S)
        // Run VAD in parallel to catch pauses that fixed-dB threshold misses.
        // coroutineScope ensures both complete before we merge.
        val vadSilences = withContext(Dispatchers.IO) {
            runCatching { vadDetector.detect(file) }.getOrElse { e ->
                logger.w("Split", "VAD failed: ${e.message}"); emptyList()
            }
        }

        // Merge: union of ffmpeg + VAD silences (de-duplicate overlapping ranges)
        val merged = mergeSilenceLists(ffmpegPrimary, vadSilences)

        val expectedCuts = ((durationMs - 1) / MAX_SEGMENT_MS).coerceAtLeast(0)
        if (durationMs > 60_000 && merged.size < expectedCuts) {
            val relaxed = runSilenceDetect(file, SILENCE_NOISE_DB_RELAXED, SILENCE_DURATION_S_RELAXED)
            val mergedRelaxed = mergeSilenceLists(relaxed, vadSilences)
            if (mergedRelaxed.size > merged.size) {
                logger.i(
                    "Split",
                    "relaxed pass picked: primary=${merged.size} relaxed=${mergedRelaxed.size} " +
                        "(ffmpeg=${ffmpegPrimary.size} vad=${vadSilences.size})"
                )
                return mergedRelaxed
            }
        }
        logger.i("Split", "detectSilences: ffmpeg=${ffmpegPrimary.size} vad=${vadSilences.size} merged=${merged.size}")
        return merged
    }

    /** Union of two silence lists: overlapping ranges are merged, sorted by startMs. */
    private fun mergeSilenceLists(a: List<SilenceRange>, b: List<SilenceRange>): List<SilenceRange> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val all = (a + b).sortedBy { it.startMs }
        val result = mutableListOf<SilenceRange>()
        var cur = all[0]
        for (i in 1 until all.size) {
            val next = all[i]
            if (next.startMs <= cur.endMs) {
                cur = SilenceRange(startMs = cur.startMs, endMs = maxOf(cur.endMs, next.endMs))
            } else {
                result.add(cur)
                cur = next
            }
        }
        result.add(cur)
        return result
    }

    private suspend fun runSilenceDetect(file: File, noiseDb: Int, durationS: Double): List<SilenceRange> {
        val cmd = "-i ${quote(file.absolutePath)} -af silencedetect=noise=${noiseDb}dB:d=$durationS -f null -"
        val deferred = CompletableDeferred<FFmpegSession>()
        FFmpegKit.executeAsync(cmd) { session -> deferred.complete(session) }
        val session = deferred.await()
        if (!ReturnCode.isSuccess(session.returnCode)) {
            logger.w("Split", "silencedetect ffmpeg returned rc=${session.returnCode} — fallback to hard cuts")
            return emptyList()
        }

        val log = session.allLogsAsString.orEmpty()
        val starts = Regex("silence_start: ([0-9.]+)").findAll(log).map { it.groupValues[1].toDouble() }.toList()
        val ends = Regex("silence_end: ([0-9.]+)").findAll(log).map { it.groupValues[1].toDouble() }.toList()
        if (starts.size != ends.size) {
            // 文件以静音收尾时常见（最后一个 silence_start 没有配对的 silence_end），这一行让排查 NOTES 第一节
            // 那种"为什么没切在我说话停顿处"问题更直接。
            logger.d("Split", "silencedetect unmatched starts=${starts.size} ends=${ends.size}")
        }
        return starts.zip(ends).map { (s, e) ->
            SilenceRange(startMs = (s * 1000).toLong(), endMs = (e * 1000).toLong())
        }
    }

    /**
     * 选择切点策略：
     *  1) 先在 [seg+MIN, seg+MAX] 找与窗口"重叠最多"的静音（不再仅看 midpoint），切在静音被窗口截断后的中点。
     *     长停顿（说完一句话的换气）天然 overlap 最大，正好就是最可靠的切点。
     *  2) 首选窗口里没有任何重叠时，放宽到 [seg+RELAXED_MIN, seg+MAX] 再试一次——
     *     宁愿这一段短到 12s 也比硬切在词中间强。
     *  3) 还是没有，就硬切 MAX-HARD_CUT_GUARD（留 200ms 安全余量）。
     */
    fun planCuts(durationMs: Long, silences: List<SilenceRange>): List<Long> {
        val cuts = mutableListOf<Long>()
        var segStart = 0L
        while (durationMs - segStart > MAX_SEGMENT_MS) {
            val cut = pickCut(segStart, silences) ?: (segStart + MAX_SEGMENT_MS - HARD_CUT_GUARD_MS)
            cuts += cut
            segStart = cut
        }
        cuts += durationMs
        return cuts
    }

    private fun pickCut(segStart: Long, silences: List<SilenceRange>): Long? {
        val winMax = segStart + MAX_SEGMENT_MS
        val preferredMin = segStart + MIN_SEGMENT_MS
        val relaxedMin = segStart + RELAXED_MIN_SEGMENT_MS

        fun overlapMs(s: SilenceRange, lo: Long, hi: Long): Long {
            val a = maxOf(s.startMs, lo)
            val b = minOf(s.endMs, hi)
            return (b - a).coerceAtLeast(0L)
        }
        fun score(s: SilenceRange, lo: Long, hi: Long): Double {
            val overlap = overlapMs(s, lo, hi).toDouble()
            val duration = s.endMs - s.startMs
            val durationBonus = when {
                duration < 500 -> 0.5   // intra-sentence breath → penalty
                duration in 500..1500 -> 1.5  // inter-sentence pause → bonus
                else -> 1.0  // paragraph gap → neutral
            }
            val clampedMid = ((s.startMs.coerceAtLeast(lo) + s.endMs.coerceAtMost(hi)) / 2).toDouble()
            val center = (lo + hi) / 2.0
            val centerScore = 1.0 - (kotlin.math.abs(clampedMid - center) / ((hi - lo) / 2.0)).coerceIn(0.0, 1.0)
            return overlap * durationBonus * (0.5 + 0.5 * centerScore)
        }
        fun bestIn(lo: Long, hi: Long): SilenceRange? {
            val candidates = silences.filter { overlapMs(it, lo, hi) > 0 }
            if (candidates.isEmpty()) return null
            return candidates.maxBy { score(it, lo, hi) }
        }

        val (winLo, picked) = bestIn(preferredMin, winMax)?.let { preferredMin to it }
            ?: bestIn(relaxedMin, winMax)?.let { relaxedMin to it }
            ?: return null

        val clampedStart = picked.startMs.coerceAtLeast(winLo)
        val clampedEnd = picked.endMs.coerceAtMost(winMax)
        return ((clampedStart + clampedEnd) / 2).coerceIn(winLo + 1, winMax)
    }

    private suspend fun sliceByCuts(input: File, outputDir: File, cuts: List<Long>): List<Slice> {
        val results = mutableListOf<Slice>()
        var startMs = 0L
        cuts.forEachIndexed { idx, endMs ->
            val out = File(outputDir, "${input.nameWithoutExtension}_seg${idx.toString().padStart(2, '0')}.wav")
            if (out.exists()) out.delete()
            val ssSec = startMs / 1000.0
            val toSec = endMs / 1000.0
            val cmd = "-y -ss $ssSec -to $toSec -i ${quote(input.absolutePath)} -ac 1 -ar 16000 -c:a pcm_s16le -f wav ${quote(out.absolutePath)}"
            val deferred = CompletableDeferred<Boolean>()
            FFmpegKit.executeAsync(cmd) { session -> deferred.complete(ReturnCode.isSuccess(session.returnCode)) }
            val ok = deferred.await()
            if (!ok) {
                logger.e("Split", "slice failed seg=$idx [$startMs..$endMs] of ${input.name}")
                error("ffmpeg slice failed for segment $idx")
            }
            logger.d("Split", "seg${idx.toString().padStart(2, '0')} [${startMs}..${endMs}]ms → ${out.name} size=${out.length()}")
            results += Slice(out, startMs, endMs)
            startMs = endMs
        }
        return results
    }

    private fun quote(path: String): String = "'${path.replace("'", "'\\''")}'"

    data class SilenceRange(val startMs: Long, val endMs: Long) {
        fun midMs(): Long = (startMs + endMs) / 2
    }
}
