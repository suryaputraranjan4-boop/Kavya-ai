package com.example.vision

import org.json.JSONArray
import org.json.JSONObject

/**
 * Authoritative registry for all 16 selected visualization, 3D, diagramming,
 * graphing, geospatial, whiteboard, and math animation technologies.
 *
 * Official GitHub repository URLs, verified npm package names/versions, and
 * licenses are declared here without fabricated offline claims.
 */
object VisionTechRegistry {

    val allTechnologies: List<VisionTechnologySpec> = listOf(
        // A. 3D & Spatial
        VisionTechnologySpec(
            id = VisionTechId.THREE_JS,
            category = VisionCategory.THREE_D_AND_SPATIAL,
            displayName = "Three.js",
            shortTagline = "Interactive WebGL procedural 3D scenes, lights, materials & GLB export",
            description = "Core WebGL 3D engine powering geometric shapes, procedural assemblies, OrbitControls, TransformControls, hierarchy tree, wireframe/lighting toggles, and GLB binary export.",
            runtimeType = VisionRuntimeType.WEBVIEW_OFFLINE_BUNDLED,
            officialRepoUrl = "https://github.com/mrdoob/three.js",
            docsUrl = "https://threejs.org/docs/",
            packageName = "three",
            version = "0.187.0",
            licenseName = "MIT",
            licenseNote = "Permissive MIT license.",
            capabilities = listOf(
                "Orbit, zoom, pan & TransformControls gizmos",
                "Procedural primitives, materials, lights & wireframe toggle",
                "Scene hierarchy inspector & object properties",
                "Camera view resets & GLB / PNG scene export"
            ),
            supportedInputFormats = listOf("ThreeScene JSON", "GLB", "GLTF"),
            supportedExportFormats = listOf(VisualizationExportFormat.GLB, VisualizationExportFormat.JSON, VisualizationExportFormat.PNG),
            assetBundleFiles = listOf("vision/vendor/three.bundle.mjs"),
            requiresWebGl = true,
            isHeavyEngine = false,
            supportsOffline = true,
            supportsUndoRedo = true,
            supportsAnimationOverlay = true,
            backendRoleDescription = "Runs 100% locally inside hardware-accelerated WebView WebGL."
        ),
        VisionTechnologySpec(
            id = VisionTechId.BABYLON_JS,
            category = VisionCategory.THREE_D_AND_SPATIAL,
            displayName = "Babylon.js",
            shortTagline = "3D engine with gravity/bounce simulation, PBR materials & scene inspector",
            description = "Production 3D engine supporting interactive camera navigation, mesh hierarchy inspection, PBR material tuning, lighting rigs, rigid/kinematic gravity bounce simulation, and glTF/GLB export.",
            runtimeType = VisionRuntimeType.WEBVIEW_OFFLINE_BUNDLED,
            officialRepoUrl = "https://github.com/BabylonJS/Babylon.js",
            docsUrl = "https://doc.babylonjs.com/",
            packageName = "@babylonjs/core",
            version = "9.36.0",
            licenseName = "Apache-2.0",
            licenseNote = "Permissive Apache 2.0 license.",
            capabilities = listOf(
                "ArcRotateCamera zoom/pan/orbit controls",
                "PBR material & dynamic light presets",
                "Real-time gravity & collision physics simulation",
                "Mesh hierarchy inspection & GLB/GLTF export"
            ),
            supportedInputFormats = listOf("BabylonScene JSON", "GLB", "GLTF"),
            supportedExportFormats = listOf(VisualizationExportFormat.GLB, VisualizationExportFormat.JSON, VisualizationExportFormat.PNG),
            assetBundleFiles = listOf("vision/vendor/babylon.bundle.mjs"),
            requiresWebGl = true,
            isHeavyEngine = true,
            supportsOffline = true,
            supportsUndoRedo = true,
            supportsAnimationOverlay = true,
            backendRoleDescription = "Runs 100% locally in WebView WebGL using bundled @babylonjs/core + serializers."
        ),
        VisionTechnologySpec(
            id = VisionTechId.MODEL_VIEWER,
            category = VisionCategory.THREE_D_AND_SPATIAL,
            displayName = "Model Viewer",
            shortTagline = "glTF/GLB inspector with camera presets, hotspots, lighting & AR placement",
            description = "Web component for glTF/GLB inspection with orbital camera presets, exposure/environment lighting sliders, interactive 3D annotations (hotspots), poster fallback, and WebXR/Scene Viewer AR check.",
            runtimeType = VisionRuntimeType.WEBVIEW_OFFLINE_BUNDLED,
            officialRepoUrl = "https://github.com/google/model-viewer",
            docsUrl = "https://modelviewer.dev/",
            packageName = "@google/model-viewer",
            version = "4.4.0",
            licenseName = "Apache-2.0",
            licenseNote = "Permissive Apache 2.0 license.",
            capabilities = listOf(
                "Load local or imported .glb / .gltf models",
                "Camera angle presets (Front, Top, Iso, Hero)",
                "Interactive hotspot annotations & exposure lighting",
                "Device-verified AR View launch & PNG/GLB export"
            ),
            supportedInputFormats = listOf("GLB", "GLTF", "Procedural GLB"),
            supportedExportFormats = listOf(VisualizationExportFormat.GLB, VisualizationExportFormat.PNG, VisualizationExportFormat.JSON),
            assetBundleFiles = listOf("vision/vendor/model-viewer.bundle.mjs"),
            requiresWebGl = true,
            isHeavyEngine = false,
            supportsOffline = true,
            supportsUndoRedo = false,
            supportsAnimationOverlay = true,
            backendRoleDescription = "Runs offline in WebView; AR button only enabled when device supports ARCore/Scene Viewer."
        ),
        VisionTechnologySpec(
            id = VisionTechId.TRIPO_SR,
            category = VisionCategory.THREE_D_AND_SPATIAL,
            displayName = "TripoSR",
            shortTagline = "Single-image-to-3D GLB mesh reconstruction via isolated Python GPU worker",
            description = "Connects securely to a remote or local LAN Python FastAPI / HuggingFace / Gradio TripoSR worker. Validates photo upload, tracks progress & cancellation, downloads the resulting .glb mesh, and previews it in Three.js/Model Viewer.",
            runtimeType = VisionRuntimeType.REMOTE_PYTHON_WORKER,
            officialRepoUrl = "https://github.com/VAST-AI-Research/TripoSR",
            docsUrl = "https://github.com/VAST-AI-Research/TripoSR#readme",
            packageName = "TripoSR Python Worker (REST /multipart)",
            version = "Worker API v1",
            licenseName = "MIT",
            licenseNote = "Requires external Python PyTorch GPU worker; NEVER bundled inside APK.",
            capabilities = listOf(
                "Pick photo via Android Photo Picker or screen capture",
                "Upload validation, job progress polling & cancellation",
                "Download reconstructed .glb / .obj mesh to local vault",
                "Immediate interactive preview inside Model Viewer & Three.js"
            ),
            supportedInputFormats = listOf("PNG", "JPEG"),
            supportedExportFormats = listOf(VisualizationExportFormat.GLB, VisualizationExportFormat.PNG),
            assetBundleFiles = emptyList(),
            requiresWebGl = true,
            isHeavyEngine = true,
            supportsOffline = false,
            supportsUndoRedo = false,
            supportsAnimationOverlay = false,
            backendRoleDescription = "Requires a live TripoSR Python FastAPI/Gradio worker endpoint (configured in Vision Settings)."
        ),
        VisionTechnologySpec(
            id = VisionTechId.THREEPIPE,
            category = VisionCategory.THREE_D_AND_SPATIAL,
            displayName = "Threepipe",
            shortTagline = "Studio 3D viewer with PBR material editing, environment lighting & snapshots",
            description = "High-quality Three.js-based studio pipeline offering material color/roughness/metalness controls, HDRI lighting presets, tonemapping/bloom post-processing, undo/redo material history, and snapshot/GLB export.",
            runtimeType = VisionRuntimeType.WEBVIEW_OFFLINE_BUNDLED,
            officialRepoUrl = "https://github.com/repalash/threepipe",
            docsUrl = "https://threepipe.org/",
            packageName = "threepipe",
            version = "0.6.0",
            licenseName = "Apache-2.0",
            licenseNote = "Permissive Apache 2.0 license.",
            capabilities = listOf(
                "PBR roughness, metalness & albedo live editor",
                "Studio lighting & ACES/AgX tone-mapping presets",
                "Material undo/redo snapshots & state JSON save",
                "Load external GLB or studio hero model + PNG/GLB export"
            ),
            supportedInputFormats = listOf("GLB", "GLTF", "Threepipe JSON"),
            supportedExportFormats = listOf(VisualizationExportFormat.PNG, VisualizationExportFormat.JSON, VisualizationExportFormat.GLB),
            assetBundleFiles = listOf("vision/vendor/threepipe.bundle.mjs"),
            requiresWebGl = true,
            isHeavyEngine = true,
            supportsOffline = true,
            supportsUndoRedo = true,
            supportsAnimationOverlay = false,
            backendRoleDescription = "Runs locally in WebView WebGL using bundled threepipe engine."
        ),
        VisionTechnologySpec(
            id = VisionTechId.GSPLAT_JS,
            category = VisionCategory.THREE_D_AND_SPATIAL,
            displayName = "gsplat.js",
            shortTagline = "3D Gaussian Splatting viewer (.splat / .ply) with WebGL memory guard",
            description = "Dedicated Gaussian Splat viewer for loading .splat or .ply point-cloud captures (local import or procedural demo splat), displaying clear CC/asset attribution when loading remote scenes and enforcing adaptive splat density/quality presets.",
            runtimeType = VisionRuntimeType.WEBVIEW_OFFLINE_BUNDLED,
            officialRepoUrl = "https://github.com/huggingface/gsplat.js",
            docsUrl = "https://github.com/huggingface/gsplat.js#readme",
            packageName = "gsplat",
            version = "1.2.9",
            licenseName = "MIT",
            licenseNote = "Permissive MIT engine; external .splat/.ply assets keep their original creator licenses.",
            capabilities = listOf(
                "Import local .splat or .ply Gaussian Splat captures",
                "Built-in procedural Gaussian cloud scene offline",
                "LOW/MED/HIGH point budget & memory guard",
                "Smooth orbital camera & PNG snapshot export"
            ),
            supportedInputFormats = listOf(".splat", ".ply", "Gaussian Splat JSON"),
            supportedExportFormats = listOf(VisualizationExportFormat.PNG, VisualizationExportFormat.JSON),
            assetBundleFiles = listOf("vision/vendor/gsplat.bundle.mjs"),
            requiresWebGl = true,
            isHeavyEngine = true,
            supportsOffline = true,
            supportsUndoRedo = false,
            supportsAnimationOverlay = false,
            backendRoleDescription = "Runs locally inside WebView WebGL using bundled gsplat engine."
        ),
        VisionTechnologySpec(
            id = VisionTechId.CESIUM_JS,
            category = VisionCategory.THREE_D_AND_SPATIAL,
            displayName = "CesiumJS",
            shortTagline = "3D globe, markers, routes, terrain & offline natural-earth fallback",
            description = "3D geospatial globe supporting pins, flight/orbit paths, polygon region overlays, layer visibility toggles, camera fly-to presets, and automatic offline natural-earth graticule/topography fallback when no Ion token or network is available.",
            runtimeType = VisionRuntimeType.WEBVIEW_OFFLINE_BUNDLED,
            officialRepoUrl = "https://github.com/CesiumGS/cesium",
            docsUrl = "https://cesium.com/learn/cesiumjs/ref-doc/",
            packageName = "cesium",
            version = "1.147.0",
            licenseName = "Apache-2.0",
            licenseNote = "Apache-2.0 engine. Optional Cesium Ion imagery token stored strictly in encrypted SecureStorage (never hardcoded).",
            capabilities = listOf(
                "3D interactive Earth globe & offline vector fallback",
                "Location pins, flight paths & region overlays",
                "Layer toggles, camera fly-to & coordinate HUD",
                "Optional Ion token support via SecureStorage"
            ),
            supportedInputFormats = listOf("CesiumGeo JSON", "GeoJSON", "CZML"),
            supportedExportFormats = listOf(VisualizationExportFormat.JSON, VisualizationExportFormat.PNG),
            assetBundleFiles = listOf("vision/vendor/cesium/Cesium.js"),
            requiresWebGl = true,
            isHeavyEngine = true,
            supportsOffline = true,
            supportsUndoRedo = false,
            supportsAnimationOverlay = false,
            backendRoleDescription = "Runs offline with Natural Earth vector/ellipsoid globe; optionally uses user-supplied Ion token / OSM online tiles."
        ),

        // B. Diagrams & Flowcharts
        VisionTechnologySpec(
            id = VisionTechId.MERMAID_JS,
            category = VisionCategory.DIAGRAMS_AND_FLOWCHARTS,
            displayName = "Mermaid.js",
            shortTagline = "Text-to-diagram engine: flowchart, sequence, state, class, ER, Gantt, journey & mindmap",
            description = "Deterministic text-to-diagram renderer supporting live syntax editing, strict parse validation with line-level correction hints, theme switching, zoom/pan, optional GSAP step highlights, and .mmd / SVG / PNG export.",
            runtimeType = VisionRuntimeType.WEBVIEW_OFFLINE_BUNDLED,
            officialRepoUrl = "https://github.com/mermaid-js/mermaid",
            docsUrl = "https://mermaid.js.org/intro/",
            packageName = "mermaid",
            version = "12.3.0",
            licenseName = "MIT",
            licenseNote = "Permissive MIT license.",
            capabilities = listOf(
                "Flowchart, Sequence, State, Class, ER, Gantt, User Journey & Mindmap",
                "Live source editor with parse validation & fix hints",
                "Dark/Light/Cyber theme switcher & zoom/pan",
                "SVG, PNG & .mmd export"
            ),
            supportedInputFormats = listOf("Mermaid (.mmd)", "Diagram JSON"),
            supportedExportFormats = listOf(VisualizationExportFormat.SVG, VisualizationExportFormat.PNG, VisualizationExportFormat.MERMAID_MMD, VisualizationExportFormat.JSON),
            assetBundleFiles = listOf("vision/vendor/mermaid.bundle.mjs"),
            requiresWebGl = false,
            isHeavyEngine = false,
            supportsOffline = true,
            supportsUndoRedo = true,
            supportsAnimationOverlay = true,
            backendRoleDescription = "Runs 100% offline inside WebView."
        ),
        VisionTechnologySpec(
            id = VisionTechId.REACT_FLOW,
            category = VisionCategory.DIAGRAMS_AND_FLOWCHARTS,
            displayName = "React Flow",
            shortTagline = "True node-graph editor: drag nodes, connect ports, mini-map, auto-layout & JSON save",
            description = "Interactive React Flow canvas (not a static image): drag nodes, draw typed edges between handles, edit node labels, add/delete blocks, toggle MiniMap, auto-arrange hierarchy, undo/redo, and save/export graph JSON or SVG/PNG.",
            runtimeType = VisionRuntimeType.WEBVIEW_OFFLINE_BUNDLED,
            officialRepoUrl = "https://github.com/xyflow/xyflow",
            docsUrl = "https://reactflow.dev/learn",
            packageName = "@xyflow/react",
            version = "13.0.0",
            licenseName = "MIT",
            licenseNote = "Permissive MIT license.",
            capabilities = listOf(
                "Interactive draggable nodes & live edge connection",
                "Add/edit/delete nodes & auto-layout hierarchy",
                "Mini-map, zoom/pan controls & undo/redo stack",
                "Full JSON state save/reload + PNG/SVG export"
            ),
            supportedInputFormats = listOf("ReactFlow Graph JSON"),
            supportedExportFormats = listOf(VisualizationExportFormat.JSON, VisualizationExportFormat.PNG, VisualizationExportFormat.SVG),
            assetBundleFiles = listOf("vision/vendor/reactflow.bundle.mjs", "vision/vendor/reactflow.css"),
            requiresWebGl = false,
            isHeavyEngine = false,
            supportsOffline = true,
            supportsUndoRedo = true,
            supportsAnimationOverlay = true,
            backendRoleDescription = "Runs 100% offline inside WebView with React 18 + @xyflow/react."
        ),
        VisionTechnologySpec(
            id = VisionTechId.EXCALIDRAW,
            category = VisionCategory.DIAGRAMS_AND_FLOWCHARTS,
            displayName = "Excalidraw",
            shortTagline = "Hand-drawn interactive whiteboard with shapes, arrows, text, undo/redo & .excalidraw export",
            description = "Full interactive Excalidraw sketching canvas supporting freehand pen, rectangles, ellipses, diamonds, arrows, text labels, selection/move/resize, undo/redo, AI-generated editable sketch layouts, and .excalidraw / PNG / SVG export.",
            runtimeType = VisionRuntimeType.WEBVIEW_OFFLINE_BUNDLED,
            officialRepoUrl = "https://github.com/excalidraw/excalidraw",
            docsUrl = "https://docs.excalidraw.com/",
            packageName = "@excalidraw/excalidraw",
            version = "0.18.1",
            licenseName = "MIT",
            licenseNote = "Permissive MIT license.",
            capabilities = listOf(
                "Full Excalidraw toolbar (Pen, Shapes, Arrows, Text, Eraser)",
                "Touch pan/zoom, multi-select, undo/redo history",
                "Load AI-generated wireframe/system sketches onto editable canvas",
                "Export .excalidraw JSON, PNG & SVG"
            ),
            supportedInputFormats = listOf(".excalidraw", "Excalidraw JSON"),
            supportedExportFormats = listOf(VisualizationExportFormat.EXCALIDRAW, VisualizationExportFormat.PNG, VisualizationExportFormat.SVG, VisualizationExportFormat.JSON),
            assetBundleFiles = listOf("vision/vendor/excalidraw.bundle.mjs", "vision/vendor/excalidraw.css"),
            requiresWebGl = false,
            isHeavyEngine = true,
            supportsOffline = true,
            supportsUndoRedo = true,
            supportsAnimationOverlay = false,
            backendRoleDescription = "Runs 100% offline inside WebView with bundled Excalidraw assets."
        ),

        // C. Charts & Data Visualization
        VisionTechnologySpec(
            id = VisionTechId.D3_JS,
            category = VisionCategory.CHARTS_AND_DATA,
            displayName = "D3.js",
            shortTagline = "Bespoke SVG data visualizer: force network, radial tree, treemap & interactive charts",
            description = "Custom data-driven SVG visualizations powered by D3 v7: force-directed node-link networks, tidy hierarchical trees, nested treemaps, stacked streams, interactive hover/tap tooltips, pan/zoom, and smooth transitions.",
            runtimeType = VisionRuntimeType.WEBVIEW_OFFLINE_BUNDLED,
            officialRepoUrl = "https://github.com/d3/d3",
            docsUrl = "https://d3js.org/",
            packageName = "d3",
            version = "7.9.2",
            licenseName = "ISC",
            licenseNote = "Permissive ISC license.",
            capabilities = listOf(
                "Force network, Tidy Tree, Radial Tree & Treemap",
                "Interactive tooltips, zoom/pan & live layout transitions",
                "CSV / JSON data import with schema check",
                "Pure SVG & PNG export"
            ),
            supportedInputFormats = listOf("D3 Spec JSON", "CSV", "JSON"),
            supportedExportFormats = listOf(VisualizationExportFormat.SVG, VisualizationExportFormat.PNG, VisualizationExportFormat.JSON, VisualizationExportFormat.CSV),
            assetBundleFiles = listOf("vision/vendor/d3.bundle.mjs"),
            requiresWebGl = false,
            isHeavyEngine = false,
            supportsOffline = true,
            supportsUndoRedo = true,
            supportsAnimationOverlay = true,
            backendRoleDescription = "Runs 100% offline inside WebView."
        ),
        VisionTechnologySpec(
            id = VisionTechId.PLOTLY_JS,
            category = VisionCategory.CHARTS_AND_DATA,
            displayName = "Plotly.js",
            shortTagline = "Scientific 2D/3D charts: surface, 3D scatter, heatmap, histogram & box/violin plots",
            description = "Scientific graphing engine rendering interactive Bar, Line, Scatter, Pie, Histogram, Heatmap, 3D Surface, and 3D Scatter plots from genuine user/imported CSV/JSON datasets (never fabricates unverified numbers).",
            runtimeType = VisionRuntimeType.WEBVIEW_OFFLINE_BUNDLED,
            officialRepoUrl = "https://github.com/plotly/plotly.js",
            docsUrl = "https://plotly.com/javascript/",
            packageName = "plotly.js-dist-min",
            version = "4.3.0",
            licenseName = "MIT",
            licenseNote = "Permissive MIT license.",
            capabilities = listOf(
                "Bar, Line, Scatter, Pie, Histogram, Heatmap & 3D Surface",
                "Zoom, box-select, pan, hover readout & trace toggles",
                "CSV / JSON import with explicit numerical validation",
                "Export PNG, SVG & Plotly Spec JSON"
            ),
            supportedInputFormats = listOf("Plotly JSON", "CSV"),
            supportedExportFormats = listOf(VisualizationExportFormat.PNG, VisualizationExportFormat.SVG, VisualizationExportFormat.PLOTLY_JSON, VisualizationExportFormat.CSV),
            assetBundleFiles = listOf("vision/vendor/plotly.bundle.js"),
            requiresWebGl = false,
            isHeavyEngine = true,
            supportsOffline = true,
            supportsUndoRedo = false,
            supportsAnimationOverlay = true,
            backendRoleDescription = "Runs 100% offline inside WebView."
        ),
        VisionTechnologySpec(
            id = VisionTechId.APACHE_ECHARTS,
            category = VisionCategory.CHARTS_AND_DATA,
            displayName = "Apache ECharts",
            shortTagline = "Interactive Canvas/SVG dashboards, radar, gauge, heatmap, treemap & dataZoom",
            description = "Responsive, high-FPS analytics engine supporting animated Bar/Line/Area, Radar, Heatmap, Treemap, Sankey, Graph, Gauge & Funnel dashboards with pinch/slider dataZoom, series legend filter, and PNG/SVG export.",
            runtimeType = VisionRuntimeType.WEBVIEW_OFFLINE_BUNDLED,
            officialRepoUrl = "https://github.com/apache/echarts",
            docsUrl = "https://echarts.apache.org/en/index.html",
            packageName = "echarts",
            version = "6.1.0",
            licenseName = "Apache-2.0",
            licenseNote = "Permissive Apache 2.0 license.",
            capabilities = listOf(
                "Dashboards, Radar, Treemap, Heatmap, Sankey & Gauge",
                "Interactive tooltips, legend toggle & dataZoom slider",
                "Dark & Light adaptive theme palette",
                "SVG, PNG, JSON & CSV export"
            ),
            supportedInputFormats = listOf("ECharts Option JSON", "CSV"),
            supportedExportFormats = listOf(VisualizationExportFormat.PNG, VisualizationExportFormat.SVG, VisualizationExportFormat.JSON, VisualizationExportFormat.CSV),
            assetBundleFiles = listOf("vision/vendor/echarts.bundle.js"),
            requiresWebGl = false,
            isHeavyEngine = false,
            supportsOffline = true,
            supportsUndoRedo = true,
            supportsAnimationOverlay = true,
            backendRoleDescription = "Runs 100% offline inside WebView."
        ),
        VisionTechnologySpec(
            id = VisionTechId.FORCE_GRAPH_3D,
            category = VisionCategory.CHARTS_AND_DATA,
            displayName = "3D Force Graph",
            shortTagline = "Interactive 3D knowledge graphs with physics simulation, node drag & link particles",
            description = "WebGL/Three.js 3D force-directed graph visualizer for knowledge networks, architecture dependencies, and mindmaps: drag nodes in 3D, tap-to-focus camera, expand/collapse neighbors, toggle labels & directional particles, and tune physics.",
            runtimeType = VisionRuntimeType.WEBVIEW_OFFLINE_BUNDLED,
            officialRepoUrl = "https://github.com/vasturiano/3d-force-graph",
            docsUrl = "https://github.com/vasturiano/3d-force-graph#readme",
            packageName = "3d-force-graph",
            version = "1.80.2",
            licenseName = "MIT",
            licenseNote = "Permissive MIT license.",
            capabilities = listOf(
                "3D WebGL force layout with drag, zoom & tap camera focus",
                "Directional particles, curved links & neighbor expand/collapse",
                "Node search & label visibility toggle",
                "Adaptive node cap for mobile GPU thermal safety"
            ),
            supportedInputFormats = listOf("Nodes/Links Graph JSON"),
            supportedExportFormats = listOf(VisualizationExportFormat.JSON, VisualizationExportFormat.PNG),
            assetBundleFiles = listOf("vision/vendor/force-graph-3d.bundle.mjs"),
            requiresWebGl = true,
            isHeavyEngine = true,
            supportsOffline = true,
            supportsUndoRedo = true,
            supportsAnimationOverlay = true,
            backendRoleDescription = "Runs 100% locally in WebView WebGL."
        ),

        // D. Animation & Explainer
        VisionTechnologySpec(
            id = VisionTechId.GSAP,
            category = VisionCategory.ANIMATION_AND_EXPLAINER,
            displayName = "GSAP",
            shortTagline = "Timeline choreography engine animating diagrams, SVG explainers, 3D scenes & steps",
            description = "Timeline-based animation engine integrated directly with Mermaid, D3, Three.js, and Kavya step-by-step explainers. Provides Play/Pause/Restart/Scrub/Speed controls, camera cue sequences, and automatic Reduced Motion support.",
            runtimeType = VisionRuntimeType.WEBVIEW_OFFLINE_BUNDLED,
            officialRepoUrl = "https://github.com/greensock/GSAP",
            docsUrl = "https://gsap.com/docs/v3/",
            packageName = "gsap",
            version = "3.15.1",
            licenseName = "GSAP Standard No-Charge License",
            licenseNote = "Standard GSAP license applies for non-Club core plugins.",
            capabilities = listOf(
                "Interactive timeline scrubber with Play/Pause/Restart/Speed",
                "Step-by-step highlight & motion choreography over SVG/DOM/WebGL",
                "Respects JarvisOrbConfig.reducedMotion accessibility setting",
                "Exports timeline script JSON & snapshot PNG"
            ),
            supportedInputFormats = listOf("GSAP Timeline JSON", "Mermaid+GSAP", "D3+GSAP"),
            supportedExportFormats = listOf(VisualizationExportFormat.JSON, VisualizationExportFormat.SVG, VisualizationExportFormat.PNG),
            assetBundleFiles = listOf("vision/vendor/gsap.bundle.mjs"),
            requiresWebGl = false,
            isHeavyEngine = false,
            supportsOffline = true,
            supportsUndoRedo = true,
            supportsAnimationOverlay = true,
            backendRoleDescription = "Runs 100% offline inside WebView and syncs cues back to Kotlin."
        ),
        VisionTechnologySpec(
            id = VisionTechId.MANIM,
            category = VisionCategory.ANIMATION_AND_EXPLAINER,
            displayName = "Manim",
            shortTagline = "Mathematical & algorithm explainer video engine via isolated Python render worker",
            description = "Generates validated Manim Community Edition Python scene scripts for calculus, vectors, matrices, sorting, geometry, and neural networks. Submits jobs to an isolated Python Manim worker with queue/render status, cancellation, tracebacks, and native ExoPlayer MP4 playback.",
            runtimeType = VisionRuntimeType.REMOTE_PYTHON_WORKER,
            officialRepoUrl = "https://github.com/ManimCommunity/manim",
            docsUrl = "https://docs.manim.community/",
            packageName = "manim (Python Worker HTTP API)",
            version = "Worker API v1",
            licenseName = "MIT",
            licenseNote = "Requires external Python Manim Community + LaTeX/FFmpeg worker; never fabricated locally.",
            capabilities = listOf(
                "Generate & edit valid Manim Scene Python scripts",
                "Syntax & safety validation before job submission",
                "Async worker queue progress, cancellation & error tracebacks",
                "Native Media3 ExoPlayer MP4 preview & video/script export"
            ),
            supportedInputFormats = listOf("Manim Python Script (.py)", "Math Prompt"),
            supportedExportFormats = listOf(VisualizationExportFormat.PYTHON_PY, VisualizationExportFormat.MP4, VisualizationExportFormat.JSON),
            assetBundleFiles = emptyList(),
            requiresWebGl = false,
            isHeavyEngine = true,
            supportsOffline = false,
            supportsUndoRedo = true,
            supportsAnimationOverlay = false,
            backendRoleDescription = "Requires a live Python ManimCommunity HTTP worker URL (configured in Vision Settings)."
        )
    )

    private val byIdMap: Map<VisionTechId, VisionTechnologySpec> = allTechnologies.associateBy { it.id }

    fun getSpec(id: VisionTechId): VisionTechnologySpec =
        byIdMap[id] ?: error("Unknown VisionTechId: $id")

    fun byCategory(category: VisionCategory?): List<VisionTechnologySpec> =
        if (category == null) allTechnologies else allTechnologies.filter { it.category == category }

    fun defaultTemplates(): List<VisualizationTemplate> = listOf(
        VisualizationTemplate(
            id = "tpl_three_solar_rig",
            techId = VisionTechId.THREE_JS,
            title = "Orbital Neural Core Assembly",
            description = "3D multi-part metallic core with orbital rings, TransformControls & GLB export",
            promptHint = "Show a 3D orbital core assembly with glowing rings",
            payloadJson = JSONObject().apply {
                put("title", "Orbital Neural Core")
                put("background", "#070B12")
                put("wireframe", false)
                put("autoRotate", true)
                put("objects", JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", "core_sphere"); put("type", "icosahedron"); put("radius", 1.05); put("detail", 2)
                        put("color", "#00F5FF"); put("metalness", 0.65); put("roughness", 0.2); put("emissive", "#004D5A")
                        put("position", JSONArray(listOf(0, 0, 0)))
                    })
                    put(JSONObject().apply {
                        put("id", "inner_ring"); put("type", "torus"); put("radius", 1.85); put("tube", 0.06)
                        put("color", "#38BDF8"); put("metalness", 0.8); put("roughness", 0.15)
                        put("rotation", JSONArray(listOf(1.1, 0.3, 0.0)))
                    })
                    put(JSONObject().apply {
                        put("id", "outer_ring"); put("type", "torus"); put("radius", 2.45); put("tube", 0.05)
                        put("color", "#10B981"); put("metalness", 0.8); put("roughness", 0.18)
                        put("rotation", JSONArray(listOf(-0.8, 0.9, 0.2)))
                    })
                })
            }.toString(),
            tags = listOf("3D", "GLB", "Interactive")
        ),
        VisualizationTemplate(
            id = "tpl_babylon_physics",
            techId = VisionTechId.BABYLON_JS,
            title = "PBR Gravity & Bounce Arena",
            description = "PBR spheres and blocks interacting with ground bounce physics",
            promptHint = "Simulate 3D bouncing spheres with Babylon physics",
            payloadJson = JSONObject().apply {
                put("title", "Babylon Physics Arena")
                put("gravityEnabled", true)
                put("groundSize", 10)
                put("bodies", JSONArray().apply {
                    put(JSONObject().apply {
                        put("name", "CyanOrb"); put("shape", "sphere"); put("diameter", 1.2)
                        put("position", JSONArray(listOf(-1.4, 4.2, 0.0))); put("restitution", 0.84)
                        put("color", "#00E5FF")
                    })
                    put(JSONObject().apply {
                        put("name", "EmeraldCube"); put("shape", "box"); put("diameter", 1.1)
                        put("position", JSONArray(listOf(0.8, 5.8, 0.4))); put("restitution", 0.72)
                        put("color", "#10B981")
                    })
                    put(JSONObject().apply {
                        put("name", "AmberNode"); put("shape", "sphere"); put("diameter", 0.9)
                        put("position", JSONArray(listOf(1.8, 3.3, -0.8))); put("restitution", 0.88)
                        put("color", "#F59E0B")
                    })
                })
            }.toString(),
            tags = listOf("Babylon", "Physics", "PBR")
        ),
        VisualizationTemplate(
            id = "tpl_model_viewer_demo",
            techId = VisionTechId.MODEL_VIEWER,
            title = "Interactive GLB Rotor Inspector",
            description = "Procedural GLB generated on-device with annotated camera hotspots & exposure controls",
            promptHint = "Inspect a 3D GLB model with annotated hotspots",
            payloadJson = JSONObject().apply {
                put("title", "Procedural Rotor Capsule")
                put("modelSource", "procedural://capsule_assembly")
                put("exposure", 1.1)
                put("shadowIntensity", 0.85)
                put("autoRotate", true)
                put("hotspots", JSONArray().apply {
                    put(JSONObject().apply {
                        put("slot", "hotspot-core"); put("position", "0 0.45 0.55"); put("normal", "0 0 1")
                        put("label", "Sensor Dome")
                    })
                    put(JSONObject().apply {
                        put("slot", "hotspot-base"); put("position", "0.55 -0.35 0"); put("normal", "1 0 0")
                        put("label", "Drive Flange")
                    })
                })
            }.toString(),
            tags = listOf("glTF", "GLB", "Hotspots")
        ),
        VisualizationTemplate(
            id = "tpl_triposr_worker",
            techId = VisionTechId.TRIPO_SR,
            title = "Photo to 3D Mesh (TripoSR Worker)",
            description = "Upload an object photo to your configured TripoSR Python worker to reconstruct a GLB mesh",
            promptHint = "Convert my photo into a 3D model using TripoSR",
            payloadJson = JSONObject().apply {
                put("title", "TripoSR Image-to-3D Job")
                put("foregroundRatio", 0.85)
                put("mcResolution", 256)
                put("outputFormat", "glb")
                put("removeBackground", true)
            }.toString(),
            tags = listOf("Remote Worker", "Photo-to-3D", "GLB")
        ),
        VisualizationTemplate(
            id = "tpl_threepipe_studio",
            techId = VisionTechId.THREEPIPE,
            title = "Threepipe PBR Studio Showcase",
            description = "Real-time roughness, metalness, albedo & tonemapping editor with undo/redo",
            promptHint = "Open PBR material studio with Threepipe",
            payloadJson = JSONObject().apply {
                put("title", "PBR Studio Material Rig")
                put("meshPreset", "torusKnot")
                put("color", "#00E5FF")
                put("metalness", 0.85)
                put("roughness", 0.22)
                put("clearcoat", 0.6)
                put("envIntensity", 1.2)
            }.toString(),
            tags = listOf("Studio", "Materials", "Threepipe")
        ),
        VisualizationTemplate(
            id = "tpl_gsplat_cloud",
            techId = VisionTechId.GSPLAT_JS,
            title = "Gaussian Splat Volumetric Field",
            description = "Interactive 3D point/splat cloud or local .splat/.ply file loader with quality budget",
            promptHint = "Show a 3D Gaussian Splat volumetric field",
            payloadJson = JSONObject().apply {
                put("title", "Volumetric Helix Splat Field")
                put("sourceMode", "procedural")
                put("splatCount", 4200)
                put("attribution", "Procedural volumetric test splat generated on-device (no remote copyrighted asset).")
            }.toString(),
            tags = listOf("3DGS", "Splat", "WebGL")
        ),
        VisualizationTemplate(
            id = "tpl_cesium_globe",
            techId = VisionTechId.CESIUM_JS,
            title = "Global AI Edge Network Globe",
            description = "3D Cesium Earth globe with markers, great-circle arcs & offline fallback mode",
            promptHint = "Show a 3D globe with flight routes between Tokyo, London and San Francisco",
            payloadJson = JSONObject().apply {
                put("title", "Global Network Routes")
                put("offlineFallback", true)
                put("markers", JSONArray().apply {
                    put(JSONObject().apply { put("name", "San Francisco"); put("lat", 37.7749); put("lon", -122.4194); put("color", "#00F5FF") })
                    put(JSONObject().apply { put("name", "London"); put("lat", 51.5072); put("lon", -0.1276); put("color", "#10B981") })
                    put(JSONObject().apply { put("name", "Tokyo"); put("lat", 35.6762); put("lon", 139.6503); put("color", "#F59E0B") })
                    put(JSONObject().apply { put("name", "Bengaluru"); put("lat", 12.9716); put("lon", 77.5946); put("color", "#38BDF8") })
                })
                put("routes", JSONArray().apply {
                    put(JSONObject().apply { put("from", "San Francisco"); put("to", "London") })
                    put(JSONObject().apply { put("from", "London"); put("to", "Bengaluru") })
                    put(JSONObject().apply { put("from", "Bengaluru"); put("to", "Tokyo") })
                })
            }.toString(),
            tags = listOf("Globe", "Geo", "Routes")
        ),
        VisualizationTemplate(
            id = "tpl_mermaid_architecture",
            techId = VisionTechId.MERMAID_JS,
            title = "Kavya Hybrid Orchestration Flowchart",
            description = "Editable Mermaid flowchart with syntax validation & SVG/PNG export",
            promptHint = "Draw a Mermaid flowchart of Jarvis voice to visualization execution",
            payloadJson = JSONObject().apply {
                put("title", "JARVIS Visualization Pipeline")
                put("diagramType", "flowchart")
                put("theme", "dark")
                put("source", """
                    flowchart TD
                        A[User Voice or Hamburger Menu] --> B{Visualization Orchestrator}
                        B -->|Diagrams| C[Mermaid / React Flow / Excalidraw]
                        B -->|3D & Geo| D[Three.js / Babylon / Cesium / gsplat]
                        B -->|Charts & Networks| E[D3 / Plotly / ECharts / 3D Force]
                        B -->|Motion & Math| F[GSAP Timeline / Manim Worker]
                        C --> G[Local SVG/PNG/JSON Vault]
                        D --> G
                        E --> G
                        F --> G
                """.trimIndent())
            }.toString(),
            tags = listOf("Mermaid", "Flowchart", "SVG")
        ),
        VisualizationTemplate(
            id = "tpl_react_flow_dag",
            techId = VisionTechId.REACT_FLOW,
            title = "Interactive AI Tool Pipeline",
            description = "Draggable React Flow node canvas with live handle wiring & mini-map",
            promptHint = "Open an interactive node workflow diagram in React Flow",
            payloadJson = JSONObject().apply {
                put("title", "Editable Tool Pipeline")
                put("nodes", JSONArray().apply {
                    put(JSONObject().apply {
                        put("id", "n1"); put("position", JSONObject().apply { put("x", 40); put("y", 90) })
                        put("data", JSONObject().apply { put("label", "Jarvis Orb Query"); put("role", "TRIGGER") })
                    })
                    put(JSONObject().apply {
                        put("id", "n2"); put("position", JSONObject().apply { put("x", 280); put("y", 30) })
                        put("data", JSONObject().apply { put("label", "Schema Validator"); put("role", "GUARD") })
                    })
                    put(JSONObject().apply {
                        put("id", "n3"); put("position", JSONObject().apply { put("x", 280); put("y", 170) })
                        put("data", JSONObject().apply { put("label", "Context Fusion"); put("role", "MEMORY") })
                    })
                    put(JSONObject().apply {
                        put("id", "n4"); put("position", JSONObject().apply { put("x", 540); put("y", 100) })
                        put("data", JSONObject().apply { put("label", "WebView Renderer"); put("role", "OUTPUT") })
                    })
                })
                put("edges", JSONArray().apply {
                    put(JSONObject().apply { put("id", "e1-2"); put("source", "n1"); put("target", "n2"); put("animated", true); put("label", "validate") })
                    put(JSONObject().apply { put("id", "e1-3"); put("source", "n1"); put("target", "n3"); put("label", "enrich") })
                    put(JSONObject().apply { put("id", "e2-4"); put("source", "n2"); put("target", "n4"); put("animated", true) })
                    put(JSONObject().apply { put("id", "e3-4"); put("source", "n3"); put("target", "n4") })
                })
            }.toString(),
            tags = listOf("Nodes", "Interactive", "Workflow")
        ),
        VisualizationTemplate(
            id = "tpl_excalidraw_sketch",
            techId = VisionTechId.EXCALIDRAW,
            title = "Editable System Whiteboard",
            description = "Pre-populated Excalidraw shapes & arrows ready for sketching, erasing & .excalidraw export",
            promptHint = "Open an interactive Excalidraw architecture whiteboard",
            payloadJson = JSONObject().apply {
                put("title", "Architecture Sketchpad")
                put("theme", "dark")
                put("elements", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "rectangle"); put("id", "ex1"); put("x", 80); put("y", 90); put("width", 190); put("height", 85)
                        put("strokeColor", "#38BDF8"); put("backgroundColor", "#0F172A"); put("labelText", "User Prompt / Voice")
                    })
                    put(JSONObject().apply {
                        put("type", "diamond"); put("id", "ex2"); put("x", 340); put("y", 75); put("width", 180); put("height", 115)
                        put("strokeColor", "#10B981"); put("backgroundColor", "#064E3B"); put("labelText", "Orchestrator")
                    })
                    put(JSONObject().apply {
                        put("type", "ellipse"); put("id", "ex3"); put("x", 590); put("y", 90); put("width", 180); put("height", 85)
                        put("strokeColor", "#F59E0B"); put("backgroundColor", "#451A03"); put("labelText", "Visual Export")
                    })
                })
            }.toString(),
            tags = listOf("Whiteboard", "Sketch", "Editable")
        ),
        VisualizationTemplate(
            id = "tpl_d3_force_network",
            techId = VisionTechId.D3_JS,
            title = "D3 Force Concept Network",
            description = "Interactive D3.js SVG force-directed graph with zoom, node dragging & tooltips",
            promptHint = "Create an interactive D3 force graph of neural components",
            payloadJson = JSONObject().apply {
                put("title", "Neural Subsystem Force Map")
                put("chartMode", "force")
                put("nodes", JSONArray().apply {
                    put(JSONObject().apply { put("id", "Vision Core"); put("group", 1); put("value", 28) })
                    put(JSONObject().apply { put("id", "OCR Extractor"); put("group", 2); put("value", 18) })
                    put(JSONObject().apply { put("id", "Gemini Reasoning"); put("group", 1); put("value", 24) })
                    put(JSONObject().apply { put("id", "WebView Host"); put("group", 3); put("value", 22) })
                    put(JSONObject().apply { put("id", "3D Mesh Engine"); put("group", 3); put("value", 18) })
                    put(JSONObject().apply { put("id", "SVG Diagrams"); put("group", 4); put("value", 18) })
                    put(JSONObject().apply { put("id", "Voice Narrator"); put("group", 2); put("value", 16) })
                })
                put("links", JSONArray().apply {
                    put(JSONObject().apply { put("source", "Vision Core"); put("target", "OCR Extractor"); put("weight", 3) })
                    put(JSONObject().apply { put("source", "Vision Core"); put("target", "Gemini Reasoning"); put("weight", 4) })
                    put(JSONObject().apply { put("source", "Gemini Reasoning"); put("target", "WebView Host"); put("weight", 5) })
                    put(JSONObject().apply { put("source", "WebView Host"); put("target", "3D Mesh Engine"); put("weight", 3) })
                    put(JSONObject().apply { put("source", "WebView Host"); put("target", "SVG Diagrams"); put("weight", 3) })
                    put(JSONObject().apply { put("source", "Vision Core"); put("target", "Voice Narrator"); put("weight", 2) })
                })
            }.toString(),
            tags = listOf("D3", "SVG", "Network")
        ),
        VisualizationTemplate(
            id = "tpl_plotly_surface",
            techId = VisionTechId.PLOTLY_JS,
            title = "Plotly 3D Loss Surface",
            description = "Interactive 3D analytical surface plot of f(x,y) = sin(r)/r optimization landscape",
            promptHint = "Plot a 3D mathematical surface in Plotly",
            payloadJson = JSONObject().apply {
                put("title", "3D Sinc Optimization Surface")
                put("mode", "math_surface")
                put("expression", "sin(sqrt(x^2 + y^2))")
                put("rangeMin", -6)
                put("rangeMax", 6)
                put("steps", 28)
            }.toString(),
            tags = listOf("Plotly", "3D Surface", "Math")
        ),
        VisualizationTemplate(
            id = "tpl_echarts_radar",
            techId = VisionTechId.APACHE_ECHARTS,
            title = "ECharts Multi-Axis Radar Benchmark",
            description = "Interactive Apache ECharts dashboard comparing on-device vs cloud inference attributes",
            promptHint = "Show a radar capability chart using Apache ECharts",
            payloadJson = JSONObject().apply {
                put("title", "Runtime Architecture Characteristics")
                put("chartType", "radar")
                put("indicators", JSONArray().apply {
                    put(JSONObject().apply { put("name", "Offline Privacy"); put("max", 100) })
                    put(JSONObject().apply { put("name", "Low Latency"); put("max", 100) })
                    put(JSONObject().apply { put("name", "Complex Reasoning"); put("max", 100) })
                    put(JSONObject().apply { put("name", "Battery Efficiency"); put("max", 100) })
                    put(JSONObject().apply { put("name", "Visual Grounding"); put("max", 100) })
                })
                put("series", JSONArray().apply {
                    put(JSONObject().apply {
                        put("name", "On-Device Core Architecture")
                        put("values", JSONArray(listOf(100, 92, 68, 86, 82)))
                    })
                    put(JSONObject().apply {
                        put("name", "Cloud Hybrid Escalation")
                        put("values", JSONArray(listOf(40, 74, 98, 90, 95)))
                    })
                })
            }.toString(),
            tags = listOf("ECharts", "Radar", "Dashboard")
        ),
        VisualizationTemplate(
            id = "tpl_force3d_knowledge",
            techId = VisionTechId.FORCE_GRAPH_3D,
            title = "3D Knowledge Topology",
            description = "Interactive 3D knowledge network with animated link particles & neighbor focus",
            promptHint = "Render a 3D knowledge graph of science concepts",
            payloadJson = JSONObject().apply {
                put("title", "3D Knowledge Constellation")
                put("nodes", JSONArray().apply {
                    put(JSONObject().apply { put("id", "AI Systems"); put("group", 1); put("val", 12) })
                    put(JSONObject().apply { put("id", "Transformers"); put("group", 2); put("val", 8) })
                    put(JSONObject().apply { put("id", "Computer Vision"); put("group", 2); put("val", 8) })
                    put(JSONObject().apply { put("id", "3D Graphics"); put("group", 3); put("val", 9) })
                    put(JSONObject().apply { put("id", "WebGL Shaders"); put("group", 3); put("val", 6) })
                    put(JSONObject().apply { put("id", "Gaussian Splats"); put("group", 3); put("val", 7) })
                    put(JSONObject().apply { put("id", "Attention"); put("group", 4); put("val", 6) })
                    put(JSONObject().apply { put("id", "OCR Fusion"); put("group", 4); put("val", 6) })
                })
                put("links", JSONArray().apply {
                    put(JSONObject().apply { put("source", "AI Systems"); put("target", "Transformers") })
                    put(JSONObject().apply { put("source", "AI Systems"); put("target", "Computer Vision") })
                    put(JSONObject().apply { put("source", "AI Systems"); put("target", "3D Graphics") })
                    put(JSONObject().apply { put("source", "Transformers"); put("target", "Attention") })
                    put(JSONObject().apply { put("source", "Computer Vision"); put("target", "OCR Fusion") })
                    put(JSONObject().apply { put("source", "3D Graphics"); put("target", "WebGL Shaders") })
                    put(JSONObject().apply { put("source", "3D Graphics"); put("target", "Gaussian Splats") })
                })
            }.toString(),
            tags = listOf("3D Graph", "WebGL", "Knowledge")
        ),
        VisualizationTemplate(
            id = "tpl_gsap_explainer",
            techId = VisionTechId.GSAP,
            title = "GSAP Synchronized Stage Walkthrough",
            description = "Timeline-scrubbed interactive step-by-step explainer animation over SVG nodes",
            promptHint = "Animate a step by step explainer timeline using GSAP",
            payloadJson = JSONObject().apply {
                put("title", "Signal Processing Pipeline Timeline")
                put("steps", JSONArray().apply {
                    put(JSONObject().apply { put("id", "s1"); put("label", "1. Capture Screen / Voice"); put("color", "#38BDF8"); put("duration", 0.9) })
                    put(JSONObject().apply { put("id", "s2"); put("label", "2. Fuse OCR + Accessibility"); put("color", "#10B981"); put("duration", 0.9) })
                    put(JSONObject().apply { put("id", "s3"); put("label", "3. Plan Structured Visuals"); put("color", "#F59E0B"); put("duration", 0.9) })
                    put(JSONObject().apply { put("id", "s4"); put("label", "4. Render & Narrate"); put("color", "#22D3EE"); put("duration", 0.9) })
                })
            }.toString(),
            tags = listOf("GSAP", "Timeline", "Explainer")
        ),
        VisualizationTemplate(
            id = "tpl_manim_math",
            techId = VisionTechId.MANIM,
            title = "Fourier Circle Decomposition (Manim Script)",
            description = "Validated Manim Community Python scene ready to send to your configured Python render worker",
            promptHint = "Generate a Manim animation explaining wave decomposition",
            payloadJson = JSONObject().apply {
                put("title", "Sine Wave Decomposition Scene")
                put("sceneClass", "SineWaveExplainer")
                put("quality", "m")
                put("script", """
                    from manim import *

                    class SineWaveExplainer(Scene):
                        def construct(self):
                            axes = Axes(
                                x_range=[0, 6.5, 1],
                                y_range=[-1.5, 1.5, 0.5],
                                axis_config={"color": BLUE_B}
                            )
                            title = Text("Harmonic Wave Sum", font_size=34).to_edge(UP)
                            wave1 = axes.plot(lambda x: np.sin(x), color=TEAL)
                            wave2 = axes.plot(lambda x: 0.5 * np.sin(2 * x), color=GOLD)
                            combined = axes.plot(lambda x: np.sin(x) + 0.5 * np.sin(2 * x), color=GREEN)

                            self.play(Write(title), Create(axes))
                            self.play(Create(wave1), run_time=1.4)
                            self.play(Create(wave2), run_time=1.2)
                            self.play(Transform(wave1.copy(), combined), run_time=1.8)
                            self.wait(1.0)
                """.trimIndent())
            }.toString(),
            tags = listOf("Manim", "Python", "Video Worker")
        )
    )
}
