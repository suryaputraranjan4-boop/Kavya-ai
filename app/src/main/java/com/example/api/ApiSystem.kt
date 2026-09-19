package com.example.api

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ApiSystem {
    companion object {
        private const val TAG = "ApiSystem"
    }

    val registry = ApiRegistry()
    val cache = ApiCache()
    val router = IntentRouter(registry)
    private val executor = ApiExecutor()
    private val scope = CoroutineScope(Dispatchers.IO)
    
    // Core Orchestration Pipeline

    suspend fun executeApiDirectly(apiId: String, paramsJson: String): String {
        val api = registry.getApi(apiId) ?: return "API not found"
        val paramsMap = mutableMapOf<String, String>()
        try {
            val json = org.json.JSONObject(paramsJson)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                paramsMap[key] = json.getString(key)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing params JSON", e)
        }
        return executor.execute(api, paramsMap) ?: "API Execution failed"
    }

    suspend fun processRequest(userInput: String): ApiExecutionResult? {
        Log.d(TAG, "Processing request: \$userInput")
        
        // 1. Intent Detection & Routing
        val match = router.routeIntent(userInput) ?: return null
        
        // Return early if Native Android tool is better
        if (!match.requiresApi) {
             return ApiExecutionResult.ToolRedirect(match.requiredTool ?: "UNKNOWN")
        }
        
        // 2. Cache Check (Request Deduplication)
        val requestKey = "\${match.capability.name}_\${match.extractedParams.hashCode()}"
        val cached = cache.getCachedResult(requestKey)
        if (cached != null) {
            Log.d(TAG, "Cache hit for \$requestKey")
            return ApiExecutionResult.Success(cached, fromCache = true)
        }
        
        // 3. Local API Search & Ranking
        val candidates = registry.searchApis(
            category = match.category,
            subcategory = match.subcategory,
            capability = match.capability
        )
        
        if (candidates.isEmpty()) {
            Log.d(TAG, "No local API found. Triggering Discovery... (Not implemented)")
            return ApiExecutionResult.Failure("No matching API found.")
        }
        
        // 4. Execution with Fallback Loop
        for (api in candidates) {
            Log.d(TAG, "Attempting execution with \${api.name} (\${api.apiId})")
            val startTime = System.currentTimeMillis()
            
            val result = executor.execute(api, match.extractedParams)
            
            if (result != null) {
                // Update Health (Success)
                val elapsed = System.currentTimeMillis() - startTime
                registry.updateHealth(api.apiId) { h ->
                    h.status = ApiHealthStatus.HEALTHY
                    h.successCount++
                    h.avgResponseTimeMs = if (h.avgResponseTimeMs == 0L) elapsed else (h.avgResponseTimeMs + elapsed) / 2
                    h.lastSuccessfulRequestTs = System.currentTimeMillis()
                    h
                }
                
                // Cache Result
                cache.putCachedResult(requestKey, result)
                
                return ApiExecutionResult.Success(result, fromCache = false)
            } else {
                 // Update Health (Failure)
                 registry.updateHealth(api.apiId) { h ->
                    h.failureCount++
                    h.lastFailureTs = System.currentTimeMillis()
                    if (h.failureCount > 3) h.status = ApiHealthStatus.DEGRADED
                    h
                }
                Log.w(TAG, "API \${api.apiId} failed. Trying fallback if available.")
            }
        }
        
        return ApiExecutionResult.Failure("All matching APIs failed.")
    }
}

sealed class ApiExecutionResult {
    data class Success(val data: String, val fromCache: Boolean) : ApiExecutionResult()
    data class Failure(val reason: String) : ApiExecutionResult()
    data class ToolRedirect(val toolName: String) : ApiExecutionResult()
}
