package com.example.scraper.maps

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class JobStatus {
    IDLE,
    CREATED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMEOUT
}

data class ScraperJob(
    val id: String = UUID.randomUUID().toString().take(8),
    val query: GoogleMapsScraperQuery,
    val status: JobStatus = JobStatus.CREATED,
    val results: List<ScrapedBusiness> = emptyList(),
    val errorMessage: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val latencyMs: Long = 0L
)

/**
 * Manages Google Maps scraper job lifecycles.
 * Enforces single active job concurrency, non-blocking execution, timeouts, and export generation.
 */
class MapsScraperJobManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "MapsJobManager"
        private const val JOB_TIMEOUT_MS = 60_000L // 60 seconds safety ceiling

        @Volatile
        private var INSTANCE: MapsScraperJobManager? = null

        fun getInstance(context: Context): MapsScraperJobManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MapsScraperJobManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val client = GoogleMapsScraperClient(context)
    private val scope = CoroutineScope(Dispatchers.IO)
    private var activeJobExecution: Job? = null

    private val _currentJobFlow = MutableStateFlow<ScraperJob?>(null)
    val currentJobFlow: StateFlow<ScraperJob?> = _currentJobFlow.asStateFlow()

    private val _lastSuccessfulResults = MutableStateFlow<List<ScrapedBusiness>>(emptyList())
    val lastSuccessfulResults: StateFlow<List<ScrapedBusiness>> = _lastSuccessfulResults.asStateFlow()

    /**
     * Starts an asynchronous non-blocking search job.
     * Cancels any currently running job to respect single active job safety.
     */
    fun startSearchJob(
        query: GoogleMapsScraperQuery,
        onComplete: ((ScrapeResult) -> Unit)? = null
    ): String {
        // Cancel existing job if running
        cancelActiveJob()

        val job = ScraperJob(
            query = query,
            status = JobStatus.CREATED
        )
        _currentJobFlow.value = job

        activeJobExecution = scope.launch {
            _currentJobFlow.value = job.copy(status = JobStatus.RUNNING)
            val startTime = System.currentTimeMillis()

            try {
                val scrapeResult = withTimeoutOrNull(JOB_TIMEOUT_MS) {
                    client.searchBusinesses(query)
                }

                val latency = System.currentTimeMillis() - startTime

                if (scrapeResult == null) {
                    // Timed out
                    _currentJobFlow.value = job.copy(
                        status = JobStatus.TIMEOUT,
                        errorMessage = "Search timed out after 60 seconds.",
                        completedAt = System.currentTimeMillis(),
                        latencyMs = latency
                    )
                    onComplete?.invoke(ScrapeResult.Error("Search timed out after 60 seconds."))
                    return@launch
                }

                when (scrapeResult) {
                    is ScrapeResult.Success -> {
                        _currentJobFlow.value = job.copy(
                            status = JobStatus.COMPLETED,
                            results = scrapeResult.businesses,
                            completedAt = System.currentTimeMillis(),
                            latencyMs = latency
                        )
                        _lastSuccessfulResults.value = scrapeResult.businesses
                        onComplete?.invoke(scrapeResult)
                    }

                    is ScrapeResult.EmptyResult -> {
                        _currentJobFlow.value = job.copy(
                            status = JobStatus.COMPLETED,
                            results = emptyList(),
                            errorMessage = "No businesses found for '${query.toSearchTerm()}'",
                            completedAt = System.currentTimeMillis(),
                            latencyMs = latency
                        )
                        onComplete?.invoke(scrapeResult)
                    }

                    is ScrapeResult.ServiceUnavailable -> {
                        _currentJobFlow.value = job.copy(
                            status = JobStatus.FAILED,
                            errorMessage = scrapeResult.error,
                            completedAt = System.currentTimeMillis(),
                            latencyMs = latency
                        )
                        onComplete?.invoke(scrapeResult)
                    }

                    is ScrapeResult.Error -> {
                        _currentJobFlow.value = job.copy(
                            status = JobStatus.FAILED,
                            errorMessage = scrapeResult.message,
                            completedAt = System.currentTimeMillis(),
                            latencyMs = latency
                        )
                        onComplete?.invoke(scrapeResult)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during scraper job: ${e.message}", e)
                _currentJobFlow.value = job.copy(
                    status = JobStatus.FAILED,
                    errorMessage = e.localizedMessage ?: "Unknown error occurred",
                    completedAt = System.currentTimeMillis()
                )
                onComplete?.invoke(ScrapeResult.Error(e.localizedMessage ?: "Job failed"))
            }
        }

        return job.id
    }

    /**
     * Cancels any currently executing scrape job.
     */
    fun cancelActiveJob() {
        if (activeJobExecution?.isActive == true) {
            activeJobExecution?.cancel()
            val current = _currentJobFlow.value
            if (current != null && current.status == JobStatus.RUNNING) {
                _currentJobFlow.value = current.copy(
                    status = JobStatus.CANCELLED,
                    errorMessage = "Job was cancelled by user",
                    completedAt = System.currentTimeMillis()
                )
            }
        }
    }

    /**
     * Exports businesses to CSV file in app's external files or documents dir.
     * Returns absolute file path.
     */
    suspend fun exportToCsv(businesses: List<ScrapedBusiness>, queryName: String = "google_maps_export"): String? = withContext(Dispatchers.IO) {
        if (businesses.isEmpty()) return@withContext null
        try {
            val exportDir = File(context.filesDir, "exports")
            if (!exportDir.exists()) exportDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val sanitizedQuery = queryName.lowercase(Locale.US).replace(Regex("[^a-z0-9_]"), "_").take(25)
            val fileName = "maps_${sanitizedQuery}_$timestamp.csv"
            val file = File(exportDir, fileName)

            val sb = StringBuilder()
            sb.appendLine(ScrapedBusiness.csvHeader())
            businesses.forEach { b ->
                sb.appendLine(b.toCsvRow())
            }

            file.writeText(sb.toString(), Charsets.UTF_8)
            Log.d(TAG, "Exported ${businesses.size} records to ${file.absolutePath}")
            return@withContext file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed exporting to CSV: ${e.message}", e)
            return@withContext null
        }
    }

    /**
     * Exports businesses to JSON file.
     * Returns absolute file path.
     */
    suspend fun exportToJson(businesses: List<ScrapedBusiness>, queryName: String = "google_maps_export"): String? = withContext(Dispatchers.IO) {
        if (businesses.isEmpty()) return@withContext null
        try {
            val exportDir = File(context.filesDir, "exports")
            if (!exportDir.exists()) exportDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val sanitizedQuery = queryName.lowercase(Locale.US).replace(Regex("[^a-z0-9_]"), "_").take(25)
            val fileName = "maps_${sanitizedQuery}_$timestamp.json"
            val file = File(exportDir, fileName)

            val array = JSONArray()
            businesses.forEach { array.put(it.toJsonObject()) }

            file.writeText(array.toString(2), Charsets.UTF_8)
            return@withContext file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed exporting to JSON: ${e.message}", e)
            return@withContext null
        }
    }
}
