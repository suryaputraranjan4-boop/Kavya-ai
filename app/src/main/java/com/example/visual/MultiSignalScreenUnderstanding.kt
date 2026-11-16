package com.example.visual

import android.graphics.Rect
import com.example.agent.AnalyzedNode
import com.example.agent.FreshScreenState
import com.example.orchestrator.CoordinateSpace
import com.example.orchestrator.ScreenTextSanitizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Multi-Signal Screen Understanding System (Upgrade 2 + Multi-Model GUI Intelligence).
 * Combines Accessibility Hierarchy, OCR Text Blocks, Florence-2 Auxiliary Vision proposals,
 * SmolVLM2 Local GUI VLM Grounding, and Online Gemini Vision into a deduplicated
 * Unified UI Representation with InputFieldRole & IconRole disambiguation,
 * V-Droid-style Pre-Action / Post-Action Verification, Short-Term Navigation Memory,
 * and Untrusted Screen Content Protection.
 */
enum class SignalSource {
    ACCESSIBILITY,
    OCR,
    VISION,
    LOCAL_CLASSIFIER,
    LOCAL_DETECTOR,
    AUX_VISION_MODEL,
    LOCAL_GUI_VLM,
    ONLINE_GEMINI_VISION
}

enum class InputFieldRole {
    SEARCH_FIELD,
    BROWSER_ADDRESS_BAR,
    CHAT_COMPOSER,
    COMMENT_BOX,
    LOGIN_FIELD,
    FORM_FIELD,
    GENERAL_EDITOR,
    NOT_INPUT
}

enum class IconRole {
    SEARCH_ICON,
    SEND_ICON,
    BACK_ICON,
    MENU_ICON,
    CLOSE_ICON,
    ATTACH_ICON,
    DOWNLOAD_ICON,
    FAB_ACTION,
    UNKNOWN_ICON,
    NONE
}

enum class ScreenSemanticType {
    HOME_FEED,
    SEARCH_ENTRY,
    SEARCH_RESULTS,
    CHAT_THREAD,
    LOGIN_SCREEN,
    SETTINGS_SCREEN,
    BROWSER_PAGE,
    MEDIA_PLAYER,
    MODAL_DIALOG,
    GENERAL_APP_SCREEN
}

data class OcrTextSignal(
    val text: String,
    val bounds: Rect,
    val language: String = "und",
    val confidence: Float = 0.85f
)

data class VisionElementSignal(
    val label: String,
    val roleHint: String = "",
    val bounds: Rect,
    val confidence: Float = 0.88f,
    val sourceSignal: SignalSource = SignalSource.VISION,
    val sourceModelId: String = "",
    val sourceModelRevision: String = "",
    val isConfidenceCalibrated: Boolean = true
)

data class SignalDisagreementRecord(
    val primarySource: SignalSource,
    val secondarySource: SignalSource,
    val centerDistancePx: Float,
    val iou: Float,
    val description: String
)

data class UnifiedUIElement(
    val elementId: String,
    val sourceSignals: Set<SignalSource>,
    val packageName: String,
    val className: String,
    val viewIdResourceName: String,
    val visibleText: String,
    val contentDescription: String,
    val hintText: String = "",
    val inferredInputRole: InputFieldRole = InputFieldRole.NOT_INPUT,
    val iconRole: IconRole = IconRole.NONE,
    val bounds: Rect,
    val centerPoint: Pair<Int, Int> = Pair(bounds.centerX(), bounds.centerY()),
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isEnabled: Boolean = true,
    val isFocused: Boolean = false,
    val isSelected: Boolean = false,
    val confidence: Float = 0.90f,
    val analyzedNode: AnalyzedNode? = null,
    val semanticRole: String = "",
    val elementType: String = className.substringAfterLast('.').ifBlank { "View" },
    val coordinateSpace: CoordinateSpace = CoordinateSpace.DISPLAY_PIXELS,
    val screenshotWidth: Int = 1080,
    val screenshotHeight: Int = 2400,
    val normalizedBounds: PrecisionCoordinateMapper.NormalizedRect =
        PrecisionCoordinateMapper.toNormalizedBounds(bounds, screenshotWidth, screenshotHeight),
    val normalizedCenter: Pair<Float, Float> =
        Pair(normalizedBounds.centerX, normalizedBounds.centerY),
    val sourceModelId: String = "",
    val sourceModelRevision: String = "",
    val isConfidenceCalibrated: Boolean = true,
    val disagreements: List<SignalDisagreementRecord> = emptyList(),
    val frameId: String = "",
    val observationTimestampMs: Long = System.currentTimeMillis(),
    val isFresh: Boolean = true,
    val screenFingerprint: String = ""
) {
    val primaryLabel: String
        get() = visibleText.ifBlank { contentDescription.ifBlank { hintText.ifBlank { viewIdResourceName.substringAfterLast("/") } } }

    val normalizedCenterX: Float
        get() = normalizedCenter.first

    val normalizedCenterY: Float
        get() = normalizedCenter.second

    /**
     * Returns true when this candidate comes exclusively from `AUX_VISION_MODEL` (Florence-2)
     * without corroboration from Accessibility, OCR, SmolVLM2 (`LOCAL_GUI_VLM`), or Gemini (`ONLINE_GEMINI_VISION`).
     * Per Section 2.3 and 6.2, Florence-2 alone is NEVER authoritative for direct tap/type actions.
     */
    val isAuxVisionOnly: Boolean
        get() = sourceSignals.isNotEmpty() && sourceSignals.all { it == SignalSource.AUX_VISION_MODEL }
}

data class PreActionVerificationResult(
    val approved: Boolean,
    val selectedCandidate: UnifiedUIElement?,
    val confidence: Float,
    val rejectionReason: String = "",
    val requiresReperception: Boolean = false,
    val requiresUserConfirmation: Boolean = false
)

data class PostActionVerificationResult(
    val transitionVerified: Boolean,
    val summary: String,
    val detectedScreenType: ScreenSemanticType,
    val shouldRetryWithAlternate: Boolean = false,
    val beforeFingerprint: String = "",
    val afterFingerprint: String = "",
    val cacheInvalidated: Boolean = false
)

/**
 * Classifies editable inputs and clickable bars to prevent Search Bar vs. Chat Composer confusion.
 */
object InputFieldClassifier {

    private val messagingPackages = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "com.instagram.android",
        "com.google.android.apps.messaging",
        "com.facebook.orca",
        "com.snapchat.android",
        "com.discord"
    )

    private val browserPackages = setOf(
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.brave.browser",
        "com.opera.browser",
        "com.sec.android.app.sbrowser"
    )

    private val searchKeywords = listOf(
        "search", "find", "query", "खोज", "खोजें", "ढूंढो", "search_src_text",
        "search_edit_frame", "search_box", "search_bar", "search_input", "explore_search"
    )

    private val addressBarKeywords = listOf(
        "url_bar", "location_bar", "address_bar", "omnibox",
        "search or type web address", "search or type url", "type a url"
    )

    private val chatComposerKeywords = listOf(
        "type a message", "write a message", "message...", "send a message", "message",
        "chat_input", "row_thread_composer", "message_edit_text", "input_message",
        "composer_edit_text", "send message", "संदेश लिखें", "संदेश", "मैसेज"
    )

    private val commentKeywords = listOf(
        "add a comment", "write a comment", "comment...", "comment_composer", "reply..."
    )

    private val loginKeywords = listOf(
        "username", "email", "password", "passcode", "phone number", "otp", "sign in", "log in"
    )

    fun classifyNode(
        node: AnalyzedNode,
        allNodes: List<AnalyzedNode> = emptyList(),
        foregroundPackage: String = "",
        screenHeight: Int = 2400
    ): InputFieldRole {
        val idLower = node.resourceId.lowercase(Locale.ROOT)
        val textLower = node.text.lowercase(Locale.ROOT)
        val descLower = node.contentDescription.lowercase(Locale.ROOT)
        val clsLower = node.className.lowercase(Locale.ROOT)
        val pkgLower = foregroundPackage.lowercase(Locale.ROOT)
        val combined = "$idLower $textLower $descLower"

        val isEditableLike = node.isEditable ||
            clsLower.contains("edittext") ||
            clsLower.contains("autocompletetextview") ||
            clsLower.contains("searchview")
        val isSearchContainer = node.isClickable && searchKeywords.any { combined.contains(it) }

        if (!isEditableLike && !isSearchContainer) {
            return InputFieldRole.NOT_INPUT
        }

        if (addressBarKeywords.any { combined.contains(it) } ||
            (pkgLower in browserPackages && (idLower.contains("url") || idLower.contains("search_box") || node.bounds.top < screenHeight * 0.25))
        ) {
            return InputFieldRole.BROWSER_ADDRESS_BAR
        }

        if (commentKeywords.any { combined.contains(it) }) {
            return InputFieldRole.COMMENT_BOX
        }

        if (loginKeywords.any { combined.contains(it) }) {
            return InputFieldRole.LOGIN_FIELD
        }

        val isWhatsAppEntry = pkgLower.startsWith("com.whatsapp") &&
            (idLower == "entry" || idLower.endsWith(":id/entry") || idLower.contains("chat_input") || idLower.contains("composer"))
        val isExplicitComposerId = idLower == "entry" || idLower.endsWith("/entry") ||
            idLower.contains("chat_input") || idLower.contains("composer") ||
            idLower.contains("message_edit") || idLower.contains("row_thread_composer")
        val isInBottomComposerZone = node.bounds.centerY() > (screenHeight * 0.58)
        val hasNearbySendOrAttach = allNodes.any { sibling ->
            sibling !== node &&
                abs(sibling.bounds.centerY() - node.bounds.centerY()) < 160 &&
                IconRoleClassifier.classifyIcon(sibling, screenHeight) in setOf(IconRole.SEND_ICON, IconRole.ATTACH_ICON)
        }

        if (isWhatsAppEntry || isExplicitComposerId ||
            chatComposerKeywords.any { kw ->
                if (kw == "message") (textLower == "message" || descLower == "message" || textLower.startsWith("message"))
                else combined.contains(kw)
            }
        ) {
            if (!searchKeywords.any { combined.contains(it) }) {
                return InputFieldRole.CHAT_COMPOSER
            }
        }

        val hasExplicitSearchKeyword = searchKeywords.any { combined.contains(it) } &&
            !combined.contains("search in chat")
        if (hasExplicitSearchKeyword) {
            return InputFieldRole.SEARCH_FIELD
        }

        if (isInBottomComposerZone && (pkgLower in messagingPackages || hasNearbySendOrAttach)) {
            return InputFieldRole.CHAT_COMPOSER
        }

        val isInTopSearchZone = node.bounds.centerY() in 1..(screenHeight * 0.28).toInt()
        val hasNearbySearchOrClearIcon = allNodes.any { sibling ->
            sibling !== node &&
                abs(sibling.bounds.centerY() - node.bounds.centerY()) < 140 &&
                IconRoleClassifier.classifyIcon(sibling, screenHeight) in setOf(IconRole.SEARCH_ICON, IconRole.CLOSE_ICON)
        }
        if (isInTopSearchZone && hasNearbySearchOrClearIcon && isEditableLike) {
            return InputFieldRole.SEARCH_FIELD
        }

        return if (isEditableLike) InputFieldRole.GENERAL_EDITOR else InputFieldRole.NOT_INPUT
    }

    fun isTrueSearchField(
        node: AnalyzedNode,
        allNodes: List<AnalyzedNode> = emptyList(),
        foregroundPackage: String = "",
        screenHeight: Int = 2400
    ): Boolean {
        val role = classifyNode(node, allNodes, foregroundPackage, screenHeight)
        return role == InputFieldRole.SEARCH_FIELD || role == InputFieldRole.BROWSER_ADDRESS_BAR
    }

    fun isTrueChatComposer(
        node: AnalyzedNode,
        allNodes: List<AnalyzedNode> = emptyList(),
        foregroundPackage: String = "",
        screenHeight: Int = 2400
    ): Boolean {
        val role = classifyNode(node, allNodes, foregroundPackage, screenHeight)
        return role == InputFieldRole.CHAT_COMPOSER || role == InputFieldRole.COMMENT_BOX
    }
}

/**
 * Understands icon-only buttons using contentDescription, resource ID, and spatial layout heuristics.
 */
object IconRoleClassifier {

    fun classifyIcon(node: AnalyzedNode, screenHeight: Int = 2400, screenWidth: Int = 1080): IconRole {
        val idLower = node.resourceId.lowercase(Locale.ROOT)
        val descLower = node.contentDescription.lowercase(Locale.ROOT)
        val textLower = node.text.lowercase(Locale.ROOT)
        val clsLower = node.className.lowercase(Locale.ROOT)
        val combined = "$idLower $descLower $textLower"

        if (combined.contains("send") || combined.contains("भेजें") || idLower == "send" || idLower.endsWith(":id/send") || idLower.contains("send_button")) {
            return IconRole.SEND_ICON
        }
        if (combined.contains("search") || combined.contains("खोज") || idLower.contains("menu_search") || idLower.contains("search_button") || idLower.contains("action_search")) {
            if (!node.isEditable) return IconRole.SEARCH_ICON
        }
        if (combined.contains("navigate up") || combined.contains("back") || combined.contains("पीछे") || idLower.contains("back_button") || idLower == "up") {
            return IconRole.BACK_ICON
        }
        if (combined.contains("more options") || combined.contains("menu") || combined.contains("drawer") || idLower.contains("overflow") || idLower.contains("hamburger")) {
            return IconRole.MENU_ICON
        }
        if (combined.contains("clear") || combined.contains("close") || combined.contains("dismiss") || idLower.contains("close_btn") || idLower.contains("clear_btn")) {
            return IconRole.CLOSE_ICON
        }
        if (combined.contains("attach") || combined.contains("camera") || combined.contains("gallery") || idLower.contains("attach")) {
            return IconRole.ATTACH_ICON
        }
        if (combined.contains("download") || combined.contains("save offline")) {
            return IconRole.DOWNLOAD_ICON
        }
        if (clsLower.contains("floatingactionbutton") || idLower.contains("fab")) {
            return IconRole.FAB_ACTION
        }

        val isIconLike = (clsLower.contains("imagebutton") || clsLower.contains("imageview") || (node.isClickable && node.text.isBlank()))
        if (isIconLike && node.bounds.width() in 24..220 && node.bounds.height() in 24..220) {
            if (node.bounds.centerY() > screenHeight * 0.72 && node.bounds.centerX() > screenWidth * 0.78) {
                return IconRole.FAB_ACTION
            }
            return IconRole.UNKNOWN_ICON
        }
        return IconRole.NONE
    }
}

/**
 * Fuses Accessibility, OCR, and Vision signals into a deduplicated list of [UnifiedUIElement]s.
 */
object MultiSignalFusionEngine {

    @Volatile
    var cachedScreenFingerprint: String = ""
        private set

    @Volatile
    private var cachedElements: List<UnifiedUIElement> = emptyList()

    fun computeScreenFingerprint(
        foregroundPackage: String,
        accessibilityNodes: List<AnalyzedNode>,
        ocrSignals: List<OcrTextSignal> = emptyList(),
        screenshotWidth: Int = 0,
        screenshotHeight: Int = 0,
        bitmapSignature: Int = 0
    ): String {
        val nodeSig = accessibilityNodes.take(30).joinToString("|") {
            "${it.resourceId}:${it.text}:${it.contentDescription}:${it.bounds.flattenToString()}"
        }
        val ocrSig = ocrSignals.take(15).joinToString("|") { "${it.text}:${it.bounds.flattenToString()}" }
        return "$foregroundPackage#${nodeSig.hashCode()}#${ocrSig.hashCode()}#${screenshotWidth}x${screenshotHeight}#$bitmapSignature"
    }

    fun computeBitmapQuickSignature(bitmap: android.graphics.Bitmap?): Int {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return 0
        var hash = bitmap.width * 31 + bitmap.height
        val stepX = (bitmap.width / 8).coerceAtLeast(1)
        val stepY = (bitmap.height / 8).coerceAtLeast(1)
        var y = stepY / 2
        while (y < bitmap.height) {
            var x = stepX / 2
            while (x < bitmap.width) {
                hash = hash * 31 + bitmap.getPixel(x, y)
                x += stepX
            }
            y += stepY
        }
        return hash
    }

    @Synchronized
    fun invalidateCacheIfFingerprintChanged(newFingerprint: String): Boolean {
        if (cachedScreenFingerprint.isNotEmpty() && cachedScreenFingerprint != newFingerprint) {
            cachedScreenFingerprint = newFingerprint
            cachedElements = emptyList()
            return true
        }
        cachedScreenFingerprint = newFingerprint
        return false
    }

    @Synchronized
    fun clearCache() {
        cachedScreenFingerprint = ""
        cachedElements = emptyList()
    }

    fun buildUnifiedElements(
        accessibilityNodes: List<AnalyzedNode>,
        foregroundPackage: String = "",
        ocrSignals: List<OcrTextSignal> = emptyList(),
        visionSignals: List<VisionElementSignal> = emptyList(),
        screenWidth: Int = 1080,
        screenHeight: Int = 2400,
        frameId: String = "frame_${System.currentTimeMillis()}",
        observationTimestampMs: Long = System.currentTimeMillis()
    ): List<UnifiedUIElement> {
        val fingerprint = computeScreenFingerprint(foregroundPackage, accessibilityNodes, ocrSignals)
        invalidateCacheIfFingerprintChanged(fingerprint)

        val unified = mutableListOf<UnifiedUIElement>()

        // 1. Convert Accessibility nodes first (highest structural fidelity)
        accessibilityNodes.forEachIndexed { idx, node ->
            val inputRole = InputFieldClassifier.classifyNode(node, accessibilityNodes, foregroundPackage, screenHeight)
            val iconRole = IconRoleClassifier.classifyIcon(node, screenHeight, screenWidth)
            val isFocusedSafe = try { node.node.isFocused } catch (_: Exception) { false }
            val isEnabledSafe = try { node.node.isEnabled } catch (_: Exception) { true }
            val isSelectedSafe = try { node.node.isSelected } catch (_: Exception) { false }
            val rect = Rect(node.bounds)

            unified.add(
                UnifiedUIElement(
                    elementId = "a11y_${idx}_${node.resourceId.substringAfterLast("/")}",
                    sourceSignals = setOf(SignalSource.ACCESSIBILITY),
                    packageName = foregroundPackage,
                    className = node.className,
                    viewIdResourceName = node.resourceId,
                    visibleText = PromptInjectionGuard.sanitizeScreenText(node.text),
                    contentDescription = PromptInjectionGuard.sanitizeScreenText(node.contentDescription),
                    inferredInputRole = inputRole,
                    iconRole = iconRole,
                    bounds = rect,
                    centerPoint = Pair(rect.centerX(), rect.centerY()),
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    isScrollable = node.isScrollable,
                    isEnabled = isEnabledSafe,
                    isFocused = isFocusedSafe,
                    isSelected = isSelectedSafe,
                    confidence = 0.92f,
                    analyzedNode = node,
                    semanticRole = when {
                        inputRole != InputFieldRole.NOT_INPUT -> inputRole.name
                        iconRole != IconRole.NONE -> iconRole.name
                        node.isClickable -> "BUTTON"
                        else -> "TEXT"
                    },
                    screenshotWidth = screenWidth,
                    screenshotHeight = screenHeight,
                    frameId = frameId,
                    observationTimestampMs = observationTimestampMs,
                    isFresh = true,
                    screenFingerprint = fingerprint
                )
            )
        }

        // 2. Fuse OCR signals: enrich matching Accessibility nodes or add standalone OCR elements
        ocrSignals.forEachIndexed { idx, ocr ->
            val sanitizedOcr = PromptInjectionGuard.sanitizeScreenText(ocr.text)
            if (sanitizedOcr.isBlank()) return@forEachIndexed

            val matchIndex = unified.indexOfFirst { existing ->
                computeIoU(existing.bounds, ocr.bounds) >= 0.35f ||
                    existing.bounds.contains(ocr.bounds.centerX(), ocr.bounds.centerY())
            }
            if (matchIndex >= 0) {
                val existing = unified[matchIndex]
                val mergedText = existing.visibleText.ifBlank { sanitizedOcr }
                unified[matchIndex] = existing.copy(
                    sourceSignals = existing.sourceSignals + SignalSource.OCR,
                    visibleText = mergedText,
                    confidence = min(0.99f, existing.confidence + 0.04f)
                )
            } else {
                val rect = Rect(ocr.bounds)
                unified.add(
                    UnifiedUIElement(
                        elementId = "ocr_$idx",
                        sourceSignals = setOf(SignalSource.OCR),
                        packageName = foregroundPackage,
                        className = "android.widget.TextView",
                        viewIdResourceName = "",
                        visibleText = sanitizedOcr,
                        contentDescription = "",
                        bounds = rect,
                        centerPoint = Pair(rect.centerX(), rect.centerY()),
                        isClickable = true,
                        isEditable = false,
                        isScrollable = false,
                        confidence = ocr.confidence,
                        semanticRole = "OCR_TEXT",
                        screenshotWidth = screenWidth,
                        screenshotHeight = screenHeight,
                        frameId = frameId,
                        observationTimestampMs = observationTimestampMs,
                        isFresh = true,
                        screenFingerprint = fingerprint
                    )
                )
            }
        }

        // 3. Fuse Vision signals (Florence-2 AUX_VISION_MODEL, SmolVLM2 LOCAL_GUI_VLM, or Gemini ONLINE_GEMINI_VISION)
        visionSignals.forEachIndexed { idx, vis ->
            val sanitizedLabel = PromptInjectionGuard.sanitizeScreenText(vis.label)
            if (sanitizedLabel.isBlank()) return@forEachIndexed

            val matchIndex = unified.indexOfFirst { existing ->
                computeIoU(existing.bounds, vis.bounds) >= 0.35f ||
                    existing.bounds.contains(vis.bounds.centerX(), vis.bounds.centerY()) ||
                    (existing.primaryLabel.isNotBlank() && existing.primaryLabel.equals(sanitizedLabel, ignoreCase = true))
            }
            val inferredVisionIcon = when {
                vis.roleHint.contains("search", ignoreCase = true) || sanitizedLabel.contains("search", ignoreCase = true) -> IconRole.SEARCH_ICON
                vis.roleHint.contains("send", ignoreCase = true) || sanitizedLabel.contains("send", ignoreCase = true) -> IconRole.SEND_ICON
                vis.roleHint.contains("back", ignoreCase = true) -> IconRole.BACK_ICON
                else -> IconRole.NONE
            }
            val inferredVisionInput = when {
                vis.roleHint.contains("search_bar", ignoreCase = true) -> InputFieldRole.SEARCH_FIELD
                vis.roleHint.contains("chat_composer", ignoreCase = true) -> InputFieldRole.CHAT_COMPOSER
                else -> InputFieldRole.NOT_INPUT
            }

            if (matchIndex >= 0) {
                val existing = unified[matchIndex]
                val iou = computeIoU(existing.bounds, vis.bounds)
                val dist = hypot(
                    (existing.bounds.centerX() - vis.bounds.centerX()).toDouble(),
                    (existing.bounds.centerY() - vis.bounds.centerY()).toDouble()
                ).toFloat()
                val updatedDisagreements = if (dist > 48f || iou < 0.50f) {
                    existing.disagreements + SignalDisagreementRecord(
                        primarySource = existing.sourceSignals.first(),
                        secondarySource = vis.sourceSignal,
                        centerDistancePx = dist,
                        iou = iou,
                        description = "Spatial disagreement between ${existing.sourceSignals.first()} and ${vis.sourceSignal} (dist=${dist.toInt()}px, iou=${"%.2f".format(Locale.ROOT, iou)})"
                    )
                } else {
                    existing.disagreements
                }

                // Keep SignalSource.VISION as well as specific vision source for full backward compatibility
                val mergedSignals = existing.sourceSignals + setOf(SignalSource.VISION, vis.sourceSignal)
                unified[matchIndex] = existing.copy(
                    sourceSignals = mergedSignals,
                    contentDescription = existing.contentDescription.ifBlank { sanitizedLabel },
                    iconRole = if (existing.iconRole == IconRole.NONE || existing.iconRole == IconRole.UNKNOWN_ICON) inferredVisionIcon else existing.iconRole,
                    inferredInputRole = if (existing.inferredInputRole == InputFieldRole.NOT_INPUT) inferredVisionInput else existing.inferredInputRole,
                    confidence = min(0.99f, existing.confidence + 0.05f),
                    sourceModelId = vis.sourceModelId.ifBlank { existing.sourceModelId },
                    sourceModelRevision = vis.sourceModelRevision.ifBlank { existing.sourceModelRevision },
                    disagreements = updatedDisagreements
                )
            } else {
                val rect = Rect(vis.bounds)
                val sigSet = if (vis.sourceSignal == SignalSource.AUX_VISION_MODEL) {
                    setOf(SignalSource.AUX_VISION_MODEL)
                } else {
                    setOf(SignalSource.VISION, vis.sourceSignal)
                }
                unified.add(
                    UnifiedUIElement(
                        elementId = "vision_$idx",
                        sourceSignals = sigSet,
                        packageName = foregroundPackage,
                        className = "android.view.View",
                        viewIdResourceName = "",
                        visibleText = sanitizedLabel,
                        contentDescription = sanitizedLabel,
                        inferredInputRole = inferredVisionInput,
                        iconRole = inferredVisionIcon,
                        bounds = rect,
                        centerPoint = Pair(rect.centerX(), rect.centerY()),
                        isClickable = true,
                        isEditable = inferredVisionInput != InputFieldRole.NOT_INPUT,
                        isScrollable = false,
                        confidence = vis.confidence,
                        semanticRole = vis.roleHint.ifBlank { "VISION_CONTROL" },
                        screenshotWidth = screenWidth,
                        screenshotHeight = screenHeight,
                        sourceModelId = vis.sourceModelId,
                        sourceModelRevision = vis.sourceModelRevision,
                        isConfidenceCalibrated = vis.isConfidenceCalibrated,
                        frameId = frameId,
                        observationTimestampMs = observationTimestampMs,
                        isFresh = true,
                        screenFingerprint = fingerprint
                    )
                )
            }
        }

        cachedElements = unified
        return unified
    }

    fun fuseSignals(
        accessibilityElements: List<com.example.services.KavyaAccessibilityService.InteractiveElement>,
        ocrSignals: List<OcrTextSignal> = emptyList(),
        visionAction: Any? = null,
        screenWidth: Int = 1080,
        screenHeight: Int = 2400,
        localGuiVlmCandidates: List<VisionElementSignal> = emptyList(),
        auxVisionCandidates: List<VisionElementSignal> = emptyList(),
        screenshotWidth: Int = screenWidth,
        screenshotHeight: Int = screenHeight,
        frameId: String = "frame_${System.currentTimeMillis()}",
        observationTimestampMs: Long = System.currentTimeMillis(),
        isObservationFresh: Boolean = true
    ): List<UnifiedUIElement> {
        val convertedA11y = accessibilityElements.mapIndexed { idx, el ->
            UnifiedUIElement(
                elementId = "a11y_${idx}_${el.viewIdResourceName.substringAfterLast("/")}",
                sourceSignals = setOf(SignalSource.ACCESSIBILITY),
                packageName = "",
                className = el.className,
                viewIdResourceName = el.viewIdResourceName,
                visibleText = PromptInjectionGuard.sanitizeScreenText(el.text),
                contentDescription = PromptInjectionGuard.sanitizeScreenText(el.contentDescription),
                bounds = Rect(el.bounds),
                centerPoint = Pair(el.bounds.centerX(), el.bounds.centerY()),
                isClickable = el.isClickable,
                isEditable = el.isEditable,
                isScrollable = el.isScrollable,
                isEnabled = el.isEnabled,
                confidence = 0.92f,
                screenshotWidth = screenshotWidth.coerceAtLeast(1),
                screenshotHeight = screenshotHeight.coerceAtLeast(1),
                frameId = frameId,
                observationTimestampMs = observationTimestampMs,
                isFresh = isObservationFresh
            )
        }
        val visionCombined = buildUnifiedElements(
            accessibilityNodes = emptyList(),
            foregroundPackage = "",
            ocrSignals = ocrSignals,
            visionSignals = auxVisionCandidates + localGuiVlmCandidates,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            frameId = frameId,
            observationTimestampMs = observationTimestampMs
        )
        return convertedA11y + visionCombined
    }

    fun resolveBestTarget(
        elements: List<UnifiedUIElement>,
        targetQuery: String
    ): UnifiedUIElement? {
        return resolveTargetWithoutFabrication(targetQuery, elements)
    }

    /**
     * Resolves a target UI element by query without ever inventing coordinates or falling back
     * to screen center (540, 1200). Returns null (`TARGET_NOT_FOUND`) if no verified candidate matches.
     */
    fun resolveTargetWithoutFabrication(
        targetQuery: String,
        elements: List<UnifiedUIElement>
    ): UnifiedUIElement? {
        val q = targetQuery.trim().lowercase(Locale.ROOT)
        if (q.isEmpty() || elements.isEmpty()) return null
        return elements
            .filter { it.isEnabled && !it.isAuxVisionOnly && it.confidence >= 0.55f }
            .firstOrNull { el ->
                el.visibleText.equals(q, ignoreCase = true) ||
                    el.contentDescription.equals(q, ignoreCase = true) ||
                    el.primaryLabel.lowercase(Locale.ROOT).contains(q)
            }
    }

    fun inferScreenType(elements: List<UnifiedUIElement>): ScreenSemanticType {
        val hasChatComposer = elements.any { it.inferredInputRole == InputFieldRole.CHAT_COMPOSER }
        if (hasChatComposer) return ScreenSemanticType.CHAT_THREAD

        val hasLogin = elements.any { it.inferredInputRole == InputFieldRole.LOGIN_FIELD }
        if (hasLogin) return ScreenSemanticType.LOGIN_SCREEN

        val hasAddressBar = elements.any { it.inferredInputRole == InputFieldRole.BROWSER_ADDRESS_BAR }
        if (hasAddressBar) return ScreenSemanticType.BROWSER_PAGE

        val focusedSearch = elements.any { it.inferredInputRole == InputFieldRole.SEARCH_FIELD && it.isFocused }
        if (focusedSearch) return ScreenSemanticType.SEARCH_ENTRY

        val hasSearchField = elements.any { it.inferredInputRole == InputFieldRole.SEARCH_FIELD }
        val scrollableCount = elements.count { it.isScrollable }
        if (hasSearchField && scrollableCount > 0 && elements.size > 8) {
            return ScreenSemanticType.SEARCH_RESULTS
        }

        return ScreenSemanticType.GENERAL_APP_SCREEN
    }

    fun computeIoU(a: Rect, b: Rect): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left).toFloat() * (bottom - top).toFloat()
        val areaA = (a.width() * a.height()).toFloat().coerceAtLeast(1f)
        val areaB = (b.width() * b.height()).toFloat().coerceAtLeast(1f)
        val union = areaA + areaB - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}

/**
 * V-Droid-Inspired Pre-Action Candidate Verifier & Post-Action Transition Verifier (Upgrade 2.5).
 */
object PreActionVerifier {

    enum class IntendedActionKind {
        SEARCH_INPUT,
        CHAT_MESSAGE_INPUT,
        SEND_MESSAGE,
        TAP_TARGET,
        CONSEQUENTIAL_ACTION
    }

    fun verifyCandidate(
        intentKind: IntendedActionKind,
        targetQuery: String,
        candidates: List<UnifiedUIElement>,
        isConsequentialAction: Boolean = false
    ): PreActionVerificationResult {
        if (candidates.isEmpty()) {
            return PreActionVerificationResult(
                approved = false,
                selectedCandidate = null,
                confidence = 0f,
                rejectionReason = "TARGET_NOT_FOUND: No candidate UI elements available on screen.",
                requiresReperception = true
            )
        }

        if (candidates.all { it.isAuxVisionOnly }) {
            return PreActionVerificationResult(
                approved = false,
                selectedCandidate = null,
                confidence = 0f,
                rejectionReason = "UNVERIFIED_AUX_VISION_TARGET: Florence-2 AUX_VISION_MODEL proposals alone are never authoritative for direct tap/type execution.",
                requiresReperception = true
            )
        }

        val scored = candidates.mapNotNull { candidate ->
            if (!candidate.isEnabled ||
                candidate.bounds.width() <= 0 ||
                candidate.bounds.height() <= 0 ||
                candidate.isAuxVisionOnly
            ) {
                return@mapNotNull null
            }

            when (intentKind) {
                IntendedActionKind.SEARCH_INPUT -> {
                    if (candidate.inferredInputRole in setOf(
                            InputFieldRole.CHAT_COMPOSER,
                            InputFieldRole.COMMENT_BOX,
                            InputFieldRole.LOGIN_FIELD
                        )
                    ) {
                        return@mapNotNull null
                    }
                    val roleScore = when (candidate.inferredInputRole) {
                        InputFieldRole.SEARCH_FIELD -> 0.96f
                        InputFieldRole.BROWSER_ADDRESS_BAR -> 0.92f
                        else -> if (candidate.iconRole == IconRole.SEARCH_ICON) 0.88f else 0f
                    }
                    if (roleScore <= 0f) null else Pair(candidate, min(0.99f, (roleScore + candidate.confidence) / 2f))
                }

                IntendedActionKind.CHAT_MESSAGE_INPUT -> {
                    if (candidate.inferredInputRole in setOf(
                            InputFieldRole.SEARCH_FIELD,
                            InputFieldRole.BROWSER_ADDRESS_BAR
                        )
                    ) {
                        return@mapNotNull null
                    }
                    val roleScore = when (candidate.inferredInputRole) {
                        InputFieldRole.CHAT_COMPOSER -> 0.96f
                        InputFieldRole.COMMENT_BOX -> 0.88f
                        InputFieldRole.GENERAL_EDITOR -> 0.72f
                        else -> 0f
                    }
                    if (roleScore <= 0f) null else Pair(candidate, min(0.99f, (roleScore + candidate.confidence) / 2f))
                }

                IntendedActionKind.SEND_MESSAGE -> {
                    if (candidate.iconRole == IconRole.SEND_ICON) {
                        Pair(candidate, 0.95f)
                    } else null
                }

                IntendedActionKind.TAP_TARGET, IntendedActionKind.CONSEQUENTIAL_ACTION -> {
                    val q = targetQuery.lowercase(Locale.ROOT).trim()
                    val label = "${candidate.visibleText} ${candidate.contentDescription} ${candidate.viewIdResourceName}".lowercase(Locale.ROOT)
                    if (q.isNotBlank() && !label.contains(q) && candidate.iconRole == IconRole.NONE) {
                        return@mapNotNull null
                    }
                    val textScore = when {
                        q.isNotBlank() && (candidate.visibleText.equals(q, ignoreCase = true) || candidate.contentDescription.equals(q, ignoreCase = true)) -> 0.97f
                        q.isNotBlank() && label.contains(q) -> 0.86f
                        else -> 0.60f
                    }
                    val rawCombined = if (candidate.confidence < 0.55f) {
                        candidate.confidence
                    } else {
                        min(0.99f, (textScore + candidate.confidence) / 2f)
                    }
                    Pair(candidate, rawCombined)
                }
            }
        }.sortedByDescending { it.second }

        val best = scored.firstOrNull()
            ?: return PreActionVerificationResult(
                approved = false,
                selectedCandidate = null,
                confidence = 0f,
                rejectionReason = "TARGET_NOT_FOUND: All candidates failed role or label verification for $intentKind.",
                requiresReperception = true
            )

        val (bestElement, bestScore) = best
        if (bestScore < 0.55f || bestElement.confidence < 0.55f) {
            return PreActionVerificationResult(
                approved = false,
                selectedCandidate = bestElement,
                confidence = bestScore,
                rejectionReason = "LOW_CONFIDENCE: Candidate confidence ($bestScore) is below threshold 0.55.",
                requiresReperception = true
            )
        }

        return PreActionVerificationResult(
            approved = !isConsequentialAction,
            selectedCandidate = bestElement,
            confidence = bestScore,
            requiresUserConfirmation = isConsequentialAction
        )
    }
}

object PostActionVerifier {

    fun verifyTransition(
        beforeScreen: FreshScreenState,
        afterScreen: FreshScreenState,
        expectedKind: PreActionVerifier.IntendedActionKind
    ): PostActionVerificationResult {
        val beforeFp = MultiSignalFusionEngine.computeScreenFingerprint(beforeScreen.foregroundPackage, beforeScreen.nodes)
        val afterFp = MultiSignalFusionEngine.computeScreenFingerprint(afterScreen.foregroundPackage, afterScreen.nodes)
        val cacheInvalidated = beforeFp != afterFp
        if (cacheInvalidated) {
            MultiSignalFusionEngine.invalidateCacheIfFingerprintChanged(afterFp)
        }

        val afterUnified = MultiSignalFusionEngine.buildUnifiedElements(afterScreen.nodes, afterScreen.foregroundPackage)
        val afterType = MultiSignalFusionEngine.inferScreenType(afterUnified)

        val packageChanged = beforeScreen.foregroundPackage != afterScreen.foregroundPackage
        val nodeSignatureBefore = beforeScreen.nodes.take(25).joinToString("|") { "${it.text}:${it.bounds}" }
        val nodeSignatureAfter = afterScreen.nodes.take(25).joinToString("|") { "${it.text}:${it.bounds}" }
        val hierarchyChanged = packageChanged || nodeSignatureBefore != nodeSignatureAfter

        val verified = when (expectedKind) {
            PreActionVerifier.IntendedActionKind.SEARCH_INPUT -> {
                afterUnified.any {
                    it.inferredInputRole == InputFieldRole.SEARCH_FIELD ||
                        it.inferredInputRole == InputFieldRole.BROWSER_ADDRESS_BAR ||
                        it.isFocused
                } || hierarchyChanged
            }
            PreActionVerifier.IntendedActionKind.CHAT_MESSAGE_INPUT,
            PreActionVerifier.IntendedActionKind.SEND_MESSAGE -> hierarchyChanged
            else -> hierarchyChanged
        }

        return PostActionVerificationResult(
            transitionVerified = verified,
            summary = if (verified) {
                "Screen transition verified (type=$afterType, pkg=${afterScreen.foregroundPackage})"
            } else {
                "Screen did not transition as expected after $expectedKind"
            },
            detectedScreenType = afterType,
            shouldRetryWithAlternate = !verified,
            beforeFingerprint = beforeFp,
            afterFingerprint = afterFp,
            cacheInvalidated = cacheInvalidated
        )
    }
}

/**
 * Short-Term UI Navigation Memory (Upgrade 2.6).
 * Tracks active app, screen semantic type, recent transitions, and resolves spatial/contextual UI references.
 */
object ShortTermNavigationMemory {

    data class ScreenMemorySnapshot(
        val packageName: String,
        val screenType: ScreenSemanticType,
        val elements: List<UnifiedUIElement>,
        val timestampMs: Long = System.currentTimeMillis()
    )

    private val history = ArrayDeque<ScreenMemorySnapshot>()
    private const val MAX_SNAPSHOTS = 8

    @Synchronized
    fun recordSnapshot(
        packageName: String,
        elements: List<UnifiedUIElement>
    ): ScreenMemorySnapshot {
        val screenType = MultiSignalFusionEngine.inferScreenType(elements)
        val snap = ScreenMemorySnapshot(
            packageName = packageName,
            screenType = screenType,
            elements = elements
        )
        history.addLast(snap)
        while (history.size > MAX_SNAPSHOTS) {
            history.removeFirst()
        }
        return snap
    }

    @Synchronized
    fun latestSnapshot(): ScreenMemorySnapshot? = history.lastOrNull()

    @Synchronized
    fun previousSnapshot(): ScreenMemorySnapshot? =
        if (history.size >= 2) history.elementAt(history.size - 2) else null

    @Synchronized
    fun resolveSpatialOrContextualReference(
        referenceQuery: String,
        currentElements: List<UnifiedUIElement> = latestSnapshot()?.elements ?: emptyList()
    ): UnifiedUIElement? {
        if (currentElements.isEmpty()) return null
        val q = referenceQuery.lowercase(Locale.ROOT).trim()
        val interactive = currentElements.filter { (it.isClickable || it.isEditable) && !it.isAuxVisionOnly }
        if (interactive.isEmpty()) return null

        return when {
            q.contains("ऊपर वाला") || q.contains("upar wala") || q.contains("top one") || q.contains("first") || q.contains("पहला") ->
                interactive.minByOrNull { it.bounds.top }
            q.contains("नीचे वाला") || q.contains("neeche wala") || q.contains("bottom one") || q.contains("last") || q.contains("आखिरी") ->
                interactive.maxByOrNull { it.bounds.bottom }
            q.contains("search bar") || q.contains("search box") ->
                interactive.firstOrNull { it.inferredInputRole == InputFieldRole.SEARCH_FIELD || it.iconRole == IconRole.SEARCH_ICON }
            q.contains("send button") || q.contains("भेजने वाला") ->
                interactive.firstOrNull { it.iconRole == IconRole.SEND_ICON }
            else -> null
        }
    }

    @Synchronized
    fun clear() {
        history.clear()
    }
}

/**
 * Protects the agent from Prompt Injection contained in untrusted on-screen text (Upgrade 2.8).
 */
object PromptInjectionGuard {

    fun isSuspiciousScreenInstruction(text: String): Boolean {
        return ScreenTextSanitizer.containsPromptInjectionAttempt(text)
    }

    fun sanitizeScreenText(text: String): String {
        return ScreenTextSanitizer.sanitizeForPrompt(text)
    }
}

object OnDeviceOcrExtractor {

    /**
     * Legacy synchronous signature: never fabricates pixel OCR from Accessibility elements when
     * no real pixel recognizer was run; returns empty list when bitmap is absent or use
     * `extractPixelOcrSignals(...)` for real ML Kit Latin + Devanagari extraction.
     */
    fun extractSignals(
        bitmap: android.graphics.Bitmap?,
        a11yElements: List<com.example.services.KavyaAccessibilityService.InteractiveElement> = emptyList()
    ): List<OcrTextSignal> {
        if (bitmap == null || bitmap.isRecycled) return emptyList()
        return kotlinx.coroutines.runBlocking {
            extractPixelOcrSignals(
                bitmap = bitmap,
                screenWidth = bitmap.width.coerceAtLeast(1),
                screenHeight = bitmap.height.coerceAtLeast(1)
            )
        }
    }

    /**
     * Runs real pixel-level OCR using ML Kit Text Recognition v2 (Latin + Devanagari/Hindi)
     * and maps recognized bounding boxes to physical display pixels via [PrecisionCoordinateMapper].
     * Never copies accessibility nodes as fake OCR results.
     */
    suspend fun extractPixelOcrSignals(
        bitmap: android.graphics.Bitmap?,
        screenWidth: Int,
        screenHeight: Int
    ): List<OcrTextSignal> {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return emptyList()
        }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val image = com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)
            val latinClient = try {
                com.google.mlkit.vision.text.TextRecognition.getClient(
                    com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
                )
            } catch (_: Throwable) {
                null
            }
            val devanagariClient = try {
                com.google.mlkit.vision.text.TextRecognition.getClient(
                    com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions.Builder().build()
                )
            } catch (_: Throwable) {
                null
            }

            val rawLines = mutableListOf<OcrTextSignal>()
            try {
                if (latinClient != null) {
                    val latinRes = awaitMlKitTask(latinClient.process(image))
                    latinRes?.textBlocks?.forEach { block ->
                        block.lines.forEach { line ->
                            val clean = PromptInjectionGuard.sanitizeScreenText(line.text).trim()
                            val box = line.boundingBox ?: block.boundingBox
                            if (clean.isNotBlank() && box != null) {
                                val mapped = PrecisionCoordinateMapper.mapScreenshotRectToDevice(
                                    screenshotRect = box,
                                    screenshotWidth = bitmap.width,
                                    screenshotHeight = bitmap.height,
                                    screenWidth = screenWidth.coerceAtLeast(1),
                                    screenHeight = screenHeight.coerceAtLeast(1)
                                )
                                rawLines.add(
                                    OcrTextSignal(
                                        text = clean,
                                        bounds = mapped,
                                        language = if (clean.any { it in '\u0900'..'\u097F' }) "hi" else "en",
                                        confidence = line.confidence.takeIf { it in 0f..1f } ?: 0.88f
                                    )
                                )
                            }
                        }
                    }
                }
                if (devanagariClient != null) {
                    val devaRes = awaitMlKitTask(devanagariClient.process(image))
                    devaRes?.textBlocks?.forEach { block ->
                        block.lines.forEach { line ->
                            val clean = PromptInjectionGuard.sanitizeScreenText(line.text).trim()
                            val box = line.boundingBox ?: block.boundingBox
                            val hasDevanagari = clean.any { it in '\u0900'..'\u097F' }
                            if (clean.isNotBlank() && box != null) {
                                val mapped = PrecisionCoordinateMapper.mapScreenshotRectToDevice(
                                    screenshotRect = box,
                                    screenshotWidth = bitmap.width,
                                    screenshotHeight = bitmap.height,
                                    screenWidth = screenWidth.coerceAtLeast(1),
                                    screenHeight = screenHeight.coerceAtLeast(1)
                                )
                                val isDuplicate = rawLines.any { existing ->
                                    MultiSignalFusionEngine.computeIoU(existing.bounds, mapped) >= 0.45f &&
                                        (!hasDevanagari || existing.text.equals(clean, ignoreCase = true))
                                }
                                if (!isDuplicate) {
                                    rawLines.add(
                                        OcrTextSignal(
                                            text = clean,
                                            bounds = mapped,
                                            language = if (hasDevanagari) "hi" else "en",
                                            confidence = line.confidence.takeIf { it in 0f..1f } ?: 0.90f
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                // ML Kit native model not yet downloaded or unavailable in JVM sandbox -> return whatever succeeded cleanly
            } finally {
                try { latinClient?.close() } catch (_: Throwable) {}
                try { devanagariClient?.close() } catch (_: Throwable) {}
            }
            rawLines
        }
    }

    private suspend fun <T> awaitMlKitTask(task: com.google.android.gms.tasks.Task<T>): T? =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            task.addOnSuccessListener { result ->
                if (cont.isActive) cont.resumeWith(Result.success(result))
            }.addOnFailureListener {
                if (cont.isActive) cont.resumeWith(Result.success(null))
            }.addOnCanceledListener {
                if (cont.isActive) cont.resumeWith(Result.success(null))
            }
        }
}
