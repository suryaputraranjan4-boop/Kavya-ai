package com.example.agent

import java.util.UUID

/**
 * Checkpoint types corresponding to verified execution stages in Kavya's automation loop.
 */
enum class CheckpointType {
    CHECKPOINT_1_APP_OPENED,
    CHECKPOINT_2_TARGET_FOUND,
    CHECKPOINT_3_TARGET_VERIFIED,
    CHECKPOINT_4_REQUIRED_SCREEN_OPENED,
    CHECKPOINT_5_INPUT_ENTERED,
    CHECKPOINT_6_ACTION_COMPLETED
}

/**
 * Immutable snapshot of a verified execution checkpoint.
 */
data class TaskCheckpoint(
    val checkpointId: String = UUID.randomUUID().toString(),
    val stepId: Int,
    val type: CheckpointType,
    val targetApp: String,
    val targetPackage: String,
    val screenFingerprint: ScreenFingerprint?,
    val dataSnapshot: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Manages automation task checkpoints and provides safe state resumption.
 * Guarantees that actions already verified (such as a sent WhatsApp message or initiated phone call)
 * are never duplicated when recovering from an error or app restart.
 */
class CheckpointManager {

    private val checkpoints = mutableListOf<TaskCheckpoint>()

    fun recordCheckpoint(
        stepId: Int,
        type: CheckpointType,
        targetApp: String,
        targetPackage: String,
        fingerprint: ScreenFingerprint? = null,
        data: Map<String, String> = emptyMap()
    ): TaskCheckpoint {
        val checkpoint = TaskCheckpoint(
            stepId = stepId,
            type = type,
            targetApp = targetApp,
            targetPackage = targetPackage,
            screenFingerprint = fingerprint,
            dataSnapshot = data
        )
        synchronized(checkpoints) {
            checkpoints.add(checkpoint)
        }
        return checkpoint
    }

    fun getLatestCheckpoint(): TaskCheckpoint? {
        synchronized(checkpoints) {
            return checkpoints.lastOrNull()
        }
    }

    fun getCheckpoint(type: CheckpointType): TaskCheckpoint? {
        synchronized(checkpoints) {
            return checkpoints.lastOrNull { it.type == type }
        }
    }

    fun hasCheckpoint(type: CheckpointType): Boolean {
        synchronized(checkpoints) {
            return checkpoints.any { it.type == type }
        }
    }

    fun getAllCheckpoints(): List<TaskCheckpoint> {
        synchronized(checkpoints) {
            return checkpoints.toList()
        }
    }

    /**
     * Prevents duplicate message sending if a message to the same recipient was already verified as sent.
     */
    fun isMessageAlreadySent(recipient: String, messageText: String): Boolean {
        synchronized(checkpoints) {
            return checkpoints.any { cp ->
                cp.type == CheckpointType.CHECKPOINT_6_ACTION_COMPLETED &&
                        cp.dataSnapshot["action"] == "SEND_MESSAGE" &&
                        cp.dataSnapshot["recipient"].equals(recipient, ignoreCase = true) &&
                        cp.dataSnapshot["message"] == messageText
            }
        }
    }

    /**
     * Prevents duplicate phone call initiation if already calling.
     */
    fun isCallAlreadyInitiated(contactOrNumber: String): Boolean {
        synchronized(checkpoints) {
            return checkpoints.any { cp ->
                cp.type == CheckpointType.CHECKPOINT_6_ACTION_COMPLETED &&
                        cp.dataSnapshot["action"] == "CALL" &&
                        cp.dataSnapshot["target"].equals(contactOrNumber, ignoreCase = true)
            }
        }
    }

    fun clear() {
        synchronized(checkpoints) {
            checkpoints.clear()
        }
    }
}
