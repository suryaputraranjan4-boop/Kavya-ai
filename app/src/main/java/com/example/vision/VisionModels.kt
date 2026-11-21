package com.example.vision

import kotlinx.serialization.Serializable
import java.util.UUID

enum class VisionCategory(
    val displayName: String,
    val badgeLabel: String
) {
    THREE_D_AND_SPATIAL("3D & Spatial", "3D"),
    DIAGRAMS_AND_FLOWCHARTS("Diagrams & Flowcharts", "Diagrams"),
    CHARTS_AND_DATA("Charts & Data", "Charts"),
    ANIMATION_AND_EXPLAINER("Animation & Explainer", "Animation")
}

enum class VisionTechId(val wireId: String) {
    THREE_JS("three_js"),
    BABYLON_JS("babylon_js"),
    MODEL_VIEWER("model_viewer"),
    TRIPO_SR("tripo_sr"),
    THREEPIPE("threepipe"),
    GSPLAT_JS("gsplat_js"),
    CESIUM_JS("cesium_js"),
    MERMAID_JS("mermaid_js"),
    REACT_FLOW("react_flow"),
    EXCALIDRAW("excalidraw"),
    D3_JS("d3_js"),
    PLOTLY_JS("plotly_js"),
    APACHE_ECHARTS("apache_echarts"),
    FORCE_GRAPH_3D("force_graph_3d"),
    GSAP("gsap"),
    MANIM("manim");

    companion object {
        fun fromWireId(id: String): VisionTechId? =
            entries.firstOrNull { it.wireId.equals(id.trim(), ignoreCase = true) }
    }
}

enum class VisionRuntimeType {
    WEBVIEW_OFFLINE_BUNDLED,
    WEBVIEW_PACKAGE_CACHE,
    REMOTE_PYTHON_WORKER
}

enum class VisionDownloadKind(val label: String) {
    NPM_WEBVIEW_PACKAGE("npm JS Package (WebView Runtime)"),
    PYTHON_MODEL_WEIGHTS_AND_WORKER("Model Weights + Python GPU Worker"),
    PYTHON_RENDER_PACKAGE_AND_WORKER("Python Wheel + LaTeX/FFmpeg Worker")
}

enum class VisionAvailabilityStatus(val label: String) {
    INSTALLED_VERIFIED("Installed & Verified"),
    NOT_INSTALLED("Not Installed"),
    DOWNLOADING("Downloading Package"),
    VERIFYING("Verifying Integrity"),
    BACKEND_REQUIRED("Worker URL Required"),
    CONFIGURED_UNTESTED("Configured — Untested"),
    BACKEND_UNREACHABLE("Worker Unreachable"),
    BACKEND_READY("Worker Ready"),
    UNSUPPORTED("Unsupported on Device"),
    ERROR("Verification Error")
}

enum class QualityPreset(val wireValue: String, val label: String, val pixelRatioCap: Float) {
    LOW("low", "Battery Saver", 1.0f),
    MEDIUM("medium", "Balanced", 1.5f),
    HIGH("high", "Ultra crisp", 2.0f)
}

enum class VisualizationExportFormat(
    val extension: String,
    val mimeType: String,
    val label: String
) {
    PNG("png", "image/png", "PNG Image"),
    SVG("svg", "image/svg+xml", "SVG Vector"),
    JSON("json", "application/json", "Scene JSON"),
    GLB("glb", "model/gltf-binary", "GLB 3D Binary"),
    EXCALIDRAW("excalidraw", "application/json", "Excalidraw Scene"),
    MERMAID_MMD("mmd", "text/plain", "Mermaid Source"),
    PLOTLY_JSON("plotly.json", "application/json", "Plotly Spec"),
    PYTHON_PY("py", "text/x-python", "Manim Python Script"),
    CSV("csv", "text/csv", "CSV Dataset"),
    MP4("mp4", "video/mp4", "MP4 Video")
}

enum class VisualizationEntrySource {
    HAMBURGER_VISION,
    JARVIS_ORB,
    TEMPLATE,
    IMPORTED_FILE
}

enum class VisualizationJobStatus {
    QUEUED,
    PLANNING,
    GENERATING,
    VALIDATING,
    RENDERING,
    READY,
    FAILED,
    CANCELLED
}

data class VisionTechnologySpec(
    val id: VisionTechId,
    val category: VisionCategory,
    val displayName: String,
    val shortTagline: String,
    val description: String,
    val runtimeType: VisionRuntimeType,
    val officialRepoUrl: String,
    val docsUrl: String,
    val packageName: String,
    val version: String,
    val licenseName: String,
    val licenseNote: String,
    val capabilities: List<String>,
    val supportedInputFormats: List<String>,
    val supportedExportFormats: List<VisualizationExportFormat>,
    val assetBundleFiles: List<String>,
    val requiresWebGl: Boolean,
    val isHeavyEngine: Boolean,
    val supportsOffline: Boolean,
    val supportsUndoRedo: Boolean,
    val supportsAnimationOverlay: Boolean,
    val backendRoleDescription: String,
    val officialSourceUrl: String = officialRepoUrl,
    val downloadKind: VisionDownloadKind = if (runtimeType == VisionRuntimeType.REMOTE_PYTHON_WORKER) {
        VisionDownloadKind.PYTHON_RENDER_PACKAGE_AND_WORKER
    } else {
        VisionDownloadKind.NPM_WEBVIEW_PACKAGE
    },
    val registryMetadataUrl: String = "",
    val visualizationRole: String = shortTagline,
    val androidLimitationNote: String = backendRoleDescription,
    val estimatedPackageSizeBytes: Long = 0L
)

data class VisionRuntimeDiagnostics(
    val techId: VisionTechId,
    val availabilityStatus: VisionAvailabilityStatus,
    val bundledAssetPresent: Boolean = false,
    val cachedOverridePresent: Boolean = false,
    val sha256Verified: Boolean = false,
    val verifiedBytes: Long = 0L,
    val expectedSha256: String = "",
    val actualSha256: String = "",
    val backendEndpointConfigured: Boolean = false,
    val backendReachable: Boolean = false,
    val webGlAvailable: Boolean = true,
    val arSupported: Boolean = false,
    val statusDetail: String = "",
    val lastError: String? = null,
    val lastCheckedAtMs: Long = 0L
)

data class VisualizationTemplate(
    val id: String,
    val techId: VisionTechId,
    val title: String,
    val description: String,
    val promptHint: String,
    val payloadJson: String,
    val tags: List<String> = emptyList()
)

@Serializable
data class VisualizationArtifact(
    val artifactId: String = UUID.randomUUID().toString(),
    val techIdWire: String,
    val secondaryTechIdWire: String? = null,
    val title: String,
    val userPrompt: String,
    val summaryText: String,
    val scenePayloadJson: String,
    val sourceWire: String = VisualizationEntrySource.HAMBURGER_VISION.name,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
    val thumbnailPath: String? = null,
    val mediaFilePath: String? = null,
    val exportedPaths: Map<String, String> = emptyMap(),
    val isSavedFavorite: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val telemetryLabel: String = ""
) {
    val techId: VisionTechId
        get() = VisionTechId.fromWireId(techIdWire) ?: VisionTechId.THREE_JS

    val secondaryTechId: VisionTechId?
        get() = secondaryTechIdWire?.let { VisionTechId.fromWireId(it) }
}

data class VisualizationJob(
    val jobId: String = UUID.randomUUID().toString(),
    val techId: VisionTechId,
    val userQuery: String,
    val status: VisualizationJobStatus = VisualizationJobStatus.QUEUED,
    val progress: Float = 0f,
    val statusMessage: String = "",
    val errorMessage: String? = null,
    val source: VisualizationEntrySource = VisualizationEntrySource.HAMBURGER_VISION,
    val resultArtifactId: String? = null,
    val startedAtMs: Long = System.currentTimeMillis()
)

data class VisualizationUndoSnapshot(
    val label: String,
    val payloadJson: String,
    val capturedAtMs: Long = System.currentTimeMillis()
)

data class WorkerEndpointStatus(
    val serviceName: String,
    val endpointUrl: String,
    val hasAuthToken: Boolean,
    val isReachable: Boolean,
    val lastCheckedMs: Long = 0L,
    val detailMessage: String = ""
)

sealed class VisionJsEvent {
    data class EngineReady(val techId: VisionTechId, val rendererInfo: String) : VisionJsEvent()
    data class RenderComplete(val techId: VisionTechId, val metricsJson: String) : VisionJsEvent()
    data class RenderError(val techId: VisionTechId, val code: String, val message: String) : VisionJsEvent()
    data class StateMutated(val techId: VisionTechId, val payloadJson: String, val label: String) : VisionJsEvent()
    data class ExportReady(val techId: VisionTechId, val format: VisualizationExportFormat, val contentBase64OrText: String, val isBase64: Boolean) : VisionJsEvent()
    data class SelectionChanged(val techId: VisionTechId, val selectionJson: String) : VisionJsEvent()
    data class TelemetryUpdate(val techId: VisionTechId, val memoryWarning: Boolean, val detail: String) : VisionJsEvent()
}
