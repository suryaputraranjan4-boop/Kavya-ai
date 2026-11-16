package com.example.vision

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.example.orchestrator.ProviderInvocationResult
import com.example.orchestrator.SelectedProviderClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

data class VisualizationRouteDecision(
    val isVisualizationIntent: Boolean,
    val primaryTech: VisionTechId,
    val secondaryTech: VisionTechId? = null,
    val reason: String
)

/**
 * Shared Orchestrator & Tool Router unifying both:
 * 1. Hamburger Menu -> Vision Workspace
 * 2. Jarvis Orb -> Conversational / Voice requests
 *
 * Pipeline:
 * Intent Detection -> Tool/Engine Selection -> Context & Prompt Planning ->
 * AI or Deterministic Scene Generation -> Strict Schema/Syntax Validation ->
 * Engine Fallback -> Render State -> Save / Export / Follow-up Editing.
 */
object VisualizationOrchestrator {

    private const val TAG = "VisualizationOrchestrator"

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var activeJob: Job? = null

    private val _activeArtifact = MutableStateFlow<VisualizationArtifact?>(null)
    val activeArtifact: StateFlow<VisualizationArtifact?> = _activeArtifact.asStateFlow()

    private val _activeJob = MutableStateFlow<VisualizationJob?>(null)
    val activeJobState: StateFlow<VisualizationJob?> = _activeJob.asStateFlow()

    private val _qualityPreset = MutableStateFlow(QualityPreset.MEDIUM)
    val qualityPreset: StateFlow<QualityPreset> = _qualityPreset.asStateFlow()

    private val _orbVisualStatusText = MutableStateFlow<String?>(null)
    val orbVisualStatusText: StateFlow<String?> = _orbVisualStatusText.asStateFlow()

    private val _lastValidationError = MutableStateFlow<String?>(null)
    val lastValidationError: StateFlow<String?> = _lastValidationError.asStateFlow()

    private val _isFullScreenPreviewOpen = MutableStateFlow(false)
    val isFullScreenPreviewOpen: StateFlow<Boolean> = _isFullScreenPreviewOpen.asStateFlow()

    /**
     * Detects whether a conversational message or voice query asks Kavya / Jarvis Orb to
     * generate, inspect, animate, or sketch a visualization rather than plain chat or UI automation.
     */
    fun classifyVisualizationRequest(
        query: String,
        explicitTechId: VisionTechId? = null
    ): VisualizationRouteDecision {
        val q = query.trim().lowercase(Locale.ROOT)
        if (explicitTechId != null) {
            return VisualizationRouteDecision(
                isVisualizationIntent = true,
                primaryTech = explicitTechId,
                secondaryTech = suggestSecondaryPairing(explicitTechId, q),
                reason = "Explicitly selected ${explicitTechId.wireId}"
            )
        }

        // Explicit technology names
        val explicitByName = when {
            q.contains("three.js") || q.contains("threejs") -> VisionTechId.THREE_JS
            q.contains("babylon") -> VisionTechId.BABYLON_JS
            q.contains("model viewer") || q.contains("model-viewer") -> VisionTechId.MODEL_VIEWER
            q.contains("triposr") -> VisionTechId.TRIPO_SR
            q.contains("threepipe") -> VisionTechId.THREEPIPE
            q.contains("gsplat") || q.contains("gaussian splat") || q.contains(".splat") || q.contains(".ply") -> VisionTechId.GSPLAT_JS
            q.contains("cesium") || q.contains("3d globe") || q.contains("flight route") -> VisionTechId.CESIUM_JS
            q.contains("mermaid") -> VisionTechId.MERMAID_JS
            q.contains("react flow") || q.contains("reactflow") -> VisionTechId.REACT_FLOW
            q.contains("excalidraw") -> VisionTechId.EXCALIDRAW
            q.contains("d3.js") || q.contains("d3js") -> VisionTechId.D3_JS
            q.contains("plotly") -> VisionTechId.PLOTLY_JS
            q.contains("echarts") || q.contains("apache echarts") -> VisionTechId.APACHE_ECHARTS
            q.contains("3d force graph") || q.contains("force graph 3d") -> VisionTechId.FORCE_GRAPH_3D
            q.contains("gsap") -> VisionTechId.GSAP
            q.contains("manim") -> VisionTechId.MANIM
            else -> null
        }
        if (explicitByName != null) {
            return VisualizationRouteDecision(
                isVisualizationIntent = true,
                primaryTech = explicitByName,
                secondaryTech = suggestSecondaryPairing(explicitByName, q),
                reason = "Explicit technology mention"
            )
        }

        // Natural-language visualization verbs & subjects (only triggers when user genuinely wants a visual)
        val visualVerbs = listOf(
            "draw", "sketch", "plot ", "plot(", "bar chart", "pie chart", "line chart", "radar chart",
            "diagram", "visualize", "visualise", "render 3d", "render a ", "3d model", "glb model",
            "gltf model", "simulate", "animate", "whiteboard", "flowchart", "mindmap",
            "sequence diagram", "er diagram", "gantt", "3d globe", "globe with", "knowledge graph",
            "force graph", "neural network diagram", "tree diagram", "heatmap", "treemap",
            "histogram", "3d surface", "banao", "dikhao chart", "diagram banao"
        )
        val matchesVerb = visualVerbs.any { q.contains(it) }
        val excludedUiAutomation = q.startsWith("open ") || q.startsWith("launch ") ||
            q.startsWith("call ") || q.startsWith("send ") || q.contains("explain this screen") ||
            q.contains("where is") || q.contains("how do i tap") || q.contains("what model")

        if (!matchesVerb || excludedUiAutomation) {
            return VisualizationRouteDecision(
                isVisualizationIntent = false,
                primaryTech = VisionTechId.MERMAID_JS,
                reason = "Non-generative conversational or automation turn"
            )
        }

        val routed = when {
            q.contains("manim") || (q.contains("animate") && (q.contains("equation") || q.contains("math") || q.contains("calculus") || q.contains("fourier") || q.contains("sorting") || q.contains("vector"))) -> VisionTechId.MANIM
            q.contains("step-by-step animation") || q.contains("timeline animation") || q.contains("animate this") || q.contains("motion explainer") -> VisionTechId.GSAP
            q.contains("photo to 3d") || q.contains("image to 3d") || q.contains("reconstruct 3d") -> VisionTechId.TRIPO_SR
            q.contains("whiteboard") || q.contains("sketch") || q.contains("hand drawn") || q.contains("hand-drawn") -> VisionTechId.EXCALIDRAW
            q.contains("editable workflow") || q.contains("node graph") || q.contains("node editor") || q.contains("pipeline nodes") -> VisionTechId.REACT_FLOW
            q.contains("flowchart") || q.contains("sequence diagram") || q.contains("state diagram") || q.contains("class diagram") || q.contains("er diagram") || q.contains("gantt") || q.contains("mindmap") || q.contains("diagram") -> VisionTechId.MERMAID_JS
            q.contains("3d knowledge") || q.contains("knowledge graph") || q.contains("dependency network") -> VisionTechId.FORCE_GRAPH_3D
            q.contains("globe") || q.contains("world map") || q.contains("flight") || q.contains("latitude") || q.contains("geospatial") -> VisionTechId.CESIUM_JS
            q.contains("3d surface") || q.contains("scatter") || q.contains("histogram") || q.contains("heatmap") || q.contains("box plot") || q.contains("violin") -> VisionTechId.PLOTLY_JS
            q.contains("radar") || q.contains("gauge") || q.contains("treemap") || q.contains("dashboard") || q.contains("pie chart") || q.contains("bar chart") || q.contains("line chart") -> VisionTechId.APACHE_ECHARTS
            q.contains("force") || q.contains("hierarchy tree") || q.contains("radial tree") || q.contains("svg") -> VisionTechId.D3_JS
            q.contains("material") || q.contains("pbr") || q.contains("studio") -> VisionTechId.THREEPIPE
            q.contains("physics") || q.contains("gravity") || q.contains("bounce") -> VisionTechId.BABYLON_JS
            q.contains("glb") || q.contains("gltf") || q.contains("ar view") -> VisionTechId.MODEL_VIEWER
            q.contains("3d") -> VisionTechId.THREE_JS
            else -> VisionTechId.MERMAID_JS
        }

        return VisualizationRouteDecision(
            isVisualizationIntent = true,
            primaryTech = routed,
            secondaryTech = suggestSecondaryPairing(routed, q),
            reason = "Matched visualization request -> ${routed.wireId}"
        )
    }

    private fun suggestSecondaryPairing(primary: VisionTechId, lowerQuery: String): VisionTechId? = when {
        primary == VisionTechId.MERMAID_JS && (lowerQuery.contains("animate") || lowerQuery.contains("step")) -> VisionTechId.GSAP
        primary == VisionTechId.TRIPO_SR -> VisionTechId.MODEL_VIEWER
        primary == VisionTechId.MANIM -> VisionTechId.PLOTLY_JS
        else -> null
    }

    fun setQualityPreset(preset: QualityPreset) {
        _qualityPreset.value = preset
    }

    fun setFullScreenPreviewOpen(open: Boolean) {
        _isFullScreenPreviewOpen.value = open
    }

    fun selectArtifact(artifact: VisualizationArtifact) {
        _lastValidationError.value = null
        _activeArtifact.value = artifact
    }

    fun openTemplate(context: Context, template: VisualizationTemplate, source: VisualizationEntrySource = VisualizationEntrySource.TEMPLATE): VisualizationArtifact {
        val artifact = VisualizationArtifact(
            techIdWire = template.techId.wireId,
            title = template.title,
            userPrompt = template.promptHint,
            summaryText = template.description,
            scenePayloadJson = template.payloadJson,
            sourceWire = source.name
        )
        val saved = VisualizationStorageRepository.saveOrUpdate(context, artifact)
        _activeArtifact.value = saved
        _lastValidationError.value = null
        return saved
    }

    fun cancelActiveJob() {
        activeJob?.cancel()
        activeJob = null
        val cur = _activeJob.value
        if (cur != null && cur.status != VisualizationJobStatus.READY && cur.status != VisualizationJobStatus.FAILED) {
            _activeJob.value = cur.copy(
                status = VisualizationJobStatus.CANCELLED,
                statusMessage = "Generation cancelled"
            )
        }
    }

    fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            _qualityPreset.value = QualityPreset.LOW
        }
    }

    /**
     * Unified entry point invoked by Hamburger -> Vision Studio AND Jarvis Orb voice/text turns.
     */
    fun generateVisualizationAsync(
        context: Context,
        prompt: String,
        preferredTechId: VisionTechId? = null,
        source: VisualizationEntrySource = VisualizationEntrySource.HAMBURGER_VISION,
        importedDataContext: String? = null,
        onComplete: ((VisualizationArtifact) -> Unit)? = null
    ) {
        activeJob?.cancel()
        val appCtx = context.applicationContext
        val route = classifyVisualizationRequest(prompt, preferredTechId)
        val targetTech = preferredTechId ?: route.primaryTech
        val spec = VisionTechRegistry.getSpec(targetTech)

        val jobId = UUID.randomUUID().toString()
        _lastValidationError.value = null
        _activeJob.value = VisualizationJob(
            jobId = jobId,
            techId = targetTech,
            userQuery = prompt,
            status = VisualizationJobStatus.PLANNING,
            progress = 0.12f,
            statusMessage = "Planning ${spec.displayName} scene...",
            source = source
        )
        _orbVisualStatusText.value = "Designing ${spec.displayName} visual..."

        activeJob = scope.launch {
            try {
                // Step 1: Respect existing AI provider (or deterministic offline generator if offline/key missing)
                _activeJob.value = _activeJob.value?.copy(
                    status = VisualizationJobStatus.GENERATING,
                    progress = 0.38f,
                    statusMessage = "Generating structured ${spec.displayName} payload..."
                )

                val aiPayload = tryGeneratePayloadViaProvider(
                    context = appCtx,
                    tech = targetTech,
                    userPrompt = prompt,
                    dataContext = importedDataContext,
                    existingArtifact = _activeArtifact.value?.takeIf {
                        it.techId == targetTech && isFollowUpEditPrompt(prompt)
                    }
                )

                val candidateJson = aiPayload ?: buildDeterministicSceneJson(
                    tech = targetTech,
                    prompt = prompt,
                    dataContext = importedDataContext,
                    existingPayload = _activeArtifact.value?.takeIf { it.techId == targetTech && isFollowUpEditPrompt(prompt) }?.scenePayloadJson
                )

                _activeJob.value = _activeJob.value?.copy(
                    status = VisualizationJobStatus.VALIDATING,
                    progress = 0.72f,
                    statusMessage = "Validating ${spec.displayName} schema & safety rules..."
                )

                val (validatedTech, validatedJson, validationWarning) = validateAndRecoverPayload(
                    tech = targetTech,
                    rawJson = candidateJson,
                    userPrompt = prompt
                )

                val isFollowUp = _activeArtifact.value?.techId == validatedTech && isFollowUpEditPrompt(prompt)
                val baseArtifact = if (isFollowUp && _activeArtifact.value != null) {
                    _activeArtifact.value!!.copy(
                        title = extractTitle(validatedJson, prompt),
                        userPrompt = prompt,
                        summaryText = validationWarning ?: "Updated ${VisionTechRegistry.getSpec(validatedTech).displayName} visualization",
                        scenePayloadJson = validatedJson,
                        sourceWire = source.name
                    )
                } else {
                    VisualizationArtifact(
                        techIdWire = validatedTech.wireId,
                        secondaryTechIdWire = route.secondaryTech?.wireId,
                        title = extractTitle(validatedJson, prompt),
                        userPrompt = prompt,
                        summaryText = validationWarning ?: buildHonestSummary(validatedTech, prompt, usedAi = aiPayload != null, hasDataContext = !importedDataContext.isNullOrBlank()),
                        scenePayloadJson = validatedJson,
                        sourceWire = source.name
                    )
                }

                val saved = VisualizationStorageRepository.saveOrUpdate(
                    context = appCtx,
                    artifact = baseArtifact,
                    pushUndoState = isFollowUp,
                    undoLabel = prompt.take(36)
                )

                _activeArtifact.value = saved
                _activeJob.value = _activeJob.value?.copy(
                    status = VisualizationJobStatus.READY,
                    progress = 1.0f,
                    statusMessage = "${VisionTechRegistry.getSpec(validatedTech).displayName} ready",
                    resultArtifactId = saved.artifactId
                )
                _orbVisualStatusText.value = saved.summaryText
                onComplete?.invoke(saved)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "Visualization pipeline error: ${e.message}")
                val err = e.localizedMessage ?: "Failed building visualization"
                _lastValidationError.value = err
                _activeJob.value = _activeJob.value?.copy(
                    status = VisualizationJobStatus.FAILED,
                    statusMessage = err,
                    errorMessage = err
                )
            }
        }
    }

    fun applyFollowUpMutationFromUi(context: Context, newPayloadJson: String, label: String): VisualizationArtifact? {
        val current = _activeArtifact.value ?: return null
        val updated = VisualizationStorageRepository.saveOrUpdate(
            context = context,
            artifact = current.copy(scenePayloadJson = newPayloadJson),
            pushUndoState = true,
            undoLabel = label
        )
        _activeArtifact.value = updated
        return updated
    }

    fun undoActive(context: Context): VisualizationArtifact? {
        val current = _activeArtifact.value ?: return null
        val restored = VisualizationStorageRepository.undo(context, current.artifactId) ?: return null
        _activeArtifact.value = restored
        return restored
    }

    fun redoActive(context: Context): VisualizationArtifact? {
        val current = _activeArtifact.value ?: return null
        val restored = VisualizationStorageRepository.redo(context, current.artifactId) ?: return null
        _activeArtifact.value = restored
        return restored
    }

    fun importExternalFileContent(
        context: Context,
        fileName: String,
        rawText: String?,
        savedBinaryFile: File? = null
    ): VisualizationArtifact {
        val lower = fileName.lowercase(Locale.ROOT)
        val detectedTech = when {
            lower.endsWith(".excalidraw") -> VisionTechId.EXCALIDRAW
            lower.endsWith(".mmd") || lower.endsWith(".mermaid") -> VisionTechId.MERMAID_JS
            lower.endsWith(".splat") || lower.endsWith(".ply") -> VisionTechId.GSPLAT_JS
            lower.endsWith(".glb") || lower.endsWith(".gltf") -> VisionTechId.MODEL_VIEWER
            lower.endsWith(".py") -> VisionTechId.MANIM
            lower.endsWith(".csv") -> VisionTechId.PLOTLY_JS
            lower.endsWith(".geojson") -> VisionTechId.CESIUM_JS
            else -> VisionTechId.D3_JS
        }

        val payloadJson = when (detectedTech) {
            VisionTechId.MERMAID_JS -> JSONObject().apply {
                put("title", fileName)
                put("source", rawText.orEmpty().trim())
                put("theme", "dark")
            }.toString()

            VisionTechId.EXCALIDRAW -> {
                val parsed = runCatching { JSONObject(rawText.orEmpty()) }.getOrNull()
                if (parsed != null && parsed.has("elements")) {
                    parsed.apply { put("title", optString("title").ifBlank { fileName }) }.toString()
                } else {
                    JSONObject().apply { put("title", fileName); put("elements", JSONArray()) }.toString()
                }
            }

            VisionTechId.MANIM -> JSONObject().apply {
                put("title", fileName)
                put("script", rawText.orEmpty())
                put("sceneClass", TripoSrAndManimWorkers.validateManimScript(rawText.orEmpty()).sceneClassName ?: "ImportedScene")
                put("quality", "m")
            }.toString()

            VisionTechId.PLOTLY_JS -> parseCsvIntoPlotlyJson(fileName, rawText.orEmpty())

            VisionTechId.MODEL_VIEWER -> JSONObject().apply {
                put("title", fileName)
                put("localFilePath", savedBinaryFile?.absolutePath.orEmpty())
                put("modelSource", savedBinaryFile?.let { "https://appassets.androidplatform.net/vision_files/${it.name}" } ?: "")
                put("autoRotate", true)
            }.toString()

            VisionTechId.GSPLAT_JS -> JSONObject().apply {
                put("title", fileName)
                put("sourceMode", "local_file")
                put("localFilePath", savedBinaryFile?.absolutePath.orEmpty())
                put("modelSource", savedBinaryFile?.let { "https://appassets.androidplatform.net/vision_files/${it.name}" } ?: "")
                put("attribution", "Local imported splat file ($fileName). External asset rights belong to its original author.")
            }.toString()

            else -> {
                val parsed = runCatching { JSONObject(rawText.orEmpty()) }.getOrNull()
                parsed?.toString() ?: JSONObject().apply { put("title", fileName); put("raw", rawText.orEmpty()) }.toString()
            }
        }

        val artifact = VisualizationArtifact(
            techIdWire = detectedTech.wireId,
            title = fileName.substringBeforeLast('.'),
            userPrompt = "Imported file: $fileName",
            summaryText = "Imported into ${VisionTechRegistry.getSpec(detectedTech).displayName}",
            scenePayloadJson = payloadJson,
            sourceWire = VisualizationEntrySource.IMPORTED_FILE.name,
            mediaFilePath = savedBinaryFile?.absolutePath
        )
        val saved = VisualizationStorageRepository.saveOrUpdate(context, artifact)
        _activeArtifact.value = saved
        return saved
    }

    internal fun validateAndRecoverPayload(
        tech: VisionTechId,
        rawJson: String,
        userPrompt: String
    ): Triple<VisionTechId, String, String?> {
        val obj = runCatching { JSONObject(rawJson) }.getOrNull()
            ?: return Triple(tech, buildDeterministicSceneJson(tech, userPrompt, null, null), "Recovered structured scene from malformed response.")

        return when (tech) {
            VisionTechId.MERMAID_JS -> {
                val src = obj.optString("source").trim()
                val validStarts = listOf("flowchart", "graph ", "sequenceDiagram", "stateDiagram", "classDiagram", "erDiagram", "gantt", "journey", "mindmap", "pie")
                if (src.isBlank() || validStarts.none { src.startsWith(it, ignoreCase = true) }) {
                    val hint = "Mermaid syntax must start with a valid diagram header (e.g., flowchart TD, sequenceDiagram, mindmap)."
                    _lastValidationError.value = hint
                    Triple(tech, buildDeterministicSceneJson(VisionTechId.MERMAID_JS, userPrompt, null, null), "Auto-corrected Mermaid header. $hint")
                } else {
                    Triple(tech, obj.toString(), null)
                }
            }

            VisionTechId.MANIM -> {
                val script = obj.optString("script")
                val v = TripoSrAndManimWorkers.validateManimScript(script)
                if (!v.isValid) {
                    _lastValidationError.value = v.issues.joinToString("; ")
                    Triple(tech, buildDeterministicSceneJson(VisionTechId.MANIM, userPrompt, null, null), "Reverted to verified Manim Scene template: ${v.issues.firstOrNull()}")
                } else {
                    Triple(tech, obj.apply { put("sceneClass", v.sceneClassName ?: "MainScene") }.toString(), null)
                }
            }

            VisionTechId.REACT_FLOW -> {
                val nodes = obj.optJSONArray("nodes")
                if (nodes == null || nodes.length() == 0) {
                    Triple(tech, buildDeterministicSceneJson(VisionTechId.REACT_FLOW, userPrompt, null, null), "Populated editable React Flow graph.")
                } else {
                    Triple(tech, obj.toString(), null)
                }
            }

            else -> Triple(tech, obj.toString(), null)
        }
    }

    private suspend fun tryGeneratePayloadViaProvider(
        context: Context,
        tech: VisionTechId,
        userPrompt: String,
        dataContext: String?,
        existingArtifact: VisualizationArtifact?
    ): String? {
        val spec = VisionTechRegistry.getSpec(tech)
        val schemaHint = schemaInstructionsFor(tech)
        val followUpContext = if (existingArtifact != null) {
            "\n\nEXISTING SCENE JSON TO MODIFY:\n${existingArtifact.scenePayloadJson.take(3500)}"
        } else ""
        val dataBlock = if (!dataContext.isNullOrBlank()) {
            "\n\nREAL INPUT DATA (use these exact values, do NOT invent fake numbers):\n${dataContext.take(4000)}"
        } else {
            "\n\nIMPORTANT TRUTH RULE: If charting user/real metrics and no numbers were provided in the prompt, use mathematical functions/example structure explicitly labeled as illustrative or structural — NEVER invent fake financial/medical/survey telemetry."
        }

        val fullPrompt = """
            Target visualization engine: ${spec.displayName} (${spec.id.wireId})
            User request: $userPrompt
            $followUpContext
            $dataBlock

            Respond with ONLY a single valid JSON object matching this schema:
            $schemaHint
        """.trimIndent()

        val result = SelectedProviderClient.invokeSelectedProvider(
            context = context,
            prompt = fullPrompt,
            systemInstruction = "You are a strict JSON generator for Kavya Vision Studio. Output ONLY valid raw JSON with zero markdown fences.",
            requiresVision = false
        )

        return when (result) {
            is ProviderInvocationResult.Success -> {
                val raw = result.text.trim()
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()
                val jsonCandidate = extractFirstJsonObject(raw)
                jsonCandidate?.takeIf { runCatching { JSONObject(it) }.isSuccess }
            }
            is ProviderInvocationResult.Failure -> null
        }
    }

    private fun extractFirstJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1)
        }
        return null
    }

    private fun schemaInstructionsFor(tech: VisionTechId): String = when (tech) {
        VisionTechId.MERMAID_JS -> """{"title":"...","diagramType":"flowchart|sequence|state|class|er|gantt|journey|mindmap","theme":"dark","source":"VALID MERMAID SYNTAX"}"""
        VisionTechId.REACT_FLOW -> """{"title":"...","nodes":[{"id":"n1","position":{"x":40,"y":60},"data":{"label":"Step 1","role":"STEP"}}],"edges":[{"id":"e1","source":"n1","target":"n2","label":"next","animated":true}]}"""
        VisionTechId.EXCALIDRAW -> """{"title":"...","theme":"dark","elements":[{"type":"rectangle|ellipse|diamond","id":"e1","x":80,"y":80,"width":180,"height":80,"strokeColor":"#38BDF8","backgroundColor":"#0F172A","labelText":"Node"}]}"""
        VisionTechId.THREE_JS -> """{"title":"...","background":"#070B12","wireframe":false,"autoRotate":true,"objects":[{"id":"obj1","type":"sphere|box|cylinder|torus|cone|icosahedron","radius":1.0,"color":"#00F5FF","metalness":0.6,"roughness":0.25,"position":[0,0,0]}]}"""
        VisionTechId.BABYLON_JS -> """{"title":"...","gravityEnabled":true,"groundSize":10,"bodies":[{"name":"Obj1","shape":"sphere|box","diameter":1.1,"position":[0,4,0],"restitution":0.8,"color":"#00E5FF"}]}"""
        VisionTechId.CESIUM_JS -> """{"title":"...","offlineFallback":true,"markers":[{"name":"City","lat":37.7,"lon":-122.4,"color":"#00F5FF"}],"routes":[{"from":"CityA","to":"CityB"}]}"""
        VisionTechId.D3_JS -> """{"title":"...","chartMode":"force|tree|treemap","nodes":[{"id":"A","group":1,"value":20}],"links":[{"source":"A","target":"B","weight":2}]}"""
        VisionTechId.PLOTLY_JS -> """{"title":"...","mode":"traces|math_surface","data":[{"type":"bar|scatter|pie|histogram|heatmap","x":[],"y":[]}],"layout":{"title":"..."}}"""
        VisionTechId.APACHE_ECHARTS -> """{"title":"...","chartType":"bar|line|radar|pie|gauge","indicators":[{"name":"Metric","max":100}],"series":[{"name":"Series","values":[60,80]}],"xAxis":[],"data":[]}"""
        VisionTechId.FORCE_GRAPH_3D -> """{"title":"...","nodes":[{"id":"Topic","group":1,"val":8}],"links":[{"source":"Topic","target":"Subtopic"}]}"""
        VisionTechId.GSAP -> """{"title":"...","steps":[{"id":"s1","label":"Phase 1","color":"#38BDF8","duration":0.9}]}"""
        VisionTechId.MANIM -> """{"title":"...","sceneClass":"MainScene","quality":"m","script":"from manim import *\nclass MainScene(Scene):\n    def construct(self):\n        ..."}"""
        VisionTechId.THREEPIPE -> """{"title":"...","meshPreset":"torusKnot|sphere|cube","color":"#00E5FF","metalness":0.8,"roughness":0.2,"clearcoat":0.5,"envIntensity":1.2}"""
        VisionTechId.GSPLAT_JS -> """{"title":"...","sourceMode":"procedural","splatCount":4200,"attribution":"Procedural splat cloud"}"""
        VisionTechId.MODEL_VIEWER -> """{"title":"...","modelSource":"procedural://capsule_assembly","exposure":1.1,"autoRotate":true,"hotspots":[{"slot":"h1","position":"0 0.4 0.5","normal":"0 0 1","label":"Part"}]}"""
        VisionTechId.TRIPO_SR -> """{"title":"TripoSR Job","foregroundRatio":0.85,"mcResolution":256,"removeBackground":true}"""
    }

    internal fun buildDeterministicSceneJson(
        tech: VisionTechId,
        prompt: String,
        dataContext: String?,
        existingPayload: String?
    ): String {
        val cleanTitle = prompt.trim().take(54).ifBlank { VisionTechRegistry.getSpec(tech).displayName }
        if (!dataContext.isNullOrBlank() && tech in listOf(VisionTechId.PLOTLY_JS, VisionTechId.APACHE_ECHARTS, VisionTechId.D3_JS)) {
            if (tech == VisionTechId.PLOTLY_JS) return parseCsvIntoPlotlyJson(cleanTitle, dataContext)
        }

        val matchingTemplate = VisionTechRegistry.defaultTemplates().firstOrNull { it.techId == tech }
        val baseObj = runCatching {
            JSONObject(existingPayload ?: matchingTemplate?.payloadJson ?: "{}")
        }.getOrElse { JSONObject() }

        baseObj.put("title", cleanTitle)

        // Personalize Mermaid flowcharts/mindmaps/sequence diagrams with words extracted from prompt
        if (tech == VisionTechId.MERMAID_JS) {
            val q = prompt.lowercase(Locale.ROOT)
            val mmdSource = when {
                q.contains("sequence") -> """
                    sequenceDiagram
                        participant User
                        participant JarvisOrb
                        participant Orchestrator
                        participant Renderer
                        User->>JarvisOrb: "$cleanTitle"
                        JarvisOrb->>Orchestrator: Plan & validate schema
                        Orchestrator->>Renderer: Load Mermaid SVG engine
                        Renderer-->>User: Interactive SVG + Export
                """.trimIndent()
                q.contains("mindmap") -> """
                    mindmap
                      root(($cleanTitle))
                        Core Concepts
                          Fundamentals
                          Architecture
                        Visual Tools
                          Diagrams
                          Interactive 3D
                        Exports
                          SVG & PNG
                          JSON State
                """.trimIndent()
                q.contains("gantt") -> """
                    gantt
                        title $cleanTitle
                        dateFormat YYYY-MM-DD
                        section Planning
                        Scope & Design :a1, 2026-01-01, 5d
                        section Build
                        Core Implementation :after a1, 10d
                        Verification & QA :5d
                """.trimIndent()
                q.contains("er diagram") -> """
                    erDiagram
                        USER ||--o{ VISUALIZATION : creates
                        VISUALIZATION ||--|{ ARTIFACT_EXPORT : generates
                        TECHNOLOGY ||--o{ VISUALIZATION : renders
                """.trimIndent()
                q.contains("state diagram") -> """
                    stateDiagram-v2
                        [*] --> Idle
                        Idle --> Planning : Prompt
                        Planning --> Validating : Schema Ready
                        Validating --> Rendering : Valid
                        Rendering --> Ready : Interactive Canvas
                """.trimIndent()
                q.contains("class diagram") -> """
                    classDiagram
                        class VisualizationOrchestrator {
                            +classifyVisualizationRequest()
                            +generateVisualizationAsync()
                        }
                        class VisionWebViewHost {
                            +loadEngine()
                            +exportPng()
                        }
                        VisualizationOrchestrator --> VisionWebViewHost
                """.trimIndent()
                else -> baseObj.optString("source")
            }
            baseObj.put("source", mmdSource)
        }
        return baseObj.toString()
    }

    private fun parseCsvIntoPlotlyJson(title: String, csvText: String): String {
        val lines = csvText.lines().map { it.trim() }.filter { it.isNotBlank() }.take(100)
        if (lines.size < 2) {
            return JSONObject().apply {
                put("title", title)
                put("mode", "traces")
                put("data", JSONArray())
                put("layout", JSONObject().apply { put("title", "$title (Provide >= 2 CSV rows)") })
            }.toString()
        }
        val headers = lines.first().split(',').map { it.trim() }
        val xVals = JSONArray()
        val yVals = JSONArray()
        for (row in lines.drop(1)) {
            val cols = row.split(',').map { it.trim() }
            if (cols.isNotEmpty()) {
                xVals.put(cols[0])
                val num = cols.getOrNull(1)?.toDoubleOrNull()
                if (num != null) yVals.put(num)
            }
        }
        return JSONObject().apply {
            put("title", title)
            put("mode", "traces")
            put("data", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "bar")
                    put("name", headers.getOrElse(1) { "Value" })
                    put("x", xVals)
                    put("y", yVals)
                })
            })
            put("layout", JSONObject().apply {
                put("title", title)
                put("xaxis", JSONObject().apply { put("title", headers.getOrElse(0) { "Category" }) })
                put("yaxis", JSONObject().apply { put("title", headers.getOrElse(1) { "Value" }) })
            })
        }.toString()
    }

    private fun isFollowUpEditPrompt(prompt: String): Boolean {
        val q = prompt.lowercase(Locale.ROOT)
        return q.contains("change ") || q.contains("update ") || q.contains("modify ") ||
            q.contains("make it ") || q.contains("convert to ") || q.contains("add a ") ||
            q.contains("color") || q.contains("theme") || q.contains("rename")
    }

    private fun extractTitle(jsonStr: String, fallbackPrompt: String): String {
        val t = runCatching { JSONObject(jsonStr).optString("title") }.getOrNull().orEmpty().trim()
        return t.ifBlank { fallbackPrompt.trim().take(48).ifBlank { "Visualization" } }
    }

    private fun buildHonestSummary(
        tech: VisionTechId,
        prompt: String,
        usedAi: Boolean,
        hasDataContext: Boolean
    ): String {
        val spec = VisionTechRegistry.getSpec(tech)
        return when {
            tech == VisionTechId.TRIPO_SR || tech == VisionTechId.MANIM ->
                "Prepared ${spec.displayName} job specification for '$prompt'. Connect a configured Python worker to execute remote rendering."
            usedAi ->
                "Generated ${spec.displayName} scene via AI provider."
            hasDataContext ->
                "Rendered ${spec.displayName} directly from imported dataset."
            else ->
                "Initialized ${spec.displayName} local scene (${spec.licenseName}, offline-ready)."
        }
    }
}
