package com.example.api

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CacheEntry(
    val result: String,
    val timestamp: Long,
    val ttlMs: Long
)

class ApiCache {
    private val cache = mutableMapOf<String, CacheEntry>()
    private val mutex = Mutex()

    suspend fun getCachedResult(requestKey: String): String? {
        mutex.withLock {
            val entry = cache[requestKey] ?: return null
            if (System.currentTimeMillis() - entry.timestamp > entry.ttlMs) {
                cache.remove(requestKey) // Expired
                return null
            }
            return entry.result
        }
    }

    suspend fun putCachedResult(requestKey: String, result: String, ttlMs: Long = 60000L) {
        mutex.withLock {
            cache[requestKey] = CacheEntry(
                result = result,
                timestamp = System.currentTimeMillis(),
                ttlMs = ttlMs
            )
        }
    }
    
    suspend fun clearExpired() {
         mutex.withLock {
             val now = System.currentTimeMillis()
             val keysToRemove = cache.filter { now - it.value.timestamp > it.value.ttlMs }.keys
             keysToRemove.forEach { cache.remove(it) }
         }
    }
}
