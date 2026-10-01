package me.rerere.rikkahub.data.container

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private fun renderCommandKey(command: String): String = MessageDigest.getInstance("SHA-256")
    .digest(command.trim().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private fun renderPreferences(raw: String): JsonObject =
    runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

internal fun terminalRendererForCommand(raw: String, command: String): TerminalRenderMode =
    TerminalRenderMode.fromId((renderPreferences(raw)[renderCommandKey(command)] as? JsonPrimitive)?.content)

/** Independent preference map: changing the renderer cannot alter any existing terminal setting. */
internal fun updatedTerminalRenderPreferences(raw: String, command: String, mode: TerminalRenderMode): String {
    val key = renderCommandKey(command)
    val entries = renderPreferences(raw).toMutableMap().apply {
        remove(key)
        put(key, JsonPrimitive(mode.id))
    }
    return JsonObject(entries.entries.toList().takeLast(50).associate { it.key to it.value }).toString()
}

/** One-time status-bar migration. Preserve custom labels/order/unknown fields and never touch KEYS. */
internal fun addTerminalRendererStatusItem(raw: String): String {
    if (raw.isBlank()) return raw // The default list already contains RENDER.
    val items = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return raw
    if (items.any { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.content == "RENDER" }) return raw
    return JsonArray(items + JsonObject(mapOf("id" to JsonPrimitive("RENDER")))).toString()
}
