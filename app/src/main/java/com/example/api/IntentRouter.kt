package com.example.api

import android.util.Log
import java.util.Locale

class IntentRouter(private val registry: ApiRegistry? = null) {
    companion object {
        private const val TAG = "IntentRouter"
    }

    // A fast, extremely lightweight keyword-based regex matcher to bypass LLM 
    // for obvious tool and API calls, saving tokens and latency.
    fun routeIntent(userInput: String): IntentMatch? {
        val lowerInput = userInput.lowercase(Locale.ROOT)
        
        // 1. Dynamic API registry matching based on tags
        if (registry != null) {
            val apis = registry.getAllApisSync()
            for (api in apis) {
                if (api.tags.any { lowerInput.contains(it.lowercase(Locale.ROOT)) }) {
                    // Very simple parameter extraction for demo
                    val extractedParams = mutableMapOf<String, String>()
                    if (api.category == ApiCategory.WEATHER) {
                        val locationMatch = Regex(
                            """\\b(?:in|for|at)\\s+(.+?)(?=\\s+(?:today|tomorrow|tonight|now|currently|current|forecast)\\b|[?.!,]|$)""",
                            RegexOption.IGNORE_CASE
                        ).find(userInput)
                        val location = locationMatch?.groupValues?.getOrNull(1)
                            ?.trim()
                            ?.trimEnd('.', '?', '!', ',')
                            .orEmpty()
                        if (location.isNotBlank()) {
                            extractedParams["location"] = location
                        }
                    }
                    return IntentMatch(
                        intentName = api.apiId + "_query",
                        category = api.category,
                        subcategory = api.subcategory,
                        capability = api.capabilities.firstOrNull() ?: ApiCapability.RETRIEVE,
                        extractedParams = extractedParams,
                        requiresApi = true
                    )
                }
            }
        }

        // 2. Tool checks (Calculations)
        if (lowerInput.matches(Regex(".*what is \\d+.*[+*x/-].*\\d+.*"))) {
            return IntentMatch(
                intentName = "calculation",
                category = ApiCategory.UNKNOWN,
                subcategory = ApiSubcategory.UNKNOWN,
                capability = ApiCapability.CALCULATE,
                extractedParams = emptyMap(),
                requiresApi = false,
                requiredTool = "CALCULATOR"
            )
        }

        // Fallback to null (requires standard LLM processing)
        return null
    }
}
