package app.murmurnote.android.data.remote.update

import app.murmurnote.android.util.Logger
import app.murmurnote.android.util.BoundedStreams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

@Singleton
class AppUpdateChecker @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val logger: Logger
) {
    suspend fun check(currentVersionName: String): AppUpdateResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()

        okHttpClient.newCall(request).also { call ->
            call.timeout().timeout(UPDATE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.execute().use { response ->
            if (!response.isSuccessful) {
                error("GitHub Release 检查失败：HTTP ${response.code}")
            }
            val body = response.body?.byteStream()?.use { input ->
                BoundedStreams.readUtf8(input, MAX_UPDATE_RESPONSE_BYTES)
            }.orEmpty()

            val release = json.decodeFromString(GitHubRelease.serializer(), body)
            val latestTag = release.tagName.ifBlank { release.name.orEmpty() }
            if (latestTag.isBlank()) error("GitHub Release 返回缺少版本号")

            logger.i("Update", "latest release checked current=$currentVersionName latest=$latestTag")
            if (isNewerVersion(latestTag, currentVersionName)) {
                AppUpdateResult.UpdateAvailable(
                    latestVersion = latestTag,
                    releaseUrl = release.htmlUrl
                )
            } else {
                AppUpdateResult.UpToDate(latestVersion = latestTag)
            }
        }
    }

    companion object {
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/wzwys9/Murmurnote/releases/latest"
        private const val UPDATE_CALL_TIMEOUT_SECONDS = 30L
        private const val MAX_UPDATE_RESPONSE_BYTES = 512L * 1024L

        fun isNewerVersion(candidate: String, current: String): Boolean {
            val next = candidate.normalizedVersionParts()
            val now = current.normalizedVersionParts()
            val max = maxOf(next.size, now.size)
            for (i in 0 until max) {
                val a = next.getOrElse(i) { 0 }
                val b = now.getOrElse(i) { 0 }
                if (a != b) return a > b
            }
            return false
        }

        private fun String.normalizedVersionParts(): List<Int> =
            trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore('-')
                .split('.')
                .mapNotNull { part -> part.takeWhile { it.isDigit() }.toIntOrNull() }
    }
}

sealed class AppUpdateResult {
    data class UpToDate(val latestVersion: String) : AppUpdateResult()
    data class UpdateAvailable(val latestVersion: String, val releaseUrl: String) : AppUpdateResult()
}

@Serializable
private data class GitHubRelease(
    @kotlinx.serialization.SerialName("tag_name")
    val tagName: String = "",
    @kotlinx.serialization.SerialName("html_url")
    val htmlUrl: String,
    val name: String? = null
)
