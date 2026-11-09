package com.example

import com.example.api.ApiRegistry
import com.example.api.ApiSystem
import com.example.api.IntentRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentRouterTest {

    @Test
    fun weatherRouteKeepsMultiWordCityName() {
        val match = IntentRouter(ApiRegistry()).routeIntent("What is the weather in New Delhi today?")

        assertNotNull(match)
        assertTrue(match!!.intentName.startsWith("open-meteo"))
        assertEquals("New Delhi", match.extractedParams["location"])
    }

    @Test
    fun weatherRouteStripsForecastTimeModifier() {
        val match = IntentRouter(ApiRegistry()).routeIntent("weather in Surat tomorrow")

        assertNotNull(match)
        assertEquals("Surat", match!!.extractedParams["location"])
    }
}
