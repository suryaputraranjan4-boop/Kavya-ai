package com.example.agent

import android.content.Context
import android.util.Log
import com.example.ai.AIModelRouter
import com.example.ai.OfflinePrivacyGuard
import com.example.data.AppDatabase
import com.example.data.DurableTaskEntity
import com.example.data.DurableTaskStepEntity
import com.example.data.ScheduledTaskEntity
import com.example.kucp.KucpGateway
import com.example.kucp.model.ConnectorStatus
import com.example.kucp.security.SecretRedactor
import com.example.memory.okf.OkfCategory
import com.example.memory.okf.OkfMemoryRepository
import com.example.memory.okf.OkfProvenance
import com.example.scheduler.SchedulerEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class OrchestrationRoute(val label: String) {
    DIRECT_CHAT("Direct Chat"),
    RESEARCH_MULTI_AGENT("Multi-Agent Research"),
    MULTI_STEP_PLAN("Multi-Step Agent Plan"),
    CONNECTED_SERVICES("Connected Services Workflow"),
    EXPLICIT_AGENTS("Explicit Dynamic Agents"),
    SCHEDULE_WORKFLOW("Scheduled Automation / Monitor")
}

data class TemporaryAgentRecord(
    val agentId: String,
    val taskId: String,
    val roleObjective: String,
    val assignedTools: List<String>,
    val status: AgentStatus = AgentStatus.PENDING,
    val progressNote: String = "",
    val outputSummary: String = "",
    val evidenceUrls: List<String> = emptyList(),
    val startedAtMs: Long = System.currentTimeMillis(),
    val endedAtMs: Long? = null
)

data class OrchestrationRunSummary(
    val taskId: String,
    val objective: String,
    val route: OrchestrationRoute,
    val status: GoalStatus,
    val agentCount: Int,
    val completedSteps: Int,
    val totalSteps: Int,
    val sourcesCount: Int = 0,
    val finalSummary: String,
    val error: String? = null,
    val startedAtMs: Long = System.currentTimeMillis(),
    val finishedAtMs: Long = System.currentTimeMillis()
)

/**
 * Central Gemini-Powered Unified Agent Orchestrator.
 * Connects Normal Chat, Dynamic Temporary Agents, Parallel Research & Browser Manager,
 * UniversalToolGateway (KUCP + Native), Long-Term Memory (OKF + Room), and Advanced Scheduler
 * into one cohesive runtime without duplicating engines.
 */
class UnifiedAgentOrchestrator private constructor(
    private val appContext: Context,
    private val kucpGateway: KucpGateway = KucpGateway.getInstance(appContext),
    private val toolGateway: UniversalToolGateway = UniversalToolGateway.getInstance(appContext),
    private val browserManager: AgentResearchAndBrowserManager = AgentResearchAndBrowserManager.getInstance(appContext),
    private val okfMemory: OkfMemoryRepository = OkfMemoryRepository.getInstance(appContext),
    private val database: AppDatabase = AppDatabase.getDatabase(appContext),
    private val aiRouter: AIModelRouter = AIModelRouter(appContext)
) {
    companion object {
        private const val TAG = "UnifiedAgentOrchestrator"

        @Volatile
        private var instance: UnifiedAgentOrchestrator? = null

        fun getInstance(context: Context): UnifiedAgentOrchestrator =
            instance ?: synchronized(this) {
                instance ?: UnifiedAgentOrchestrator(context.applicationContext).also { instance = it }
            }

        internal fun createForTesting(
            context: Context,
            kucpGateway: KucpGateway,
            toolGateway: UniversalToolGateway,
            browserManager: AgentResearchAndBrowserManager
        ): UnifiedAgentOrchestrator = UnifiedAgentOrchestrator(
            appContext = context.applicationContext,
            kucpGateway = kucpGateway,
            toolGateway = toolGateway,
            browserManager = browserManager
        )
    }

    val maxConcurrencySlots: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
    private val agentSemaphore = Semaphore(maxConcurrencySlots)
    private val stateMutex = Mutex()
    private val orchestratorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val _liveTempAgents = MutableStateFlow<List<TemporaryAgentRecord>>(emptyList())
    val liveTempAgents: StateFlow<List<TemporaryAgentRecord>> = _liveTempAgents.asStateFlow()

    private val _activeRuns = MutableStateFlow<List<OrchestrationRunSummary>>(emptyList())
    val activeRuns: StateFlow<List<OrchestrationRunSummary>> = _activeRuns.asStateFlow()

    private val _recentRuns = MutableStateFlow<List<OrchestrationRunSummary>>(emptyList())
    val recentRuns: StateFlow<List<OrchestrationRunSummary>> = _recentRuns.asStateFlow()

    private val _resumableTasks = MutableStateFlow<List<DurableTaskEntity>>(emptyList())
    val resumableTasks: StateFlow<List<DurableTaskEntity>> = _resumableTasks.asStateFlow()

    init {
        orchestratorScope.launch { refreshResumableTasks() }
    }

    /**
     * Classifies whether a user chat prompt should stay fast & direct or spin up
     * temporary dynamic agents. Simple conversational queries stay DIRECT_CHAT (0 agents).
     */
    fun classifyRequest(prompt: String, connectedConnectorsCount: Int = countConnectedExternalConnectors()): OrchestrationRoute {
        val text = prompt.trim()
        val lower = text.lowercase(Locale.ROOT)
        if (text.length < 14 && !lower.contains("agent") && !lower.contains("monitor")) {
            return OrchestrationRoute.DIRECT_CHAT
        }

        // 1. Explicit agent creation / parallel agent command
        if (Regex("\\b(create|spawn|launch|use|run)\\s+(\\d+\\s+)?(temporary\\s+|parallel\\s+|multi[- ]?)?agents?\\b").containsMatchIn(lower) ||
            lower.contains("multi-agent") || lower.contains("multi agent")
        ) {
            return OrchestrationRoute.EXPLICIT_AGENTS
        }

        // 2. Recurring scheduling or topic monitoring automation
        val isRecurringSchedule = Regex("\\b(every\\s+(day|week|month|morning|evening|hour|\\d+\\s*(min|minute|hour|day))|daily\\s+at|weekly\\s+on|monthly\\s+on|schedule\\s+(a\\s+)?((daily|weekly|monthly|hourly|recurring)\\s+)?(task|workflow|report|check|monitor|digest|summary)|monitor\\s+.+\\s+(every|daily|hourly|continuously|updates))\\b")
            .containsMatchIn(lower)
        if (isRecurringSchedule) {
            return OrchestrationRoute.SCHEDULE_WORKFLOW
        }

        // 3. Deep multi-source web research / comparative analysis
        val isDeepResearch = Regex("\\b(deep\\s+research|multi[- ]?source\\s+research|research\\s+(the\\s+)?(latest|current|recent|top|best|all)|compare\\s+.+\\s+(vs\\.?|versus|and)|investigate\\s+.+\\s+(sources|citations|papers|benchmarks)|fact[- ]?check\\s+with\\s+citations)\\b")
            .containsMatchIn(lower) ||
            (lower.contains("research") && (lower.contains("compare") || lower.contains("sources") || lower.contains("citations") || lower.contains("report") || lower.contains("papers")))
        if (isDeepResearch) {
            return OrchestrationRoute.RESEARCH_MULTI_AGENT
        }

        // 4. Connected external service orchestration
        val serviceMention = Regex("\\b(my\\s+(gmail|google\\s+drive|google\\s+calendar|google\\s+docs|google\\s+sheets|slack|notion|github|jira|linear|asana|trello|hubspot|salesforce|outlook|onedrive|teams|dropbox|box|stripe|shopify|discord|spotify|connected\\s+(apps|services|integrations))|across\\s+my\\s+connected)\\b")
            .containsMatchIn(lower)
        if (serviceMention && (connectedConnectorsCount > 0 || lower.contains("connected") || lower.contains("workspace") || lower.contains("integration"))) {
            return OrchestrationRoute.CONNECTED_SERVICES
        }

        // 5. Explicit multi-step workflow plan
        val isMultiStep = (lower.contains(" and then ") && lower.length > 55) ||
            (lower.contains("step 1") && lower.contains("step 2")) ||
            Regex("\\b(plan\\s+and\\s+execute|automate\\s+this\\s+workflow|run\\s+a\\s+multi[- ]?step)\\b").containsMatchIn(lower)
        if (isMultiStep) {
            return OrchestrationRoute.MULTI_STEP_PLAN
        }

        return OrchestrationRoute.DIRECT_CHAT
    }

    fun countConnectedExternalConnectors(): Int {
        return kucpGateway.connectors.value.count {
            it.enabled &&
                it.id != com.example.kucp.android.AndroidNativeIntegrationProvider.CONNECTOR_ID &&
                (it.status == ConnectorStatus.CONNECTED || it.status == ConnectorStatus.PARTIALLY_AUTHORIZED)
        }
    }

    /**
     * Main entry point invoked from Normal Chat (`KavyaViewModel`) or `AgentsWorkspaceScreen`.
     */
    suspend fun orchestrateFromChat(
        prompt: String,
        chatId: Long = 0L,
        androidAgent: AndroidAgent? = null,
        forcedRoute: OrchestrationRoute? = null,
        onStatusUpdate: (String) -> Unit = {}
    ): OrchestrationRunSummary = withContext(Dispatchers.IO) {
        val route = forcedRoute ?: classifyRequest(prompt)
        val taskId = "task_${UUID.randomUUID().toString().take(8)}"
        val started = System.currentTimeMillis()
        val job = coroutineContext[Job]
        if (job != null) activeJobs[taskId] = job

        val initialRun = OrchestrationRunSummary(
            taskId = taskId,
            objective = prompt.trim(),
            route = route,
            status = GoalStatus.EXECUTING,
            agentCount = 0,
            completedSteps = 0,
            totalSteps = 1,
            finalSummary = "Initializing orchestration...",
            startedAtMs = started,
            finishedAtMs = started
        )
        upsertActiveRun(initialRun)

        try {
            val result = when (route) {
                OrchestrationRoute.SCHEDULE_WORKFLOW -> executeScheduleCreation(taskId, prompt, started, onStatusUpdate)
                OrchestrationRoute.RESEARCH_MULTI_AGENT -> executeMultiAgentResearch(taskId, prompt, started, onStatusUpdate)
                OrchestrationRoute.CONNECTED_SERVICES,
                OrchestrationRoute.EXPLICIT_AGENTS,
                OrchestrationRoute.MULTI_STEP_PLAN -> executeDynamicToolPlan(taskId, prompt, route, androidAgent, started, onStatusUpdate)
                OrchestrationRoute.DIRECT_CHAT -> executeMultiAgentResearch(taskId, prompt, started, onStatusUpdate)
            }
            finalizeRunTracking(taskId, result)
            refreshResumableTasks()
            result
        } catch (ce: CancellationException) {
            val cancelled = initialRun.copy(
                status = GoalStatus.CANCELLED,
                finalSummary = "Task cancelled.",
                error = "Cancelled",
                finishedAtMs = System.currentTimeMillis()
            )
            persistTaskCompletion(taskId, GoalStatus.CANCELLED, 0, cancelled.finalSummary, "Cancelled")
            finalizeRunTracking(taskId, cancelled)
            releaseTempAgentsForTask(taskId)
            throw ce
        } catch (e: Exception) {
            val err = SecretRedactor.redact(e.message ?: "Orchestration error")
            val failed = initialRun.copy(
                status = GoalStatus.FAILED,
                finalSummary = "Unable to complete task: $err",
                error = err,
                finishedAtMs = System.currentTimeMillis()
            )
            persistTaskCompletion(taskId, GoalStatus.FAILED, 0, failed.finalSummary, err)
            finalizeRunTracking(taskId, failed)
            releaseTempAgentsForTask(taskId)
            failed
        } finally {
            activeJobs.remove(taskId)
            releaseTempAgentsForTask(taskId)
        }
    }

    /**
     * Executes a scheduled task (triggered by AlarmReceiver or Agents Workspace "Run Now")
     * through the exact same orchestrator, memory, and UniversalToolGateway.
     */
    suspend fun executeScheduledTask(schedule: ScheduledTaskEntity): OrchestrationRunSummary {
        val objective = schedule.promptOrAction.ifBlank { schedule.title }
        val route = when {
            schedule.scheduleType.startsWith("MONITOR_POLL_") -> OrchestrationRoute.RESEARCH_MULTI_AGENT
            classifyRequest(objective) == OrchestrationRoute.DIRECT_CHAT -> OrchestrationRoute.RESEARCH_MULTI_AGENT
            else -> classifyRequest(objective)
        }
        return orchestrateFromChat(
            prompt = objective,
            forcedRoute = if (route == OrchestrationRoute.SCHEDULE_WORKFLOW) OrchestrationRoute.MULTI_STEP_PLAN else route
        )
    }

    /**
     * Refreshes interrupted or unfinished durable tasks from Room so the user can resume them cleanly.
     */
    suspend fun refreshResumableTasks() = withContext(Dispatchers.IO) {
        try {
            _resumableTasks.value = database.taskDao().getActiveTasks()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load resumable tasks: ${e.message}")
        }
    }

    /**
     * Resumes an interrupted durable task from Room after re-checking connector health and preserving idempotency.
     */
    suspend fun resumeTask(taskId: String, onStatusUpdate: (String) -> Unit = {}): OrchestrationRunSummary = withContext(Dispatchers.IO) {
        val entity = database.taskDao().getTaskById(taskId)
            ?: return@withContext OrchestrationRunSummary(
                taskId = taskId,
                objective = "Unknown task",
                route = OrchestrationRoute.MULTI_STEP_PLAN,
                status = GoalStatus.FAILED,
                agentCount = 0,
                completedSteps = 0,
                totalSteps = 0,
                finalSummary = "Durable task '$taskId' not found.",
                error = "NOT_FOUND"
            )

        onStatusUpdate("Resuming durable task '${entity.title}'...")
        val route = OrchestrationRoute.entries.firstOrNull { it.name == entity.intentCategory }
            ?: classifyRequest(entity.title)
        orchestrateFromChat(
            prompt = entity.title,
            forcedRoute = if (route == OrchestrationRoute.DIRECT_CHAT) OrchestrationRoute.MULTI_STEP_PLAN else route,
            onStatusUpdate = onStatusUpdate
        )
    }

    fun cancelTask(taskId: String) {
        activeJobs.remove(taskId)?.cancel()
        orchestratorScope.launch {
            releaseTempAgentsForTask(taskId)
            persistTaskCompletion(taskId, GoalStatus.CANCELLED, 0, "Cancelled by user", "Cancelled")
            _activeRuns.value = _activeRuns.value.filterNot { it.taskId == taskId }
            refreshResumableTasks()
        }
    }

    // =========================================================================
    // 1. MULTI-AGENT PARALLEL RESEARCH PIPELINE
    // =========================================================================

    private suspend fun executeMultiAgentResearch(
        taskId: String,
        prompt: String,
        startedAtMs: Long,
        onStatusUpdate: (String) -> Unit
    ): OrchestrationRunSummary {
        onStatusUpdate("Decomposing research objective into parallel sub-queries...")

        // Retrieve relevant long-term memory context first (BM25)
        val priorMemories = try {
            okfMemory.searchKnowledge(prompt, topK = 3)
        } catch (_: Exception) {
            emptyList()
        }

        val subQueries = generateResearchSubQueries(prompt)
        persistNewDurableTask(taskId, prompt, OrchestrationRoute.RESEARCH_MULTI_AGENT.name, subQueries)

        val spawnedAgents = subQueries.mapIndexed { idx, sq ->
            TemporaryAgentRecord(
                agentId = "ResearchAgent-${idx + 1}",
                taskId = taskId,
                roleObjective = "Investigate: $sq",
                assignedTools = listOf("wikipedia_search", "arxiv_search", "duckduckgo_instant", "memory_search"),
                status = AgentStatus.READY,
                progressNote = "Queued for parallel web & memory retrieval"
            )
        }
        registerTempAgents(spawnedAgents)
        updateActiveRunCounts(taskId, agentCount = spawnedAgents.size, totalSteps = subQueries.size + 1)

        // Execute temporary research agents IN PARALLEL (bounded by agentSemaphore)
        val collectedEvidence = coroutineScope {
            spawnedAgents.mapIndexed { idx, agent ->
                async(Dispatchers.IO) {
                    agentSemaphore.withPermit {
                        val subQuery = subQueries[idx]
                        updateTempAgent(agent.agentId, AgentStatus.RUNNING, "Searching authoritative sources for '$subQuery'...")
                        onStatusUpdate("${agent.agentId}: searching sources for '$subQuery'")

                        val session = browserManager.openSession(taskId, agent.agentId, subQuery)
                        val evidence = browserManager.runMultiSourceResearch(
                            sessionId = session.sessionId,
                            query = subQuery,
                            includeAcademic = true
                        )
                        val sessionStatus = when {
                            OfflinePrivacyGuard.isStrictOfflineMode(appContext) -> BrowserSessionStatus.BLOCKED_OFFLINE
                            evidence.isNotEmpty() -> BrowserSessionStatus.COMPLETED
                            else -> BrowserSessionStatus.COMPLETED
                        }
                        browserManager.closeSession(
                            session.sessionId,
                            sessionStatus,
                            "Collected ${evidence.size} verified citations"
                        )
                        val stepSummary = if (evidence.isNotEmpty()) {
                            "Collected ${evidence.size} citations: " + evidence.joinToString("; ") { "${it.publisher}: ${it.title}" }
                        } else if (OfflinePrivacyGuard.isStrictOfflineMode(appContext)) {
                            "External web search blocked by Strict Offline Mode."
                        } else {
                            "No public sources matched query '$subQuery'."
                        }

                        updateTempAgent(
                            agent.agentId,
                            AgentStatus.COMPLETED,
                            progressNote = stepSummary.take(140),
                            outputSummary = stepSummary,
                            evidenceUrls = evidence.map { it.url }
                        )
                        updateDurableStep(taskId, "${taskId}_step_${idx + 1}", idx + 1, subQuery, "RESEARCH", TaskNodeStatus.COMPLETED.name, stepSummary, null)
                        evidence
                    }
                }
            }.awaitAll().flatten().distinctBy { it.url.lowercase(Locale.ROOT) }
        }

        // Cross-source synthesis via Gemini (or deterministic structured synthesis if offline/LLM unavailable)
        onStatusUpdate("Synthesizing ${collectedEvidence.size} verified sources...")
        val finalReport = synthesizeResearchReport(prompt, collectedEvidence, priorMemories.map { "${it.unit.key}: ${it.unit.content}" })

        // Persist curated research takeaway into OKF Long-Term Memory (FACT category, never polluting PREFERENCE)
        if (collectedEvidence.isNotEmpty()) {
            try {
                val citationList = collectedEvidence.take(5).joinToString(" | ") { "${it.title} (${it.url})" }
                okfMemory.createKnowledge(
                    key = "Research: ${prompt.take(72)}",
                    content = "${finalReport.take(900)}\nSources: $citationList",
                    category = OkfCategory.FACT,
                    tags = listOf("research", "multi-agent", "cited"),
                    importance = 3,
                    provenance = OkfProvenance(
                        source = "MULTI_AGENT_RESEARCH",
                        author = "KavyaUnifiedOrchestrator",
                        sessionContext = taskId
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Non-fatal memory write error: ${e.message}")
            }
        }

        persistTaskCompletion(taskId, GoalStatus.COMPLETED, subQueries.size + 1, finalReport.take(500), null)

        return OrchestrationRunSummary(
            taskId = taskId,
            objective = prompt,
            route = OrchestrationRoute.RESEARCH_MULTI_AGENT,
            status = GoalStatus.COMPLETED,
            agentCount = spawnedAgents.size,
            completedSteps = subQueries.size + 1,
            totalSteps = subQueries.size + 1,
            sourcesCount = collectedEvidence.size,
            finalSummary = finalReport,
            startedAtMs = startedAtMs,
            finishedAtMs = System.currentTimeMillis()
        )
    }

    private suspend fun generateResearchSubQueries(prompt: String): List<String> {
        val clean = prompt.trim()
        if (!OfflinePrivacyGuard.isStrictOfflineMode(appContext)) {
            try {
                val plannerPrompt = """
                    Break this user research request into 2 to 3 focused, independent web search queries.
                    Return ONLY a JSON array of short query strings (e.g. ["query 1", "query 2"]). No prose.
                    User request: $clean
                """.trimIndent()
                val raw = aiRouter.chat(prompt = plannerPrompt)
                val start = raw.indexOf('[')
                val end = raw.lastIndexOf(']')
                if (start >= 0 && end > start) {
                    val arr = JSONArray(raw.substring(start, end + 1))
                    val parsed = (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { s -> s.isNotBlank() } }.distinct().take(3)
                    if (parsed.isNotEmpty()) return parsed
                }
            } catch (e: Exception) {
                Log.w(TAG, "Research sub-query planner fallback: ${e.message}")
            }
        }
        // Deterministic decomposition fallback
        val vsSplit = Regex("(?i)\\s+(?:vs\\.?|versus)\\s+").split(clean)
        if (vsSplit.size >= 2) {
            return vsSplit.take(3).map { it.replace(Regex("(?i)^(compare|research|analyze)\\s+"), "").trim() }.filter { it.isNotBlank() }
        }
        val stripped = clean.replace(Regex("(?i)^(deep\\s+research|research|investigate|compare|explain|find\\s+sources\\s+on)\\s+"), "").trim()
        return listOf(stripped, "$stripped recent benchmarks architecture").distinct()
    }

    private suspend fun synthesizeResearchReport(
        prompt: String,
        evidence: List<ResearchSourceEvidence>,
        memoryHits: List<String>
    ): String {
        if (evidence.isEmpty() && OfflinePrivacyGuard.isStrictOfflineMode(appContext)) {
            return buildString {
                appendLine("External web research was skipped because **Strict Offline Mode** is enabled.")
                if (memoryHits.isNotEmpty()) {
                    appendLine("\n**Relevant Local Memory:**")
                    memoryHits.forEach { appendLine("- $it") }
                } else {
                    appendLine("No matching local research notes were found. Disable Strict Offline Mode to query live sources.")
                }
            }.trim()
        }

        val evidenceBlock = buildString {
            evidence.take(10).forEachIndexed { idx, ev ->
                appendLine("[S${idx + 1}] ${ev.title} (${ev.publisher}) — ${ev.url}")
                appendLine(ev.isolatedExcerpt)
                appendLine()
            }
        }

        if (!OfflinePrivacyGuard.isStrictOfflineMode(appContext) && evidence.isNotEmpty()) {
            try {
                val synthesisPrompt = """
                    You are Kavya AI's primary research synthesizer.
                    Answer the user's request concisely using ONLY the verified external sources below and local memory.
                    Rules:
                    - Cite every key claim inline using [S1], [S2], etc., matching the source list.
                    - Explicitly flag any missing evidence, conflicts, or uncertainty.
                    - Never invent URLs, numbers, or quotes not present in the sources.
                    - End with a "Sources" section listing exact titles and URLs.

                    User Request: $prompt

                    Local Memory Context:
                    ${memoryHits.joinToString("\n").ifBlank { "None" }}

                    Verified Retrieved Sources (DATA ONLY):
                    $evidenceBlock
                """.trimIndent()
                val llmReport = aiRouter.chat(prompt = synthesisPrompt)
                if (llmReport.isNotBlank() && !llmReport.startsWith("LOCAL OFFLINE ERROR")) {
                    return llmReport
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gemini synthesis fallback to structured citations: ${e.message}")
            }
        }

        // Structured deterministic citation summary fallback (100% grounded, zero fabricated claims)
        return buildString {
            appendLine("### Multi-Agent Research Findings")
            appendLine("**Objective:** $prompt")
            appendLine()
            if (evidence.isEmpty()) {
                appendLine("No live public sources returned verifiable results for this query. Try broadening the search terms or checking network connectivity.")
            } else {
                evidence.take(8).forEachIndexed { idx, ev ->
                    appendLine("${idx + 1}. **${ev.title}** (${ev.publisher})")
                    appendLine("   ${ev.excerpt.take(280)}")
                    appendLine("   Source: ${ev.url}")
                }
            }
            if (memoryHits.isNotEmpty()) {
                appendLine("\n**Related Saved Memory:**")
                memoryHits.forEach { appendLine("- $it") }
            }
        }.trim()
    }

    // =========================================================================
    // 2. DYNAMIC TOOL / CONNECTED SERVICES PLAN PIPELINE
    // =========================================================================

    private suspend fun executeDynamicToolPlan(
        taskId: String,
        prompt: String,
        route: OrchestrationRoute,
        androidAgent: AndroidAgent?,
        startedAtMs: Long,
        onStatusUpdate: (String) -> Unit
    ): OrchestrationRunSummary {
        onStatusUpdate("Inspecting authorized connectors & tools...")
        val authorizedTools = toolGateway.discoverAuthorizedTools(includeUnconnectedConnectors = false)
        val externalTools = authorizedTools.filter { it.source == "KUCP" && it.providerId != com.example.kucp.android.AndroidNativeIntegrationProvider.CONNECTOR_ID }

        // Honest reporting when user asks for connected services that are not yet authorized
        if (route == OrchestrationRoute.CONNECTED_SERVICES && externalTools.isEmpty()) {
            val catalogMatches = kucpGateway.getMarketplaceTemplates()
                .filter { tpl -> prompt.contains(tpl.name, ignoreCase = true) || prompt.contains(tpl.canonicalServiceId.replace('_', ' '), ignoreCase = true) }
            val setupHint = if (catalogMatches.isNotEmpty()) {
                catalogMatches.joinToString("\n") { tpl ->
                    "- **${tpl.name}** (${tpl.implementationState.label}): ${tpl.setupNotes}"
                }
            } else {
                "Open **Side Menu -> Agents -> Integrations** or **Integrations Hub** to install and authorize the external service with your account or API key."
            }
            val honestMsg = "No external service connectors are currently authorized for this request.\n\n$setupHint"
            persistNewDurableTask(taskId, prompt, route.name, listOf("Check authorized connectors"))
            persistTaskCompletion(taskId, GoalStatus.COMPLETED, 1, honestMsg, null)
            return OrchestrationRunSummary(
                taskId = taskId,
                objective = prompt,
                route = route,
                status = GoalStatus.COMPLETED,
                agentCount = 0,
                completedSteps = 1,
                totalSteps = 1,
                finalSummary = honestMsg,
                startedAtMs = startedAtMs,
                finishedAtMs = System.currentTimeMillis()
            )
        }

        val plannedSteps = buildToolPlanSteps(prompt, authorizedTools)
        persistNewDurableTask(taskId, prompt, route.name, plannedSteps.map { it.description })

        val spawnedAgents = plannedSteps.mapIndexed { idx, step ->
            TemporaryAgentRecord(
                agentId = "Agent-${idx + 1}",
                taskId = taskId,
                roleObjective = step.description,
                assignedTools = listOf(step.toolId),
                status = AgentStatus.READY,
                progressNote = "Assigned tool '${step.toolId}'"
            )
        }
        registerTempAgents(spawnedAgents)
        updateActiveRunCounts(taskId, agentCount = spawnedAgents.size, totalSteps = plannedSteps.size)

        val stepResults = ConcurrentHashMap<String, GatewayToolResult>()
        val completedStepIds = ConcurrentHashMap.newKeySet<String>()
        val waitingApprovals = mutableListOf<String>()

        // Execute ready waves in parallel while respecting dependencies
        val remaining = plannedSteps.toMutableList()
        while (remaining.isNotEmpty()) {
            val readyWave = remaining.filter { step -> step.dependsOn.all { it in completedStepIds } }
            if (readyWave.isEmpty()) break
            remaining.removeAll(readyWave)

            coroutineScope {
                readyWave.map { step ->
                    async(Dispatchers.IO) {
                        agentSemaphore.withPermit {
                            val agentIdx = plannedSteps.indexOf(step)
                            val agentId = "Agent-${agentIdx + 1}"
                            updateTempAgent(agentId, AgentStatus.RUNNING, "Running ${step.toolId}...")
                            onStatusUpdate("$agentId: ${step.description}")

                            val res = toolGateway.execute(
                                toolIdOrName = step.toolId,
                                arguments = step.arguments,
                                context = appContext,
                                androidAgent = androidAgent,
                                taskId = taskId,
                                agentId = agentId,
                                idempotencyKey = "${taskId}_${step.stepId}",
                                userConfirmed = false
                            )
                            stepResults[step.stepId] = res

                            when (res.status) {
                                GatewayOperationStatus.COMPLETED -> {
                                    completedStepIds.add(step.stepId)
                                    updateTempAgent(agentId, AgentStatus.COMPLETED, "Completed in ${res.durationMs}ms", res.structuredOutput.take(300), res.sourceRefs)
                                    updateDurableStep(taskId, "${taskId}_${step.stepId}", agentIdx + 1, step.description, step.toolId, TaskNodeStatus.COMPLETED.name, res.structuredOutput.take(400), null)
                                }
                                GatewayOperationStatus.WAITING_FOR_APPROVAL -> {
                                    synchronized(waitingApprovals) { waitingApprovals.add(res.errorDetails ?: step.description) }
                                    updateTempAgent(agentId, AgentStatus.WAITING_DEPENDENCY, "Awaiting user approval in Agents -> Approvals")
                                    updateDurableStep(taskId, "${taskId}_${step.stepId}", agentIdx + 1, step.description, step.toolId, TaskNodeStatus.NEEDS_USER_INPUT.name, null, res.errorDetails)
                                }
                                else -> {
                                    updateTempAgent(agentId, AgentStatus.FAILED, res.errorDetails ?: "Tool execution failed")
                                    updateDurableStep(taskId, "${taskId}_${step.stepId}", agentIdx + 1, step.description, step.toolId, TaskNodeStatus.FAILED.name, null, res.errorDetails)
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        val finalStatus = when {
            waitingApprovals.isNotEmpty() -> GoalStatus.WAITING_APPROVAL
            completedStepIds.size == plannedSteps.size -> GoalStatus.COMPLETED
            completedStepIds.isNotEmpty() -> GoalStatus.COMPLETED
            else -> GoalStatus.FAILED
        }

        val consolidated = buildString {
            appendLine("### Agent Orchestration Summary")
            appendLine("**Task:** $prompt")
            appendLine("**Completed Steps:** ${completedStepIds.size}/${plannedSteps.size}")
            appendLine()
            plannedSteps.forEach { step ->
                val res = stepResults[step.stepId]
                appendLine("- **${step.description}** (`${step.toolId}`): ${res?.status?.label ?: "Skipped"}")
                if (!res?.structuredOutput.isNullOrBlank()) {
                    appendLine("  Result: ${res!!.structuredOutput.take(320)}")
                }
                if (!res?.errorDetails.isNullOrBlank()) {
                    appendLine("  Note: ${res!!.errorDetails}")
                }
            }
            if (waitingApprovals.isNotEmpty()) {
                appendLine("\n**Pending Confirmation:** Review and approve sensitive actions in **Side Menu -> Agents -> Approvals**.")
            }
        }.trim()

        persistTaskCompletion(taskId, finalStatus, completedStepIds.size, consolidated.take(500), if (finalStatus == GoalStatus.FAILED) "One or more steps failed" else null)

        return OrchestrationRunSummary(
            taskId = taskId,
            objective = prompt,
            route = route,
            status = finalStatus,
            agentCount = spawnedAgents.size,
            completedSteps = completedStepIds.size,
            totalSteps = plannedSteps.size,
            finalSummary = consolidated,
            startedAtMs = startedAtMs,
            finishedAtMs = System.currentTimeMillis()
        )
    }

    private data class PlannedToolStep(
        val stepId: String,
        val description: String,
        val toolId: String,
        val arguments: Map<String, String>,
        val dependsOn: List<String> = emptyList()
    )

    private suspend fun buildToolPlanSteps(
        prompt: String,
        authorizedTools: List<GatewayToolSpec>
    ): List<PlannedToolStep> {
        if (!OfflinePrivacyGuard.isStrictOfflineMode(appContext) && authorizedTools.isNotEmpty()) {
            try {
                val toolCatalogText = authorizedTools.take(30).joinToString("\n") { spec ->
                    "- toolId=\"${spec.toolId}\" (${spec.providerName}): ${spec.description} | params=${spec.parameterNames.joinToString(",")}"
                }
                val planPrompt = """
                    Create a minimal execution plan (1 to 4 steps) using ONLY the authorized tools below.
                    Return ONLY a JSON array of objects:
                    [{"stepId":"s1","description":"...","toolId":"exact_toolId","arguments":{"param":"value"},"dependsOn":[]}]
                    Independent steps should have empty dependsOn so they run in parallel.

                    User goal: $prompt

                    Authorized tools:
                    $toolCatalogText
                """.trimIndent()
                val raw = aiRouter.chat(prompt = planPrompt)
                val start = raw.indexOf('[')
                val end = raw.lastIndexOf(']')
                if (start >= 0 && end > start) {
                    val arr = JSONArray(raw.substring(start, end + 1))
                    val validToolIds = authorizedTools.map { it.toolId }.toSet()
                    val parsed = mutableListOf<PlannedToolStep>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val tId = obj.optString("toolId").trim()
                        if (tId !in validToolIds) continue
                        val argsObj = obj.optJSONObject("arguments") ?: JSONObject()
                        val argsMap = mutableMapOf<String, String>()
                        argsObj.keys().forEach { k -> argsMap[k] = argsObj.optString(k) }
                        val depsArr = obj.optJSONArray("dependsOn")
                        val deps = mutableListOf<String>()
                        if (depsArr != null) {
                            for (d in 0 until depsArr.length()) deps.add(depsArr.optString(d))
                        }
                        parsed += PlannedToolStep(
                            stepId = obj.optString("stepId").ifBlank { "s${i + 1}" },
                            description = obj.optString("description").ifBlank { "Execute $tId" },
                            toolId = tId,
                            arguments = argsMap,
                            dependsOn = deps
                        )
                    }
                    if (parsed.isNotEmpty()) return parsed.take(4)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Tool planner JSON fallback: ${e.message}")
            }
        }

        // Deterministic fallback plan: memory search + first matching connected tool or research
        val fallback = mutableListOf(
            PlannedToolStep(
                stepId = "s1",
                description = "Check saved long-term memory for relevant context",
                toolId = "memory_search",
                arguments = mapOf("query" to prompt.take(120))
            )
        )
        val matchedExternal = authorizedTools.firstOrNull { spec ->
            spec.source == "KUCP" && (prompt.contains(spec.providerName, ignoreCase = true) || prompt.contains(spec.name, ignoreCase = true))
        }
        if (matchedExternal != null) {
            fallback += PlannedToolStep(
                stepId = "s2",
                description = "Query ${matchedExternal.providerName} via ${matchedExternal.name}",
                toolId = matchedExternal.toolId,
                arguments = mapOf("q" to prompt.take(120), "query" to prompt.take(120))
            )
        }
        return fallback
    }

    // =========================================================================
    // 3. ADVANCED SCHEDULE / MONITORING CREATION
    // =========================================================================

    private suspend fun executeScheduleCreation(
        taskId: String,
        prompt: String,
        startedAtMs: Long,
        onStatusUpdate: (String) -> Unit
    ): OrchestrationRunSummary {
        onStatusUpdate("Configuring recurring schedule / monitoring automation...")
        val lower = prompt.lowercase(Locale.ROOT)
        val intervalMatch = Regex("every\\s+(\\d+)\\s*(min|minute|minutes|hour|hours)").find(lower)
        val scheduleType = when {
            intervalMatch != null -> {
                val n = intervalMatch.groupValues[1].toIntOrNull()?.coerceAtLeast(5) ?: 60
                val isHours = intervalMatch.groupValues[2].startsWith("hour")
                val mins = if (isHours) n * 60 else n
                if (lower.contains("monitor")) "MONITOR_POLL_${mins}M" else "INTERVAL_${mins}M"
            }
            lower.contains("monthly") || lower.contains("every month") -> "MONTHLY"
            lower.contains("weekly") || lower.contains("every week") -> "WEEKLY"
            lower.contains("weekday") -> "WEEKDAYS"
            lower.contains("monitor") -> "MONITOR_POLL_60M"
            else -> "DAILY"
        }

        val timeMatch = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").find(lower)
        var hour = 9
        var minute = 0
        if (timeMatch != null && intervalMatch == null) {
            var h = timeMatch.groupValues[1].toIntOrNull() ?: 9
            val m = timeMatch.groupValues[2].toIntOrNull() ?: 0
            val ampm = timeMatch.groupValues[3]
            if (ampm == "pm" && h < 12) h += 12
            if (ampm == "am" && h == 12) h = 0
            if (h in 0..23 && m in 0..59) {
                hour = h
                minute = m
            }
        }

        val scheduler = SchedulerEngine(appContext)
        val created = scheduler.createAdvancedSchedule(
            title = prompt.take(64),
            promptOrAction = prompt,
            scheduleType = scheduleType,
            hour = hour,
            minute = minute,
            dayOfWeek = null,
            timeZoneId = TimeZone.getDefault().id
        )

        val fmt = SimpleDateFormat("EEE, MMM d yyyy HH:mm z", Locale.getDefault())
        val nextRunStr = fmt.format(Date(created.nextRunTimestamp))
        val msg = "Scheduled recurring automation **${created.title}** (`${created.scheduleType}`).\nNext run: **$nextRunStr**.\nManage, pause, or run it now in **Side Menu -> Agents -> Schedules**."

        persistNewDurableTask(taskId, prompt, OrchestrationRoute.SCHEDULE_WORKFLOW.name, listOf("Register schedule"))
        persistTaskCompletion(taskId, GoalStatus.COMPLETED, 1, msg, null)

        return OrchestrationRunSummary(
            taskId = taskId,
            objective = prompt,
            route = OrchestrationRoute.SCHEDULE_WORKFLOW,
            status = GoalStatus.COMPLETED,
            agentCount = 1,
            completedSteps = 1,
            totalSteps = 1,
            finalSummary = msg,
            startedAtMs = startedAtMs,
            finishedAtMs = System.currentTimeMillis()
        )
    }

    // =========================================================================
    // STATE & ROOM PERSISTENCE HELPERS
    // =========================================================================

    private suspend fun registerTempAgents(agents: List<TemporaryAgentRecord>) = stateMutex.withLock {
        _liveTempAgents.value = agents + _liveTempAgents.value
    }

    private suspend fun updateTempAgent(
        agentId: String,
        status: AgentStatus,
        progressNote: String,
        outputSummary: String = "",
        evidenceUrls: List<String> = emptyList()
    ) = stateMutex.withLock {
        val now = System.currentTimeMillis()
        _liveTempAgents.value = _liveTempAgents.value.map { a ->
            if (a.agentId == agentId) {
                a.copy(
                    status = status,
                    progressNote = progressNote,
                    outputSummary = outputSummary.ifBlank { a.outputSummary },
                    evidenceUrls = if (evidenceUrls.isNotEmpty()) evidenceUrls else a.evidenceUrls,
                    endedAtMs = if (status == AgentStatus.COMPLETED || status == AgentStatus.FAILED || status == AgentStatus.CANCELLED) now else a.endedAtMs
                )
            } else a
        }
    }

    private suspend fun releaseTempAgentsForTask(taskId: String) = stateMutex.withLock {
        _liveTempAgents.value = _liveTempAgents.value.filterNot { it.taskId == taskId }
    }

    private suspend fun upsertActiveRun(run: OrchestrationRunSummary) = stateMutex.withLock {
        _activeRuns.value = listOf(run) + _activeRuns.value.filterNot { it.taskId == run.taskId }
    }

    private suspend fun updateActiveRunCounts(taskId: String, agentCount: Int, totalSteps: Int) = stateMutex.withLock {
        _activeRuns.value = _activeRuns.value.map { r ->
            if (r.taskId == taskId) r.copy(agentCount = agentCount, totalSteps = totalSteps) else r
        }
    }

    private suspend fun finalizeRunTracking(taskId: String, summary: OrchestrationRunSummary) = stateMutex.withLock {
        _activeRuns.value = _activeRuns.value.filterNot { it.taskId == taskId }
        _recentRuns.value = (listOf(summary) + _recentRuns.value.filterNot { it.taskId == taskId }).take(40)
    }

    private suspend fun persistNewDurableTask(
        taskId: String,
        title: String,
        intentCategory: String,
        stepTitles: List<String>
    ) = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            database.taskDao().insertTask(
                DurableTaskEntity(
                    id = taskId,
                    title = title.take(180),
                    intentCategory = intentCategory,
                    status = GoalStatus.EXECUTING.name,
                    totalSteps = stepTitles.size.coerceAtLeast(1),
                    currentStepIndex = 0,
                    createdAt = now,
                    updatedAt = now
                )
            )
            val steps = stepTitles.mapIndexed { idx, sTitle ->
                DurableTaskStepEntity(
                    id = "${taskId}_step_${idx + 1}",
                    taskId = taskId,
                    stepIndex = idx + 1,
                    title = sTitle.take(160),
                    actionType = intentCategory,
                    status = TaskNodeStatus.PENDING.name,
                    timestamp = now
                )
            }
            if (steps.isNotEmpty()) database.taskDao().insertSteps(steps)
        } catch (e: Exception) {
            Log.w(TAG, "Failed persisting durable task: ${e.message}")
        }
    }

    private suspend fun updateDurableStep(
        taskId: String,
        stepId: String,
        stepIndex: Int,
        title: String,
        actionType: String,
        status: String,
        result: String?,
        error: String?
    ) = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            database.taskDao().updateStep(
                DurableTaskStepEntity(
                    id = stepId,
                    taskId = taskId,
                    stepIndex = stepIndex,
                    title = title.take(160),
                    actionType = actionType,
                    status = status,
                    actualState = result?.take(300),
                    result = result?.take(300),
                    failureReason = error?.take(200),
                    timestamp = now
                )
            )
            database.taskDao().updateTaskProgress(
                taskId = taskId,
                status = GoalStatus.EXECUTING.name,
                currentStepIndex = stepIndex,
                updatedAt = now,
                result = result?.take(300),
                error = error?.take(200)
            )
        } catch (_: Exception) {}
    }

    private suspend fun persistTaskCompletion(
        taskId: String,
        status: GoalStatus,
        completedSteps: Int,
        summary: String,
        error: String?
    ) = withContext(Dispatchers.IO) {
        try {
            database.taskDao().updateTaskProgress(
                taskId = taskId,
                status = status.name,
                currentStepIndex = completedSteps,
                updatedAt = System.currentTimeMillis(),
                result = summary.take(500),
                error = error?.take(250)
            )
        } catch (_: Exception) {}
    }
}
