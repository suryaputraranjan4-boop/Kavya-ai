package com.example.ai.providers

import android.content.Context
import android.util.Log
import com.example.ai.RetrofitClient
import com.example.security.SecureStorage
import com.example.utils.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Data structures for real Hugging Face outputs.
 */
data class HfClassificationItem(
    val label: String,
    val score: Double
)

data class HfBoundingBox(
    val xmin: Double,
    val ymin: Double,
    val xmax: Double,
    val ymax: Double
)

data class HfDetectedObject(
    val label: String,
    val score: Double,
    val box: HfBoundingBox? = null
)

data class HfInferenceResult(
    val success: Boolean,
    val text: String,
    val rawJson: String? = null,
    val bytes: ByteArray? = null,
    val error: String? = null,
    val latencyMs: Long = 0L
)

enum class HfSpecializedTask(val pipelineTag: String) {
    OCR_DOCUMENT("image-to-text"),
    IMAGE_ANALYSIS("image-to-text"),
    IMAGE_CLASSIFICATION("image-classification"),
    OBJECT_DETECTION("object-detection"),
    EMBEDDINGS("feature-extraction"),
    SPEECH_TO_TEXT("automatic-speech-recognition"),
    TRANSLATION("translation"),
    IMAGE_GENERATION("text-to-image"),
    SENTIMENT_CLASSIFICATION("text-classification")
}

data class HfSpecializedResult(
    val success: Boolean,
    val task: HfSpecializedTask,
    val outputText: String,
    val rawJson: String? = null,
    val imageBytes: ByteArray? = null,
    val modelUsed: String = "",
    val error: String? = null
)

/**
 * Requirement 4 & 5: Real Hugging Face AI Provider.
 * Integrates real Hugging Face Inference API and Model Discovery metadata.
 */
class HuggingFaceProvider : AIProvider {

    companion object {
        private const val TAG = "KavyaHFProvider"
        const val PROVIDER_ID = "huggingface"
        private const val API_BASE = "https://router.huggingface.co/hf-inference/models"
        private const val HUB_API_BASE = "https://huggingface.co/api"
        private const val CHAT_API_BASE = "https://router.huggingface.co/v1"

        // Default specialized verified models per task
        const val MODEL_TEXT_GEN = "meta-llama/Llama-3.2-1B-Instruct"
        const val MODEL_OCR = "microsoft/trocr-base-printed"
        const val MODEL_IMAGE_ANALYSIS = "Salesforce/blip-image-captioning-large"
        const val MODEL_IMAGE_CLASSIFICATION = "google/vit-base-patch16-224"
        const val MODEL_OBJECT_DETECTION = "facebook/detr-resnet-50"
        const val MODEL_EMBEDDINGS = "sentence-transformers/all-MiniLM-L6-v2"
        const val MODEL_SPEECH_TO_TEXT = "openai/whisper-large-v3-turbo"
        const val MODEL_TRANSLATION = "Helsinki-NLP/opus-mt-en-hi"
        const val MODEL_IMAGE_GEN = "black-forest-labs/FLUX.1-schnell"
        const val MODEL_SENTIMENT = "cardiffnlp/twitter-roberta-base-sentiment-latest"

        val DEFAULT_VERIFIED_MODELS = listOf(
            ProviderModelInfo(MODEL_TEXT_GEN, "Llama 3.2 1B Instruct", ProviderType.HUGGINGFACE, listOf("text-generation", "reasoning"), 32768, "Hosted inference availability and pricing depend on the selected provider.", false),
            ProviderModelInfo(MODEL_IMAGE_ANALYSIS, "Salesforce BLIP Captioning", ProviderType.HUGGINGFACE, listOf("image-to-text", "vision"), 2048, "Hosted inference availability and pricing depend on the selected provider.", false),
            ProviderModelInfo(MODEL_OCR, "Microsoft TrOCR Printed", ProviderType.HUGGINGFACE, listOf("image-to-text", "ocr"), 1024, "Hosted inference availability and pricing depend on the selected provider.", false),
            ProviderModelInfo(MODEL_OBJECT_DETECTION, "Facebook DETR ResNet-50", ProviderType.HUGGINGFACE, listOf("object-detection", "vision"), 1024, "Hosted inference availability and pricing depend on the selected provider.", false),
            ProviderModelInfo(MODEL_IMAGE_CLASSIFICATION, "Google ViT Base", ProviderType.HUGGINGFACE, listOf("image-classification", "vision"), 1024, "Hosted inference availability and pricing depend on the selected provider.", false),
            ProviderModelInfo(MODEL_EMBEDDINGS, "Sentence-Transformers all-MiniLM", ProviderType.HUGGINGFACE, listOf("feature-extraction", "embeddings"), 512, "Hosted inference availability and pricing depend on the selected provider.", false)
        )
    }

    override val providerId: String = PROVIDER_ID
    override val displayName: String = "Hugging Face"
    override val providerType: ProviderType = ProviderType.HUGGINGFACE
    override val isPrimaryOrchestrator: Boolean = false

    private val cachedDiscoveredModels = CopyOnWriteArrayList<ProviderModelInfo>(DEFAULT_VERIFIED_MODELS)
    private val cachedChatModels = CopyOnWriteArrayList<ProviderModelInfo>(
        listOf(DEFAULT_VERIFIED_MODELS.first())
    )

    override fun isConfigured(context: Context): Boolean {
        val key = getToken(context)
        return key.isNotBlank()
    }

    private fun getToken(context: Context): String {
        val secure = SecureStorage.getSecret(context, "huggingface_api_key")
        if (secure.isNotBlank()) return secure
        return AppPreferences.getHuggingFaceApiKey(context)
    }

    /**
     * Requirement 4: validateToken()
     * Sends real HTTPS GET request to https://huggingface.co/api/whoami-v2.
     */
    override suspend fun validateCredentials(context: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val token = getToken(context)
        return@withContext validateTokenWithKey(token)
    }

    suspend fun validateToken(context: Context): Pair<Boolean, String> = validateCredentials(context)

    suspend fun validateTokenWithKey(apiKey: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Pair(false, "Hugging Face API token is empty. Please enter your token.")
        }

        try {
            val url = "$HUB_API_BASE/whoami-v2"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${apiKey.trim()}")
                .get()
                .build()

            val response = RetrofitClient.okHttpClient.newCall(request).execute()
            val code = response.code
            val body = response.body?.string() ?: ""
            response.close()

            if (code in 200..299) {
                var username = "User"
                try {
                    val json = JSONObject(body)
                    username = json.optString("name", json.optString("fullname", "Active user"))
                } catch (ignored: Exception) {}
                return@withContext Pair(true, "Hugging Face connected successfully as $username.")
            } else if (code == 401) {
                return@withContext Pair(false, "Invalid API credentials. Invalid Hugging Face token (401).")
            } else if (code == 403) {
                return@withContext Pair(false, "Hugging Face token has insufficient permissions (403).")
            } else if (code == 429) {
                return@withContext Pair(false, "Provider rate limit reached. Trying configured fallback if compatible.")
            } else {
                return@withContext Pair(false, handleError(code, body))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.e(TAG, "Hugging Face token validation error (${e.javaClass.simpleName}).")
            val msg = handleError(-1, e.localizedMessage)
            return@withContext Pair(false, "Hugging Face connection failed: $msg")
        }
    }

    /**
     * Requirement 5: Model Discovery.
     * Queries Hugging Face API to discover available models and their pipeline tags.
     */
    /**
     * Lists chat-completion models from the same router used by generate()/streamGenerate().
     * This avoids presenting masked-language or vision-only models as conversational LLMs.
     */
    override suspend fun getModels(context: Context): List<ProviderModelInfo> = withContext(Dispatchers.IO) {
        val token = getToken(context)
        if (token.isBlank()) return@withContext cachedChatModels.toList()

        try {
            val request = Request.Builder()
                .url("$CHAT_API_BASE/models")
                .addHeader("Authorization", "Bearer ${token.trim()}")
                .get()
                .build()

            RetrofitClient.okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val data = JSONObject(response.body?.string().orEmpty()).optJSONArray("data")
                    ?: return@use null
                val discovered = mutableListOf<ProviderModelInfo>()
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    if (id.isBlank()) continue
                    discovered.add(
                        ProviderModelInfo(
                            id = id,
                            name = item.optString("name", id),
                            provider = ProviderType.HUGGINGFACE,
                            supportedTasks = listOf("chat", "text-generation", "reasoning"),
                            contextLength = item.optInt("context_length", 0),
                            description = "Chat model served through Hugging Face Inference Providers; availability and pricing vary.",
                            isFree = false
                        )
                    )
                }
                if (discovered.isNotEmpty()) {
                    cachedChatModels.clear()
                    cachedChatModels.addAll(discovered)
                    return@withContext discovered
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "Hugging Face chat model discovery failed (${e.javaClass.simpleName}).")
        }
        cachedChatModels.toList()
    }

    suspend fun discoverModels(pipelineTag: String? = null, context: Context): List<ProviderModelInfo> = withContext(Dispatchers.IO) {
        val token = getToken(context)
        val tagParam = if (!pipelineTag.isNullOrBlank()) "&pipeline_tag=$pipelineTag" else ""
        val url = "$HUB_API_BASE/models?inference_provider=hf-inference&limit=25&sort=downloads&direction=-1$tagParam"

        val requestBuilder = Request.Builder().url(url)
        if (token.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer ${token.trim()}")
        }

        try {
            val response = RetrofitClient.okHttpClient.newCall(requestBuilder.build()).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                response.close()
                val array = JSONArray(body)
                val discovered = mutableListOf<ProviderModelInfo>()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    if (id.isBlank()) continue
                    val tag = obj.optString("pipeline_tag", "unknown")
                    val isPrivate = obj.optBoolean("private", false)
                    if (isPrivate) continue

                    discovered.add(
                        ProviderModelInfo(
                            id = id,
                            name = id.substringAfterLast("/"),
                            provider = ProviderType.HUGGINGFACE,
                            supportedTasks = listOf(tag),
                            description = "Available through the hf-inference provider ($tag); token required and usage may be metered.",
                            isFree = false
                        )
                    )
                }
                if (discovered.isNotEmpty()) {
                    cachedDiscoveredModels.clear()
                    cachedDiscoveredModels.addAll(discovered)
                    return@withContext discovered
                }
            } else {
                response.close()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "Hugging Face model discovery failed; using verified cache (${e.javaClass.simpleName}).")
        }
        return@withContext cachedDiscoveredModels.toList()
    }

    /**
     * Requirement 4: runInference()
     * Sends real HTTPS POST request to Hugging Face Inference API.
     */
    suspend fun runInference(
        model: String,
        inputs: Any,
        context: Context,
        parameters: Map<String, Any>? = null
    ): HfInferenceResult = withContext(Dispatchers.IO) {
        val token = getToken(context)
        if (token.isBlank()) {
            return@withContext HfInferenceResult(
                success = false,
                text = "This provider is not configured. Missing Hugging Face API key.",
                error = "This provider is not configured."
            )
        }

        val url = "$API_BASE/$model"
        val startTime = System.currentTimeMillis()

        try {
            val requestBuilder = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${token.trim()}")

            when (inputs) {
                is ByteArray -> {
                    val mediaType = "application/octet-stream".toMediaType()
                    requestBuilder.post(inputs.toRequestBody(mediaType))
                }
                is String -> {
                    val json = JSONObject().apply {
                        put("inputs", inputs)
                        if (parameters != null) {
                            val paramsObj = JSONObject()
                            parameters.forEach { (k, v) -> paramsObj.put(k, v) }
                            put("parameters", paramsObj)
                        }
                    }
                    val mediaType = "application/json; charset=utf-8".toMediaType()
                    requestBuilder.post(json.toString().toRequestBody(mediaType))
                }
                is JSONObject -> {
                    val mediaType = "application/json; charset=utf-8".toMediaType()
                    requestBuilder.post(inputs.toString().toRequestBody(mediaType))
                }
                else -> {
                    val json = JSONObject().apply { put("inputs", inputs.toString()) }
                    val mediaType = "application/json; charset=utf-8".toMediaType()
                    requestBuilder.post(json.toString().toRequestBody(mediaType))
                }
            }

            val response = RetrofitClient.okHttpClient.newCall(requestBuilder.build()).execute()
            val code = response.code
            val latency = System.currentTimeMillis() - startTime

            if (code in 200..299) {
                val contentType = response.header("Content-Type") ?: ""
                if (contentType.startsWith("image/") || contentType.startsWith("application/octet-stream")) {
                    val bytes = response.body?.bytes()
                    response.close()
                    return@withContext HfInferenceResult(
                        success = true,
                        text = "Binary data received (${bytes?.size ?: 0} bytes)",
                        bytes = bytes,
                        latencyMs = latency
                    )
                }

                val bodyStr = response.body?.string() ?: ""
                response.close()

                var parsedText = bodyStr
                try {
                    if (bodyStr.startsWith("[")) {
                        val arr = JSONArray(bodyStr)
                        if (arr.length() > 0) {
                            val first = arr.get(0)
                            if (first is JSONObject) {
                                parsedText = when {
                                    first.has("generated_text") -> first.getString("generated_text")
                                    first.has("label") -> "${first.getString("label")} (${String.format("%.2f", first.optDouble("score"))})"
                                    else -> first.toString()
                                }
                            }
                        }
                    } else if (bodyStr.startsWith("{")) {
                        val obj = JSONObject(bodyStr)
                        parsedText = when {
                            obj.has("generated_text") -> obj.getString("generated_text")
                            obj.has("text") -> obj.getString("text")
                            else -> bodyStr
                        }
                    }
                } catch (ignored: Exception) {}

                return@withContext HfInferenceResult(
                    success = true,
                    text = parsedText.trim(),
                    rawJson = bodyStr,
                    latencyMs = latency
                )
            } else {
                val errBody = response.body?.string() ?: ""
                response.close()
                val errorMsg = handleError(code, errBody)
                return@withContext HfInferenceResult(
                    success = false,
                    text = errorMsg,
                    rawJson = errBody,
                    error = errorMsg,
                    latencyMs = latency
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            val msg = handleError(-1, e.localizedMessage)
            return@withContext HfInferenceResult(
                success = false,
                text = msg,
                error = e.message,
                latencyMs = latency
            )
        }
    }

    /**
     * Requirement 4: textGeneration()
     */
    suspend fun textGeneration(
        model: String = MODEL_TEXT_GEN,
        prompt: String,
        context: Context
    ): AIProviderResult = withContext(Dispatchers.IO) {
        val result = runInference(model, prompt, context, mapOf("max_new_tokens" to 300))
        return@withContext AIProviderResult(
            success = result.success,
            text = result.text,
            rawJson = result.rawJson,
            providerId = providerId,
            model = model,
            error = result.error,
            latencyMs = result.latencyMs
        )
    }

    /**
     * Requirement 4: imageAnalysis()
     */
    suspend fun imageAnalysis(
        model: String = MODEL_IMAGE_ANALYSIS,
        imageBytes: ByteArray,
        context: Context
    ): AIProviderResult = withContext(Dispatchers.IO) {
        val result = runInference(model, imageBytes, context)
        return@withContext AIProviderResult(
            success = result.success,
            text = result.text,
            rawJson = result.rawJson,
            providerId = providerId,
            model = model,
            error = result.error,
            latencyMs = result.latencyMs
        )
    }

    /**
     * Requirement 4: imageClassification()
     */
    suspend fun imageClassification(
        model: String = MODEL_IMAGE_CLASSIFICATION,
        imageBytes: ByteArray,
        context: Context
    ): List<HfClassificationItem> = withContext(Dispatchers.IO) {
        val result = runInference(model, imageBytes, context)
        val list = mutableListOf<HfClassificationItem>()
        if (result.success && !result.rawJson.isNullOrBlank()) {
            try {
                val array = JSONArray(result.rawJson)
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val label = item.optString("label", "unknown")
                    val score = item.optDouble("score", 0.0)
                    list.add(HfClassificationItem(label, score))
                }
            } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
                Log.w(TAG, "Classification response parsing failed (${e.javaClass.simpleName}).")
            }
        }
        return@withContext list
    }

    /**
     * Requirement 4: objectDetection()
     */
    suspend fun objectDetection(
        model: String = MODEL_OBJECT_DETECTION,
        imageBytes: ByteArray,
        context: Context
    ): List<HfDetectedObject> = withContext(Dispatchers.IO) {
        val result = runInference(model, imageBytes, context)
        val list = mutableListOf<HfDetectedObject>()
        if (result.success && !result.rawJson.isNullOrBlank()) {
            try {
                val array = JSONArray(result.rawJson)
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val label = item.optString("label", "unknown")
                    val score = item.optDouble("score", 0.0)
                    val boxObj = item.optJSONObject("box")
                    val box = if (boxObj != null) {
                        HfBoundingBox(
                            xmin = boxObj.optDouble("xmin", 0.0),
                            ymin = boxObj.optDouble("ymin", 0.0),
                            xmax = boxObj.optDouble("xmax", 0.0),
                            ymax = boxObj.optDouble("ymax", 0.0)
                        )
                    } else null
                    list.add(HfDetectedObject(label, score, box))
                }
            } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
                Log.w(TAG, "Object detection response parsing failed (${e.javaClass.simpleName}).")
            }
        }
        return@withContext list
    }

    /**
     * Requirement 4: imageToText() (OCR)
     */
    suspend fun imageToText(
        model: String = MODEL_OCR,
        imageBytes: ByteArray,
        context: Context
    ): String = withContext(Dispatchers.IO) {
        val result = runInference(model, imageBytes, context)
        if (result.success) {
            return@withContext result.text
        } else {
            return@withContext "OCR Error: ${result.error ?: "Unable to read text from image."}"
        }
    }

    /**
     * Requirement 4: embeddings()
     */
    suspend fun embeddings(
        model: String = MODEL_EMBEDDINGS,
        text: String,
        context: Context
    ): FloatArray = withContext(Dispatchers.IO) {
        val result = runInference(model, text, context)
        if (result.success && !result.rawJson.isNullOrBlank()) {
            try {
                val json = result.rawJson
                if (json.startsWith("[")) {
                    val arr = JSONArray(json)
                    // If nested array (e.g. [[0.1, 0.2]])
                    val targetArr = if (arr.length() > 0 && arr.get(0) is JSONArray) arr.getJSONArray(0) else arr
                    val floats = FloatArray(targetArr.length())
                    for (i in 0 until targetArr.length()) {
                        floats[i] = targetArr.optDouble(i, 0.0).toFloat()
                    }
                    return@withContext floats
                }
            } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
                Log.w(TAG, "Embeddings response parsing failed (${e.javaClass.simpleName}).")
            }
        }
        return@withContext FloatArray(0)
    }

    private fun selectedChatModel(context: Context): String {
        val configured = AppPreferences.getHuggingFaceModel(context).trim()
        // This was the old default, but DistilBERT is not a chat-generation model.
        return if (configured.isBlank() || configured.equals("distilbert-base-uncased", true)) {
            MODEL_TEXT_GEN
        } else {
            configured
        }
    }

    private fun extractAssistantContent(message: JSONObject): String {
        val content = message.opt("content")
        return when (content) {
            is String -> content.trim()
            is JSONArray -> buildString {
                for (index in 0 until content.length()) {
                    val part = content.optJSONObject(index) ?: continue
                    val text = part.optString("text")
                    if (text.isNotBlank()) append(text)
                }
            }.trim()
            else -> ""
        }
    }

    override suspend fun generate(
        prompt: String,
        systemInstruction: String?,
        context: Context
    ): AIProviderResult = withContext(Dispatchers.IO) {
        val token = getToken(context)
        val model = selectedChatModel(context)
        if (token.isBlank()) {
            return@withContext AIProviderResult(
                success = false,
                text = "This provider is not configured. Missing Hugging Face token.",
                providerId = providerId,
                model = model,
                error = "Missing Hugging Face token."
            )
        }

        val messages = JSONArray()
        if (!systemInstruction.isNullOrBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", systemInstruction))
        }
        messages.put(JSONObject().put("role", "user").put("content", prompt))
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.7)
            .put("max_tokens", 512)
        val startTime = System.currentTimeMillis()

        try {
            val request = Request.Builder()
                .url("$CHAT_API_BASE/chat/completions")
                .addHeader("Authorization", "Bearer ${token.trim()}")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            RetrofitClient.okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val latency = System.currentTimeMillis() - startTime
                if (!response.isSuccessful) {
                    val error = handleError(response.code, body)
                    return@withContext AIProviderResult(
                        success = false, text = error, rawJson = body,
                        providerId = providerId, model = model, error = error, latencyMs = latency
                    )
                }
                val choice = JSONObject(body).optJSONArray("choices")?.optJSONObject(0)
                val message = choice?.optJSONObject("message")
                val answer = message?.let(::extractAssistantContent).orEmpty()
                if (answer.isBlank()) {
                    return@withContext AIProviderResult(
                        success = false,
                        text = "Hugging Face returned no assistant text for model $model.",
                        rawJson = body,
                        providerId = providerId,
                        model = model,
                        error = "Empty chat completion.",
                        latencyMs = latency
                    )
                }
                return@withContext AIProviderResult(
                    success = true,
                    text = answer,
                    rawJson = body,
                    providerId = providerId,
                    model = model,
                    latencyMs = latency
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            val error = handleError(-1, e.localizedMessage)
            return@withContext AIProviderResult(
                success = false, text = error, providerId = providerId,
                model = model, error = e.message, latencyMs = System.currentTimeMillis() - startTime
            )
        }
    }

    override fun streamGenerate(
        prompt: String,
        systemInstruction: String?,
        context: Context,
        modelOverride: String?
    ): Flow<String> = flow {
        val token = getToken(context)
        if (token.isBlank()) {
            emit("This provider is not configured. Missing Hugging Face token.")
            return@flow
        }

        val model = modelOverride?.trim()?.takeIf { it.isNotEmpty() } ?: selectedChatModel(context)
        val messages = JSONArray()
        if (!systemInstruction.isNullOrBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", systemInstruction))
        }
        messages.put(JSONObject().put("role", "user").put("content", prompt))
        val payload = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.7)
            .put("max_tokens", 512)
            .put("stream", true)

        try {
            val request = Request.Builder()
                .url("$CHAT_API_BASE/chat/completions")
                .addHeader("Authorization", "Bearer ${token.trim()}")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            RetrofitClient.okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(handleError(response.code, response.body?.string()))
                    return@use
                }
                val body = response.body ?: run {
                    emit("Hugging Face returned an empty streaming response.")
                    return@use
                }
                body.charStream().buffered().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        val data = line.trim()
                        if (!data.startsWith("data:")) continue
                        val chunk = data.removePrefix("data:").trim()
                        if (chunk == "[DONE]") break
                        try {
                            val choice = JSONObject(chunk).optJSONArray("choices")?.optJSONObject(0)
                            val delta = choice?.optJSONObject("delta")
                            val text = delta?.opt("content")
                            when (text) {
                                is String -> if (text.isNotEmpty()) emit(text)
                                is JSONArray -> {
                                    for (index in 0 until text.length()) {
                                        val part = text.optJSONObject(index) ?: continue
                                        val value = part.optString("text")
                                        if (value.isNotEmpty()) emit(value)
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            // Ignore keep-alive and non-content SSE events.
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            emit(handleError(-1, e.localizedMessage))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Specialized legacy task execution bridge for backward compatibility with existing UI.
     */
    suspend fun executeSpecializedTask(
        task: HfSpecializedTask,
        textInput: String? = null,
        binaryInput: ByteArray? = null,
        modelOverride: String? = null,
        context: Context
    ): HfSpecializedResult = withContext(Dispatchers.IO) {
        val model = modelOverride?.ifBlank { null } ?: when (task) {
            HfSpecializedTask.OCR_DOCUMENT -> MODEL_OCR
            HfSpecializedTask.IMAGE_ANALYSIS -> MODEL_IMAGE_ANALYSIS
            HfSpecializedTask.IMAGE_CLASSIFICATION -> MODEL_IMAGE_CLASSIFICATION
            HfSpecializedTask.OBJECT_DETECTION -> MODEL_OBJECT_DETECTION
            HfSpecializedTask.EMBEDDINGS -> MODEL_EMBEDDINGS
            HfSpecializedTask.SPEECH_TO_TEXT -> MODEL_SPEECH_TO_TEXT
            HfSpecializedTask.TRANSLATION -> MODEL_TRANSLATION
            HfSpecializedTask.IMAGE_GENERATION -> MODEL_IMAGE_GEN
            HfSpecializedTask.SENTIMENT_CLASSIFICATION -> MODEL_SENTIMENT
        }

        val inputData: Any = binaryInput ?: textInput ?: ""
        val infResult = runInference(model, inputData, context)

        return@withContext HfSpecializedResult(
            success = infResult.success,
            task = task,
            outputText = infResult.text,
            rawJson = infResult.rawJson,
            imageBytes = infResult.bytes,
            modelUsed = model,
            error = infResult.error
        )
    }

    override fun handleError(code: Int, errorBody: String?): String {
        val parsedMsg = if (!errorBody.isNullOrBlank()) {
            try {
                val json = JSONObject(errorBody)
                json.optString("error", json.optJSONArray("error")?.optString(0) ?: "")
            } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
                null
            }
        } else null

        return when (code) {
            401 -> "Invalid API credentials. Invalid Hugging Face token (401)."
            403 -> "Access forbidden / token lacks model inference permission (403)."
            404 -> "Selected model was not found on Hugging Face (404)."
            429 -> "Provider rate limit reached. Trying configured fallback if compatible."
            503 -> {
                val waitTime = if (parsedMsg?.contains("estimated_time") == true) " Estimated ready time: $parsedMsg." else ""
                "Hugging Face model is loading into memory.$waitTime Please retry in a moment."
            }
            500, 502 -> "Hugging Face inference service error: ${parsedMsg ?: "internal failure"}"
            504 -> "Provider timed out."
            else -> {
                if (errorBody != null && (errorBody.contains("timeout", ignoreCase = true) || errorBody.contains("timed out", ignoreCase = true))) {
                    "Provider timed out."
                } else {
                    parsedMsg ?: "Hugging Face request failed: ${errorBody?.take(120) ?: "Unknown network error"}"
                }
            }
        }
    }
}
