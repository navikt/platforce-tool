package no.nav.platforce.tool.node

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.stream.JsonWriter
import java.io.StringWriter

internal fun nodeJson(
    value: JsonObject,
    original: String,
): String {
    val indentation =
        if (original.trim().contains('\n')) Regex("""(?m)^([ \t]+)"""").find(original)?.groupValues?.get(1) ?: "  " else ""
    val output = StringWriter()
    JsonWriter(output).use { writer ->
        writer.setIndent(indentation)
        GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create()
            .toJson(value, writer)
    }
    val newline = if ("\r\n" in original) "\r\n" else "\n"
    return output.toString().replace("\n", newline) + if (original.endsWith('\n')) newline else ""
}
