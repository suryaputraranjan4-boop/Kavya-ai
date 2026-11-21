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

enum class ModelCatalogGroup(val title: String) {
    VOICE("Voice Synthesis"),
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
 * artifacts leave expectedSha256 blank until queried from Hugging Face `/api/models/.../tree/main`
 * or `X-Linked-Etag` during download—zero synthetic or fabricated hashes.
 */
object ModelCatalog {

    val KOKORO_GPU_MANIFEST = OfflineModelManifest(
        modelId = "kokoro-82m-gpu-fp16",
        sourceRepoId = "hexgrad/Kokoro-82M",
        runtimeRepoId = "litert-community/Kokoro-82M",
        pinnedRevision = "main",
        license = "Apache-2.0",
        minimumProfile = OfflineStorageProfile.STRICT_2GB,
        artifacts = listOf(
            OfflineArtifactSpec(
                fileName = "kokoro_int8_gpu_fp16.litertlm",
                relativePath = "kokoro/main/kokoro_int8_gpu_fp16.litertlm",
                downloadUrl = "https://huggingface.co/litert-community/Kokoro-82M/resolve/main/kokoro_int8_gpu_fp16.litertlm",
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
        pinnedRevision = "main",
        license = "Apache-2.0",
        minimumProfile = OfflineStorageProfile.STRICT_2GB,
        artifacts = listOf(
            OfflineArtifactSpec(
                fileName = "kokoro_int8_fp16.litertlm",
                relativePath = "kokoro/main/kokoro_int8_fp16.litertlm",
                downloadUrl = "https://huggingface.co/litert-community/Kokoro-82M/resolve/main/kokoro_int8_fp16.litertlm",
                expectedSizeBytes = 294_783_298L,
                expectedSha256 = "e7b931ee5d4b7104012a838c9eee240d19ea69cb3ca269041a3274f83dbb7fa1",
                format = GemmaModelFormat.LITERT_LM,
                isMandatory = true,
                role = "kokoro_litert_cpu"
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
        getVoiceDescriptors() + LIGHTWEIGHT_VISION_DESCRIPTORS + getAdvancedVisionDescriptors()

    fun findById(modelId: String): ManagedModelDescriptor? =
        allDescriptors().firstOrNull { it.manifest.modelId == modelId }
}
