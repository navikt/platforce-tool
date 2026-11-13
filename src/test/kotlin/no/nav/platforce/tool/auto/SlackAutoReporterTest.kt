package no.nav.platforce.tool.auto

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class SlackAutoReporterTest {
    private val webhook = "https://hooks.slack.com/services/test/report/synthetic"
    private val gui = "https://platforce-tool.intern.nav.no/internal/gui/"

    private fun client(reply: (JsonObject) -> Pair<Int, String>) =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                assertEquals(webhook, request.url.toString())
                assertEquals("POST", request.method)
                assertEquals("application/json; charset=utf-8", request.body!!.contentType().toString())
                val buffer = Buffer()
                request.body!!.writeTo(buffer)
                val (code, body) = reply(JsonParser.parseString(buffer.readUtf8()).asJsonObject)
                Response
                    .Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("Synthetic Slack response")
                    .body(body.toResponseBody("text/plain".toMediaType()))
                    .build()
            }.build()

    @Test
    fun `vulnerability warning contains a concise call to action and GUI button without process logs`() {
        var calls = 0
        val reporter =
            SlackAutoReporter(
                client { payload ->
                    calls++
                    val attachment = payload.getAsJsonArray("attachments").single().asJsonObject
                    assertEquals("#d99a00", attachment["color"].asString)
                    val blocks = attachment.getAsJsonArray("blocks")
                    assertEquals(3, blocks.size())
                    assertEquals("plain_text", blocks[0].asJsonObject["text"].asJsonObject["type"].asString)
                    assertEquals(
                        ":warning: Auto paused: vulnerabilities found",
                        blocks[0].asJsonObject["text"].asJsonObject["text"].asString,
                    )
                    val buttons = blocks[2].asJsonObject.getAsJsonArray("elements")
                    assertEquals(1, buttons.size())
                    assertEquals("Review vulnerabilities", buttons[0].asJsonObject["text"].asJsonObject["text"].asString)
                    assertEquals(gui, buttons[0].asJsonObject["url"].asString)
                    assertFalse(payload["unfurl_links"].asBoolean)
                    assertFalse(payload.has("username"))
                    assertFalse(payload.has("icon_url"))
                    assertFalse(payload.has("channel"))
                    assertTrue(payload["text"].asString.contains(gui))
                    200 to "ok"
                },
                webhook,
                gui,
            )
        reporter.report(AutoRunNotification(vulnerabilitiesFound = true))
        assertEquals(1, calls)
    }

    @Test
    fun `completed report presents PR URLs as buttons with counts and a GUI link`() {
        val url = "https://github.com/example/library/pull/42"
        val reporter =
            SlackAutoReporter(
                client { payload ->
                    val attachment = payload.getAsJsonArray("attachments").single().asJsonObject
                    assertEquals("#2e7d32", attachment["color"].asString)
                    val blocks = attachment.getAsJsonArray("blocks")
                    val buttons = blocks[2].asJsonObject.getAsJsonArray("elements")
                    assertEquals(1, buttons.size())
                    assertEquals("library #42", buttons[0].asJsonObject["text"].asJsonObject["text"].asString)
                    assertEquals(url, buttons[0].asJsonObject["url"].asString)
                    assertEquals(
                        gui,
                        blocks[3]
                            .asJsonObject
                            .getAsJsonArray("elements")[0]
                            .asJsonObject["url"]
                            .asString,
                    )
                    assertTrue(payload["text"].asString.contains("1 pull request is ready"))
                    200 to "ok"
                },
                webhook,
                gui,
            )
        reporter.report(AutoRunNotification(pullRequests = listOf(url, url)))
    }

    @Test
    fun `every PR gets a button within Slack action and message limits including boundary sizes`() {
        listOf(5, 6, 25, 26, 100, 1175, 1176).forEach { count ->
            val urls = (1..count).map { "https://github.com/example/${"a".repeat(90)}/pull/$it" }
            val reported = mutableListOf<String>()
            var messages = 0
            val reporter =
                SlackAutoReporter(
                    client { payload ->
                        messages++
                        val blocks =
                            payload
                                .getAsJsonArray("attachments")
                                .single()
                                .asJsonObject
                                .getAsJsonArray("blocks")
                        assertTrue(blocks.size() <= 50)
                        val actions = blocks.filter { it.asJsonObject["type"].asString == "actions" }
                        actions.forEach { action ->
                            val buttons = action.asJsonObject.getAsJsonArray("elements")
                            assertTrue(buttons.size() in 1..25)
                            buttons.forEach {
                                val button = it.asJsonObject
                                assertTrue(button["text"].asJsonObject["text"].asString.length <= 75)
                                if (button["url"].asString != gui) reported += button["url"].asString
                            }
                        }
                        assertEquals(
                            gui,
                            actions
                                .last()
                                .asJsonObject
                                .getAsJsonArray("elements")[0]
                                .asJsonObject["url"]
                                .asString,
                        )
                        assertTrue(payload["text"].asString.contains("$count pull requests are ready"))
                        assertFalse(payload["text"].asString.contains("Showing 5"))
                        if (count > 1175) {
                            assertTrue(
                                blocks[0]
                                    .asJsonObject["text"]
                                    .asJsonObject["text"]
                                    .asString
                                    .endsWith("($messages/2)"),
                            )
                        }
                        200 to "ok"
                    },
                    webhook,
                    gui,
                )
            reporter.report(AutoRunNotification(pullRequests = urls))
            assertEquals(urls, reported)
            assertEquals(if (count > 1175) 2 else 1, messages)
        }
    }

    @Test
    fun `empty runs do not post and HTTP rejection or unexpected Slack responses fail explicitly without secrets`() {
        val quiet = SlackAutoReporter(client { error("No request expected") }, webhook, gui)
        quiet.report(AutoRunNotification())
        listOf(429 to "rate_limited", 500 to webhook, 302 to "redirect", 200 to "invalid_payload").forEach { reply ->
            var calls = 0
            val reporter =
                SlackAutoReporter(
                    client {
                        calls++
                        reply
                    },
                    webhook,
                    gui,
                )
            val failure =
                assertThrows(IllegalStateException::class.java) {
                    reporter.report(AutoRunNotification(vulnerabilitiesFound = true))
                }
            assertEquals(1, calls)
            assertFalse(failure.message!!.contains(webhook))
        }
        val unavailable = SlackAutoReporter(client { throw IOException("Connection failed") }, webhook, gui)
        assertThrows(IOException::class.java) { unavailable.report(AutoRunNotification(vulnerabilitiesFound = true)) }
    }

    @Test
    fun `invalid webhook and PR URLs are rejected without exposing the webhook`() {
        val http = client { error("No request expected") }
        listOf("not a webhook", "http://hooks.slack.com/services/private", "https://example.com/services/private").forEach { endpoint ->
            val failure = assertThrows(IllegalArgumentException::class.java) { SlackAutoReporter(http, endpoint, gui) }
            assertEquals("WEBHOOK_REPORTS must be a Slack incoming webhook URL", failure.message)
        }
        val reporter = SlackAutoReporter(http, webhook, gui)
        assertThrows(IllegalStateException::class.java) {
            reporter.report(AutoRunNotification(pullRequests = listOf("https://example.com/pull/1")))
        }
    }
}
