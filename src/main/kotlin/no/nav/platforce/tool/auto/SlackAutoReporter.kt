package no.nav.platforce.tool.auto

import com.google.gson.Gson
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration

data class AutoRunNotification(
    val vulnerabilitiesFound: Boolean = false,
    val pullRequests: List<String> = emptyList(),
)

class SlackAutoReporter(
    httpClient: OkHttpClient,
    webhook: String,
    private val guiUrl: String,
) {
    private val endpoint =
        requireNotNull(webhook.toHttpUrlOrNull()) { "WEBHOOK_REPORTS must be a Slack incoming webhook URL" }.also {
            require(it.scheme == "https" && it.host == "hooks.slack.com" && it.encodedPath.startsWith("/services/")) {
                "WEBHOOK_REPORTS must be a Slack incoming webhook URL"
            }
        }
    private val client =
        httpClient
            .newBuilder()
            .callTimeout(Duration.ofSeconds(10))
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

    fun report(notification: AutoRunNotification) {
        val urls = notification.pullRequests.distinct()
        if (!notification.vulnerabilitiesFound && urls.isEmpty()) return
        val warning = notification.vulnerabilitiesFound
        val title = if (warning) ":warning: Auto paused: vulnerabilities found" else ":white_check_mark: Auto PRs ready for review"
        val summary =
            if (warning) {
                "Review vulnerable targets in Platforce Tool, update Target Versions, then run Auto again."
            } else {
                "${urls.size} ${if (urls.size == 1) "pull request is" else "pull requests are"} ready. " +
                    "Review the changes and merge when checks pass."
            }
        val links =
            if (warning) {
                emptyList()
            } else {
                urls.map { url ->
                    val match =
                        checkNotNull(Regex("^https://github\\.com/([\\w.-]+)/([\\w.-]+)/pull/(\\d+)$").matchEntire(url)) {
                            "Auto report contains an invalid GitHub PR URL"
                        }
                    "<$url|${match.groupValues[2]} #${match.groupValues[3]}>"
                }
            }
        val pages = links.chunked(47).ifEmpty { listOf(emptyList()) }
        pages.forEachIndexed { index, page ->
            if (index > 0) Thread.sleep(1000)
            val pageTitle = title + if (pages.size > 1) " (${index + 1}/${pages.size})" else ""
            val blocks =
                listOf(
                    mapOf("type" to "header", "text" to plain(pageTitle)),
                    section(summary),
                ) + page.map(::section) +
                    section("<$guiUrl|${if (warning) "Review vulnerabilities" else "Open Platforce Tool"}>")
            val payload =
                mapOf(
                    "text" to "$pageTitle. $summary <$guiUrl|Open Platforce Tool>",
                    "unfurl_links" to false,
                    "unfurl_media" to false,
                    "attachments" to listOf(mapOf("color" to if (warning) "#d99a00" else "#2e7d32", "blocks" to blocks)),
                )
            val request =
                Request
                    .Builder()
                    .url(endpoint)
                    .post(Gson().toJson(payload).toRequestBody("application/json".toMediaType()))
                    .build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful && response.body.string().trim() == "ok") {
                    "Slack Auto report was not accepted (HTTP ${response.code}, message ${index + 1}/${pages.size})"
                }
            }
        }
    }

    private fun plain(text: String) = mapOf("type" to "plain_text", "text" to text, "emoji" to true)

    private fun section(text: String) = mapOf("type" to "section", "text" to mapOf("type" to "mrkdwn", "text" to text))
}
