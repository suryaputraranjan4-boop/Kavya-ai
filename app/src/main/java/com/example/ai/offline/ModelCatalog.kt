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

    val LIGHTWEIGHT_VISION_DESCRIPTORS: List<ManagedModelDescriptor> = listOf(
        ManagedModelDescriptor(
            manifest = OfflineModelManifest(
                modelId = "mobilenet-v3-small",
                sourceRepoId = "qualcomm/MobileNet-v3-Small",
                runtimeRepoId = "qualcomm/MobileNet-v3-Small",
                pinnedRevision = "main",
                license = "BSD-3-Clause",
                minimumProfile = OfflineStorageProfile.STRICT_2GB,
                artifacts = listOf(
                    OfflineArtifactSpec(
                        fileName = "MobileNet-v3-Small.onnx",
                        relativePath = "vision/mobilenet_v3_small/MobileNet-v3-Small.onnx",
                        downloadUrl = "https://huggingface.co/qualcomm/MobileNet-v3-Small/resolve/main/MobileNet-v3-Small.onnx",
                        expectedSizeBytes = 10_184_906L,
                        expectedSha256 = "f65b8a8e7166bc8229e4bc0fb3ebf7f75f46ac8ca138c997df7c3d2e7e7128c2",
                        format = GemmaModelFormat.ONNX,
                        role = "classifier"
                    )
                )
            ),
            displayName = "MobileNet-v3 Small (ONNX)",
            group = ModelCatalogGroup.LIGHTWEIGHT_VISION,
            purposeSummary = "Ultra-fast app/scene & UI context classifier (~9.7 MB)",
            estimatedSizeBytes = 10_184_906L,
            capability = LightweightVisionCapability.UI_CLASSIFICATION,
            recommendedDefault = true
        ),
        ManagedModelDescriptor(
            manifest = OfflineModelManifest(
                modelId = "mobilenet-v2",
                sourceRepoId = "qualcomm/MobileNet-v2",
                runtimeRepoId = "qualcomm/MobileNet-v2",
                pinnedRevision = "main",
                license = "BSD-3-Clause",
                minimumProfile = OfflineStorageProfile.STRICT_2GB,
                artifacts = listOf(
                    OfflineArtifactSpec(
                        fileName = "MobileNet-v2.onnx",
                        relativePath = "vision/mobilenet_v2/MobileNet-v2.onnx",
                        downloadUrl = "https://huggingface.co/qualcomm/MobileNet-v2/resolve/main/MobileNet-v2.onnx",
                        expectedSizeBytes = 13_982_183L,
                        expectedSha256 = "91b90985f5bae0e4734e75b9092964828d45dff97c557730b0eecca01b0ad9b9",
                        format = GemmaModelFormat.ONNX,
                        role = "classifier"
                    )
                )
            ),
            displayName = "MobileNet-v2 (ONNX)",
            group = ModelCatalogGroup.LIGHTWEIGHT_VISION,
            purposeSummary = "Compact visual scene & image classifier (~13.3 MB)",
            estimatedSizeBytes = 13_982_183L,
            capability = LightweightVisionCapability.UI_CLASSIFICATION
        ),
        ManagedModelDescriptor(
            manifest = OfflineModelManifest(
                modelId = "mobilenet-v3-large",
                sourceRepoId = "qualcomm/MobileNet-v3-Large",
                runtimeRepoId = "qualcomm/MobileNet-v3-Large",
                pinnedRevision = "main",
                license = "BSD-3-Clause",
                minimumProfile = OfflineStorageProfile.STRICT_2GB,
                artifacts = listOf(
                    OfflineArtifactSpec(
                        fileName = "MobileNet-v3-Large.onnx",
                        relativePath = "vision/mobilenet_v3_large/MobileNet-v3-Large.onnx",
                        downloadUrl = "https://huggingface.co/qualcomm/MobileNet-v3-Large/resolve/main/MobileNet-v3-Large.onnx",
                        expectedSizeBytes = 0L,
                        expectedSha256 = "ac30a84945ce91777fe25a38ac143724689ab3965b8b5b6dee72f19efa80c63e",
                        format = GemmaModelFormat.ONNX,
                        role = "classifier"
                    )
                )
            ),
            displayName = "MobileNet-v3 Large (ONNX)",
            group = ModelCatalogGroup.LIGHTWEIGHT_VISION,
            purposeSummary = "High-accuracy mobile classifier for visual semantics (~21.9 MB)",
            estimatedSizeBytes = 22_960_000L,
            capability = LightweightVisionCapability.UI_CLASSIFICATION
        ),
        ManagedModelDescriptor(
            manifest = OfflineModelManifest(
                modelId = "efficientnet-b0",
                sourceRepoId = "qualcomm/EfficientNet-B0",
                runtimeRepoId = "qualcomm/EfficientNet-B0",
                pinnedRevision = "main",
                license = "BSD-3-Clause",
                minimumProfile = OfflineStorageProfile.STRICT_2GB,
                artifacts = listOf(
                    OfflineArtifactSpec(
                        fileName = "EfficientNet-B0.onnx",
                        relativePath = "vision/efficientnet_b0/EfficientNet-B0.onnx",
                        downloadUrl = "https://huggingface.co/qualcomm/EfficientNet-B0/resolve/main/EfficientNet-B0.onnx",
                        expectedSizeBytes = 21_142_521L,
                        expectedSha256 = "fff7f522b0539dda4554a407cf98618f62a4c0142df1bee40e50ce350a3d4924",
                        format = GemmaModelFormat.ONNX,
                        role = "classifier"
                    )
                )
            ),
            displayName = "EfficientNet-B0 (ONNX)",
            group = ModelCatalogGroup.LIGHTWEIGHT_VISION,
            purposeSummary = "Balanced region & cropped object classifier (~20.2 MB)",
            estimatedSizeBytes = 21_142_521L,
            capability = LightweightVisionCapability.UI_CLASSIFICATION
        ),
        ManagedModelDescriptor(
            manifest = OfflineModelManifest(
                modelId = "mnasnet-05",
                sourceRepoId = "qualcomm/MNASNet-05",
                runtimeRepoId = "qualcomm/MNASNet-05",
                pinnedRevision = "main",
                license = "BSD-3-Clause",
                minimumProfile = OfflineStorageProfile.STRICT_2GB,
                artifacts = listOf(
                    OfflineArtifactSpec(
                        fileName = "MNASNet05.onnx",
                        relativePath = "vision/mnasnet_05/MNASNet05.onnx",
                        downloadUrl = "https://huggingface.co/qualcomm/MNASNet-05/resolve/main/MNASNet05.onnx",
                        expectedSizeBytes = 0L,
                        expectedSha256 = "ae220787294fba240c4fc936ea5c60dc5a8e64c58d8927affdb3db4bae5a620c",
                        format = GemmaModelFormat.ONNX,
                        role = "classifier"
                    )
                )
            ),
            displayName = "MNASNet-0.5 (ONNX)",
            group = ModelCatalogGroup.LIGHTWEIGHT_VISION,
            purposeSummary = "Low-power mobile vision classifier (~8.9 MB)",
            estimatedSizeBytes = 9_290_000L,
            capability = LightweightVisionCapability.UI_CLASSIFICATION
        ),
        ManagedModelDescriptor(
            manifest = OfflineModelManifest(
                modelId = "shufflenet-v2",
                sourceRepoId = "qualcomm/Shufflenet-v2",
                runtimeRepoId = "qualcomm/Shufflenet-v2",
                pinnedRevision = "main",
                license = "BSD-3-Clause",
                minimumProfile = OfflineStorageProfile.STRICT_2GB,
                artifacts = listOf(
                    OfflineArtifactSpec(
                        fileName = "Shufflenet-v2.onnx",
                        relativePath = "vision/shufflenet_v2/Shufflenet-v2.onnx",
                        downloadUrl = "https://huggingface.co/qualcomm/Shufflenet-v2/resolve/main/Shufflenet-v2.onnx",
                        expectedSizeBytes = 0L,
                        expectedSha256 = "c9b1ae5b986cd528e4dc4c12940dc38e87acd8537f14bc8bac672f3bb70edbf8",
                        format = GemmaModelFormat.ONNX,
                        role = "classifier"
                    )
                )
            ),
            displayName = "ShuffleNet-v2 (ONNX)",
            group = ModelCatalogGroup.LIGHTWEIGHT_VISION,
            purposeSummary = "Fastest ultra-tiny frame classifier (~5.5 MB)",
            estimatedSizeBytes = 5_770_000L,
            capability = LightweightVisionCapability.UI_CLASSIFICATION
        ),
        ManagedModelDescriptor(
            manifest = OfflineModelManifest(
                modelId = "googlenet",
                sourceRepoId = "qualcomm/GoogLeNet",
                runtimeRepoId = "qualcomm/GoogLeNet",
                pinnedRevision = "main",
                license = "BSD-3-Clause",
                minimumProfile = OfflineStorageProfile.STRICT_2GB,
                artifacts = listOf(
                    OfflineArtifactSpec(
                        fileName = "GoogLeNet.onnx",
                        relativePath = "vision/googlenet/GoogLeNet.onnx",
                        downloadUrl = "https://huggingface.co/qualcomm/GoogLeNet/resolve/main/GoogLeNet.onnx",
                        expectedSizeBytes = 0L,
                        expectedSha256 = "4b5c431e9319a2f4715853ec19c48160a3338dd505b544d30e98b0afcd693a31",
                        format = GemmaModelFormat.ONNX,
                        role = "classifier"
                    )
                )
            ),
            displayName = "GoogLeNet Inception (ONNX)",
            group = ModelCatalogGroup.LIGHTWEIGHT_VISION,
            purposeSummary = "Multi-scale crop classification verifier (~26.5 MB)",
            estimatedSizeBytes = 27_780_000L,
            capability = LightweightVisionCapability.UI_CLASSIFICATION
        ),
        ManagedModelDescriptor(
            manifest = OfflineModelManifest(
                modelId = "yolov8n-gui-detect",
                sourceRepoId = "SpotLab/YOLOv8Detection",
                runtimeRepoId = "SpotLab/YOLOv8Detection",
                pinnedRevision = "main",
                license = "AGPL-3.0",
                minimumProfile = OfflineStorageProfile.STRICT_2GB,
                artifacts = listOf(
                    OfflineArtifactSpec(
                        fileName = "yolov8n.onnx",
                        relativePath = "vision/yolov8n/yolov8n.onnx",
                        downloadUrl = "https://huggingface.co/SpotLab/YOLOv8Detection/resolve/main/yolov8n.onnx",
                        expectedSizeBytes = 0L,
                        expectedSha256 = "dd48a79dd7fec8ca25fde4eca742ff7bca23b27e2e903eb23bc1d9f83a459bd2",
                        format = GemmaModelFormat.ONNX,
                        role = "object_detector"
                    )
                )
            ),
            displayName = "YOLOv8n Nano Detector (ONNX)",
            group = ModelCatalogGroup.LIGHTWEIGHT_VISION,
            purposeSummary = "Real bounding-box visual region & object detector (~12.8 MB)",
            estimatedSizeBytes = 13_420_000L,
            capability = LightweightVisionCapability.OBJECT_DETECTION,
            recommendedDefault = true
        ),
        ManagedModelDescriptor(
            manifest = OfflineModelManifest(
                modelId = "yolo-v7-tiny-detect",
                sourceRepoId = "qualcomm/Yolo-v7",
                runtimeRepoId = "qualcomm/Yolo-v7",
                pinnedRevision = "main",
                license = "BSD-3-Clause",
                minimumProfile = OfflineStorageProfile.STRICT_2GB,
                artifacts = listOf(
                    OfflineArtifactSpec(
                        fileName = "Yolo-v7.onnx",
                        relativePath = "vision/yolov7/Yolo-v7.onnx",
                        downloadUrl = "https://huggingface.co/qualcomm/Yolo-v7/resolve/main/Yolo-v7.onnx",
                        expectedSizeBytes = 25_015_342L,
                        expectedSha256 = "80d94327a501d86357801099cddb081372c496242c1e945d98636835d47854ca",
                        format = GemmaModelFormat.ONNX,
                        role = "object_detector"
                    )
                )
            ),
            displayName = "YOLOv7 Tiny Detector (ONNX)",
            group = ModelCatalogGroup.LIGHTWEIGHT_VISION,
            purposeSummary = "Compact box proposals & visual card/icon detection (~23.9 MB)",
            estimatedSizeBytes = 25_015_342L,
            capability = LightweightVisionCapability.OBJECT_DETECTION
        ),
        ManagedModelDescriptor(
            manifest = OfflineModelManifest(
                modelId = "paddleocr-v6-det",
                sourceRepoId = "42ailab/PaddleOCR-ONNX",
                runtimeRepoId = "42ailab/PaddleOCR-ONNX",
                pinnedRevision = "main",
                license = "Apache-2.0",
                minimumProfile = OfflineStorageProfile.STRICT_2GB,
                artifacts = listOf(
                    OfflineArtifactSpec(
                        fileName = "pp-ocrv6_small_det.onnx",
                        relativePath = "vision/paddleocr_v6_det/pp-ocrv6_small_det.onnx",
                        downloadUrl = "https://huggingface.co/42ailab/PaddleOCR-ONNX/resolve/main/pp-ocrv6_small_det.onnx",
                        expectedSizeBytes = 0L,
                        expectedSha256 = "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e",
                        format = GemmaModelFormat.ONNX,
                        role = "ocr_detector"
                    )
                )
            ),
            displayName = "PaddleOCR v6 Text Detector (ONNX)",
            group = ModelCatalogGroup.LIGHTWEIGHT_VISION,
            purposeSummary = "Pixel-level screen text box localization (~9.4 MB)",
            estimatedSizeBytes = 9_860_000L,
            capability = LightweightVisionCapability.OCR_TEXT_DETECTION,
            recommendedDefault = true
        ),
        ManagedModelDescriptor(
            manifest = OfflineModelManifest(
                modelId = "paddleocr-v6-rec",
                sourceRepoId = "42ailab/PaddleOCR-ONNX",
                runtimeRepoId = "42ailab/PaddleOCR-ONNX",
                pinnedRevision = "main",
                license = "Apache-2.0",
                minimumProfile = OfflineStorageProfile.STRICT_2GB,
                artifacts = listOf(
                    OfflineArtifactSpec(
                        fileName = "pp-ocrv6_small_rec.onnx",
                        relativePath = "vision/paddleocr_v6_rec/pp-ocrv6_small_rec.onnx",
                        downloadUrl = "https://huggingface.co/42ailab/PaddleOCR-ONNX/resolve/main/pp-ocrv6_small_rec.onnx",
                        expectedSizeBytes = 0L,
                        expectedSha256 = "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634",
                        format = GemmaModelFormat.ONNX,
                        role = "ocr_recognizer"
                    ),
                    OfflineArtifactSpec(
                        fileName = "ppocrv6_dict.txt",
                        relativePath = "vision/paddleocr_v6_rec/ppocrv6_dict.txt",
                        downloadUrl = "https://huggingface.co/42ailab/PaddleOCR-ONNX/resolve/main/ppocrv6_dict.txt",
                        expectedSizeBytes = 0L,
                        expectedSha256 = "b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d",
                        format = GemmaModelFormat.UNKNOWN,
                        isMandatory = true,
                        role = "ocr_dict"
                    )
                )
            ),
            displayName = "PaddleOCR v6 Recognizer + Dict (ONNX)",
            group = ModelCatalogGroup.LIGHTWEIGHT_VISION,
            purposeSummary = "On-device CTC text line recognition (~20.2 MB)",
            estimatedSizeBytes = 21_200_000L,
            capability = LightweightVisionCapability.OCR_TEXT_RECOGNITION
        ),
        ManagedModelDescriptor(
            manifest = OfflineModelManifest(
                modelId = "deeplabv3-plus-mobilenet",
                sourceRepoId = "qualcomm/DeepLabV3-Plus-MobileNet",
                runtimeRepoId = "qualcomm/DeepLabV3-Plus-MobileNet",
                pinnedRevision = "main",
                license = "BSD-3-Clause",
                minimumProfile = OfflineStorageProfile.STRICT_2GB,
                artifacts = listOf(
                    OfflineArtifactSpec(
                        fileName = "DeepLabV3-Plus-MobileNet.onnx",
                        relativePath = "vision/deeplabv3_mobilenet/DeepLabV3-Plus-MobileNet.onnx",
                        downloadUrl = "https://huggingface.co/qualcomm/DeepLabV3-Plus-MobileNet/resolve/main/DeepLabV3-Plus-MobileNet.onnx",
                        expectedSizeBytes = 0L,
                        expectedSha256 = "4b0e6bfe7074bd406b1c22b1edee66624f227b767a2a0368dc2987f4fa160382",
                        format = GemmaModelFormat.ONNX,
                        role = "segmentation"
                    )
                )
            ),
            displayName = "DeepLabV3+ MobileNet Segmentation (ONNX)",
            group = ModelCatalogGroup.LIGHTWEIGHT_VISION,
            purposeSummary = "Foreground / salient UI region segmentation (~23.2 MB)",
            estimatedSizeBytes = 24_330_000L,
            capability = LightweightVisionCapability.REGION_SEGMENTATION
        )
    )

    val EXCLUDED_MODELS: List<ExcludedModelRecord> = listOf(
        GemmaModelManager.FASTVLM_EXCLUSION_RECORD,
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
            purposeSummary = "Structured <OD>, <CAPTION>, and <OCR> ONNX vision bundle (Requires Expanded profile)",
            estimatedSizeBytes = 235_000_000L,
            minApiLevel = 24
        )
    )

    fun allDescriptors(): List<ManagedModelDescriptor> =
        getVoiceDescriptors() + LIGHTWEIGHT_VISION_DESCRIPTORS + getAdvancedVisionDescriptors()

    fun findById(modelId: String): ManagedModelDescriptor? =
        allDescriptors().firstOrNull { it.manifest.modelId == modelId }
}
