package com.example

import org.junit.Test
import java.io.File
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.*

class TestGeminiAudio {
    @Test
    fun testAudioGeneration() {
        val apiKey = "AQ.Ab8RN6KuNKiCaQU6gZZFs9HvIT3_G80GPrG-PIW7ocJtiF2zdg"
        val requestBody = """{"contents": [{"role":"user", "parts": [{"text": "Say hello!"}]}], "generationConfig": {"responseModalities": ["AUDIO"], "speechConfig": {"voiceConfig": {"prebuiltVoiceConfig": {"voiceName": "Kore"}}}}}"""
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent?key=$apiKey")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()
        val response = OkHttpClient().newCall(request).execute()
        val responseBody = response.body?.string() ?: ""
        println("Response snippet: " + responseBody.take(500))
    }
}
