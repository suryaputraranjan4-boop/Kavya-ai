package com.example

import org.junit.Test
import java.util.Base64
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.*

class TestGeminiAudio2 {
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
        
        val json = Json.parseToJsonElement(responseBody).jsonObject
        val candidates = json["candidates"]?.jsonArray
        val parts = candidates?.get(0)?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
        val inlineData = parts?.firstOrNull { it.jsonObject.containsKey("inlineData") }?.jsonObject?.get("inlineData")?.jsonObject
        val base64Data = inlineData?.get("data")?.jsonPrimitive?.content ?: ""
        val mimeType = inlineData?.get("mimeType")?.jsonPrimitive?.content ?: ""
        
        println("MimeType: $mimeType")
        
        if (base64Data.isNotEmpty()) {
            val bytes = Base64.getDecoder().decode(base64Data)
            val headerString = bytes.take(4).toByteArray().toString(Charsets.UTF_8)
            println("Header: $headerString")
        }
    }
}
