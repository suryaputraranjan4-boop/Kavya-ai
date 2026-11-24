package com.example.dubbing

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Pipeline processing stages for persistent resume & dependency-aware invalidation (Requirement 15).
 */
@Serializable
enum class DubbingStage(val order: Int, val label: String) {
    CREATED(0, "Project Created"),
    MEDIA_EXTRACTED(1, "1. Video & Audio Extracted"),
    TRANSCRIBED(2, "2. Japanese Transcribed"),
    SEGMENTED(3, "3. Dialogue Timestamps & Overlaps Segmented"),
    DIARIZED_AND_MAPPED(4, "4. Speakers & Anime Characters Identified"),
    TRANSLATED(5, "5. Roman Hinglish Translated & Dictionary Applied"),
    VOICE_GENERATED(6, "6. Character-Wise Voice Batch Generated"),
    BACKGROUND_SEPARATED(7, "7. Background Music & SFX Separated"),
    TIMELINE_AND_QC_READY(8, "8. Timeline Assembled & QC Inspected"),
    EXPORTED(9, "9. Final Dubbed Video Exported")
}

@Serializable
enum class DialogueProcessingStatus(val label: String) {
    PENDING_TRANSCRIPTION("Pending Transcription"),
    TRANSCRIBED("Transcribed"),
    UNCERTAIN_TRANSCRIPT("Uncertain Transcript (Retained)"),
    SEGMENTED("Segmented"),
    SPEAKER_ASSIGNED("Speaker Assigned"),
    TRANSLATED("Translated (Roman Hinglish)"),
    VOICE_GENERATED("Voice Generated"),
    TIMING_ADJUSTED("Timing Matched"),
    QC_PASSED("QC Passed"),
    QC_WARNING("QC Warning"),
    FAILED("Failed")
}

@Serializable
enum class DictionaryScope(val label: String) {
    CURRENT_DIALOGUE("Change Only Here (Current Dialogue)"),
    CURRENT_PROJECT("Change Everywhere in Project (Anime Dictionary)"),
    GLOBAL_DICTIONARY("Change Everywhere Globally (Global Dictionary)")
}

@Serializable
enum class QcIssueType(val severity: String, val title: String) {
    MISSING_DIALOGUE("HIGH", "Missing Dialogue Text"),
    INCORRECT_CHARACTER("HIGH", "Missing or Invalid Character Assignment"),
    DUPLICATE_GENERATED_AUDIO("MEDIUM", "Duplicate Generated Audio Reference"),
    INVALID_GLOBAL_NUMBERING("CRITICAL", "Non-Sequential Global Dialogue ID"),
    MISSING_AUDIO_REFERENCE("HIGH", "Missing Generated Audio Asset"),
    INVALID_TIMESTAMPS("HIGH", "Invalid Dialogue Timestamps"),
    UNEXPECTED_SILENCE("MEDIUM", "Unexpected Silence in Generated Audio"),
    OVERLAP_SYNC_DRIFT("MEDIUM", "Overlapping Dialogue Synchronization Drift"),
    TRANSLATION_INCONSISTENCY("MEDIUM", "Dictionary / Translation Inconsistency"),
    DEVANAGARI_DETECTED("HIGH", "Devanagari Detected (Must Be Roman Hinglish)"),
    PRONUNCIATION_RISK("LOW", "Pronunciation / Phonetic Length Risk"),
    VOICE_IDENTITY_MISMATCH("HIGH", "Voice Reference Mismatch Across Character Batch"),
    BACKGROUND_AUDIO_LOSS("HIGH", "Background Music / SFX Energy Loss"),
    MIXING_CLIPPING("HIGH", "Audio Clipping Detected (> -0.2 dBFS)"),
    DURATION_MISMATCH("MEDIUM", "Generated Audio Duration Drift vs Original Slot")
}

@Serializable
data class QcIssue(
    val id: String,
    val projectId: String,
    val globalDialogueId: String? = null,
    val type: QcIssueType,
    val description: String,
    val autoFixable: Boolean = true,
    val resolved: Boolean = false
)

/**
 * Persistent Project Entity (Requirement 2 & 15).
 * Original video URI/file is strictly read-only and never overwritten.
 */
@Entity(tableName = "dubbing_projects")
data class DubbingProjectEntity(
    @PrimaryKey val projectId: String,
    val animeTitle: String,
    val episodeTitle: String,
    val originalVideoPath: String,
    val extractedAudioWavPath: String = "",
    val backgroundMusicSfxWavPath: String = "",
    val separatedVocalStemWavPath: String = "",
    val mixedDubAudioWavPath: String = "",
    val exportedVideoPath: String = "",
    val durationMs: Long = 0L,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val frameRate: Float = 24f,
    val audioSampleRate: Int = 16000,
    val audioChannels: Int = 1,
    val currentStage: String = DubbingStage.CREATED.name,
    val lastCompletedStage: String = DubbingStage.CREATED.name,
    val statusMessage: String = "Ready",
    val lastError: String? = null,
    val qcPassed: Boolean = false,
    val qcSummaryJson: String = "[]",
    val separationMethod: String = "",
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Dialogue Record with Global Sequential Numbering (Requirements 4, 6, 9, 10, 12).
 *
 * CRITICAL (Requirement 6):
 * `globalSequenceNumber` is a strictly 1-based episode-wide counter (1, 2, 3, 4, ...).
 * `globalDialogueId` is formatted as `<characterId>-<3-digit globalSequenceNumber>`, e.g.:
 *   A-001 -> B-002 -> A-003 -> C-004 -> A-005 -> B-006
 * Separate per-character counters are NEVER used.
 */
@Entity(
    tableName = "dubbing_dialogues",
    indices = [
        Index(value = ["projectId", "globalSequenceNumber"], unique = true),
        Index(value = ["projectId", "globalDialogueId"], unique = true),
        Index(value = ["projectId", "characterId"])
    ]
)
data class DubbingDialogueEntity(
    @PrimaryKey val recordId: String, // "${projectId}_${globalSequenceNumber}"
    val projectId: String,
    val globalSequenceNumber: Int, // 1, 2, 3, ... global across entire episode
    val globalDialogueId: String, // e.g. "A-001", "B-002", "A-003", "C-004"
    val characterId: String, // e.g. "A", "B", "C"
    val speakerProfileId: String,
    val originalJapaneseText: String,
    val translatedHinglishText: String = "",
    val startTimeMs: Long,
    val endTimeMs: Long,
    val spokenDurationMs: Long, // Excludes internal silence > 250ms (Requirement 4)
    val internalPauseCount: Int = 0,
    val internalPauseTotalMs: Long = 0L,
    val emotion: String = "neutral", // Kept separate from speaker identity (Requirement 5)
    val isOverlapping: Boolean = false,
    val overlapsWithDialogueIds: String = "", // Comma-separated globalDialogueIds (Requirements 4, 5, 12)
    val trackIndex: Int = 0, // Independent parallel track index for simultaneous speakers
    val confidence: Float = 1.0f,
    val isUncertainTranscript: Boolean = false, // Retained, never silently discarded (Requirement 3)
    val generatedAudioPath: String = "",
    val generatedAudioDurationMs: Long = 0L,
    val speedMultiplierApplied: Float = 1.0f,
    val timingDriftMs: Long = 0L,
    val generationSignature: String = "", // Hash(translatedText + characterVoiceRef + emotion + speed) for cache reuse
    val processingStatus: String = DialogueProcessingStatus.TRANSCRIBED.name,
    val qcStatus: String = "PENDING",
    val errorDetail: String? = null,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    val slotDurationMs: Long get() = (endTimeMs - startTimeMs).coerceAtLeast(1L)

    companion object {
        fun formatGlobalDialogueId(characterId: String, globalSequenceNumber: Int): String {
            val cleanPrefix = characterId.trim().uppercase().ifBlank { "A" }
            return String.format(java.util.Locale.US, "%s-%03d", cleanPrefix, globalSequenceNumber)
        }
    }
}

/**
 * Persistent Speaker & Anime Character Voice Profile (Requirements 5 & 9).
 * Voice identity is invariant to emotion (angry/calm/happy/sad stay one character profile).
 */
@Entity(
    tableName = "dubbing_characters",
    indices = [Index(value = ["projectId", "characterId"], unique = true)]
)
data class DubbingCharacterEntity(
    @PrimaryKey val profileId: String, // "${projectId}_${characterId}"
    val projectId: String,
    val characterId: String, // "A", "B", "C", ...
    val characterName: String, // Visual/contextual anime character name or "Character A"
    val visualDescription: String = "",
    val personalityTraits: String = "natural anime character",
    val respectRegister: String = "tum/dost", // "aap", "tum", "tu" for Gemini contextual Hinglish
    val pitchMeanHz: Float = 165f,
    val spectralCentroidHz: Float = 1800f,
    val embeddingVectorJson: String = "[]", // 16-dim normalized acoustic speaker embedding
    val referenceSampleWavPath: String = "", // Extracted clean Japanese voice reference WAV for Chatterbox
    val referenceSampleDurationMs: Long = 0L,
    val referenceValidated: Boolean = false,
    val referenceQualityScore: Float = 0f,
    val basePitchMultiplier: Float = 1.0f,
    val baseSpeedMultiplier: Float = 1.0f,
    val sampleFrameTimestampMs: Long = 0L
)

/**
 * Two-Level Persistent Dictionary: Global (`projectId = "__GLOBAL__"`) and Anime-Specific (`projectId = <projectId>`)
 * (Requirement 8).
 */
@Entity(
    tableName = "dubbing_dictionary",
    indices = [Index(value = ["projectId", "sourceTerm"], unique = true)]
)
data class DubbingDictionaryEntryEntity(
    @PrimaryKey val entryId: String,
    val projectId: String, // "__GLOBAL__" for Global Dictionary, or specific projectId for Anime Dictionary
    val sourceTerm: String, // Japanese or English canonical term (e.g. "ゴブリン", "Goblin", "魔王", "Mana")
    val preferredHinglishTerm: String, // Approved Roman Hinglish term (e.g. "Goblin", "Demon King", "Mana")
    val category: String = "TERMINOLOGY", // CHARACTER_NAME, ATTACK, POWER, LOCATION, TERMINOLOGY
    val notes: String = "",
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    val isGlobal: Boolean get() = projectId == GLOBAL_PROJECT_ID

    companion object {
        const val GLOBAL_PROJECT_ID = "__GLOBAL__"
    }
}

/**
 * Persistent Translation Memory for approved Japanese -> Roman Hinglish dialogue pairs (Requirement 8).
 */
@Entity(
    tableName = "dubbing_translation_memory",
    indices = [Index(value = ["normalizedJapanese"], unique = false)]
)
data class DubbingTranslationMemoryEntity(
    @PrimaryKey val memoryId: String,
    val projectId: String,
    val characterId: String,
    val normalizedJapanese: String,
    val approvedHinglish: String,
    val emotion: String = "neutral",
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Dao
interface DubbingDao {
    // Projects
    @Query("SELECT * FROM dubbing_projects ORDER BY updatedAtMs DESC")
    fun getAllProjectsFlow(): Flow<List<DubbingProjectEntity>>

    @Query("SELECT * FROM dubbing_projects ORDER BY updatedAtMs DESC")
    suspend fun getAllProjects(): List<DubbingProjectEntity>

    @Query("SELECT * FROM dubbing_projects WHERE projectId = :projectId LIMIT 1")
    suspend fun getProjectById(projectId: String): DubbingProjectEntity?

    @Upsert
    suspend fun upsertProject(project: DubbingProjectEntity)

    @Query("DELETE FROM dubbing_projects WHERE projectId = :projectId")
    suspend fun deleteProject(projectId: String)

    // Dialogues
    @Query("SELECT * FROM dubbing_dialogues WHERE projectId = :projectId ORDER BY globalSequenceNumber ASC")
    fun getDialoguesForProjectFlow(projectId: String): Flow<List<DubbingDialogueEntity>>

    @Query("SELECT * FROM dubbing_dialogues WHERE projectId = :projectId ORDER BY globalSequenceNumber ASC")
    suspend fun getDialoguesForProject(projectId: String): List<DubbingDialogueEntity>

    @Query("SELECT * FROM dubbing_dialogues WHERE recordId = :recordId LIMIT 1")
    suspend fun getDialogueByRecordId(recordId: String): DubbingDialogueEntity?

    @Upsert
    suspend fun upsertDialogues(dialogues: List<DubbingDialogueEntity>)

    @Upsert
    suspend fun upsertDialogue(dialogue: DubbingDialogueEntity)

    @Query("DELETE FROM dubbing_dialogues WHERE projectId = :projectId")
    suspend fun deleteDialoguesForProject(projectId: String)

    // Characters
    @Query("SELECT * FROM dubbing_characters WHERE projectId = :projectId ORDER BY characterId ASC")
    fun getCharactersForProjectFlow(projectId: String): Flow<List<DubbingCharacterEntity>>

    @Query("SELECT * FROM dubbing_characters WHERE projectId = :projectId ORDER BY characterId ASC")
    suspend fun getCharactersForProject(projectId: String): List<DubbingCharacterEntity>

    @Upsert
    suspend fun upsertCharacters(characters: List<DubbingCharacterEntity>)

    @Upsert
    suspend fun upsertCharacter(character: DubbingCharacterEntity)

    @Query("DELETE FROM dubbing_characters WHERE projectId = :projectId")
    suspend fun deleteCharactersForProject(projectId: String)

    // Dictionary (Global + Project)
    @Query("SELECT * FROM dubbing_dictionary WHERE projectId = :projectId OR projectId = '__GLOBAL__' ORDER BY projectId DESC, sourceTerm ASC")
    fun getDictionaryForProjectFlow(projectId: String): Flow<List<DubbingDictionaryEntryEntity>>

    @Query("SELECT * FROM dubbing_dictionary WHERE projectId = :projectId OR projectId = '__GLOBAL__' ORDER BY projectId DESC, sourceTerm ASC")
    suspend fun getDictionaryForProject(projectId: String): List<DubbingDictionaryEntryEntity>

    @Query("SELECT * FROM dubbing_dictionary WHERE projectId = '__GLOBAL__' ORDER BY sourceTerm ASC")
    suspend fun getGlobalDictionary(): List<DubbingDictionaryEntryEntity>

    @Upsert
    suspend fun upsertDictionaryEntries(entries: List<DubbingDictionaryEntryEntity>)

    @Upsert
    suspend fun upsertDictionaryEntry(entry: DubbingDictionaryEntryEntity)

    @Query("DELETE FROM dubbing_dictionary WHERE entryId = :entryId")
    suspend fun deleteDictionaryEntry(entryId: String)

    // Translation Memory
    @Query("SELECT * FROM dubbing_translation_memory WHERE normalizedJapanese = :normalizedJp ORDER BY updatedAtMs DESC LIMIT 1")
    suspend fun findTranslationMemory(normalizedJp: String): DubbingTranslationMemoryEntity?

    @Query("SELECT * FROM dubbing_translation_memory ORDER BY updatedAtMs DESC LIMIT 200")
    fun getAllTranslationMemoryFlow(): Flow<List<DubbingTranslationMemoryEntity>>

    @Upsert
    suspend fun upsertTranslationMemory(entry: DubbingTranslationMemoryEntity)
}
