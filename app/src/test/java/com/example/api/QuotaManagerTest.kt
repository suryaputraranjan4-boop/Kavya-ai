package com.example.api

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaManagerTest {
    @Test
    fun duplicateInFlightRequestIsRejectedAndReleasedOnce() = runBlocking {
        val manager = QuotaManager()

        assertTrue(manager.acquireQuota("request-1", RequestPriority.P0_ACTIVE_USER_REQUEST))
        assertFalse(manager.acquireQuota("request-1", RequestPriority.P0_ACTIVE_USER_REQUEST))
        assertEquals(1, manager.currentActiveRequests)

        manager.releaseQuota("request-1")
        manager.releaseQuota("request-1")
        assertEquals(0, manager.currentActiveRequests)

        assertTrue(manager.acquireQuota("request-1", RequestPriority.P0_ACTIVE_USER_REQUEST))
        manager.releaseQuota("request-1")
        assertEquals(0, manager.currentActiveRequests)
    }

    @Test
    fun differentRequestsCanRunConcurrently() = runBlocking {
        val manager = QuotaManager()

        assertTrue(manager.acquireQuota("request-a", RequestPriority.P0_ACTIVE_USER_REQUEST))
        assertTrue(manager.acquireQuota("request-b", RequestPriority.P1_ACTIVE_TASK_EXECUTION))
        assertEquals(2, manager.currentActiveRequests)

        manager.releaseQuota("request-a")
        manager.releaseQuota("request-b")
        assertEquals(0, manager.currentActiveRequests)
    }
}
