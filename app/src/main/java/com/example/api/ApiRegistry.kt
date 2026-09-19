package com.example.api
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ApiRegistry {
    private val _apis = mutableMapOf<String, ApiMetadata>()
    private val _apiFlow = MutableStateFlow<List<ApiMetadata>>(emptyList())
    val apis: StateFlow<List<ApiMetadata>> = _apiFlow.asStateFlow()
    private val mutex = Mutex()

    init {
        // Pre-populate with some essential, safe, free APIs as examples
        val openMeteo = ApiMetadata(
            apiId = "open-meteo-v1",
            name = "Open-Meteo Weather API",
            category = ApiCategory.WEATHER,
            subcategory = ApiSubcategory.CURRENT_WEATHER,
            capabilities = listOf(ApiCapability.FORECAST, ApiCapability.RETRIEVE),
            tags = listOf("weather", "forecast", "temperature", "rain"),
            endpoint = "https://api.open-meteo.com/v1/forecast",
            method = HttpMethod.GET,
            authType = AuthType.NONE,
            requiredParams = listOf(
                ApiParameter("latitude", "float", true, "Latitude"),
                ApiParameter("longitude", "float", true, "Longitude")
            ),
            optionalParams = listOf(
                ApiParameter("current_weather", "boolean", false, "Include current weather")
            ),
            isFree = true,
            priority = ApiPriority.P0_ESSENTIAL,
            fallbackGroupId = "weather-core",
            description = "Free open-source weather API requiring no API key."
        )
        val frankfurter = ApiMetadata(
            apiId = "frankfurter-currency-v1",
            name = "Frankfurter Currency API",
            category = ApiCategory.FINANCE,
            subcategory = ApiSubcategory.EXCHANGE_RATES,
            capabilities = listOf(ApiCapability.CONVERT, ApiCapability.RETRIEVE),
            tags = listOf("currency", "exchange", "money", "conversion"),
            endpoint = "https://api.frankfurter.app/latest",
            method = HttpMethod.GET,
            authType = AuthType.NONE,
            requiredParams = emptyList(),
            optionalParams = listOf(
                ApiParameter("amount", "float", false, "Amount to convert"),
                ApiParameter("from", "string", false, "Base currency"),
                ApiParameter("to", "string", false, "Target currencies")
            ),
            isFree = true,
            priority = ApiPriority.P0_ESSENTIAL,
            fallbackGroupId = "currency-core",
            description = "Free currency exchange rates."
        )
        val hfSpeech = ApiMetadata(
            apiId = "hf-whisper",
            name = "HuggingFace Whisper Speech Recognition",
            category = ApiCategory.UTILITY,
            subcategory = ApiSubcategory.TRANSCRIPTION,
            capabilities = listOf(ApiCapability.ANALYZE),
            tags = listOf("speech", "audio", "transcription", "whisper"),
            endpoint = "https://api-inference.huggingface.co/models/openai/whisper-large-v3",
            method = HttpMethod.POST,
            authType = AuthType.BEARER_TOKEN,
            requiredParams = listOf(
                ApiParameter("inputs", "audio", true, "Audio file to transcribe")
            ),
            optionalParams = emptyList(),
            isFree = true,
            priority = ApiPriority.P1_FREQUENT,
            fallbackGroupId = "hf-models",
            description = "Specialized AI model for speech recognition"
        )
        val hfVision = ApiMetadata(
            apiId = "hf-vit",
            name = "HuggingFace Vision Transformer",
            category = ApiCategory.UTILITY,
            subcategory = ApiSubcategory.IMAGE_ANALYSIS,
            capabilities = listOf(ApiCapability.ANALYZE),
            tags = listOf("vision", "image", "classification"),
            endpoint = "https://api-inference.huggingface.co/models/google/vit-base-patch16-224",
            method = HttpMethod.POST,
            authType = AuthType.BEARER_TOKEN,
            requiredParams = listOf(
                ApiParameter("inputs", "image", true, "Image file to classify")
            ),
            optionalParams = emptyList(),
            isFree = true,
            priority = ApiPriority.P1_FREQUENT,
            fallbackGroupId = "hf-models",
            description = "Specialized AI model for image understanding"
        )
        _apis[openMeteo.apiId] = openMeteo
        _apis[frankfurter.apiId] = frankfurter
        _apis[hfSpeech.apiId] = hfSpeech
        _apis[hfVision.apiId] = hfVision
        _apiFlow.value = _apis.values.toList()
    }

    suspend fun addOrUpdateApi(api: ApiMetadata) {
        mutex.withLock {
            _apis[api.apiId] = api
            _apiFlow.value = _apis.values.toList()
        }
    }

    suspend fun getApi(apiId: String): ApiMetadata? {
        return mutex.withLock { _apis[apiId] }
    }

    fun getAllApisSync(): List<ApiMetadata> {
        return _apis.values.toList()
    }

    suspend fun searchApis(
        category: ApiCategory? = null,
        subcategory: ApiSubcategory? = null,
        capability: ApiCapability? = null
    ): List<ApiMetadata> {
        return mutex.withLock {
            _apis.values.filter { api ->
                val catMatch = category == null || api.category == category
                val subcatMatch = subcategory == null || api.subcategory == subcategory
                val capMatch = capability == null || api.capabilities.contains(capability)
                val enabledMatch = api.isEnabled
                catMatch && subcatMatch && capMatch && enabledMatch
            }.sortedWith(
                compareBy<ApiMetadata> { it.priority.ordinal }
                    .thenByDescending { it.reliabilityScore }
                    .thenByDescending { it.speedScore }
            )
        }
    }

    suspend fun updateHealth(apiId: String, healthTransform: (ApiHealth) -> ApiHealth) {
        mutex.withLock {
            val api = _apis[apiId]
            if (api != null) {
                val newHealth = healthTransform(api.health.copy())
                val updatedApi = api.copy(health = newHealth)
                val totalReqs = newHealth.successCount + newHealth.failureCount
                if (totalReqs > 0) {
                    updatedApi.reliabilityScore = ((newHealth.successCount.toFloat() / totalReqs) * 100).toInt()
                }
                _apis[apiId] = updatedApi
                _apiFlow.value = _apis.values.toList()
            }
        }
    }
}
