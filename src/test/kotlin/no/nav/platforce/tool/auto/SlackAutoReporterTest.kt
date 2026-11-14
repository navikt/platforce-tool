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
    fun `vulnerability warning contains a concise call to action and GUI button`() {
        var calls = 0
        val reporter =
            SlackAutoReporter(
                client { payload ->
                    calls++
                    val attachment = payload.getAsJsonArray("attachments").single().asJsonObject
                    assertEquals("#d99a00", attachment["color"].asString)
                    val blocks = attachment.getAsJsonArray("blocks")
                    assertEquals(1, blocks.size())
                    assertTrue(payload["text"].asString.startsWith(":warning: Auto paused: vulnerabilities found."))
                    assertTrue(blocks.all { it.asJsonObject["type"].asString == "actions" })
                    val buttons = blocks[0].asJsonObject.getAsJsonArray("elements")
                    assertEquals(1, buttons.size())
                    val button = buttons[0].asJsonObject
                    assertEquals("button", button["type"].asString)
                    assertEquals("Review vulnerabilities", button["text"].asJsonObject["text"].asString)
                    assertEquals(gui, button["url"].asString)
                    assertEquals("open_gui", button["action_id"].asString)
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
    fun `completed report presents PR URLs as buttons with counts and a GUI button`() {
        val url = "https://github.com/example/library/pull/42"
        val reporter =
            SlackAutoReporter(
                client { payload ->
                    val attachment = payload.getAsJsonArray("attachments").single().asJsonObject
                    assertEquals("#2e7d32", attachment["color"].asString)
                    val blocks = attachment.getAsJsonArray("blocks")
                    assertEquals(2, blocks.size())
                    assertTrue(payload["text"].asString.startsWith(":white_check_mark: Auto PRs ready for review."))
                    assertTrue(blocks.all { it.asJsonObject["type"].asString == "actions" })
                    assertFalse(attachment.toString().contains("Auto PRs ready for review"))
                    assertFalse(attachment.toString().contains("Review the changes"))
                    val buttons = blocks[0].asJsonObject.getAsJsonArray("elements")
                    assertEquals(1, buttons.size())
                    assertEquals("library #42", buttons[0].asJsonObject["text"].asJsonObject["text"].asString)
                    assertEquals(url, buttons[0].asJsonObject["url"].asString)
                    val guiButton = blocks[1].asJsonObject.getAsJsonArray("elements")[0].asJsonObject
                    assertEquals("Open Platforce Tool", guiButton["text"].asJsonObject["text"].asString)
                    assertEquals(gui, guiButton["url"].asString)
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
                        val actionIds = mutableSetOf<String>()
                        blocks.toList().dropLast(1).forEach { block ->
                            assertEquals("actions", block.asJsonObject["type"].asString)
                            val buttons = block.asJsonObject.getAsJsonArray("elements")
                            assertTrue(buttons.size() in 1..25)
                            buttons.forEach { element ->
                                val button = element.asJsonObject
                                assertEquals("button", button["type"].asString)
                                assertEquals("plain_text", button["text"].asJsonObject["type"].asString)
                                assertTrue(button["text"].asJsonObject["text"].asString.length <= 75)
                                assertTrue(actionIds.add(button["action_id"].asString))
                                reported += button["url"].asString
                            }
                        }
                        assertEquals(
                            gui,
                            blocks
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
                                payload["text"].asString.contains("Auto PRs ready for review ($messages/${(count + 1174) / 1175})."),
                            )
                        }
                        200 to "ok"
                    },
                    webhook,
                    gui,
                )
            reporter.report(AutoRunNotification(pullRequests = urls))
            assertEquals(urls, reported)
            assertEquals((count + 1174) / 1175, messages)
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
