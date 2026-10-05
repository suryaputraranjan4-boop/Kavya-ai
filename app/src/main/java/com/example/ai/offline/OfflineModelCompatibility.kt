package com.example.ai.offline

/** Generic local-model format metadata; no model family is hard-coded. */
enum class OfflineModelFormat { LITERT_LM, MEDIA_PIPE_TASK, GGUF, UNKNOWN }

enum class OfflineRuntimeCapability { LITERT_LM_INFERENCE, MEDIAPIPE_TASK_INFERENCE, GGUF_INFERENCE, UNSUPPORTED }

object OfflineModelCompatibility {
    fun formatForName(name: String): OfflineModelFormat = when (name.substringAfterLast('.', "").lowercase()) {
        "litertlm" -> OfflineModelFormat.LITERT_LM
        "task" -> OfflineModelFormat.MEDIA_PIPE_TASK
        "gguf" -> OfflineModelFormat.GGUF
        else -> OfflineModelFormat.UNKNOWN
    }

    fun capabilityFor(format: OfflineModelFormat): OfflineRuntimeCapability = when (format) {
        OfflineModelFormat.LITERT_LM -> OfflineRuntimeCapability.LITERT_LM_INFERENCE
        OfflineModelFormat.MEDIA_PIPE_TASK -> OfflineRuntimeCapability.MEDIAPIPE_TASK_INFERENCE
        OfflineModelFormat.GGUF -> OfflineRuntimeCapability.GGUF_INFERENCE
        OfflineModelFormat.UNKNOWN -> OfflineRuntimeCapability.UNSUPPORTED
    }
}
