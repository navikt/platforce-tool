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
        val buttons =
            if (warning) {
                emptyList()
            } else {
                urls.mapIndexed { index, url ->
                    val match =
                        checkNotNull(Regex("^https://github\\.com/([\\w.-]+)/([\\w.-]+)/pull/(\\d+)$").matchEntire(url)) {
                            "Auto report contains an invalid GitHub PR URL"
                        }
                    button("${match.groupValues[2].take(50)} #${match.groupValues[3]}", url, "pr_$index")
                }
            }
        val pages = buttons.chunked(25).chunked(47).ifEmpty { listOf(emptyList()) }
        pages.forEachIndexed { index, page ->
            if (index > 0) Thread.sleep(1000)
            val pageTitle = title + if (pages.size > 1) " (${index + 1}/${pages.size})" else ""
            val blocks =
                page.map { mapOf("type" to "actions", "elements" to it) } +
                    mapOf(
                        "type" to "actions",
                        "elements" to listOf(button(if (warning) "Review vulnerabilities" else "Open Platforce Tool", guiUrl, "open_gui")),
                    )
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

    private fun button(
        label: String,
        url: String,
        action: String,
    ) = mapOf("type" to "button", "text" to plain(label.take(75)), "url" to url, "action_id" to action)
}
