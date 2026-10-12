package com.example.api

enum class ApiCategory {
    AI_ML, WEATHER, LOCATION, FINANCE, CURRENCY, NEWS, MUSIC, MOVIES, ENTERTAINMENT,
    GAMING, BOOKS, EDUCATION, SCIENCE, SPACE, HEALTH, BUSINESS, SHOPPING, SOCIAL,
    SEARCH, MEDIA, TRANSLATION, COMMUNICATION, TECH, NETWORK, SECURITY, GOVERNMENT,
    SPORTS, TRAVEL, FOOD, UTILITIES, UTILITY, DATA, NATURE, OPENDATA, DEVELOPMENT, OTHER, UNKNOWN, TRANSCRIPTION, IMAGE_ANALYSIS
}

enum class ApiSubcategory {
    CURRENT_WEATHER, FORECAST, HISTORICAL_WEATHER,
    EXCHANGE_RATES, STOCKS, CRYPTO,
    GEOCODING, PLACES, DIRECTIONS,
    GENERAL_NEWS, TECH_NEWS, SPORTS_NEWS,
    TEXT_TRANSLATION, DICTIONARY,
    WEB_SEARCH, IMAGE_SEARCH,
    CALCULATOR, CONVERTER,
    UNKNOWN, TRANSCRIPTION, IMAGE_ANALYSIS
}

enum class ApiCapability {
    SEARCH, LOOKUP, GENERATE, CONVERT, TRANSLATE, SUMMARIZE, FORECAST, CALCULATE,
    DETECT, RECOGNIZE, CLASSIFY, COMPARE, TRACK, MONITOR, RETRIEVE, CREATE,
    DOWNLOAD, UPLOAD, GEOCODE, NAVIGATE, STREAM, RECOMMEND, ANALYZE, VALIDATE,
    VERIFY, QUERY, AUTOCOMPLETE
}

enum class ApiHealthStatus {
    HEALTHY, SLOW, DEGRADED, FAILED, RATE_LIMITED, UNAVAILABLE, UNKNOWN, TRANSCRIPTION, IMAGE_ANALYSIS
}

enum class ApiPriority {
    P0_ESSENTIAL, P1_FREQUENT, P2_USEFUL, P3_RARE
}

enum class HttpMethod {
    GET, POST, PUT, DELETE, PATCH
}

enum class AuthType {
    NONE, API_KEY_QUERY, API_KEY_HEADER, BEARER_TOKEN, OAUTH
}

data class ApiParameter(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String
)

data class ApiHealth(
    var status: ApiHealthStatus = ApiHealthStatus.UNKNOWN,
    var successCount: Int = 0,
    var failureCount: Int = 0,
    var avgResponseTimeMs: Long = 0,
    var lastSuccessfulRequestTs: Long = 0,
    var lastFailureTs: Long = 0
)

data class ApiMetadata(
    val apiId: String,
    val name: String,
    val category: ApiCategory,
    val subcategory: ApiSubcategory,
    val capabilities: List<ApiCapability>,
    val tags: List<String>,
    val endpoint: String,
    val method: HttpMethod = HttpMethod.GET,
    val authType: AuthType = AuthType.NONE,
    val authKeyName: String? = null,
    val requiredParams: List<ApiParameter> = emptyList(),
    val optionalParams: List<ApiParameter> = emptyList(),
    val responseFormat: String = "JSON",
    var reliabilityScore: Int = 100,
    var speedScore: Int = 100,
    val isFree: Boolean = true,
    val priority: ApiPriority = ApiPriority.P2_USEFUL,
    val fallbackGroupId: String? = null,
    var isEnabled: Boolean = true,
    val description: String,
    val health: ApiHealth = ApiHealth()
)

data class IntentMatch(
    val intentName: String,
    val category: ApiCategory,
    val subcategory: ApiSubcategory,
    val capability: ApiCapability,
    val extractedParams: Map<String, String>,
    val requiresApi: Boolean,
    val requiredTool: String? = null
)
