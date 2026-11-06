package com.example.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RequestPriority {
    P0_ACTIVE_USER_REQUEST,
    P1_ACTIVE_TASK_EXECUTION,
    P2_IMPORTANT_OPERATION,
    P3_PROACTIVE_CONVERSATION
}

class QuotaManager {
    private val mutex = Mutex()
    private var requestsInLastMinute = 0
    private var requestsInLastHour = 0
    private var requestsToday = 0
    private var activeRequests = 0
    
    // Debug stats
    var lastErrorType: String = "None"
    var lastErrorTime: Long = 0
    var lastErrorRequestType: String = ""
    var duplicateRequestsPrevented: Int = 0
    var localRequestsCount: Int = 0
    var proactiveRequestsCount: Int = 0
    var rateLimitErrorsCount: Int = 0
    var totalVoiceRequests: Int = 0
    
    val currentRequestsInMinute get() = requestsInLastMinute
    val currentRequestsInHour get() = requestsInLastHour
    val currentRequestsToday get() = requestsToday
    val currentActiveRequests get() = activeRequests
    
    private val minuteWindowStart = MutableStateFlow(System.currentTimeMillis())
    private val hourWindowStart = MutableStateFlow(System.currentTimeMillis())
    private var dayWindowStart = System.currentTimeMillis() / 86_400_000L

    // Active requests are released by each caller's finally block. Do not expire
    // them by wall-clock age: valid streams can run longer than 20 seconds, and
    // expiring one can let an old release remove a newer request with the same ID.
    private val activeRequestMap = mutableSetOf<String>()

    companion object {
        const val MAX_REQUESTS_PER_MINUTE = 120
        const val MAX_REQUESTS_PER_HOUR = 1200
        val instance = QuotaManager()
    }

    suspend fun acquireQuota(requestId: String, priority: RequestPriority): Boolean {
        return mutex.withLock {
            val now = System.currentTimeMillis()
            activeRequests = activeRequestMap.size

            if (activeRequestMap.contains(requestId)) {
                duplicateRequestsPrevented++
                return@withLock false // Prevent duplicate in-flight requests
            }

            if (now - minuteWindowStart.value > 60000) {
                minuteWindowStart.value = now
                requestsInLastMinute = 0
            }
            if (now - hourWindowStart.value > 3600000) {
                hourWindowStart.value = now
                requestsInLastHour = 0
            }
            
            // Reset the daily counter independently from the minute window.
            val today = now / 86_400_000L
            if (dayWindowStart != today) {
                dayWindowStart = today
                requestsToday = 0
            }

            // High volume protection - throttle low priority proactive requests
            if (priority == RequestPriority.P3_PROACTIVE_CONVERSATION && requestsInLastMinute >= 5) {
                return@withLock false
            }

            // Allow user foreground requests priority access
            if (priority != RequestPriority.P0_ACTIVE_USER_REQUEST && priority != RequestPriority.P1_ACTIVE_TASK_EXECUTION) {
                if (requestsInLastMinute >= MAX_REQUESTS_PER_MINUTE || requestsInLastHour >= MAX_REQUESTS_PER_HOUR) {
                    return@withLock false
                }
            }

            requestsInLastMinute++
            requestsInLastHour++
            requestsToday++
            activeRequests++
            
            if (priority == RequestPriority.P3_PROACTIVE_CONVERSATION) {
                proactiveRequestsCount++
            }
            activeRequestMap.add(requestId)
            true
        }
    }

    suspend fun releaseQuota(requestId: String) {
        mutex.withLock {
            if (activeRequestMap.remove(requestId) != null) {
                activeRequests--
            }
        }
    }
}
