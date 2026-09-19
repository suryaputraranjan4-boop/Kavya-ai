with open("app/src/main/java/com/example/api/QuotaManager.kt", "r") as f:
    content = f.read()

target = """    private var requestsInLastMinute = 0
    private var requestsInLastHour = 0
    private var activeRequests = 0"""

replacement = """    private var requestsInLastMinute = 0
    private var requestsInLastHour = 0
    private var requestsToday = 0
    private var activeRequests = 0
    
    // Debug stats
    var lastErrorType: String = "None"
    var lastErrorTime: Long = 0
    var lastErrorRequestType: String = ""
    var duplicateRequestsPrevented: Int = 0
    var localRequestsCount: Int = 0
    var proactiveRequestsCount: Int = 0
    var rateLimitErrorsCount: Int = 0
    var totalVoiceRequests: Int = 0
    
    val currentRequestsInMinute get() = requestsInLastMinute
    val currentRequestsInHour get() = requestsInLastHour
    val currentRequestsToday get() = requestsToday
    val currentActiveRequests get() = activeRequests"""

content = content.replace(target, replacement)

target2 = """            if (now - hourWindowStart.value > 3600000) {
                hourWindowStart.value = now
                requestsInLastHour = 0
            }"""
            
replacement2 = """            if (now - hourWindowStart.value > 3600000) {
                hourWindowStart.value = now
                requestsInLastHour = 0
            }
            
            // Just simple daily reset hack for dashboard
            val todayDate = now / (1000 * 60 * 60 * 24)
            if (minuteWindowStart.value / (1000 * 60 * 60 * 24) != todayDate) {
                requestsToday = 0
            }"""

content = content.replace(target2, replacement2)

target3 = """            requestsInLastMinute++
            requestsInLastHour++
            activeRequests++"""
replacement3 = """            requestsInLastMinute++
            requestsInLastHour++
            requestsToday++
            activeRequests++
            
            if (priority == RequestPriority.P3_PROACTIVE_CONVERSATION) {
                proactiveRequestsCount++
            }"""

content = content.replace(target3, replacement3)

target4 = """            if (activeRequestIds.contains(requestId)) {
                return@withLock false // Prevent duplicate requests
            }"""
            
replacement4 = """            if (activeRequestIds.contains(requestId)) {
                duplicateRequestsPrevented++
                return@withLock false // Prevent duplicate requests
            }"""
content = content.replace(target4, replacement4)

with open("app/src/main/java/com/example/api/QuotaManager.kt", "w") as f:
    f.write(content)
