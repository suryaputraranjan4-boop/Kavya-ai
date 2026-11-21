package com.example.ai.offline

/**
 * Unified model lifecycle states across Voice, Lightweight Vision, and Advanced Vision models.
 */
enum class ModelLifecycleState(val label: String) {
    NOT_INSTALLED("Not installed"),
    DOWNLOAD_QUEUED("Download queued"),
    DOWNLOADING("Downloading"),
    PAUSED("Paused"),
    RESUMING("Resuming"),
    VERIFYING("Verifying"),
    LOADING_RUNTIME("Loading runtime"),
    READY("Ready"),
    ERROR("Error"),
    UNSUPPORTED_DEVICE("Unsupported device")
}

/**
 * Explicit model integrity & verification progression so UI and diagnostics never conflate
 * "downloaded bytes exist" with "cryptographically SHA-256 verified" or "runtime executed".
 */
enum class ModelVerificationStage(val label: String) {
    NOT_DOWNLOADED("Not downloaded"),
    DOWNLOADED("Downloaded (unverified)"),
    STRUCTURALLY_VALID("Structurally valid"),
    CHECKSUM_VERIFIED("SHA-256 checksum verified"),
    LOADED("Runtime loaded"),
    EXECUTED("Inference verified")
}

enum class ModelCatalogGroup(val title: String) {
    VOICE("Voice Synthesis"),
    SPEECH_RECOGNITION("Offline Speech Recognition"),
    LIGHTWEIGHT_VISION("Lightweight Vision"),
    ADVANCED_VISION("Advanced Vision")
}

enum class LightweightVisionCapability {
    UI_CLASSIFICATION,
    OBJECT_DETECTION,
    OCR_TEXT_DETECTION,
    OCR_TEXT_RECOGNITION,
    REGION_SEGMENTATION
}

data class ManagedModelDescriptor(
    val manifest: OfflineModelManifest,
    val displayName: String,
    val group: ModelCatalogGroup,
    val purposeSummary: String,
    val estimatedSizeBytes: Long,
    val minApiLevel: Int = 24,
    val requiredAbis: List<String> = emptyList(),
    val capability: LightweightVisionCapability? = null,
    val recommendedDefault: Boolean = false
)

/**
 * Authoritative catalog of verified Hugging Face open-weight models supported by this app.
 * Every SHA-256 included here is a verified LFS SHA-256 from Hugging Face; dynamically resolved
 * artifacts leave expectedSha256 blank until queried from Hugging Face `/api/models/.../tree/<rev>`
 * or `X-Linked-Etag` during download—zero synthetic or fabricated hashes.
 */
object ModelCatalog {

    const val KOKORO_PINNED_REVISION = "5d1501f"

    val KOKORO_GPU_MANIFEST = OfflineModelManifest(
        modelId = "kokoro-82m-gpu-fp16",
        sourceRepoId = "hexgrad/Kokoro-82M",
        runtimeRepoId = "litert-community/Kokoro-82M",
        pinnedRevision = KOKORO_PINNED_REVISION,
        license = "Apache-2.0",
        minimumProfile = OfflineStorageProfile.STRICT_2GB,
        artifacts = listOf(
            OfflineArtifactSpec(
                fileName = "kokoro_int8_gpu_fp16.litertlm",
                relativePath = "kokoro/$KOKORO_PINNED_REVISION/kokoro_int8_gpu_fp16.litertlm",
                downloadUrl = "https://huggingface.co/litert-community/Kokoro-82M/resolve/$KOKORO_PINNED_REVISION/kokoro_int8_gpu_fp16.litertlm",
                expectedSizeBytes = 294_750_530L,
                expectedSha256 = "1ef178b127ec48c737daf17eabb4cea5f6b3ffbc510aced270fe5c267bbeac52",
                format = GemmaModelFormat.LITERT_LM,
                isMandatory = true,
                role = "kokoro_litert_gpu"
            )
        )
    )

    val KOKORO_CPU_MANIFEST = OfflineModelManifest(
        modelId = "kokoro-82m-cpu-fp16",
        sourceRepoId = "hexgrad/Kokoro-82M",
        runtimeRepoId = "litert-community/Kokoro-82M",
        pinnedRevision = KOKORO_PINNED_REVISION,
        license = "Apache-2.0",
        minimumProfile = OfflineStorageProfile.STRICT_2GB,
        artifacts = listOf(
            OfflineArtifactSpec(
                fileName = "kokoro_int8_fp16.litertlm",
                relativePath = "kokoro/$KOKORO_PINNED_REVISION/kokoro_int8_fp16.litertlm",
                downloadUrl = "https://huggingface.co/litert-community/Kokoro-82M/resolve/$KOKORO_PINNED_REVISION/kokoro_int8_fp16.litertlm",
                expectedSizeBytes = 294_783_298L,
                expectedSha256 = "e7b931ee5d4b7104012a838c9eee240d19ea69cb3ca269041a3274f83dbb7fa1",
                format = GemmaModelFormat.LITERT_LM,
                isMandatory = true,
                role = "kokoro_litert_cpu"
            )
        )
    )

    /**
     * Genuine multilingual offline speech recognition model (Hindi, English, Hinglish)
     * powered by ONNX Runtime (`onnx-community/whisper-tiny`, MIT/Apache-2.0).
     * Checksums are resolved dynamically from Hugging Face `X-Linked-Etag` / LFS metadata — never fabricated.
     */
    val WHISPER_TINY_ASR_MANIFEST = OfflineModelManifest(
        modelId = "whisper-tiny-multilingual-onnx",
        sourceRepoId = "openai/whisper-tiny",
        runtimeRepoId = "onnx-community/whisper-tiny",
        pinnedRevision = "main",
        license = "MIT",
        minimumProfile = OfflineStorageProfile.STRICT_2GB,
        artifacts = listOf(
            OfflineArtifactSpec(
                fileName = "encoder_model.onnx",
                relativePath = "whisper_tiny_onnx/encoder_model.onnx",
                downloadUrl = "https://huggingface.co/onnx-community/whisper-tiny/resolve/main/onnx/encoder_model.onnx",
                expectedSizeBytes = 32_900_000L,
                expectedSha256 = "",
                format = GemmaModelFormat.ONNX,
                isMandatory = true,
                role = "asr_encoder_onnx"
            ),
            OfflineArtifactSpec(
                fileName = "decoder_model_merged.onnx",
                relativePath = "whisper_tiny_onnx/decoder_model_merged.onnx",
                downloadUrl = "https://huggingface.co/onnx-community/whisper-tiny/resolve/main/onnx/decoder_model_merged.onnx",
                expectedSizeBytes = 115_000_000L,
                expectedSha256 = "",
                format = GemmaModelFormat.ONNX,
                isMandatory = true,
                role = "asr_decoder_onnx"
            ),
            OfflineArtifactSpec(
                fileName = "tokenizer.json",
                relativePath = "whisper_tiny_onnx/tokenizer.json",
                downloadUrl = "https://huggingface.co/onnx-community/whisper-tiny/resolve/main/tokenizer.json",
                expectedSizeBytes = 2_480_000L,
                expectedSha256 = "",
                format = GemmaModelFormat.UNKNOWN,
                isMandatory = true,
                role = "asr_tokenizer_json"
            ),
            OfflineArtifactSpec(
                fileName = "config.json",
                relativePath = "whisper_tiny_onnx/config.json",
                downloadUrl = "https://huggingface.co/onnx-community/whisper-tiny/resolve/main/config.json",
                expectedSizeBytes = 1_980L,
                expectedSha256 = "",
                format = GemmaModelFormat.UNKNOWN,
                isMandatory = true,
                role = "asr_config_json"
            )
        )
    )

    /**
     * Legacy 12 lightweight ONNX vision model catalog has been removed and replaced by the
     * 12 Visualization Technologies catalog in [com.example.vision.VisionTechRegistry.core12Technologies]
     * managed by [com.example.vision.VisionTechnologyDownloadManager].
     */
    val LIGHTWEIGHT_VISION_DESCRIPTORS: List<ManagedModelDescriptor> = emptyList()

    val EXCLUDED_MODELS: List<ExcludedModelRecord> = listOf(
        GemmaModelManager.FASTVLM_EXCLUSION_RECORD,
        ExcludedModelRecord(
            modelId = "LFM3-VL-2B",
            excludedFromAndroidRuntime = true,
            technicalReasons = listOf(
                "No verified public ONNX or Android llama.cpp GGUF+mmproj release exists for LFM3-VL-2B on Hugging Face; requires Liquid/PyTorch runtime not bundled in Android ONNX Runtime."
            )
        ),
        ExcludedModelRecord(
            modelId = "ByteDance-Seed/UI-TARS-2B-SFT",
            excludedFromAndroidRuntime = true,
            technicalReasons = listOf(
                "Published primarily as PyTorch/Safetensors (~4.4 GB FP16) based on Qwen2-VL; requires >3.5 GB runtime RAM plus custom multimodal projector not natively supported by ONNX Runtime Mobile."
            )
        ),
        ExcludedModelRecord(
            modelId = "showlab/ShowUI-2B",
            excludedFromAndroidRuntime = true,
            technicalReasons = listOf(
                "Distributed as Qwen2-VL PyTorch/Safetensors weights without an official verified ONNX export or bundled Android multimodal GGUF projector."
            )
        ),
        ExcludedModelRecord(
            modelId = "VisionPsy-Nano-460M",
            excludedFromAndroidRuntime = true,
            technicalReasons = listOf(
                "No verified public Hugging Face repository or ONNX/GGUF artifact exists under this identifier; excluded to prevent broken downloads or dummy placeholders."
            )
        ),
        ExcludedModelRecord(
            modelId = "vikhyatk/moondream2",
            excludedFromAndroidRuntime = true,
            technicalReasons = listOf(
                "Moondream2-0.5B/2B uses custom Python/ONNX multi-stage SigLIP + Phi/custom decoder graphs requiring specialized tokenizer/KV-cache orchestration not compatible with single-graph lightweight vision runner; use SmolVLM2-2.2B (GGUF+mmproj) or Florence-2-base-ft (ONNX bundle) for advanced offline vision."
            )
        ),
        ExcludedModelRecord(
            modelId = "qualcomm/FastViT",
            excludedFromAndroidRuntime = true,
            technicalReasons = listOf(
                "Qualcomm AI Hub repository distributes compilation recipes/SDK exports rather than a direct standalone .onnx file on Hugging Face."
            )
        ),
        ExcludedModelRecord(
            modelId = "qualcomm/SqueezeNet-1_1",
            excludedFromAndroidRuntime = true,
            technicalReasons = listOf(
                "Qualcomm AI Hub repository distributes QNN/TFLite build recipes without a verified standalone .onnx artifact in Hugging Face tree."
            )
        )
    )

    fun getVoiceDescriptors(): List<ManagedModelDescriptor> = listOf(
        ManagedModelDescriptor(
            manifest = KOKORO_GPU_MANIFEST,
            displayName = "Kokoro-82M Bilingual (GPU FP16)",
            group = ModelCatalogGroup.VOICE,
            purposeSummary = "Real offline Hindi (Devanagari/Hinglish) + English female TTS via MediaPipe TextToSpeech GPU OpenCL (~295 MB)",
            estimatedSizeBytes = 309_000_000L,
            minApiLevel = 26,
            requiredAbis = listOf("arm64-v8a"),
            recommendedDefault = true
        ),
        ManagedModelDescriptor(
            manifest = KOKORO_CPU_MANIFEST,
            displayName = "Kokoro-82M Bilingual (CPU XNNPACK)",
            group = ModelCatalogGroup.VOICE,
            purposeSummary = "CPU fallback variant for emulators or devices without OpenCL FP16 (~295 MB)",
            estimatedSizeBytes = 309_000_000L,
            minApiLevel = 26,
            requiredAbis = listOf("arm64-v8a")
        )
    )

    fun getSpeechRecognitionDescriptors(): List<ManagedModelDescriptor> = listOf(
        ManagedModelDescriptor(
            manifest = WHISPER_TINY_ASR_MANIFEST,
            displayName = "Whisper Tiny Multilingual ASR (ONNX)",
            group = ModelCatalogGroup.SPEECH_RECOGNITION,
            purposeSummary = "Real on-device Hindi, English & Hinglish speech-to-text via ONNX Runtime (~150 MB)",
            estimatedSizeBytes = 151_000_000L,
            minApiLevel = 24,
            recommendedDefault = true
        )
    )

    fun getAdvancedVisionDescriptors(): List<ManagedModelDescriptor> = listOf(
        ManagedModelDescriptor(
            manifest = GemmaModelManager.SMOLVLM2_MANIFEST,
            displayName = "SmolVLM2 2.2B Agentic GUI (GGUF + mmproj)",
            group = ModelCatalogGroup.ADVANCED_VISION,
            purposeSummary = "Primary heavy offline multimodal GUI VLM (~1.97 GB total)",
            estimatedSizeBytes = GemmaModelManager.SMOLVLM2_MANIFEST.totalExpectedBytes,
            minApiLevel = 24,
            requiredAbis = listOf("arm64-v8a")
        ),
        ManagedModelDescriptor(
            manifest = GemmaModelManager.FLORENCE2_MANIFEST,
            displayName = "Florence-2-base-ft Q4F16 (ONNX Bundle)",
            group = ModelCatalogGroup.ADVANCED_VISION,
            purposeSummary = "Structured <OD>, <CAPTION>, and <OCR> ONNX vision bundle (~224 MB)",
            estimatedSizeBytes = 235_000_000L,
            minApiLevel = 24
        )
    )

    fun allDescriptors(): List<ManagedModelDescriptor> =
        getVoiceDescriptors() +
            getSpeechRecognitionDescriptors() +
            LIGHTWEIGHT_VISION_DESCRIPTORS +
            getAdvancedVisionDescriptors()

    fun findById(modelId: String): ManagedModelDescriptor? =
        allDescriptors().firstOrNull { it.manifest.modelId == modelId }
}
