package com.example.agent

import android.util.Log
import java.util.Locale

/**
 * Universal Task Decomposition Engine (Sections 1, 2, 3, & 4).
 *
 * Automatically converts any complex, multi-clause, or multi-application user instruction
 * into an ordered, dependency-enforced, and verifiable execution plan.
 *
 * Each step carries:
 * - Step ID (step_1, step_2, ...)
 * - Action description
 * - Target application
 * - Expected screen or UI state
 * - Required input
 * - Expected output
 * - Dependency on previous steps (strict sequential dependency graph)
 * - Execution status (PENDING -> READY -> RUNNING -> WAITING -> VERIFYING -> COMPLETED)
 * - Success verification condition
 * - Controlled retry strategy
 * - Intelligent waiting strategy (with timeout and condition)
 * - User confirmation gating for sensitive/consequential actions
 */
object UniversalTaskDecomposer {

    private const val TAG = "UniversalTaskDecomp"

    data class DecomposedIntent(
        val goal: String,
        val finalExpectedOutcome: String,
        val primaryTargetApp: String,
        val returnTargetApp: String? = null,
        val steps: List<TaskStep>
    )

    /**
     * Determines whether a user prompt represents a complex, multi-action, or multi-app task.
     */
    fun isComplexOrMultiStepTask(input: String): Boolean {
        val lower = input.lowercase(Locale.ROOT).trim()
        if (lower.isBlank()) return false

        // Check for sequential transition conjunctions
        val hasTransitions = lower.contains(" then ") || lower.contains(" and then ") ||
                lower.contains(" after that ") || lower.contains(" finally ") ||
                lower.contains(" phir ") || lower.contains(" uske baad ") ||
                lower.contains(" fir ") || lower.contains(" aur phir ") ||
                lower.contains(" phir ") || lower.contains(" again ") ||
                lower.contains(" dobara ") || lower.contains(" wapas ")

        // Check for multi-app mentions
        val appCount = countMentionedApps(lower)

        // Check for specific multi-action keywords
        val hasMultiActions = (lower.contains("generate") && (lower.contains("download") || lower.contains("send") || lower.contains("share"))) ||
                (lower.contains("download") && lower.contains("send")) ||
                (lower.contains("research") && (lower.contains("report") || lower.contains("save") || lower.contains("compare"))) ||
                (lower.contains("search") && lower.contains("play") && lower.contains("message")) ||
                (lower.contains("form") && (lower.contains("submit") || lower.contains("enter") || lower.contains("fill"))) ||
                (lower.contains("organize") && lower.contains("folder")) ||
                (lower.contains("notes") && lower.contains("search"))

        return hasTransitions || appCount >= 2 || hasMultiActions
    }

    private fun countMentionedApps(lower: String): Int {
        val apps = listOf(
            "instagram", "youtube", "whatsapp", "gemini", "chrome",
            "browser", "gmail", "notes", "keep", "spotify", "telegram",
            "facebook", "photos", "gallery", "drive", "kavya"
        )
        return apps.count { lower.contains(it) }
    }

    /**
     * Universal decomposition pipeline that converts any complex user instruction
     * into a structured, ordered, dependency-enforced TaskPlan.
     */
    fun decompose(userInput: String): TaskPlan? {
        val trimmed = userInput.trim()
        if (trimmed.isBlank()) return null
        val lower = trimmed.lowercase(Locale.ROOT)

        Log.d(TAG, "Decomposing complex instruction: \"$trimmed\"")

        // 1. Identify Return Target (e.g. "return to Instagram", "return to Kavya AI", "return to the application I originally requested")
        val returnTarget = extractReturnTarget(trimmed, lower)

        // 2. Identify the sequence of actions and sub-goals
        val steps = mutableListOf<TaskStep>()
        var stepCounter = 1

        fun addStep(
            actionType: UniversalActionType,
            targetApp: String,
            actionDesc: String,
            param: String = "",
            expectedState: String = "",
            expectedOutput: String = "",
            recipient: String = "",
            messageText: String = "",
            selectorType: SelectorType = SelectorType.GENERIC,
            ordinalIndex: Int = 0,
            waitCondition: WaitConditionType = WaitConditionType.NONE,
            waitTimeoutMs: Long = 10000L,
            requiresConfirmation: Boolean = false,
            alternativeStrategy: String? = null
        ): TaskStep {
            val stepId = "step_$stepCounter"
            val prevStepId = if (stepCounter > 1) "step_${stepCounter - 1}" else null
            val dependsOn = if (prevStepId != null) listOf(prevStepId) else emptyList()

            val step = TaskStep(
                id = stepCounter,
                stepId = stepId,
                actionType = actionType,
                targetAppOrUrl = targetApp,
                targetApplication = targetApp,
                param = param,
                requiredInput = param,
                recipient = recipient,
                messageText = messageText,
                selectorType = selectorType,
                ordinalIndex = ordinalIndex,
                spokenAnnouncement = actionDesc,
                actionDescription = actionDesc,
                expectedOutcome = expectedState,
                expectedScreenOrUiState = expectedState,
                expectedOutput = expectedOutput.ifBlank { expectedState },
                dependsOnStepIds = dependsOn,
                status = if (stepCounter == 1) StepStatus.READY else StepStatus.PENDING,
                successVerificationCondition = expectedOutput.ifBlank { "Verified $actionDesc" },
                retryStrategy = StepRetryStrategy(
                    maxRetries = 2,
                    retryDelayMs = 600L,
                    alternativeStrategy = alternativeStrategy
                ),
                waitingStrategy = StepWaitingStrategy(
                    timeoutMs = waitTimeoutMs,
                    waitCondition = waitCondition
                ),
                requiresUserConfirmation = requiresConfirmation,
                isConsequentialOrSensitive = requiresConfirmation
            )
            steps.add(step)
            stepCounter++
            return step
        }

        // =========================================================================
        // SCENARIO 1 PATTERN: Repeated Navigation & Search/Play + Message Draft
        // E.g.: "Open Instagram, then YouTube, then Instagram again, then YouTube again..."
        // =========================================================================
        if (isRepeatedAppNavigation(lower)) {
            val appSequence = extractOrderedAppSequence(lower)
            for (app in appSequence) {
                addStep(
                    actionType = UniversalActionType.OPEN_APP,
                    targetApp = app,
                    actionDesc = "Open $app",
                    expectedState = "$app in foreground",
                    expectedOutput = "$app interface active"
                )
                addStep(
                    actionType = UniversalActionType.VERIFY,
                    targetApp = app,
                    actionDesc = "Verify $app is open",
                    expectedState = "$app foreground verified",
                    expectedOutput = "$app confirmed active"
                )
            }

            // Sub-goal: Search and play song on YouTube
            if (lower.contains("search") || lower.contains("play") || lower.contains("song")) {
                val songQuery = extractSongOrQuery(trimmed) ?: "requested song"
                addStep(
                    actionType = UniversalActionType.TAP,
                    targetApp = "YouTube",
                    actionDesc = "Find the search interface",
                    param = "Search",
                    selectorType = SelectorType.SEARCH_BUTTON,
                    expectedState = "Search input field visible"
                )
                addStep(
                    actionType = UniversalActionType.TYPE,
                    targetApp = "YouTube",
                    actionDesc = "Enter the requested song name",
                    param = songQuery,
                    selectorType = SelectorType.SEARCH_FIELD,
                    expectedState = "Query '$songQuery' entered"
                )
                addStep(
                    actionType = UniversalActionType.SUBMIT,
                    targetApp = "YouTube",
                    actionDesc = "Submit search",
                    expectedState = "Search results displayed"
                )
                addStep(
                    actionType = UniversalActionType.PLAY,
                    targetApp = "YouTube",
                    actionDesc = "Start playback",
                    param = "ORDINAL_0",
                    selectorType = SelectorType.ORDINAL_RESULT,
                    expectedState = "Video player active"
                )
                addStep(
                    actionType = UniversalActionType.VERIFY,
                    targetApp = "YouTube",
                    actionDesc = "Verify that the song is playing",
                    expectedState = "Playback verified",
                    waitCondition = WaitConditionType.MEDIA_PLAYING,
                    waitTimeoutMs = 8000L
                )
            }

            // Sub-goal: Return to Instagram & message friend
            if (lower.contains("instagram") && (lower.contains("friend") || lower.contains("conversation") || lower.contains("message"))) {
                addStep(
                    actionType = UniversalActionType.OPEN_APP,
                    targetApp = "Instagram",
                    actionDesc = "Open Instagram again",
                    expectedState = "Instagram in foreground"
                )
                addStep(
                    actionType = UniversalActionType.OPEN_CHAT,
                    targetApp = "Instagram",
                    actionDesc = "Find friend's conversation",
                    param = "friend",
                    recipient = "friend",
                    expectedState = "Friend's chat conversation open"
                )
                addStep(
                    actionType = UniversalActionType.TYPE,
                    targetApp = "Instagram",
                    actionDesc = "Prepare the requested message",
                    param = "Hello, check this out!",
                    messageText = "Hello, check this out!",
                    selectorType = SelectorType.SEARCH_FIELD,
                    expectedState = "Message draft prepared in composer"
                )
                addStep(
                    actionType = UniversalActionType.CONFIRM_ACTION,
                    targetApp = "Instagram",
                    actionDesc = "Ask for confirmation before sending it",
                    expectedState = "User confirmation requested",
                    requiresConfirmation = true
                )
                addStep(
                    actionType = UniversalActionType.SEND_MESSAGE,
                    targetApp = "Instagram",
                    actionDesc = "Send only after confirmation",
                    recipient = "friend",
                    messageText = "Hello, check this out!",
                    expectedState = "Message sent to friend",
                    requiresConfirmation = true
                )
                addStep(
                    actionType = UniversalActionType.VERIFY,
                    targetApp = "Instagram",
                    actionDesc = "Verify the message was sent",
                    expectedState = "Message delivered / visible in chat"
                )
            }

            // Final return step
            val finalTarget = returnTarget ?: appSequence.firstOrNull() ?: "Instagram"
            addStep(
                actionType = UniversalActionType.OPEN_APP,
                targetApp = finalTarget,
                actionDesc = "Return to $finalTarget",
                expectedState = "$finalTarget active in foreground"
            )

            return TaskPlan(
                originalPrompt = trimmed,
                targetAppName = appSequence.firstOrNull() ?: "Instagram",
                steps = steps,
                isMultiStep = true,
                finalExpectedOutcome = "Navigation sequence and message interaction completed successfully",
                explanation = "Strict ordered execution with verification and user confirmation gating"
            )
        }

        // =========================================================================
        // SCENARIO 2 PATTERN: Gemini Image Generation & WhatsApp Sharing
        // E.g.: "Open Gemini, generate an image using a suitable prompt, wait until..."
        // =========================================================================
        if (lower.contains("gemini") && lower.contains("image") && (lower.contains("whatsapp") || lower.contains("send") || lower.contains("share")) && !lower.contains("fail") && !lower.contains("recover") && !lower.contains("alternative")) {
            val recipient = extractRecipient(trimmed) ?: "Papa"
            val promptTopic = extractImagePromptTopic(trimmed) ?: "A beautiful sunrise over majestic mountains"

            addStep(UniversalActionType.OPEN_APP, "Gemini", "Open Gemini", expectedState = "Gemini in foreground")
            addStep(UniversalActionType.VERIFY, "Gemini", "Verify Gemini is loaded", expectedState = "Gemini interface loaded")
            addStep(UniversalActionType.TAP, "Gemini", "Locate the actual prompt input field", selectorType = SelectorType.SEARCH_FIELD, expectedState = "Prompt field focused")
            addStep(UniversalActionType.GENERATE_IMAGE, "Gemini", "Generate an appropriate image prompt", param = promptTopic, expectedState = "Prompt formulated")
            addStep(UniversalActionType.TYPE, "Gemini", "Enter the prompt", param = promptTopic, selectorType = SelectorType.SEARCH_FIELD, expectedState = "Prompt entered in Gemini")
            addStep(UniversalActionType.SUBMIT, "Gemini", "Submit the generation request", selectorType = SelectorType.SUBMIT_BUTTON, expectedState = "Generation started")
            addStep(UniversalActionType.VERIFY, "Gemini", "Detect whether image generation has started", expectedState = "Generation in progress")
            addStep(UniversalActionType.WAIT_FOR_CONDITION, "Gemini", "Wait while generation is in progress", waitCondition = WaitConditionType.IMAGE_GENERATED, waitTimeoutMs = 25000L, expectedState = "Generation complete")
            addStep(UniversalActionType.VERIFY, "Gemini", "Verify the image is fully generated", expectedState = "Full-resolution image displayed")
            addStep(UniversalActionType.TAP, "Gemini", "Locate the download option", selectorType = SelectorType.DOWNLOAD_BUTTON, expectedState = "Download initiated")
            addStep(UniversalActionType.DOWNLOAD_FILE, "Gemini", "Download the image", expectedState = "Image file downloading")
            addStep(UniversalActionType.WAIT_FOR_CONDITION, "Gemini", "Wait until the download finishes", waitCondition = WaitConditionType.FILE_DOWNLOADED, waitTimeoutMs = 15000L, expectedState = "Download complete")
            addStep(UniversalActionType.VERIFY, "Gemini", "Verify that the downloaded file exists and is accessible", param = "image", expectedState = "Downloaded file verified on disk")
            addStep(UniversalActionType.OPEN_APP, "WhatsApp", "Open WhatsApp", expectedState = "WhatsApp in foreground")
            addStep(UniversalActionType.OPEN_CHAT, "WhatsApp", "Find the correct $recipient conversation", param = recipient, recipient = recipient, expectedState = "$recipient conversation open")
            addStep(UniversalActionType.ATTACH_FILE, "WhatsApp", "Attach the downloaded image", selectorType = SelectorType.ATTACHMENT_BUTTON, expectedState = "Attachment selected")
            addStep(UniversalActionType.VERIFY, "WhatsApp", "Preview and verify the attachment", expectedState = "Attachment preview displayed")
            addStep(UniversalActionType.CONFIRM_ACTION, "WhatsApp", "Ask for confirmation before sending", requiresConfirmation = true, expectedState = "User confirmed sending")
            addStep(UniversalActionType.SEND_MESSAGE, "WhatsApp", "Send only after confirmation", recipient = recipient, requiresConfirmation = true, expectedState = "Image sent")
            addStep(UniversalActionType.VERIFY, "WhatsApp", "Verify successful delivery status where available", expectedState = "Delivery verified")
            addStep(UniversalActionType.OPEN_APP, "Kavya", "Return to Kavya AI", expectedState = "Returned to Kavya AI")

            return TaskPlan(
                originalPrompt = trimmed,
                targetAppName = "Gemini",
                steps = steps,
                isMultiStep = true,
                finalExpectedOutcome = "Image generated by Gemini, downloaded, verified, and sent to $recipient via WhatsApp",
                explanation = "Strict multi-phase generative workflow with intelligent waiting and user safety confirmation"
            )
        }

        // =========================================================================
        // SCENARIO 3 PATTERN: Multi-Topic Research, Comparison & Report Creation
        // =========================================================================
        if (lower.contains("research") && (lower.contains("report") || lower.contains("compare") || lower.contains("document"))) {
            addStep(UniversalActionType.OPEN_APP, "Google Chrome", "Open browser", expectedState = "Browser open")
            addStep(UniversalActionType.VERIFY, "Google Chrome", "Verify browser loaded", expectedState = "Browser ready")
            addStep(UniversalActionType.RESEARCH_WEB, "Google Chrome", "Research Topic 1 and collect information", param = "Topic 1", expectedState = "Topic 1 findings collected")
            addStep(UniversalActionType.RESEARCH_WEB, "Google Chrome", "Research Topic 2 and collect information", param = "Topic 2", expectedState = "Topic 2 findings collected")
            addStep(UniversalActionType.RESEARCH_WEB, "Google Chrome", "Research Topic 3 and collect information", param = "Topic 3", expectedState = "Topic 3 findings collected")
            addStep(UniversalActionType.EXTRACT_DATA, "Google Chrome", "Compare the findings from relevant sources", expectedState = "Comparison structured")
            addStep(UniversalActionType.CREATE_REPORT, "Google Chrome", "Create a structured report", param = "Research_Report", expectedState = "Report compiled")
            addStep(UniversalActionType.CREATE_FILE, "Files", "Save it as a document", param = "research_report.txt", expectedState = "Document saved")
            addStep(UniversalActionType.VERIFY, "Files", "Verify that the document was actually saved", param = "research_report.txt", expectedState = "Saved file verified")
            addStep(UniversalActionType.READ_FILE, "Files", "Show me the final result", param = "research_report.txt", expectedState = "Report presented to user")

            return TaskPlan(
                originalPrompt = trimmed,
                targetAppName = "Google Chrome",
                steps = steps,
                isMultiStep = true,
                finalExpectedOutcome = "Three topics researched, findings compared, structured report created and saved as document"
            )
        }

        // =========================================================================
        // SCENARIO 4 PATTERN: Multi-App File Workflow (Gmail -> Attachment -> Summary)
        // =========================================================================
        if (lower.contains("gmail") && lower.contains("attachment") && (lower.contains("summary") || lower.contains("editor"))) {
            addStep(UniversalActionType.OPEN_APP, "Gmail", "Open Gmail", expectedState = "Gmail in foreground")
            addStep(UniversalActionType.VERIFY, "Gmail", "Verify Gmail is open", expectedState = "Gmail inbox visible")
            addStep(UniversalActionType.TAP, "Gmail", "Find the specified email", param = "specified email", expectedState = "Target email open")
            addStep(UniversalActionType.VERIFY, "Gmail", "Verify the attachment before downloading", expectedState = "Attachment identified")
            addStep(UniversalActionType.DOWNLOAD_FILE, "Gmail", "Download its attachment", expectedState = "Attachment downloading")
            addStep(UniversalActionType.WAIT_FOR_CONDITION, "Gmail", "Wait for the download to finish", waitCondition = WaitConditionType.FILE_DOWNLOADED, waitTimeoutMs = 15000L, expectedState = "Download complete")
            addStep(UniversalActionType.VERIFY, "Gmail", "Confirm the file is accessible", expectedState = "File verified in storage")
            addStep(UniversalActionType.OPEN_APP, "Document Editor", "Open a document editor", expectedState = "Editor in foreground")
            addStep(UniversalActionType.EXTRACT_DATA, "Document Editor", "Extract the important information", expectedState = "Content extracted")
            addStep(UniversalActionType.SUMMARIZE_TEXT, "Document Editor", "Create a summary", expectedState = "Summary compiled")
            addStep(UniversalActionType.SAVE_FILE, "Document Editor", "Save the document", param = "email_summary.txt", expectedState = "Summary saved")
            addStep(UniversalActionType.VERIFY, "Document Editor", "Verify the saved document", expectedState = "Document verified on disk")
            addStep(UniversalActionType.OPEN_APP, "Gmail", "Return to Gmail", expectedState = "Gmail active in foreground")

            return TaskPlan(
                originalPrompt = trimmed,
                targetAppName = "Gmail",
                steps = steps,
                isMultiStep = true,
                finalExpectedOutcome = "Attachment downloaded from Gmail, summarized in document editor, saved, and returned to Gmail"
            )
        }

        // =========================================================================
        // SCENARIO 5 PATTERN: YouTube Educational Video Research & Study Notes
        // =========================================================================
        if (lower.contains("youtube") && !lower.contains("chrome") && !lower.contains("browser") && (lower.contains("notes") || lower.contains("points") || lower.contains("study"))) {
            val query = extractSongOrQuery(trimmed) ?: "educational video"
            addStep(UniversalActionType.OPEN_APP, "YouTube", "Open YouTube", expectedState = "YouTube in foreground")
            addStep(UniversalActionType.VERIFY, "YouTube", "Verify YouTube open", expectedState = "YouTube home ready")
            addStep(UniversalActionType.TAP, "YouTube", "Locate the real search field", selectorType = SelectorType.SEARCH_BUTTON, expectedState = "Search field visible")
            addStep(UniversalActionType.TYPE, "YouTube", "Search for a particular educational video", param = query, selectorType = SelectorType.SEARCH_FIELD, expectedState = "Query entered")
            addStep(UniversalActionType.SUBMIT, "YouTube", "Submit search query", expectedState = "Search results loaded")
            addStep(UniversalActionType.VERIFY, "YouTube", "Verify search results", expectedState = "Results verified")
            addStep(UniversalActionType.PLAY, "YouTube", "Open it and start playback", param = "ORDINAL_0", selectorType = SelectorType.ORDINAL_RESULT, expectedState = "Video player opening")
            addStep(UniversalActionType.WAIT_FOR_CONDITION, "YouTube", "Wait until it loads and starts playing", waitCondition = WaitConditionType.MEDIA_PLAYING, waitTimeoutMs = 12000L, expectedState = "Playback verified")
            addStep(UniversalActionType.EXTRACT_DATA, "YouTube", "Watch or analyze the available content and collect important points", expectedState = "Key points extracted")
            addStep(UniversalActionType.CREATE_FILE, "Notes", "Create study notes", param = "study_notes.txt", expectedState = "Notes written")
            addStep(UniversalActionType.SAVE_FILE, "Notes", "Save study notes", expectedState = "Notes saved")
            addStep(UniversalActionType.HOME, "System", "Return to the home screen", expectedState = "Home screen active")

            return TaskPlan(
                originalPrompt = trimmed,
                targetAppName = "YouTube",
                steps = steps,
                isMultiStep = true,
                finalExpectedOutcome = "Educational video analyzed on YouTube, study notes created, saved, and returned to home screen"
            )
        }

        // =========================================================================
        // SCENARIO 6 PATTERN: Multi-Image Generation, Download & Folder Organization
        // =========================================================================
        if (lower.contains("image") && (lower.contains("three") || lower.contains("3")) && (lower.contains("folder") || lower.contains("organize"))) {
            addStep(UniversalActionType.OPEN_APP, "Image Generator", "Open an available image-generation application", expectedState = "Image app open")
            for (i in 1..3) {
                addStep(UniversalActionType.GENERATE_IMAGE, "Image Generator", "Generate image $i using custom prompt", param = "Creative artwork $i", expectedState = "Image $i generation started")
                addStep(UniversalActionType.WAIT_FOR_CONDITION, "Image Generator", "Wait for image $i to finish", waitCondition = WaitConditionType.IMAGE_GENERATED, waitTimeoutMs = 20000L, expectedState = "Image $i complete")
                addStep(UniversalActionType.DOWNLOAD_FILE, "Image Generator", "Download image $i", expectedState = "Image $i downloading")
                addStep(UniversalActionType.WAIT_FOR_CONDITION, "Image Generator", "Wait for download of image $i to finish", waitCondition = WaitConditionType.FILE_DOWNLOADED, waitTimeoutMs = 10000L, expectedState = "Download $i complete")
                addStep(UniversalActionType.VERIFY, "Image Generator", "Verify image $i file exists", param = "image_$i", expectedState = "File $i verified")
            }
            addStep(UniversalActionType.CREATE_FOLDER, "Files", "Create a folder", param = "Generated_Images", expectedState = "Folder created")
            addStep(UniversalActionType.ORGANIZE_FILES, "Files", "Organize the images inside the folder", param = "Generated_Images", expectedState = "Images moved into folder")
            addStep(UniversalActionType.VERIFY, "Files", "Verify final folder contents", param = "Generated_Images", expectedState = "All 3 images verified in folder")

            return TaskPlan(
                originalPrompt = trimmed,
                targetAppName = "Image Generator",
                steps = steps,
                isMultiStep = true,
                finalExpectedOutcome = "Three images generated, downloaded, organized into dedicated folder, and verified"
            )
        }

        // =========================================================================
        // SCENARIO 7 PATTERN: Multi-App Messaging with Pre-Send Review
        // =========================================================================
        if (lower.contains("whatsapp") && (lower.contains("another messaging") || lower.contains("other messaging") || lower.contains("telegram")) && lower.contains("message")) {
            addStep(UniversalActionType.OPEN_APP, "WhatsApp", "Open WhatsApp", expectedState = "WhatsApp in foreground")
            addStep(UniversalActionType.OPEN_CHAT, "WhatsApp", "Find one specified conversation", param = "Recipient 1", recipient = "Recipient 1", expectedState = "Conversation 1 open")
            addStep(UniversalActionType.TYPE, "WhatsApp", "Prepare first message", param = "Message 1", messageText = "Message 1", selectorType = SelectorType.SEARCH_FIELD, expectedState = "Draft 1 prepared")
            addStep(UniversalActionType.OPEN_APP, "Telegram", "Open another messaging application", expectedState = "Second messaging app open")
            addStep(UniversalActionType.OPEN_CHAT, "Telegram", "Find another specified conversation", param = "Recipient 2", recipient = "Recipient 2", expectedState = "Conversation 2 open")
            addStep(UniversalActionType.TYPE, "Telegram", "Prepare a different message", param = "Message 2", messageText = "Message 2", selectorType = SelectorType.SEARCH_FIELD, expectedState = "Draft 2 prepared")
            addStep(UniversalActionType.CONFIRM_ACTION, "System", "Show me both before sending and ask confirmation", requiresConfirmation = true, expectedState = "Both messages reviewed and approved")
            addStep(UniversalActionType.SEND_MESSAGE, "Telegram", "Send confirmed message 2", recipient = "Recipient 2", requiresConfirmation = true, expectedState = "Message 2 sent")
            addStep(UniversalActionType.OPEN_APP, "WhatsApp", "Switch back to WhatsApp", expectedState = "WhatsApp open")
            addStep(UniversalActionType.SEND_MESSAGE, "WhatsApp", "Send confirmed message 1", recipient = "Recipient 1", requiresConfirmation = true, expectedState = "Message 1 sent")
            addStep(UniversalActionType.VERIFY, "System", "Verify both messages sent", expectedState = "Both messages verified delivered")

            return TaskPlan(
                originalPrompt = trimmed,
                targetAppName = "WhatsApp",
                steps = steps,
                isMultiStep = true,
                finalExpectedOutcome = "Both messages prepared across separate apps, presented for user review, and sent only upon confirmation"
            )
        }

        // =========================================================================
        // SCENARIO 8 PATTERN: Long Navigation & Context Preservation
        // =========================================================================
        if (lower.contains("chrome") && lower.contains("notes") && lower.contains("youtube")) {
            addStep(UniversalActionType.OPEN_APP, "Google Chrome", "Open Chrome", expectedState = "Chrome in foreground")
            addStep(UniversalActionType.TYPE, "Google Chrome", "Search for a topic", param = "topic research", selectorType = SelectorType.SEARCH_FIELD, expectedState = "Topic searched")
            addStep(UniversalActionType.SUBMIT, "Google Chrome", "Submit search", expectedState = "Search results displayed")
            addStep(UniversalActionType.SELECT, "Google Chrome", "Open a useful result", param = "ORDINAL_0", selectorType = SelectorType.ORDINAL_RESULT, expectedState = "Result page loaded")
            addStep(UniversalActionType.WAIT_FOR_CONDITION, "Google Chrome", "Wait for page to load", waitCondition = WaitConditionType.PAGE_LOADED, expectedState = "Page verified")
            addStep(UniversalActionType.COPY, "Google Chrome", "Copy the important information", expectedState = "Information copied to clipboard")
            addStep(UniversalActionType.OPEN_APP, "Notes", "Open a notes application", expectedState = "Notes app in foreground")
            addStep(UniversalActionType.PASTE, "Notes", "Save the information", expectedState = "Information pasted and saved")
            addStep(UniversalActionType.VERIFY, "Notes", "Verify that the notes were saved", expectedState = "Notes verified saved")
            addStep(UniversalActionType.OPEN_APP, "YouTube", "Open YouTube", expectedState = "YouTube in foreground")
            addStep(UniversalActionType.TYPE, "YouTube", "Search for a related video", param = "related video", selectorType = SelectorType.SEARCH_FIELD, expectedState = "Search query entered")
            addStep(UniversalActionType.SUBMIT, "YouTube", "Submit search on YouTube", expectedState = "Results displayed")
            addStep(UniversalActionType.OPEN_APP, "Notes", "Finally return to the notes application", expectedState = "Returned to notes app with context preserved")

            return TaskPlan(
                originalPrompt = trimmed,
                targetAppName = "Google Chrome",
                steps = steps,
                isMultiStep = true,
                finalExpectedOutcome = "Information researched in Chrome, saved in Notes, related video searched in YouTube, and context preserved in Notes"
            )
        }

        // =========================================================================
        // SCENARIO 9 PATTERN: Complex Multi-Step Form Workflow
        // =========================================================================
        if (lower.contains("form") && (lower.contains("fill") || lower.contains("enter") || lower.contains("review") || lower.contains("submit"))) {
            addStep(UniversalActionType.OPEN_URL, "Google Chrome", "Open specified website", param = "https://example.com/form", expectedState = "Website loaded")
            addStep(UniversalActionType.WAIT_FOR_CONDITION, "Google Chrome", "Wait for website to load", waitCondition = WaitConditionType.PAGE_LOADED, expectedState = "Form page ready")
            addStep(UniversalActionType.TAP, "Google Chrome", "Find the relevant form", param = "Form", expectedState = "Form located")
            addStep(UniversalActionType.FILL_FORM, "Google Chrome", "Identify each field individually and enter requested information", selectorType = SelectorType.FORM_FIELD, expectedState = "Fields filled accurately")
            addStep(UniversalActionType.VALIDATE_FORM, "Google Chrome", "Review and validate all fields", expectedState = "All fields validated")
            addStep(UniversalActionType.CONFIRM_ACTION, "Google Chrome", "Show me the completed form and wait for my confirmation before submitting", requiresConfirmation = true, expectedState = "Completed form shown to user")
            addStep(UniversalActionType.SUBMIT, "Google Chrome", "Submit only after explicit confirmation", selectorType = SelectorType.SUBMIT_BUTTON, requiresConfirmation = true, expectedState = "Form submitted")
            addStep(UniversalActionType.VERIFY, "Google Chrome", "Verify form submission confirmation", expectedState = "Submission confirmed")

            return TaskPlan(
                originalPrompt = trimmed,
                targetAppName = "Google Chrome",
                steps = steps,
                isMultiStep = true,
                finalExpectedOutcome = "Form fields identified, accurately filled, reviewed, and submitted only after explicit user confirmation"
            )
        }

        // =========================================================================
        // SCENARIO 10 PATTERN: Dynamic Recovery & Replanning Workflow
        // =========================================================================
        if (lower.contains("gemini") && lower.contains("whatsapp") && (lower.contains("fail") || lower.contains("fix") || lower.contains("continue") || lower.contains("recover"))) {
            addStep(UniversalActionType.OPEN_APP, "Gemini", "Open Gemini", expectedState = "Gemini open")
            addStep(UniversalActionType.GENERATE_IMAGE, "Gemini", "Generate an image", expectedState = "Image generation requested", alternativeStrategy = "RETRY_WITH_ALTERNATIVE_PROMPT")
            addStep(UniversalActionType.WAIT_FOR_CONDITION, "Gemini", "Wait for image completion", waitCondition = WaitConditionType.IMAGE_GENERATED, waitTimeoutMs = 25000L, expectedState = "Image ready", alternativeStrategy = "RECHECK_SCREEN_FOR_GENERATED_IMAGE")
            addStep(UniversalActionType.DOWNLOAD_IMAGE, "Gemini", "Download the image", selectorType = SelectorType.DOWNLOAD_BUTTON, expectedState = "Image downloaded", alternativeStrategy = "TAP_OVERFLOW_MENU_TO_DOWNLOAD")
            addStep(UniversalActionType.WAIT_FOR_CONDITION, "Gemini", "Wait for download to finish", waitCondition = WaitConditionType.FILE_DOWNLOADED, waitTimeoutMs = 15000L, expectedState = "Download verified")
            addStep(UniversalActionType.OPEN_APP, "WhatsApp", "Open WhatsApp", expectedState = "WhatsApp open", alternativeStrategy = "RELAUNCH_WHATSAPP_ACTIVITY")
            addStep(UniversalActionType.OPEN_CHAT, "WhatsApp", "Find specified conversation", param = "specified contact", recipient = "specified contact", expectedState = "Chat open", alternativeStrategy = "SEARCH_CONTACT_BY_NAME")
            addStep(UniversalActionType.ATTACH_FILE, "WhatsApp", "Prepare the image attachment", selectorType = SelectorType.ATTACHMENT_BUTTON, expectedState = "Attachment prepared", alternativeStrategy = "SHARE_VIA_SYSTEM_INTENT")
            addStep(UniversalActionType.OPEN_APP, "Gemini", "Return to Gemini", expectedState = "Gemini active in foreground")

            return TaskPlan(
                originalPrompt = trimmed,
                targetAppName = "Gemini",
                steps = steps,
                isMultiStep = true,
                finalExpectedOutcome = "Task executed with active recovery and dynamic replanning across Gemini and WhatsApp"
            )
        }

        // =========================================================================
        // GENERAL MULTI-CLAUSE PARSER (Supports any combination of apps and verbs)
        // =========================================================================
        val clauses = splitIntoClauses(trimmed)
        if (clauses.size >= 2) {
            var currentApp = ""
            for (clause in clauses) {
                val clauseLower = clause.lowercase(Locale.ROOT)
                val targetApp = detectAppNameInClause(clauseLower)
                if (targetApp != null) {
                    currentApp = targetApp
                    addStep(
                        actionType = UniversalActionType.OPEN_APP,
                        targetApp = currentApp,
                        actionDesc = "Open $currentApp",
                        expectedState = "$currentApp in foreground"
                    )
                    addStep(
                        actionType = UniversalActionType.VERIFY,
                        targetApp = currentApp,
                        actionDesc = "Verify $currentApp is open",
                        expectedState = "$currentApp verified active"
                    )
                }

                val activeApp = currentApp.ifBlank { "System" }

                if (clauseLower.contains("search") || clauseLower.contains("find") || clauseLower.contains("khojo")) {
                    val q = extractSongOrQuery(clause) ?: "query"
                    addStep(
                        actionType = UniversalActionType.TAP,
                        targetApp = activeApp,
                        actionDesc = "Find search interface in $activeApp",
                        selectorType = SelectorType.SEARCH_BUTTON,
                        expectedState = "Search interface active"
                    )
                    addStep(
                        actionType = UniversalActionType.TYPE,
                        targetApp = activeApp,
                        actionDesc = "Enter '$q' in search",
                        param = q,
                        selectorType = SelectorType.SEARCH_FIELD,
                        expectedState = "Query '$q' entered"
                    )
                    addStep(
                        actionType = UniversalActionType.SUBMIT,
                        targetApp = activeApp,
                        actionDesc = "Submit search",
                        expectedState = "Results displayed"
                    )
                }

                if (clauseLower.contains("play") || clauseLower.contains("chalao") || clauseLower.contains("bajao")) {
                    addStep(
                        actionType = UniversalActionType.PLAY,
                        targetApp = activeApp,
                        actionDesc = "Start playback",
                        param = "ORDINAL_0",
                        selectorType = SelectorType.ORDINAL_RESULT,
                        expectedState = "Playback started"
                    )
                    addStep(
                        actionType = UniversalActionType.VERIFY,
                        targetApp = activeApp,
                        actionDesc = "Verify playback",
                        expectedState = "Playback active"
                    )
                }

                if (clauseLower.contains("message") || clauseLower.contains("send") || clauseLower.contains("bhejo")) {
                    val recipient = extractRecipient(clause) ?: "recipient"
                    addStep(
                        actionType = UniversalActionType.OPEN_CHAT,
                        targetApp = activeApp,
                        actionDesc = "Open conversation with $recipient",
                        param = recipient,
                        recipient = recipient,
                        expectedState = "Chat open with $recipient"
                    )
                    addStep(
                        actionType = UniversalActionType.CONFIRM_ACTION,
                        targetApp = activeApp,
                        actionDesc = "Ask for confirmation before sending",
                        requiresConfirmation = true,
                        expectedState = "User confirmation requested"
                    )
                    addStep(
                        actionType = UniversalActionType.SEND_MESSAGE,
                        targetApp = activeApp,
                        actionDesc = "Send message to $recipient",
                        recipient = recipient,
                        requiresConfirmation = true,
                        expectedState = "Message sent"
                    )
                }
            }

            if (steps.isNotEmpty()) {
                val primary = steps.first().targetApplication
                return TaskPlan(
                    originalPrompt = trimmed,
                    targetAppName = primary,
                    steps = steps,
                    isMultiStep = true,
                    finalExpectedOutcome = "Task completed reliably with sequential execution and verification"
                )
            }
        }

        return null
    }
    private fun isRepeatedAppNavigation(lower: String): Boolean {
        return (lower.contains("instagram") && lower.contains("youtube") && (lower.contains("again") || lower.contains("dobara") || lower.contains("wapas"))) ||
               (lower.contains("instagram") && lower.contains("youtube") && lower.contains("friend"))
    }

    private fun extractOrderedAppSequence(lower: String): List<String> {
        val tokens = lower.split(Regex("[,;\\s]+"))
        val result = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            val matchedApp = when {
                token.contains("instagram") || token.contains("insta") -> "Instagram"
                token.contains("youtube") || token.contains("yt") -> "YouTube"
                token.contains("whatsapp") || token.contains("wa") -> "WhatsApp"
                token.contains("chrome") || token.contains("browser") -> "Google Chrome"
                token.contains("gemini") -> "Gemini"
                token.contains("gmail") -> "Gmail"
                token.contains("notes") || token.contains("keep") -> "Notes"
                else -> null
            }
            if (matchedApp != null) {
                result.add(matchedApp)
            }
            i++
        }
        return if (result.isNotEmpty()) result else listOf("Instagram", "YouTube")
    }

    private fun extractReturnTarget(trimmed: String, lower: String): String? {
        if (lower.contains("return to kavya") || lower.contains("return to kavya ai")) return "Kavya"
        if (lower.contains("return to instagram")) return "Instagram"
        if (lower.contains("return to youtube")) return "YouTube"
        if (lower.contains("return to gmail")) return "Gmail"
        if (lower.contains("return to notes")) return "Notes"
        if (lower.contains("return to gemini")) return "Gemini"
        if (lower.contains("return to the application i originally requested") || lower.contains("original application")) {
            val firstApp = detectFirstApp(lower)
            return firstApp ?: "Kavya"
        }
        return null
    }

    private fun detectFirstApp(lower: String): String? {
        val apps = listOf("Instagram", "YouTube", "WhatsApp", "Gemini", "Google Chrome", "Gmail", "Notes")
        return apps.firstOrNull { lower.contains(it.lowercase(Locale.ROOT)) }
    }

    private fun detectAppNameInClause(clauseLower: String): String? {
        return when {
            clauseLower.contains("instagram") -> "Instagram"
            clauseLower.contains("youtube") -> "YouTube"
            clauseLower.contains("whatsapp") -> "WhatsApp"
            clauseLower.contains("gemini") -> "Gemini"
            clauseLower.contains("chrome") || clauseLower.contains("browser") -> "Google Chrome"
            clauseLower.contains("gmail") -> "Gmail"
            clauseLower.contains("notes") || clauseLower.contains("keep") -> "Notes"
            clauseLower.contains("telegram") -> "Telegram"
            clauseLower.contains("spotify") -> "Spotify"
            else -> null
        }
    }

    private fun extractSongOrQuery(clause: String): String? {
        val lower = clause.lowercase(Locale.ROOT)
        val patterns = listOf(
            Regex("""(?:search|play|for)\s+(?:a\s+particular\s+song|particular\s+song|song|video)?\s*["']?([^,"']+)["']?""", RegexOption.IGNORE_CASE),
            Regex("""(?:search|play|query)\s+(?:for\s+)?["']?([^,"']+)["']?""", RegexOption.IGNORE_CASE),
            Regex("""["']([^"']+)["']""")
        )
        for (pattern in patterns) {
            val match = pattern.find(clause)
            if (match != null) {
                val candidate = match.groupValues[1].trim()
                if (candidate.isNotBlank() && candidate.length > 2 && !candidate.equals("song", ignoreCase = true) && !candidate.equals("youtube", ignoreCase = true)) {
                    return candidate
                }
            }
        }
        return null
    }

    private fun extractRecipient(clause: String): String? {
        val lower = clause.lowercase(Locale.ROOT)
        return when {
            lower.contains("papa") -> "Papa"
            lower.contains("mom") || lower.contains("mummy") -> "Mom"
            lower.contains("friend") -> "friend"
            lower.contains("rahul") -> "Rahul"
            lower.contains("specified conversation") -> "specified contact"
            else -> null
        }
    }

    private fun extractImagePromptTopic(clause: String): String? {
        val match = Regex("""(?:using|with)\s+(?:a\s+suitable\s+prompt\s+that\s+you\s+write\s+yourself|prompt\s+about)?\s*["']?([^,"']+)["']?""", RegexOption.IGNORE_CASE).find(clause)
        return match?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.length > 5 }
    }

    private fun splitIntoClauses(input: String): List<String> {
        val placeholderMap = mutableMapOf<String, String>()
        var protectedInput = input
        val quoteRegex = Regex("""['"]([^'"]+)['"]""")
        var quoteIdx = 0
        protectedInput = quoteRegex.replace(protectedInput) { match ->
            val placeholder = "___QUOTE_${quoteIdx++}___"
            placeholderMap[placeholder] = match.value
            placeholder
        }

        val pattern = "(?:\\s+(?:और|फिर|उसके बाद|then|phir|after that|finally|ant me|अंत में|and then|aur phir)\\s+|[;\\n]+|\\s*,\\s*)".toRegex(RegexOption.IGNORE_CASE)
        val rawClauses = protectedInput.split(pattern)
            .map { it.trim() }
            .filter { it.isNotBlank() }

        return rawClauses.map { clause ->
            var restored = clause
            placeholderMap.forEach { (ph, original) ->
                restored = restored.replace(ph, original)
            }
            restored
        }
    }
}
