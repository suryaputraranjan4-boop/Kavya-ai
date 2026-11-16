package com.example

import android.app.Application
import android.content.Context
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.example.ui.components.KavyaSideMenu
import com.example.vision.*
import com.example.visual.JarvisFloatingOrb
import com.example.visual.JarvisOrbConfig
import com.example.visual.JarvisOrbState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VisionEcosystemTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun appContext(): Context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun registryContainsAll16VerifiedTechnologiesAndTemplates() {
        val specs = VisionTechRegistry.allTechnologies
        assertEquals(16, specs.size)
        assertEquals(VisionTechId.entries.toSet(), specs.map { it.id }.toSet())

        val expectedRepos = mapOf(
            VisionTechId.THREE_JS to "https://github.com/mrdoob/three.js",
            VisionTechId.BABYLON_JS to "https://github.com/BabylonJS/Babylon.js",
            VisionTechId.MODEL_VIEWER to "https://github.com/google/model-viewer",
            VisionTechId.TRIPO_SR to "https://github.com/VAST-AI-Research/TripoSR",
            VisionTechId.THREEPIPE to "https://github.com/repalash/threepipe",
            VisionTechId.GSPLAT_JS to "https://github.com/huggingface/gsplat.js",
            VisionTechId.CESIUM_JS to "https://github.com/CesiumGS/cesium",
            VisionTechId.MERMAID_JS to "https://github.com/mermaid-js/mermaid",
            VisionTechId.REACT_FLOW to "https://github.com/xyflow/xyflow",
            VisionTechId.EXCALIDRAW to "https://github.com/excalidraw/excalidraw",
            VisionTechId.D3_JS to "https://github.com/d3/d3",
            VisionTechId.PLOTLY_JS to "https://github.com/plotly/plotly.js",
            VisionTechId.APACHE_ECHARTS to "https://github.com/apache/echarts",
            VisionTechId.FORCE_GRAPH_3D to "https://github.com/vasturiano/3d-force-graph",
            VisionTechId.GSAP to "https://github.com/greensock/GSAP",
            VisionTechId.MANIM to "https://github.com/ManimCommunity/manim"
        )
        expectedRepos.forEach { (id, repoUrl) ->
            val spec = VisionTechRegistry.getSpec(id)
            assertEquals("Repo mismatch for $id", repoUrl, spec.officialRepoUrl)
            assertTrue(spec.docsUrl.startsWith("https://"))
            assertTrue(spec.licenseName.isNotBlank())
            assertTrue(spec.capabilities.size >= 3)
        }

        val templates = VisionTechRegistry.defaultTemplates()
        assertTrue(templates.size >= 16)
        VisionTechId.entries.forEach { id ->
            assertTrue("Missing starter template for $id", templates.any { it.techId == id })
        }
    }

    @Test
    fun packageManagerVerifiesRealSha256BundlesAndHonestWorkerStatus() = runBlocking {
        val ctx = appContext()
        VisionPackageManager.setTripoSrEndpoint(ctx, "", "")
        VisionPackageManager.setManimEndpoint(ctx, "", "")
        VisionPackageManager.setCesiumIonToken(ctx, "")

        val diagMap = VisionPackageManager.verifyAllAsync(ctx)
        assertEquals(16, diagMap.size)

        // All 14 local JS/WebView engines must genuinely exist in assets & match SHA-256 manifest
        val offlineIds = VisionTechId.entries.filter { it != VisionTechId.TRIPO_SR && it != VisionTechId.MANIM }
        offlineIds.forEach { id ->
            val diag = diagMap[id]!!
            assertEquals(
                "Expected INSTALLED_VERIFIED for $id but got ${diag.availabilityStatus} (${diag.statusDetail})",
                VisionAvailabilityStatus.INSTALLED_VERIFIED,
                diag.availabilityStatus
            )
            assertTrue("Missing SHA verification for $id", diag.sha256Verified)
            assertTrue("Expected non-zero verified bytes for $id", diag.verifiedBytes > 10_000L)
        }

        // Python workers must truthfully report BACKEND_REQUIRED when unconfigured
        assertEquals(VisionAvailabilityStatus.BACKEND_REQUIRED, diagMap[VisionTechId.TRIPO_SR]!!.availabilityStatus)
        assertEquals(VisionAvailabilityStatus.BACKEND_REQUIRED, diagMap[VisionTechId.MANIM]!!.availabilityStatus)

        // Configuring worker & Cesium token persists cleanly
        VisionPackageManager.setTripoSrEndpoint(ctx, "http://10.0.2.2:8000", "secret-tripo-123")
        VisionPackageManager.setCesiumIonToken(ctx, "user-ion-token-xyz")
        val updated = VisionPackageManager.refreshAllDiagnostics(ctx)
        assertEquals(VisionAvailabilityStatus.BACKEND_READY, updated[VisionTechId.TRIPO_SR]!!.availabilityStatus)
        assertEquals("secret-tripo-123", VisionPackageManager.getTripoSrToken(ctx))
        assertEquals("user-ion-token-xyz", VisionPackageManager.getCesiumIonToken(ctx))

        // Cleanup secrets
        VisionPackageManager.setTripoSrEndpoint(ctx, "", "")
        VisionPackageManager.setCesiumIonToken(ctx, "")
    }

    @Test
    fun orchestratorRoutesVisualQueriesCorrectlyAndIgnoresNormalChat() {
        // Positive routes across categories
        assertEquals(VisionTechId.MERMAID_JS, VisualizationOrchestrator.classifyVisualizationRequest("Draw a Mermaid flowchart of authentication").primaryTech)
        assertEquals(VisionTechId.REACT_FLOW, VisualizationOrchestrator.classifyVisualizationRequest("Open React Flow editable node workflow").primaryTech)
        assertEquals(VisionTechId.EXCALIDRAW, VisualizationOrchestrator.classifyVisualizationRequest("Sketch system architecture on Excalidraw whiteboard").primaryTech)
        assertEquals(VisionTechId.THREE_JS, VisualizationOrchestrator.classifyVisualizationRequest("Render a 3D model of a rotating cube").primaryTech)
        assertEquals(VisionTechId.BABYLON_JS, VisualizationOrchestrator.classifyVisualizationRequest("Simulate 3D physics bounce with Babylon").primaryTech)
        assertEquals(VisionTechId.CESIUM_JS, VisualizationOrchestrator.classifyVisualizationRequest("Show a 3D globe with flight routes").primaryTech)
        assertEquals(VisionTechId.FORCE_GRAPH_3D, VisualizationOrchestrator.classifyVisualizationRequest("Visualize a 3D knowledge graph of AI concepts").primaryTech)
        assertEquals(VisionTechId.PLOTLY_JS, VisualizationOrchestrator.classifyVisualizationRequest("Plot a 3D surface in Plotly").primaryTech)
        assertEquals(VisionTechId.APACHE_ECHARTS, VisualizationOrchestrator.classifyVisualizationRequest("Draw a radar chart in Apache ECharts").primaryTech)
        assertEquals(VisionTechId.D3_JS, VisualizationOrchestrator.classifyVisualizationRequest("Create an interactive hierarchy tree in D3.js").primaryTech)
        assertEquals(VisionTechId.GSAP, VisualizationOrchestrator.classifyVisualizationRequest("Animate a step-by-step explainer with GSAP").primaryTech)
        assertEquals(VisionTechId.MANIM, VisualizationOrchestrator.classifyVisualizationRequest("Animate Fourier math decomposition in Manim").primaryTech)
        assertEquals(VisionTechId.TRIPO_SR, VisualizationOrchestrator.classifyVisualizationRequest("Convert this photo to 3D with TripoSR").primaryTech)
        assertEquals(VisionTechId.THREEPIPE, VisualizationOrchestrator.classifyVisualizationRequest("Show PBR material lighting in Threepipe").primaryTech)
        assertEquals(VisionTechId.GSPLAT_JS, VisualizationOrchestrator.classifyVisualizationRequest("Open gsplat gaussian splatting scene").primaryTech)
        assertEquals(VisionTechId.MODEL_VIEWER, VisualizationOrchestrator.classifyVisualizationRequest("Inspect GLB hotspots with model-viewer").primaryTech)

        // Negative cases: ordinary chat & device automation must NOT falsely trigger
        assertFalse(VisualizationOrchestrator.classifyVisualizationRequest("Hello, how are you?").isVisualizationIntent)
        assertFalse(VisualizationOrchestrator.classifyVisualizationRequest("What model are you using right now?").isVisualizationIntent)
        assertFalse(VisualizationOrchestrator.classifyVisualizationRequest("Open Chrome").isVisualizationIntent)
        assertFalse(VisualizationOrchestrator.classifyVisualizationRequest("Explain this screen").isVisualizationIntent)
    }

    @Test
    fun validationManimSafetyAndCsvImportPreserveTruthfulness() {
        val ctx = appContext()

        // Unsafe Python calls are rejected by Manim safety validator
        val unsafe = TripoSrAndManimWorkers.validateManimScript(
            """
            import os
            from manim import *
            class Bad(Scene):
                def construct(self):
                    os.system("rm -rf /")
            """.trimIndent()
        )
        assertFalse(unsafe.isValid)
        assertTrue(unsafe.issues.any { it.contains("os.system(") })

        val valid = TripoSrAndManimWorkers.validateManimScript(
            """
            from manim import *
            class CircleScene(Scene):
                def construct(self):
                    self.play(Create(Circle()))
            """.trimIndent()
        )
        assertTrue(valid.isValid)
        assertEquals("CircleScene", valid.sceneClassName)

        // Mermaid malformed source auto-recovers rather than crashing
        val (_, recoveredJson, warning) = VisualizationOrchestrator.validateAndRecoverPayload(
            tech = VisionTechId.MERMAID_JS,
            rawJson = """{"title":"Bad","source":"not valid header"}""",
            userPrompt = "Draw sequence diagram"
        )
        assertNotNull(warning)
        assertTrue(JSONObject(recoveredJson).optString("source").contains("sequenceDiagram"))

        // CSV import preserves exact numerical rows without fake padding
        val csvArtifact = VisualizationOrchestrator.importExternalFileContent(
            context = ctx,
            fileName = "metrics.csv",
            rawText = "Epoch,Loss\n1,0.92\n2,0.45\n3,0.18",
            savedBinaryFile = null
        )
        assertEquals(VisionTechId.PLOTLY_JS, csvArtifact.techId)
        assertTrue(csvArtifact.scenePayloadJson.contains("0.92"))
        assertTrue(csvArtifact.scenePayloadJson.contains("0.18"))
    }

    @Test
    fun storageRepositorySupportsCrudUndoRedoAndExport() {
        val ctx = appContext()
        val initial = VisualizationArtifact(
            techIdWire = VisionTechId.MERMAID_JS.wireId,
            title = "Flow V1",
            userPrompt = "Draw flow",
            summaryText = "V1 summary",
            scenePayloadJson = """{"title":"Flow V1","source":"flowchart TD\nA-->B"}"""
        )
        val saved1 = VisualizationStorageRepository.saveOrUpdate(ctx, initial)
        assertFalse(saved1.canUndo)

        val saved2 = VisualizationStorageRepository.saveOrUpdate(
            ctx,
            saved1.copy(scenePayloadJson = """{"title":"Flow V2","source":"flowchart TD\nA-->B-->C"}"""),
            pushUndoState = true,
            undoLabel = "Add C"
        )
        assertTrue(saved2.canUndo)

        val undone = VisualizationStorageRepository.undo(ctx, saved2.artifactId)!!
        assertTrue(undone.scenePayloadJson.contains("Flow V1"))
        assertTrue(undone.canRedo)

        val redone = VisualizationStorageRepository.redo(ctx, saved2.artifactId)!!
        assertTrue(redone.scenePayloadJson.contains("Flow V2"))

        val renamed = VisualizationStorageRepository.renameArtifact(ctx, redone.artifactId, "Renamed Flow")!!
        assertEquals("Renamed Flow", renamed.title)

        val fav = VisualizationStorageRepository.toggleFavorite(ctx, renamed.artifactId)!!
        assertTrue(fav.isSavedFavorite)

        val dup = VisualizationStorageRepository.duplicateArtifact(ctx, fav.artifactId)!!
        assertTrue(dup.title.contains("Copy"))

        val exportedSvg: File = VisualizationStorageRepository.saveExportFile(
            context = ctx,
            artifactId = fav.artifactId,
            format = VisualizationExportFormat.SVG,
            content = "<svg xmlns='http://www.w3.org/2000/svg'></svg>",
            isBase64 = false
        )
        assertTrue(exportedSvg.isFile && exportedSvg.length() > 10L)

        VisualizationStorageRepository.deleteArtifact(ctx, fav.artifactId)
        VisualizationStorageRepository.deleteArtifact(ctx, dup.artifactId)
        assertFalse(VisualizationStorageRepository.artifacts.value.any { it.artifactId == fav.artifactId })
    }

    @Test
    fun hamburgerMenuVisionNavigationAndJarvisOrbActionAreConnected() {
        var navigatedRoute: String? = null
        var orbVisionTapped = false

        composeRule.setContent {
            val navController = rememberNavController()
            val drawerState = rememberDrawerState(DrawerValue.Open)
            val scope = rememberCoroutineScope()

            NavHost(navController = navController, startDestination = "home") {
                composable("home") {
                    KavyaSideMenu(
                        navController = navController,
                        drawerState = drawerState,
                        scope = scope
                    )
                }
                composable("jarvis_intelligence") {
                    navigatedRoute = "jarvis_intelligence"
                }
            }

            JarvisFloatingOrb(
                state = JarvisOrbState.IDLE,
                config = JarvisOrbConfig(),
                isWindowOverlay = true,
                isExpanded = true,
                onOpenVisionStudio = { orbVisionTapped = true }
            )
        }

        // Verify Hamburger Menu has 'Vision' item and navigates cleanly to jarvis_intelligence
        composeRule.onNodeWithTag("drawer_item_vision").assertExists().performClick()
        composeRule.waitForIdle()
        assertEquals("jarvis_intelligence", navigatedRoute)

        // Verify Jarvis Floating Orb quick bar exposes Vision Studio hub action
        composeRule.onNodeWithTag("jarvis_orb_vision_action").assertExists().performClick()
        assertTrue(orbVisionTapped)
    }
}
