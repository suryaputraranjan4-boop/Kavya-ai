package com.example.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Event category tags matching User Requirement 34.
 */
enum class EventTag {
    VOICE,
    PARSED,
    APP,
    SCREEN,
    ACTION,
    VERIFY,
    TASK,
    ERROR
}

data class AutomationLogEntry(
    val id: Long = System.currentTimeMillis(),
    val tag: EventTag,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    val formatted: String
        get() {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
            return "[$time] [${tag.name}] $message"
        }
}

/**
 * Central observable event logger for real-time automation transparency.
 */
object AutomationEventLogger {

    private val _logs = MutableStateFlow<List<AutomationLogEntry>>(emptyList())
    val logs: StateFlow<List<AutomationLogEntry>> = _logs.asStateFlow()

    fun log(tag: EventTag, message: String) {
        val entry = AutomationLogEntry(tag = tag, message = message)
        val current = _logs.value.toMutableList()
        if (current.size > 150) {
            current.removeAt(current.size - 1)
        }
        current.add(0, entry)
        _logs.value = current
    }

    fun voice(msg: String) = log(EventTag.VOICE, msg)
    fun parsed(msg: String) = log(EventTag.PARSED, msg)
    fun app(msg: String) = log(EventTag.APP, msg)
    fun screen(msg: String) = log(EventTag.SCREEN, msg)
    fun action(msg: String) = log(EventTag.ACTION, msg)
    fun verify(msg: String) = log(EventTag.VERIFY, msg)
    fun task(msg: String) = log(EventTag.TASK, msg)
    fun error(msg: String) = log(EventTag.ERROR, msg)

    fun clear() {
        _logs.value = emptyList()
    }
}
