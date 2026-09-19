package com.example.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.ai.providers.HfSpecializedTask
import com.example.ai.providers.HuggingFaceProvider
import com.example.ai.providers.OpenRouterProvider
import com.example.services.KavyaAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Definition of a callable tool in Kavya's Tool Architecture.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, String> // paramName to description
)

/**
 * Result of a tool execution containing real operational data or honest error details.
 */
data class ToolExecutionResult(
    val toolName: String,
    val success: Boolean,
    val data: String,
    val error: String? = null
)

/**
 * Central Tool Registry executing real functionality for all AI-callable tools.
 * Never fabricates success or returns simulated mock results.
 */
object KavyaToolRegistry {

    private const val TAG = "KavyaToolRegistry"

    val AVAILABLE_TOOLS = listOf(
        ToolDefinition("open_app", "Opens an installed Android application by name.", mapOf("app_name" to "Target application name")),
        ToolDefinition("close_app", "Closes the current foreground app and navigates to home screen.", emptyMap()),
        ToolDefinition("tap", "Taps on a UI element matching semantic text, content description, or view ID.", mapOf("target" to "Semantic element label or selector")),
        ToolDefinition("type_text", "Enters text into the focused or target editable input field.", mapOf("text" to "Text string to input", "target" to "Optional target field identifier")),
        ToolDefinition("scroll", "Scrolls the active window content.", mapOf("direction" to "UP, DOWN, LEFT, or RIGHT")),
        ToolDefinition("swipe", "Performs a directional touch swipe across the display.", mapOf("direction" to "UP, DOWN, LEFT, or RIGHT")),
        ToolDefinition("press_back", "Triggers the Android system back button.", emptyMap()),
        ToolDefinition("search_web", "Searches the web via the default web browser.", mapOf("query" to "Search query string")),
        ToolDefinition("open_url", "Opens a specific web URL in the browser.", mapOf("url" to "Web URL starting with http/https")),
        ToolDefinition("read_screen", "Reads visible text, buttons, and accessibility hierarchy on the active screen.", emptyMap()),
        ToolDefinition("screenshot", "Captures the active screen display as an image bitmap.", emptyMap()),
        ToolDefinition("download_file", "Initiates file download from URL or saves file locally.", mapOf("url" to "Download URL", "file_name" to "Target filename")),
        ToolDefinition("switch_app", "Switches to an application or opens recent apps overview.", mapOf("app_name" to "Target app name, or blank for recents overview")),
        ToolDefinition("analyze_image", "Performs computer vision analysis or image captioning on an image.", mapOf("image_path" to "Image file path or description")),
        ToolDefinition("research_web", "Executes multi-source deep research using OpenRouter engine.", mapOf("query" to "Research question or investigation topic")),
        ToolDefinition("generate_image", "Generates an image from a prompt via Hugging Face diffusion models.", mapOf("prompt" to "Visual description prompt")),
        ToolDefinition("semantic_search", "Computes embeddings and semantic similarity for text search.", mapOf("query" to "Search query", "content" to "Target text content"))
    )

    /**
     * Executes the requested tool with real Android or AI capabilities.
     */
    suspend fun executeTool(
        name: String,
        parameters: Map<String, String>,
        context: Context,
        androidAgent: AndroidAgent
    ): ToolExecutionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Executing tool: $name with parameters: $parameters")
        val cleanName = name.trim().lowercase()

        try {
            when (cleanName) {
                "open_app" -> {
                    val appName = parameters["app_name"] ?: parameters["app"] ?: parameters["target"] ?: ""
                    if (appName.isBlank()) {
                        return@withContext ToolExecutionResult(name, false, "", "Missing parameter: app_name")
                    }
                    val step = TaskStep(1, UniversalActionType.OPEN_APP, targetAppOrUrl = appName, param = appName)
                    val res = androidAgent.executeAtomicStep(step)
                    ToolExecutionResult(name, res.success, res.output, if (!res.success) res.diagnostic?.verification ?: res.output else null)
                }

                "close_app" -> {
                    val step = TaskStep(1, UniversalActionType.CLOSE_APP)
                    val res = androidAgent.executeAtomicStep(step)
                    ToolExecutionResult(name, res.success, res.output, if (!res.success) res.diagnostic?.verification ?: res.output else null)
                }

                "tap" -> {
                    val target = parameters["target"] ?: parameters["selector"] ?: parameters["text"] ?: ""
                    val step = TaskStep(1, UniversalActionType.TAP, param = target, selectorType = SelectorType.SEMANTIC_HINT)
                    val res = androidAgent.executeAtomicStep(step)
                    ToolExecutionResult(name, res.success, res.output, if (!res.success) res.diagnostic?.verification ?: res.output else null)
                }

                "type_text" -> {
                    val text = parameters["text"] ?: parameters["query"] ?: ""
                    val step = TaskStep(1, UniversalActionType.TYPE, param = text, selectorType = SelectorType.SEARCH_FIELD)
                    val res = androidAgent.executeAtomicStep(step)
                    ToolExecutionResult(name, res.success, res.output, if (!res.success) res.diagnostic?.verification ?: res.output else null)
                }

                "scroll" -> {
                    val direction = parameters["direction"] ?: "DOWN"
                    val step = TaskStep(1, UniversalActionType.SCROLL, param = direction)
                    val res = androidAgent.executeAtomicStep(step)
                    ToolExecutionResult(name, res.success, res.output, if (!res.success) res.diagnostic?.verification ?: res.output else null)
                }

                "swipe" -> {
                    val direction = parameters["direction"] ?: "DOWN"
                    val step = TaskStep(1, UniversalActionType.SWIPE, param = direction)
                    val res = androidAgent.executeAtomicStep(step)
                    ToolExecutionResult(name, res.success, res.output, if (!res.success) res.diagnostic?.verification ?: res.output else null)
                }

                "press_back" -> {
                    val step = TaskStep(1, UniversalActionType.BACK)
                    val res = androidAgent.executeAtomicStep(step)
                    ToolExecutionResult(name, res.success, res.output, if (!res.success) res.diagnostic?.verification ?: res.output else null)
                }

                "search_web" -> {
                    val query = parameters["query"] ?: parameters["target"] ?: ""
                    val searchUrl = "https://www.google.com/search?q=" + Uri.encode(query)
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ToolExecutionResult(name, true, "Opened browser search for: $query")
                }

                "open_url" -> {
                    val rawUrl = parameters["url"] ?: parameters["target"] ?: ""
                    val url = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) rawUrl else "https://$rawUrl"
                    val step = TaskStep(1, UniversalActionType.OPEN_URL, targetAppOrUrl = url, param = url)
                    val res = androidAgent.executeAtomicStep(step)
                    ToolExecutionResult(name, res.success, res.output, if (!res.success) res.diagnostic?.verification ?: res.output else null)
                }

                "read_screen" -> {
                    val step = TaskStep(1, UniversalActionType.READ_SCREEN)
                    val res = androidAgent.executeAtomicStep(step)
                    ToolExecutionResult(name, res.success, res.output, if (!res.success) res.diagnostic?.verification ?: res.output else null)
                }

                "screenshot" -> {
                    val step = TaskStep(1, UniversalActionType.SCREENSHOT)
                    val res = androidAgent.executeAtomicStep(step)
                    ToolExecutionResult(name, res.success, res.output, if (!res.success) res.diagnostic?.verification ?: res.output else null)
                }

                "download_file" -> {
                    val url = parameters["url"] ?: parameters["file_name"] ?: ""
                    val step = TaskStep(1, UniversalActionType.DOWNLOAD_FILE, param = url)
                    val res = androidAgent.executeAtomicStep(step)
                    ToolExecutionResult(name, res.success, res.output, if (!res.success) res.diagnostic?.verification ?: res.output else null)
                }

                "switch_app" -> {
                    val target = parameters["app_name"] ?: parameters["target"] ?: ""
                    val step = TaskStep(1, UniversalActionType.SWITCH_APP, targetAppOrUrl = target)
                    val res = androidAgent.executeAtomicStep(step)
                    ToolExecutionResult(name, res.success, res.output, if (!res.success) res.diagnostic?.verification ?: res.output else null)
                }

                "analyze_image" -> {
                    val input = parameters["image_path"] ?: parameters["text"] ?: ""
                    val hf = HuggingFaceProvider()
                    val res = hf.executeSpecializedTask(HfSpecializedTask.IMAGE_ANALYSIS, textInput = input, context = context)
                    ToolExecutionResult(name, res.success, res.outputText, res.error)
                }

                "research_web" -> {
                    val query = parameters["query"] ?: parameters["topic"] ?: ""
                    val openRouter = OpenRouterProvider()
                    val res = openRouter.deepResearch(query, context)
                    ToolExecutionResult(name, res.success, res.report, res.error)
                }

                "generate_image" -> {
                    val prompt = parameters["prompt"] ?: parameters["text"] ?: ""
                    val hf = HuggingFaceProvider()
                    val res = hf.executeSpecializedTask(HfSpecializedTask.IMAGE_GENERATION, textInput = prompt, context = context)
                    ToolExecutionResult(name, res.success, res.outputText, res.error)
                }

                "semantic_search" -> {
                    val query = parameters["query"] ?: parameters["text"] ?: ""
                    val hf = HuggingFaceProvider()
                    val res = hf.executeSpecializedTask(HfSpecializedTask.EMBEDDINGS, textInput = query, context = context)
                    ToolExecutionResult(name, res.success, res.outputText, res.error)
                }

                else -> ToolExecutionResult(name, false, "", "Unknown tool: $name")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution failed: ${e.message}", e)
            ToolExecutionResult(name, false, "", e.message)
        }
    }
}
