package com.example.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject
import retrofit2.HttpException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.math.min
import kotlin.random.Random

/**
 * Central AI Error Classification & Recovery System (Upgrade 1).
 * Distinguishes every AI, HTTP, streaming, network, and voice input failure mode
 * with accurate user-facing messages, retryability rules, and secret redaction.
 */
enum class AiErrorCategory(
    val isRetryableByDefault: Boolean,
    val userFacingHindi: String,
    val userFacingEnglish: String
) {
    INVALID_API_KEY(
        isRetryableByDefault = false,
        userFacingHindi = "AI API key मान्य नहीं है। कृपया Settings में अपनी API key जांचें।",
        userFacingEnglish = "The configured AI API key is invalid. Please check your API key in Settings."
    ),
    MISSING_API_KEY(
        isRetryableByDefault = false,
        userFacingHindi = "AI सेवा के लिए API key कॉन्फ़िगर नहीं है। कृपया Settings में API key जोड़ें।",
        userFacingEnglish = "No AI API key is configured. Please add your API key in Settings."
    ),
    AUTHENTICATION_FAILURE(
        isRetryableByDefault = false,
        userFacingHindi = "AI सेवा प्रमाणीकरण (Authentication) विफल रहा। कृपया अपनी API key या खाता जांचें।",
        userFacingEnglish = "AI service authentication failed (HTTP 401). Please verify your credentials."
    ),
    PERMISSION_DENIED(
        isRetryableByDefault = false,
        userFacingHindi = "इस AI मॉडल या प्रोजेक्ट के लिए अनुमति (Permission) उपलब्ध नहीं है।",
        userFacingEnglish = "Access to this AI model or resource was denied (HTTP 403)."
    ),
    RATE_LIMIT(
        isRetryableByDefault = true,
        userFacingHindi = "अभी बहुत सारे अनुरोध भेजे गए हैं (Rate Limit)। कृपया कुछ सेकंड प्रतीक्षा करें और फिर प्रयास करें।",
        userFacingEnglish = "AI rate limit reached (HTTP 429). Please wait a few seconds and try again."
    ),
    QUOTA_EXHAUSTED(
        isRetryableByDefault = false,
        userFacingHindi = "आपकी AI API का कोटा या बिलिंग लिमिट समाप्त हो गई है। कृपया अपना प्लान या दूसरी API key जांचें।",
        userFacingEnglish = "Your AI API quota or billing limit is exhausted. Please check your plan or switch providers."
    ),
    NETWORK_UNAVAILABLE(
        isRetryableByDefault = true,
        userFacingHindi = "इंटरनेट कनेक्शन उपलब्ध नहीं है। कृपया अपना Wi-Fi या मोबाइल डेटा चालू करें।",
        userFacingEnglish = "No internet connection available. Please check your Wi-Fi or mobile data."
    ),
    DNS_FAILURE(
        isRetryableByDefault = true,
        userFacingHindi = "AI सर्वर का पता (DNS) नहीं मिल पा रहा है। कृपया अपना इंटरनेट कनेक्शन जांचें।",
        userFacingEnglish = "Could not resolve the AI server address (DNS failure). Please check your network."
    ),
    CONNECTION_TIMEOUT(
        isRetryableByDefault = true,
        userFacingHindi = "AI सर्वर से जवाब आने में अधिक समय लग गया (Timeout)। कृपया दोबारा प्रयास करें।",
        userFacingEnglish = "Connection to the AI server timed out. Please try again."
    ),
    SERVER_ERROR(
        isRetryableByDefault = true,
        userFacingHindi = "AI सर्वर में आंतरिक त्रुटि (HTTP 500) आई है। कृपया कुछ क्षण बाद प्रयास करें।",
        userFacingEnglish = "The AI provider experienced an internal server error (HTTP 500). Please try again shortly."
    ),
    SERVICE_UNAVAILABLE(
        isRetryableByDefault = true,
        userFacingHindi = "AI सेवा अभी अस्थायी रूप से व्यस्त या अनुपलब्ध (HTTP 503) है। कृपया थोड़ी देर में प्रयास करें।",
        userFacingEnglish = "The AI service is temporarily overloaded or unavailable (HTTP 503). Please try again shortly."
    ),
    INVALID_REQUEST(
        isRetryableByDefault = false,
        userFacingHindi = "AI अनुरोध का प्रारूप अमान्य है (Invalid Request / Model Configuration)।",
        userFacingEnglish = "The AI request or model configuration was invalid (HTTP 400)."
    ),
    EMPTY_RESPONSE(
        isRetryableByDefault = true,
        userFacingHindi = "AI मॉडल से खाली उत्तर प्राप्त हुआ। कृपया अपना प्रश्न दोबारा पूछें।",
        userFacingEnglish = "The AI model returned an empty response. Please try asking again."
    ),
    MALFORMED_RESPONSE(
        isRetryableByDefault = false,
        userFacingHindi = "AI सर्वर से अपूर्ण या अमान्य डेटा प्राप्त हुआ। कृपया दोबारा प्रयास करें।",
        userFacingEnglish = "Received a malformed response from the AI service. Please try again."
    ),
    STREAMING_INTERRUPTION(
        isRetryableByDefault = true,
        userFacingHindi = "AI का उत्तर बीच में ही रुक गया (Stream Interrupted)। कृपया दोबारा प्रयास करें।",
        userFacingEnglish = "The AI response stream was interrupted before completion. Please try again."
    ),
    REQUEST_CANCELLED(
        isRetryableByDefault = false,
        userFacingHindi = "अनुरोध रद्द कर दिया गया।",
        userFacingEnglish = "The request was cancelled."
    ),
    UNKNOWN_FAILURE(
        isRetryableByDefault = false,
        userFacingHindi = "AI सेवा से जुड़ने में अप्रत्याशित त्रुटि हुई। कृपया दोबारा प्रयास करें।",
        userFacingEnglish = "An unexpected error occurred while communicating with the AI service."
    )
}

/**
 * Microphone & Speech-to-Text / Transcription Error Categories (Upgrade 1.6).
 */
enum class VoiceInputErrorCategory(
    val isRetryable: Boolean,
    val userFacingHindi: String,
    val userFacingEnglish: String
) {
    MIC_PERMISSION_DENIED(
        isRetryable = false,
        userFacingHindi = "माइक्रोफ़ोन की अनुमति (Permission) नहीं दी गई है। कृपया ऐप सेटिंग्स में माइक्रोफ़ोन अनुमति दें।",
        userFacingEnglish = "Microphone permission is denied. Please grant microphone access in Settings."
    ),
    MIC_UNAVAILABLE(
        isRetryable = true,
        userFacingHindi = "माइक्रोफ़ोन अभी किसी अन्य ऐप द्वारा उपयोग में है या उपलब्ध नहीं है।",
        userFacingEnglish = "The microphone is currently busy or unavailable."
    ),
    AUDIO_INIT_FAILURE(
        isRetryable = true,
        userFacingHindi = "ऑडियो रिकॉर्डर शुरू नहीं हो सका। कृपया दोबारा प्रयास करें।",
        userFacingEnglish = "Audio capture failed to initialize. Please try again."
    ),
    RECOGNITION_SERVICE_UNAVAILABLE(
        isRetryable = true,
        userFacingHindi = "आवाज़ पहचानने की सेवा (Speech Recognition) इस डिवाइस पर उपलब्ध नहीं है।",
        userFacingEnglish = "Speech recognition service is unavailable on this device."
    ),
    NO_SPEECH_RECOGNIZED(
        isRetryable = true,
        userFacingHindi = "कोई स्पष्ट आवाज़ सुनाई नहीं दी। कृपया थोड़ा पास से बोलें।",
        userFacingEnglish = "No speech was detected. Please speak clearly into the microphone."
    ),
    RECOGNITION_NETWORK_FAILURE(
        isRetryable = true,
        userFacingHindi = "आवाज़ पहचानने के लिए नेटवर्क कनेक्शन विफल रहा।",
        userFacingEnglish = "Network failure occurred during speech recognition."
    ),
    RECOGNITION_TIMEOUT(
        isRetryable = true,
        userFacingHindi = "आवाज़ सुनने का समय समाप्त हो गया (Timeout)। कृपया फिर से बोलें।",
        userFacingEnglish = "Speech recognition timed out. Please try speaking again."
    ),
    UNSUPPORTED_CONFIGURATION(
        isRetryable = false,
        userFacingHindi = "यह भाषा या ऑडियो कॉन्फ़िगरेशन समर्थित नहीं है।",
        userFacingEnglish = "The requested speech language or audio configuration is unsupported."
    ),
    EMPTY_TRANSCRIPTION(
        isRetryable = true,
        userFacingHindi = "ऑडियो से कोई शब्द पहचाना नहीं जा सका। कृपया दोबारा बोलें।",
        userFacingEnglish = "Transcription returned empty text. Please speak again."
    ),
    TRANSCRIPTION_SERVICE_ERROR(
        isRetryable = true,
        userFacingHindi = "ऑडियो ट्रांसक्रिप्शन सेवा में त्रुटि हुई। कृपया दोबारा प्रयास करें।",
        userFacingEnglish = "The transcription service encountered an error. Please try again."
    )
}

data class AiClassifiedError(
    val category: AiErrorCategory,
    val httpStatus: Int? = null,
    val providerStatus: String? = null,
    val providerMessage: String = "",
    val isRetryable: Boolean = category.isRetryableByDefault,
    val retryAfterMs: Long? = null,
    val userFacingText: String = category.userFacingHindi,
    val userFacingSpokenText: String = category.userFacingHindi,
    val redactedLogSummary: String = ""
) {
    fun toErrorPrefixString(): String = "Error:[${category.name}] $userFacingText"
}

class AiDiagnosticException(
    val classified: AiClassifiedError,
    cause: Throwable? = null
) : IOException(classified.redactedLogSummary.ifBlank { classified.category.userFacingEnglish }, cause)

/**
 * Redacts API keys, Bearer tokens, Authorization headers, and query credentials from logs/diagnostics.
 */
object SecretRedactor {
    private val keyQueryRegex = Regex("""([?&](?:key|api_key|apikey|token|access_token)=)([^&\s"']+)""", RegexOption.IGNORE_CASE)
    private val bearerRegex = Regex("""(Bearer\s+)([A-Za-z0-9._\-+/=]{8,})""", RegexOption.IGNORE_CASE)
    private val googleKeyRegex = Regex("""AIza[0-9A-Za-z\-_]{15,}""")
    private val skKeyRegex = Regex("""(?:sk|gsk|xai)-[0-9A-Za-z\-_]{12,}""")
    private val jsonSecretRegex = Regex("""("(?:apiKey|api_key|authorization|x-goog-api-key|token)"\s*:\s*")([^"]+)(")""", RegexOption.IGNORE_CASE)

    fun redact(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw
            .replace(keyQueryRegex, "$1[REDACTED]")
            .replace(bearerRegex, "$1[REDACTED]")
            .replace(googleKeyRegex, "AIza[REDACTED]")
            .replace(skKeyRegex, "sk-[REDACTED]")
            .replace(jsonSecretRegex, "$1[REDACTED]$3")
    }
}

object AiErrorClassifier {

    @Volatile
    var lastClassifiedError: AiClassifiedError? = null
        private set

    @Volatile
    var lastVoiceInputError: VoiceInputErrorCategory? = null
        private set

    fun recordError(error: AiClassifiedError): AiClassifiedError {
        lastClassifiedError = error
        return error
    }

    fun recordVoiceError(error: VoiceInputErrorCategory): VoiceInputErrorCategory {
        lastVoiceInputError = error
        return error
    }

    fun clearLastError() {
        lastClassifiedError = null
    }

    /**
     * Parses HTTP status code, raw error body JSON, and Retry-After header into a structured [AiClassifiedError].
     */
    fun classifyHttpResponse(
        httpStatus: Int,
        errorBody: String?,
        retryAfterHeader: String? = null
    ): AiClassifiedError {
        val redactedBody = SecretRedactor.redact(errorBody)
        var providerStatus = ""
        var providerMessage = ""
        var errorReason = ""

        if (!errorBody.isNullOrBlank()) {
            try {
                val root = JSONObject(errorBody)
                val errObj = root.optJSONObject("error") ?: root
                providerStatus = errObj.optString("status", "")
                providerMessage = errObj.optString("message", "")
                val details = errObj.optJSONArray("details")
                if (details != null) {
                    for (i in 0 until details.length()) {
                        val d = details.optJSONObject(i) ?: continue
                        val r = d.optString("reason", "")
                        if (r.isNotBlank()) {
                            errorReason = r
                            break
                        }
                    }
                }
            } catch (_: Exception) {
                providerMessage = redactedBody.take(240)
            }
        }

        val combinedLower = "$providerStatus $errorReason $providerMessage $redactedBody".lowercase()
        val parsedRetryAfterMs = parseRetryAfterMs(retryAfterHeader, combinedLower)

        val category = when (httpStatus) {
            400 -> {
                if (combinedLower.contains("api_key_invalid") ||
                    combinedLower.contains("api key not valid") ||
                    combinedLower.contains("invalid api key") ||
                    combinedLower.contains("keyexpired")
                ) {
                    AiErrorCategory.INVALID_API_KEY
                } else {
                    AiErrorCategory.INVALID_REQUEST
                }
            }
            401 -> {
                if (combinedLower.contains("api key") || combinedLower.contains("invalid_api_key")) {
                    AiErrorCategory.INVALID_API_KEY
                } else {
                    AiErrorCategory.AUTHENTICATION_FAILURE
                }
            }
            403 -> {
                if (combinedLower.contains("api_key_invalid") || combinedLower.contains("invalid api key")) {
                    AiErrorCategory.INVALID_API_KEY
                } else if (combinedLower.contains("quota") || combinedLower.contains("billing")) {
                    AiErrorCategory.QUOTA_EXHAUSTED
                } else {
                    AiErrorCategory.PERMISSION_DENIED
                }
            }
            404 -> AiErrorCategory.INVALID_REQUEST
            408 -> AiErrorCategory.CONNECTION_TIMEOUT
            429 -> {
                val isHardQuota = combinedLower.contains("quota") &&
                    (combinedLower.contains("billing") ||
                        combinedLower.contains("plan") ||
                        combinedLower.contains("daily") ||
                        combinedLower.contains("per day") ||
                        combinedLower.contains("limit: 0") ||
                        combinedLower.contains("insufficient_quota"))
                if (isHardQuota) {
                    AiErrorCategory.QUOTA_EXHAUSTED
                } else {
                    AiErrorCategory.RATE_LIMIT
                }
            }
            500, 502 -> AiErrorCategory.SERVER_ERROR
            503, 504, 529 -> AiErrorCategory.SERVICE_UNAVAILABLE
            else -> {
                if (httpStatus in 500..599) AiErrorCategory.SERVER_ERROR
                else if (httpStatus in 400..499) AiErrorCategory.INVALID_REQUEST
                else AiErrorCategory.UNKNOWN_FAILURE
            }
        }

        val summary = buildString {
            append("HTTP $httpStatus -> ${category.name}")
            if (providerStatus.isNotBlank()) append(" [$providerStatus]")
            if (errorReason.isNotBlank()) append(" ($errorReason)")
            if (providerMessage.isNotBlank()) append(": ${SecretRedactor.redact(providerMessage).take(160)}")
        }

        return recordError(
            AiClassifiedError(
                category = category,
                httpStatus = httpStatus,
                providerStatus = providerStatus.ifBlank { null },
                providerMessage = SecretRedactor.redact(providerMessage),
                isRetryable = category.isRetryableByDefault,
                retryAfterMs = parsedRetryAfterMs,
                userFacingText = category.userFacingHindi,
                userFacingSpokenText = category.userFacingHindi,
                redactedLogSummary = summary
            )
        )
    }

    /**
     * Classifies any Throwable (HttpException, network IOExceptions, CancellationException, etc.).
     */
    fun classifyThrowable(throwable: Throwable): AiClassifiedError {
        if (throwable is CancellationException) {
            return AiClassifiedError(
                category = AiErrorCategory.REQUEST_CANCELLED,
                isRetryable = false,
                userFacingText = AiErrorCategory.REQUEST_CANCELLED.userFacingHindi,
                userFacingSpokenText = AiErrorCategory.REQUEST_CANCELLED.userFacingHindi,
                redactedLogSummary = "Request cancelled"
            )
        }
        if (throwable is AiDiagnosticException) {
            return recordError(throwable.classified)
        }
        if (throwable is HttpException) {
            val code = throwable.code()
            val errorBody = try {
                throwable.response()?.errorBody()?.string()
            } catch (_: Exception) {
                null
            }
            val retryAfter = try {
                throwable.response()?.headers()?.get("Retry-After")
            } catch (_: Exception) {
                null
            }
            return classifyHttpResponse(code, errorBody, retryAfter)
        }

        val messageLower = SecretRedactor.redact(throwable.message ?: "").lowercase()

        val category = when (throwable) {
            is UnknownHostException -> {
                if (messageLower.contains("unable to resolve host") || messageLower.contains("no address associated")) {
                    AiErrorCategory.DNS_FAILURE
                } else {
                    AiErrorCategory.NETWORK_UNAVAILABLE
                }
            }
            is SocketTimeoutException -> AiErrorCategory.CONNECTION_TIMEOUT
            is ConnectException -> AiErrorCategory.NETWORK_UNAVAILABLE
            is EOFException -> AiErrorCategory.STREAMING_INTERRUPTION
            is SSLException -> AiErrorCategory.NETWORK_UNAVAILABLE
            is IOException -> {
                when {
                    messageLower.contains("api key") && messageLower.contains("missing") -> AiErrorCategory.MISSING_API_KEY
                    messageLower.contains("api_key_invalid") || messageLower.contains("invalid api key") -> AiErrorCategory.INVALID_API_KEY
                    messageLower.contains("insufficient_quota") ||
                        (messageLower.contains("quota") && (messageLower.contains("exceeded") || messageLower.contains("billing") || messageLower.contains("exhausted") || messageLower.contains("credit limit") || messageLower.contains("limit: 0") || messageLower.contains("resource_exhausted"))) -> AiErrorCategory.QUOTA_EXHAUSTED
                    messageLower.contains("rate limit") || messageLower.contains("429") -> AiErrorCategory.RATE_LIMIT
                    messageLower.contains("malformed") || messageLower.contains("json") -> AiErrorCategory.MALFORMED_RESPONSE
                    messageLower.contains("empty response") -> AiErrorCategory.EMPTY_RESPONSE
                    messageLower.contains("timeout") || messageLower.contains("timed out") -> AiErrorCategory.CONNECTION_TIMEOUT
                    messageLower.contains("unable to resolve host") || messageLower.contains("dns") -> AiErrorCategory.DNS_FAILURE
                    messageLower.contains("network is unreachable") ||
                        messageLower.contains("failed to connect") ||
                        messageLower.contains("connection refused") ||
                        messageLower.contains("offline") -> AiErrorCategory.NETWORK_UNAVAILABLE
                    messageLower.contains("stream") || messageLower.contains("prematurely") || messageLower.contains("reset") ->
                        AiErrorCategory.STREAMING_INTERRUPTION
                    else -> AiErrorCategory.NETWORK_UNAVAILABLE
                }
            }
            else -> {
                when {
                    messageLower.contains("api key") && messageLower.contains("missing") -> AiErrorCategory.MISSING_API_KEY
                    messageLower.contains("api_key_invalid") || messageLower.contains("invalid api key") -> AiErrorCategory.INVALID_API_KEY
                    messageLower.contains("insufficient_quota") ||
                        (messageLower.contains("quota") && (messageLower.contains("exceeded") || messageLower.contains("billing") || messageLower.contains("exhausted") || messageLower.contains("credit limit") || messageLower.contains("limit: 0") || messageLower.contains("resource_exhausted"))) -> AiErrorCategory.QUOTA_EXHAUSTED
                    messageLower.contains("rate limit") || messageLower.contains("429") -> AiErrorCategory.RATE_LIMIT
                    messageLower.contains("malformed") || messageLower.contains("json") -> AiErrorCategory.MALFORMED_RESPONSE
                    messageLower.contains("empty response") -> AiErrorCategory.EMPTY_RESPONSE
                    else -> AiErrorCategory.UNKNOWN_FAILURE
                }
            }
        }

        val summary = "${throwable.javaClass.simpleName} -> ${category.name}: ${SecretRedactor.redact(throwable.message).take(160)}"
        return recordError(
            AiClassifiedError(
                category = category,
                isRetryable = category.isRetryableByDefault,
                userFacingText = category.userFacingHindi,
                userFacingSpokenText = category.userFacingHindi,
                redactedLogSummary = summary
            )
        )
    }

    /**
     * Classifies Android SpeechRecognizer error codes into [VoiceInputErrorCategory].
     */
    fun classifySpeechRecognizerError(errorCode: Int): VoiceInputErrorCategory {
        // Android SpeechRecognizer constants:
        // 1 = ERROR_NETWORK_TIMEOUT, 2 = ERROR_NETWORK, 3 = ERROR_AUDIO, 4 = ERROR_SERVER,
        // 5 = ERROR_CLIENT, 6 = ERROR_SPEECH_TIMEOUT, 7 = ERROR_NO_MATCH,
        // 8 = ERROR_RECOGNIZER_BUSY, 9 = ERROR_INSUFFICIENT_PERMISSIONS,
        // 12 = ERROR_LANGUAGE_NOT_SUPPORTED, 13 = ERROR_LANGUAGE_UNAVAILABLE
        val category = when (errorCode) {
            1 -> VoiceInputErrorCategory.RECOGNITION_TIMEOUT
            2 -> VoiceInputErrorCategory.RECOGNITION_NETWORK_FAILURE
            3 -> VoiceInputErrorCategory.AUDIO_INIT_FAILURE
            4 -> VoiceInputErrorCategory.TRANSCRIPTION_SERVICE_ERROR
            5 -> VoiceInputErrorCategory.MIC_UNAVAILABLE
            6 -> VoiceInputErrorCategory.RECOGNITION_TIMEOUT
            7 -> VoiceInputErrorCategory.NO_SPEECH_RECOGNIZED
            8 -> VoiceInputErrorCategory.MIC_UNAVAILABLE
            9 -> VoiceInputErrorCategory.MIC_PERMISSION_DENIED
            12, 13 -> VoiceInputErrorCategory.UNSUPPORTED_CONFIGURATION
            else -> VoiceInputErrorCategory.RECOGNITION_SERVICE_UNAVAILABLE
        }
        return recordVoiceError(category)
    }

    /**
     * Maps any raw error string or prefix (`Error:[CATEGORY] ...` or legacy strings) into
     * consistent spoken and displayed explanation without collapsing distinct causes.
     */
    fun resolveConsistentUserExplanation(rawText: String): String {
        val trimmed = rawText.trim()
        if (trimmed.startsWith("Error:[")) {
            val endBracket = trimmed.indexOf(']')
            if (endBracket > 7) {
                val catName = trimmed.substring(7, endBracket)
                val matched = AiErrorCategory.values().firstOrNull { it.name == catName }
                if (matched != null) {
                    return matched.userFacingHindi
                }
            }
        }

        val lower = trimmed.lowercase()
        return when {
            lower.contains("api key is not configured") || lower.contains("missing_api_key") || lower.contains("api key कॉन्फ़िगर नहीं") ->
                AiErrorCategory.MISSING_API_KEY.userFacingHindi
            lower.contains("api_key_invalid") || lower.contains("invalid api key") || lower.contains("api key मान्य नहीं") ->
                AiErrorCategory.INVALID_API_KEY.userFacingHindi
            lower.contains("401") || lower.contains("authentication") ->
                AiErrorCategory.AUTHENTICATION_FAILURE.userFacingHindi
            lower.contains("403") || lower.contains("permission denied") ->
                AiErrorCategory.PERMISSION_DENIED.userFacingHindi
            lower.contains("insufficient_quota") || (lower.contains("quota") && (lower.contains("billing") || lower.contains("exceeded") || lower.contains("exhausted") || lower.contains("credit limit"))) ->
                AiErrorCategory.QUOTA_EXHAUSTED.userFacingHindi
            lower.contains("429") || lower.contains("rate limit") || lower.contains("rate_limit") ->
                AiErrorCategory.RATE_LIMIT.userFacingHindi
            lower.contains("dns") || lower.contains("unable to resolve host") ->
                AiErrorCategory.DNS_FAILURE.userFacingHindi
            lower.contains("timeout") || lower.contains("timed out") ->
                AiErrorCategory.CONNECTION_TIMEOUT.userFacingHindi
            lower.contains("503") || lower.contains("service unavailable") || lower.contains("overloaded") ->
                AiErrorCategory.SERVICE_UNAVAILABLE.userFacingHindi
            lower.contains("500") || lower.contains("server error") ->
                AiErrorCategory.SERVER_ERROR.userFacingHindi
            lower.contains("offline") || lower.contains("no internet") || lower.contains("network unavailable") ->
                AiErrorCategory.NETWORK_UNAVAILABLE.userFacingHindi
            lower.contains("stream") && lower.contains("interrupt") ->
                AiErrorCategory.STREAMING_INTERRUPTION.userFacingHindi
            lower.contains("malformed") ->
                AiErrorCategory.MALFORMED_RESPONSE.userFacingHindi
            lower.contains("empty response") ->
                AiErrorCategory.EMPTY_RESPONSE.userFacingHindi
            else -> lastClassifiedError?.userFacingText ?: trimmed.removePrefix("Error:").trim()
        }
    }

    fun isErrorText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.startsWith("Error:[") || trimmed.startsWith("Error:")) return true
        return AiErrorCategory.values().any { it.userFacingHindi == trimmed || it.userFacingEnglish == trimmed }
    }

    internal fun parseRetryAfterMs(retryAfterHeader: String?, bodyLower: String = ""): Long? {
        if (!retryAfterHeader.isNullOrBlank()) {
            val seconds = retryAfterHeader.trim().toLongOrNull()
            if (seconds != null && seconds >= 0) {
                return min(seconds * 1000L, 15_000L)
            }
        }
        val retryInRegex = Regex("""retry in (\d+(?:\.\d+)?)\s*s""")
        val match = retryInRegex.find(bodyLower)
        if (match != null) {
            val secs = match.groupValues[1].toDoubleOrNull()
            if (secs != null && secs > 0) {
                return min((secs * 1000.0).toLong(), 15_000L)
            }
        }
        return null
    }

    /**
     * Executes a suspending block with bounded retry, exponential backoff + jitter,
     * and non-retryable fast-fail.
     */
    suspend fun <T> executeWithBoundedRetry(
        maxAttempts: Int = 3,
        initialDelayMs: Long = 800L,
        maxDelayMs: Long = 6000L,
        onRetry: ((attempt: Int, classified: AiClassifiedError, delayMs: Long) -> Unit)? = null,
        block: suspend (attempt: Int) -> T
    ): T {
        var currentDelay = initialDelayMs
        var lastError: AiClassifiedError? = null
        var lastThrowable: Throwable? = null

        for (attempt in 1..maxAttempts.coerceAtLeast(1)) {
            try {
                return block(attempt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                val classified = classifyThrowable(t)
                lastError = classified
                lastThrowable = t
                if (!classified.isRetryable || attempt >= maxAttempts) {
                    throw AiDiagnosticException(classified, t)
                }
                val jitter = Random.nextLong(50L, 250L)
                val waitMs = (classified.retryAfterMs ?: currentDelay) + jitter
                val boundedWaitMs = min(waitMs, maxDelayMs)
                onRetry?.invoke(attempt, classified, boundedWaitMs)
                delay(boundedWaitMs)
                currentDelay = min(currentDelay * 2, maxDelayMs)
            }
        }
        throw AiDiagnosticException(
            lastError ?: classifyThrowable(lastThrowable ?: IOException("Retry exhausted")),
            lastThrowable
        )
    }
}
