package com.example.vision

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File

class VisionWebViewController {
    internal var webView: WebView? = null
    internal var isPageLoaded: Boolean = false
    private var pendingRenderJs: String? = null

    fun render(
        artifact: VisualizationArtifact,
        quality: QualityPreset,
        reducedMotion: Boolean,
        cesiumIonToken: String,
        arSupported: Boolean
    ) {
        val optionsJson = JSONObject().apply {
            put("quality", quality.wireValue)
            put("reducedMotion", reducedMotion)
            put("cesiumIonToken", cesiumIonToken)
            put("arSupported", arSupported)
        }.toString()

        val payloadLiteral = artifact.scenePayloadJson.ifBlank { "{}" }
        val safeTech = JSONObject.quote(artifact.techId.wireId)
        val js = "window.KavyaVision && window.KavyaVision.render($safeTech, $payloadLiteral, $optionsJson);"
        val view = webView
        if (view != null && isPageLoaded) {
            view.post { view.evaluateJavascript(js, null) }
        } else {
            pendingRenderJs = js
        }
    }

    internal fun flushPending() {
        val js = pendingRenderJs ?: return
        pendingRenderJs = null
        webView?.post { webView?.evaluateJavascript(js, null) }
    }

    fun requestExport(format: VisualizationExportFormat) {
        val fmtLit = JSONObject.quote(format.name)
        webView?.post {
            webView?.evaluateJavascript("window.KavyaVision && window.KavyaVision.requestExport($fmtLit);", null)
        }
    }

    fun disposeEngines() {
        webView?.post {
            webView?.evaluateJavascript("window.KavyaVision && window.KavyaVision.disposeAll();", null)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VisionWebViewHost(
    artifact: VisualizationArtifact,
    quality: QualityPreset,
    reducedMotion: Boolean,
    cesiumIonToken: String,
    arSupported: Boolean,
    controller: VisionWebViewController,
    onEvent: (VisionJsEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentOnEvent by rememberUpdatedState(onEvent)
    var isLoaded by remember { mutableStateOf(false) }

    val assetLoader = remember(context) {
        val filesRoot = File(context.filesDir, "visualizations").apply { mkdirs() }
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .addPathHandler("/vision_files/", WebViewAssetLoader.InternalStoragePathHandler(context, filesRoot))
            .build()
    }

    val bridge = remember {
        object {
            @JavascriptInterface
            fun onEngineReady(techId: String, info: String) {
                VisionTechId.fromWireId(techId)?.let { id ->
                    controller.webView?.post {
                        currentOnEvent(VisionJsEvent.EngineReady(id, info))
                    }
                }
            }

            @JavascriptInterface
            fun onRenderComplete(techId: String, metricsJson: String) {
                VisionTechId.fromWireId(techId)?.let { id ->
                    controller.webView?.post {
                        currentOnEvent(VisionJsEvent.RenderComplete(id, metricsJson))
                    }
                }
            }

            @JavascriptInterface
            fun onError(techId: String, code: String, message: String) {
                VisionTechId.fromWireId(techId)?.let { id ->
                    controller.webView?.post {
                        currentOnEvent(VisionJsEvent.RenderError(id, code, message))
                    }
                }
            }

            @JavascriptInterface
            fun onStateMutated(techId: String, payloadJson: String, label: String) {
                VisionTechId.fromWireId(techId)?.let { id ->
                    controller.webView?.post {
                        currentOnEvent(VisionJsEvent.StateMutated(id, payloadJson, label))
                    }
                }
            }

            @JavascriptInterface
            fun onExportResult(techId: String, formatName: String, data: String, isBase64: Boolean) {
                val id = VisionTechId.fromWireId(techId) ?: return
                val fmt = runCatching { VisualizationExportFormat.valueOf(formatName) }.getOrNull() ?: return
                controller.webView?.post {
                    currentOnEvent(VisionJsEvent.ExportReady(id, fmt, data, isBase64))
                }
            }
        }
    }

    LaunchedEffect(artifact.artifactId, artifact.scenePayloadJson, artifact.techIdWire, quality, reducedMotion) {
        controller.render(
            artifact = artifact,
            quality = quality,
            reducedMotion = reducedMotion,
            cesiumIonToken = cesiumIonToken,
            arSupported = arSupported
        )
    }

    DisposableEffect(controller) {
        onDispose {
            controller.webView?.let { wv ->
                wv.evaluateJavascript("window.KavyaVision && window.KavyaVision.disposeAll();", null)
                wv.removeJavascriptInterface("VisionJsBridge")
                wv.onPause()
                wv.destroy()
            }
            controller.webView = null
            controller.isPageLoaded = false
        }
    }

    Box(
        modifier = modifier
            .background(Color(0xFF070B12))
            .testTag("vision_webview_stage")
    ) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(android.graphics.Color.parseColor("#070B12"))
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        mediaPlaybackRequiresUserGesture = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        builtInZoomControls = true
                        displayZoomControls = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    }
                    addJavascriptInterface(bridge, "VisionJsBridge")
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            val response = assetLoader.shouldInterceptRequest(request.url) ?: return null
                            val path = request.url.path.orEmpty().lowercase()
                            when {
                                path.endsWith(".mjs") || path.endsWith(".js") -> response.mimeType = "application/javascript"
                                path.endsWith(".css") -> response.mimeType = "text/css"
                                path.endsWith(".glb") -> response.mimeType = "model/gltf-binary"
                                path.endsWith(".woff2") -> response.mimeType = "font/woff2"
                                path.endsWith(".svg") -> response.mimeType = "image/svg+xml"
                                path.endsWith(".json") -> response.mimeType = "application/json"
                            }
                            return response
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest
                        ): Boolean {
                            val uri = request.url
                            if (uri.host == "appassets.androidplatform.net") return false
                            // Isolate WebView URL loading: route external docs/repo links to system browser only
                            runCatching {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri.toString())).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            }
                            return true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            controller.isPageLoaded = true
                            isLoaded = true
                            controller.flushPending()
                            controller.render(
                                artifact = artifact,
                                quality = quality,
                                reducedMotion = reducedMotion,
                                cesiumIonToken = cesiumIonToken,
                                arSupported = arSupported
                            )
                        }
                    }
                    controller.webView = this
                    loadUrl("https://appassets.androidplatform.net/assets/vision/index.html")
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (!isLoaded) {
            CircularProgressIndicator(
                color = Color(0xFF00E5FF),
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
