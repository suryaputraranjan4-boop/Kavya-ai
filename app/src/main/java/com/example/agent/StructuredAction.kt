package com.example.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Universal Structured Action Command returned internally by Gemini or TaskPlanner.
 * Clean, type-safe representation of Android interactions.
 */
data class StructuredAction(
    val action: String,
    val target: String = "",
    val query: String = "",
    val text: String = "",
    val selector: String = "",
    val direction: String = "DOWN",
    val key: String = "",
    val value: String = "",
    val rawParam: String = "",
    val recipient: String = "",
    val message: String = "",
    val index: Int = 0,
    val spokenMessage: String = ""
)

object StructuredActionParser {

    private val ACTION_TAG_REGEX = Regex("""<ACTION:([^:>]+)(?::([^>]*))?>""")
    private val JSON_ACTION_BLOCK_REGEX = Regex("""```(?:json)?\s*(\[\s*\{.*?\}\s*\]|\{.*?\})\s*```""", RegexOption.DOT_MATCHES_ALL)
    private val BARE_JSON_REGEX = Regex("""(\[\s*\{.*?\}\s*\]|\{\s*"(?:action|type)"\s*:.*?\})""", RegexOption.DOT_MATCHES_ALL)

    /**
     * Parses structured actions from model response text.
     * Supports both:
     * 1. JSON blocks: `[{"action": "OPEN_APP", "target": "YouTube"}]` or `{"action": "..."}`
     * 2. Bare JSON blocks without code fences: `{"action": "OPEN_APP", "app": "YouTube"}`
     * 3. Traditional structured tags: `<ACTION:OPEN_APP:YouTube>`
     */
    fun parseActions(response: String): List<StructuredAction> {
        val actions = mutableListOf<StructuredAction>()
        if (response.isBlank()) return actions

        // 1. Check for fenced JSON format
        val jsonMatch = JSON_ACTION_BLOCK_REGEX.find(response)
        if (jsonMatch != null) {
            val jsonContent = jsonMatch.groupValues[1].trim()
            try {
                if (jsonContent.startsWith("[")) {
                    val array = JSONArray(jsonContent)
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i)
                        if (obj != null) {
                            actions.add(parseJsonObject(obj))
                        }
                    }
                } else if (jsonContent.startsWith("{")) {
                    val obj = JSONObject(jsonContent)
                    if (obj.has("actions")) {
                        val acts = obj.optJSONArray("actions")
                        if (acts != null) {
                            for (j in 0 until acts.length()) {
                                val actObj = acts.optJSONObject(j)
                                if (actObj != null) actions.add(parseJsonObject(actObj))
                            }
                        }
                    } else {
                        actions.add(parseJsonObject(obj))
                    }
                }
            } catch (e: Exception) {
                // fallback to bare or tag parsing
            }
        }

        // 2. Check for bare JSON format if not found in code fence
        if (actions.isEmpty()) {
            val bareMatch = BARE_JSON_REGEX.find(response)
            if (bareMatch != null) {
                val jsonContent = bareMatch.groupValues[1].trim()
                try {
                    if (jsonContent.startsWith("[")) {
                        val array = JSONArray(jsonContent)
                        for (i in 0 until array.length()) {
                            val obj = array.optJSONObject(i)
                            if (obj != null) {
                                actions.add(parseJsonObject(obj))
                            }
                        }
                    } else if (jsonContent.startsWith("{")) {
                        val obj = JSONObject(jsonContent)
                        if (obj.has("actions")) {
                            val acts = obj.optJSONArray("actions")
                            if (acts != null) {
                                for (j in 0 until acts.length()) {
                                    val actObj = acts.optJSONObject(j)
                                    if (actObj != null) actions.add(parseJsonObject(actObj))
                                }
                            }
                        } else {
                            actions.add(parseJsonObject(obj))
                        }
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
        }

        // 3. Also support tag format <ACTION:Type:Param>
        if (actions.isEmpty()) {
            val matches = ACTION_TAG_REGEX.findAll(response)
            for (match in matches) {
                val type = match.groupValues[1].trim()
                val param = match.groupValues.getOrNull(2)?.trim() ?: ""
                actions.add(fromTag(type, param))
            }
        }

        return actions
    }

    fun sanitizeQuery(raw: String): String {
        var q = raw.trim()
        val cutoffs = listOf(
            " and open the second video",
            " and open second video",
            " and open the 2nd video",
            " and open 2nd video",
            " and open the second",
            " and open second",
            " and open",
            " and play",
            " and click",
            " aur dusra",
            " aur doosra",
            " then open",
            " then play",
            ", and open",
            ", then"
        )
        val lower = q.lowercase(java.util.Locale.ROOT)
        for (cutoff in cutoffs) {
            val idx = lower.indexOf(cutoff)
            if (idx != -1) {
                q = q.substring(0, idx).trim()
            }
        }
        return q.trim(',', ' ', ';', '.')
    }

    private fun parseJsonObject(obj: JSONObject): StructuredAction {
        var rawAction = obj.optString("action", obj.optString("type", "UNKNOWN")).trim().uppercase()
        var target = ""
        var selector = obj.optString("selector", obj.optString("element", "")).trim()
        var computedIndex = 0

        // Handle target field which could be String or JSONObject
        if (obj.has("target")) {
            val targetVal = obj.opt("target")
            if (targetVal is JSONObject) {
                val tType = targetVal.optString("type", "").lowercase(java.util.Locale.ROOT)
                val pos = targetVal.optInt("position", 1)
                if (tType.contains("list_item") || tType.contains("item") || tType.contains("result")) {
                    rawAction = "SELECT_RESULT"
                    computedIndex = (pos - 1).coerceAtLeast(0)
                } else {
                    selector = targetVal.optString("name", targetVal.optString("text", ""))
                }
            } else {
                target = targetVal?.toString()?.trim() ?: ""
            }
        }
        if (target.isBlank()) {
            target = obj.optString("package", obj.optString("appName", obj.optString("app", ""))).trim()
        }

        // Map snake_case actions to canonical action names
        val action = when (rawAction) {
            "OPEN_APP", "OPEN" -> "OPEN_APP"
            "FIND_AND_CLICK", "CLICK", "TAP", "UI_CLICK" -> "UI_CLICK"
            "TYPE_TEXT", "TYPE", "UI_TYPE" -> "UI_TYPE"
            "SUBMIT_SEARCH", "SUBMIT" -> "SUBMIT"
            "SELECT_RESULT", "SELECT" -> "SELECT_RESULT"
            "SCROLL_DOWN", "SCROLL_UP", "SCROLL", "UI_SCROLL" -> "UI_SCROLL"
            else -> rawAction
        }

        val rawQuery = obj.optString("query", obj.optString("search", "")).trim()
        val query = sanitizeQuery(rawQuery)
        val text = obj.optString("text", obj.optString("input", "")).trim()
        val direction = obj.optString("direction", if (rawAction == "SCROLL_UP") "UP" else "DOWN").trim().uppercase()
        val key = obj.optString("key", "").trim()
        val value = obj.optString("value", "").trim()
        val param = obj.optString("param", "").trim()
        val recipient = obj.optString("recipient", "").trim()
        val message = obj.optString("message", "").trim()
        val combinedTarget = "$target $selector $param".lowercase(java.util.Locale.ROOT)
        val index = if (obj.has("index")) {
            obj.getInt("index")
        } else if (computedIndex > 0) {
            computedIndex
        } else if (combinedTarget.contains("second") || combinedTarget.contains("2nd") || combinedTarget.contains("doosra") || combinedTarget.contains("dusra")) {
            1
        } else {
            0
        }
        val spokenMessage = obj.optString("spoken_message", "").trim()

        return StructuredAction(
            action = action,
            target = target,
            query = query,
            text = text,
            selector = selector,
            direction = direction,
            key = key,
            value = value,
            rawParam = param,
            recipient = recipient,
            message = message,
            index = index,
            spokenMessage = spokenMessage
        )
    }

    fun fromTag(type: String, param: String): StructuredAction {
        val normalizedType = type.trim().uppercase()
        return when (normalizedType) {
            "OPEN_APP" -> StructuredAction(action = "OPEN_APP", target = param, rawParam = param)
            "OPEN_AND_SEARCH" -> {
                val parts = param.split(":", limit = 2)
                val target = parts.getOrNull(0)?.trim() ?: ""
                val query = parts.getOrNull(1)?.trim() ?: ""
                StructuredAction(action = "OPEN_AND_SEARCH", target = target, query = query, rawParam = param)
            }
            "SEARCH_WEB" -> StructuredAction(action = "SEARCH_WEB", query = param, rawParam = param)
            "UI_CLICK", "TAP" -> StructuredAction(action = "UI_CLICK", selector = param, rawParam = param)
            "UI_TYPE", "TYPE" -> {
                val parts = param.split(":", limit = 2)
                if (parts.size == 2) {
                    StructuredAction(action = "UI_TYPE", selector = parts[0].trim(), text = parts[1].trim(), rawParam = param)
                } else {
                    StructuredAction(action = "UI_TYPE", text = param, rawParam = param)
                }
            }
            "UI_SCROLL", "SCROLL" -> StructuredAction(action = "UI_SCROLL", direction = param.ifBlank { "DOWN" }, rawParam = param)
            "GLOBAL_ACTION" -> StructuredAction(action = "GLOBAL_ACTION", rawParam = param)
            "SAVE_MEMORY" -> {
                val parts = param.split("|", limit = 2)
                val k = parts.getOrNull(0)?.trim() ?: ""
                val v = parts.getOrNull(1)?.trim() ?: ""
                StructuredAction(action = "SAVE_MEMORY", key = k, value = v, rawParam = param)
            }
            "GOOGLE_MAPS_SEARCH", "MAPS_SEARCH", "SEARCH_MAPS" -> {
                StructuredAction(action = "GOOGLE_MAPS_SEARCH", query = param, rawParam = param)
            }
            "MEMORY_TOOL", "MEMORY_SEARCH" -> {
                StructuredAction(action = "MEMORY_TOOL", query = param, rawParam = param)
            }
            else -> StructuredAction(action = normalizedType, rawParam = param)
        }
    }

    /**
     * Strips both JSON action code blocks, bare JSON action objects, and ACTION tags from spoken/rendered output.
     */
    fun stripActions(text: String): String {
        var clean = text
        clean = JSON_ACTION_BLOCK_REGEX.replace(clean, "")
        clean = BARE_JSON_REGEX.replace(clean, "")
        clean = ACTION_TAG_REGEX.replace(clean, "")
        return clean.trim()
    }
}
