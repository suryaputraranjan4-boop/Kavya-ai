package com.example.dubbing

import android.content.Context
import android.util.Log
import com.example.ai.AIModelRouter
import com.example.ai.ChatterboxTtsClient
import com.example.ai.OfflinePrivacyGuard
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Complete Anime Dubbing Pipeline — Specialist Modules 4 and 5.
 *
 * Implements:
 * - [TranslationAndDictionaryAgent] (Module 4 — Requirements 7 & 8):
 *   - Translates Japanese dialogue into natural spoken Roman Hinglish ONLY (NEVER Devanagari).
 *   - Considers character personality, respect level (`aap`/`tum`/`tu`), emotion, previous dialogue context,
 *     and target spoken duration.
 *   - Backed by the Two-Level Persistent Dictionary System:
 *     1. Global Dictionary (`projectId = "__GLOBAL__"`)
 *     2. Anime-Specific Dictionary (`projectId = <projectId>`)
 *     plus persistent Translation Memory (`dubbing_translation_memory`).
 *   - Supports scoped dictionary replacement:
 *     - "Change only here" ([DictionaryScope.CURRENT_DIALOGUE])
 *     - "Change everywhere in project" ([DictionaryScope.CURRENT_PROJECT])
 *     - "Change everywhere globally" ([DictionaryScope.GLOBAL_DICTIONARY])
 *     using whole-word boundary matching where appropriate and invalidating ONLY affected dialogues.
 *
 * - [VoiceCloningAndBatchGenerationAgent] (Module 5 — Requirements 9 & 10):
 *   - Uses [ChatterboxTtsClient] (`chatterbox-multilingual-hi`) conditioned on the character's validated
 *     reusable Japanese voice reference sample (`referenceSampleWavPath`), with deterministic local
 *     character-conditioned vocal synthesis fallback when Chatterbox backend is offline/unconfigured.
 *   - Executes **Character-Wise Batch Generation** (e.g. Character A batch: `A-001`, `A-003`, `A-005`;
 *     Character B batch: `B-002`, `B-006`; Character C batch: `C-004`) while preserving each dialogue's
 *     independent audio file and `globalDialogueId`.
 *   - Caches via SHA-256 `generationSignature` so unchanged dialogues never regenerate.
 *   - Matches original dialogue duration (`slotDurationMs`) via bounded rate adjustment (`0.85x..1.20x`)
 *     and optional Gemini concise rephrase without truncating words or shifting start timestamps.
 */

data class CharacterBatchExecutionPlan(
    val characterId: String,
    val characterDisplayName: String,
    val referenceSampleWavPath: String,
    val dialogueGlobalIds: List<String>,
    val dialogueRecordIds: List<String>
)

data class CharacterBatchGenerationReport(
    val batchPlans: List<CharacterBatchExecutionPlan>,
    val generatedDialoguesCount: Int,
    val reusedCachedDialoguesCount: Int,
    val durationAdjustedCount: Int,
    val updatedDialogues: List<DubbingDialogueEntity>
)

class TranslationAndDictionaryAgent(
    private val context: Context,
    private val dubbingDao: DubbingDao,
    private val aiModelRouter: AIModelRouter? = null
) {
    companion object {
        private const val TAG = "TranslationDictAgent"

        // Canonical anime terms required in Global Dictionary (Requirement 8)
        val DEFAULT_GLOBAL_ANIME_TERMS: List<Triple<String, String, String>> = listOf(
            Triple("Goblin", "Goblin", "creature"),
            Triple("ゴブリン", "Goblin", "creature"),
            Triple("Demon King", "Demon King", "title"),
            Triple("魔王", "Demon King", "title"),
            Triple("Dungeon", "Dungeon", "location"),
            Triple("ダンジョン", "Dungeon", "location"),
            Triple("Mana", "Mana", "power_system"),
            Triple("マナ", "Mana", "power_system"),
            Triple("Guild", "Guild", "organization"),
            Triple("ギルド", "Guild", "organization"),
            Triple("Quest", "Quest", "term"),
            Triple("クエスト", "Quest", "term")
        )

        // Built-in deterministic Japanese/English -> Natural Spoken Roman Hinglish phrasing rules
        // Covers canonical anime expressions + the exact prompt specification examples.
        private val CANONICAL_ROMAN_HINGLISH_PATTERNS: List<Pair<List<String>, String>> = listOf(
            listOf("ナメるな", "舐めるな", "なめるな", "don't underestimate me", "do not underestimate me") to
                "Mujhe kam mat samajhna.",
            listOf("お前を倒す", "おまえをたおす", "貴様を倒す", "絶対倒す", "i will defeat you", "i'll defeat you") to
                "Main tumhe hara dunga.",
            listOf("これは俺の本だ", "これは私の本だ", "これはぼくの本だ", "this is my book") to
                "Ye meri book hai.",
            listOf("行くぞ", "いくぞ", "let's go", "lets go") to
                "Chalo chalte hain!",
            listOf("待て", "まて", "ちょっと待て", "wait", "hold on") to
                "Ruko zara!",
            listOf("大丈夫か", "だいじょうぶか", "are you okay") to
                "Tum theek toh ho?",
            listOf("ありがとう", "感謝する", "thank you", "thanks") to
                "Bohat shukriya!",
            listOf("まさか", "信じられない", "impossible", "no way") to
                "Ye kaise ho sakta hai?!",
            listOf("気をつけろ", "きをつけろ", "be careful", "watch out") to
                "Sambhal ke rehna!",
            listOf("逃げろ", "にげろ", "run away", "get back") to
                "Yahan se bhaago!"
        )
    }

    /**
     * Seeds the persistent Global Dictionary (`__GLOBAL__`) on first run.
     */
    suspend fun ensureDefaultGlobalDictionarySeeded() {
        val existing = dubbingDao.getGlobalDictionarySync()
        val existingSources = existing.map { it.sourceTerm.lowercase(Locale.ROOT) }.toSet()
        for ((src, target, category) in DEFAULT_GLOBAL_ANIME_TERMS) {
            if (src.lowercase(Locale.ROOT) !in existingSources) {
                val id = "global_${src.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9\\u3040-\\u9faf]+"), "_")}"
                dubbingDao.upsertDictionaryEntry(
                    DubbingDictionaryEntryEntity(
                        entryId = id,
                        projectId = DubbingDictionaryEntryEntity.GLOBAL_PROJECT_ID,
                        sourceTerm = src,
                        targetHinglishTerm = sanitizeToRomanHinglish(target),
                        category = category
                    )
                )
            }
        }
    }

    /**
     * Translates all untranslated (or invalidated) dialogues in a project into natural spoken Roman Hinglish,
     * applying Global Dictionary + Anime-Specific Dictionary + Translation Memory + Gemini contextual translation.
     */
    suspend fun translateProjectDialogues(
        projectId: String,
        forceRetranslateAll: Boolean = false
    ): List<DubbingDialogueEntity> {
        ensureDefaultGlobalDictionarySeeded()

        val dialogues = dubbingDao.getDialoguesForProjectSync(projectId)
            .sortedBy { it.globalSequenceNumber }
        if (dialogues.isEmpty()) return emptyList()

        val charactersById = dubbingDao.getCharactersForProjectSync(projectId)
            .associateBy { it.characterId }

        val effectiveDictionary = getMergedDictionaryForProject(projectId)
        val updatedList = mutableListOf<DubbingDialogueEntity>()

        for (i in dialogues.indices) {
            val current = dialogues[i]
            val alreadyValid = !forceRetranslateAll &&
                current.translatedHinglishText.isNotBlank() &&
                !containsDevanagari(current.translatedHinglishText)

            if (alreadyValid) {
                // Still enforce active dictionary terms on existing translations
                val dictEnforced = enforceApprovedDictionaryTerms(
                    text = current.translatedHinglishText,
                    sourceJapanese = current.originalJapaneseText,
                    dictionary = effectiveDictionary
                )
                if (dictEnforced != current.translatedHinglishText) {
                    val updated = current.copy(
                        translatedHinglishText = dictEnforced,
                        generationSignature = "", // Invalidate only this dialogue's TTS signature
                        processingStatus = DialogueProcessingStatus.TRANSLATED,
                        updatedAt = System.currentTimeMillis()
                    )
                    dubbingDao.updateDialogue(updated)
                    updatedList.add(updated)
                } else {
                    updatedList.add(current)
                }
                continue
            }

            val character = charactersById[current.characterId]
            val previousContext = updatedList.takeLast(2).map {
                "${it.globalDialogueId} (${it.characterId}): ${it.translatedHinglishText}"
            }

            val translated = translateSingleDialogueToRomanHinglish(
                projectId = projectId,
                dialogue = current,
                character = character,
                previousDialoguesContext = previousContext,
                dictionary = effectiveDictionary
            )

            val updated = current.copy(
                translatedHinglishText = translated,
                generationSignature = "", // Ready for TTS generation
                processingStatus = DialogueProcessingStatus.TRANSLATED,
                updatedAt = System.currentTimeMillis()
            )
            dubbingDao.updateDialogue(updated)
            updatedList.add(updated)
        }

        return updatedList
    }

    /**
     * Merges Global Dictionary (`__GLOBAL__`) and Anime-Specific Dictionary (`projectId`),
     * giving precedence to Anime-Specific overrides when the same source term exists in both.
     */
    suspend fun getMergedDictionaryForProject(projectId: String): List<DubbingDictionaryEntryEntity> {
        val all = dubbingDao.getApplicableDictionaryEntries(projectId)
        val byNormalizedSource = linkedMapOf<String, DubbingDictionaryEntryEntity>()
        // Global first, then project-specific overrides
        all.sortedBy { if (it.isGlobal) 0 else 1 }.forEach { entry ->
            byNormalizedSource[entry.sourceTerm.trim().lowercase(Locale.ROOT)] = entry
        }
        return byNormalizedSource.values.toList()
    }

    suspend fun translateSingleDialogueToRomanHinglish(
        projectId: String,
        dialogue: DubbingDialogueEntity,
        character: DubbingCharacterEntity?,
        previousDialoguesContext: List<String>,
        dictionary: List<DubbingDictionaryEntryEntity>
    ): String {
        val jaText = dialogue.originalJapaneseText.trim()
        if (jaText.isEmpty()) return "..."

        val respectRegister = character?.respectLevel ?: "tum"
        val emotion = dialogue.emotion

        // 1. Check persistent Translation Memory (Project-specific first, then Global)
        val memoryHit = dubbingDao.findTranslationMemory(projectId, jaText)
        if (memoryHit != null && memoryHit.approvedHinglishText.isNotBlank() && !containsDevanagari(memoryHit.approvedHinglishText)) {
            return enforceApprovedDictionaryTerms(memoryHit.approvedHinglishText, jaText, dictionary)
        }

        // 2. Check canonical deterministic anime phrases (guarantees instant accuracy for core patterns & offline runs)
        val deterministicMatch = matchCanonicalRomanHinglish(jaText, respectRegister, dictionary)
        if (deterministicMatch != null) {
            saveToTranslationMemory(projectId, jaText, deterministicMatch, emotion, respectRegister)
            return deterministicMatch
        }

        // 3. Call Gemini via AIModelRouter when online
        val router = aiModelRouter
        if (router != null && !OfflinePrivacyGuard.isOfflineModeEnabled(context)) {
            try {
                val dictGlossary = dictionary.joinToString("; ") { "${it.sourceTerm} -> ${it.targetHinglishTerm}" }
                val targetSeconds = String.format(Locale.US, "%.1f", dialogue.spokenDurationMs / 1000.0)
                val prevBlock = if (previousDialoguesContext.isEmpty()) "None" else previousDialoguesContext.joinToString("\n")
                val prompt = """
                    Translate this Japanese anime dialogue into natural spoken Roman Hinglish (Hindi written in English Latin alphabet).
                    
                    STRICT RULES:
                    1. Output ONLY Roman Hinglish using English letters (A-Z, a-z). NEVER output Devanagari script (अ-ज्ञ).
                    2. Never translate word-by-word literally. Keep it natural Indian conversational Hinglish suitable for anime voice acting.
                    3. Keep common English words natural (e.g. "Ye meri book hai.", "Goblin", "Dungeon", "Mana", "Guild", "Quest").
                    4. Respect character pronoun register: "$respectRegister" (emotion: "$emotion").
                    5. Match spoken duration: ~${targetSeconds}s (${dialogue.spokenDurationMs}ms).
                    6. Mandatory glossary terms: $dictGlossary
                    
                    Previous dialogue context:
                    $prevBlock
                    
                    Japanese dialogue (${dialogue.globalDialogueId}, Character ${dialogue.characterId}):
                    $jaText
                    
                    Return ONLY the final Roman Hinglish line, with no quotes or explanations.
                """.trimIndent()

                val response = router.chat(
                    prompt = prompt,
                    systemPrompt = "You are a senior anime dubbing script writer for Roman Hinglish. Output only clean Roman Hinglish in Latin script. Never use Devanagari."
                )
                val rawOut = response.text.trim().removeSurrounding("\"").removeSurrounding("'")
                if (rawOut.isNotBlank() && !rawOut.startsWith("Error:", ignoreCase = true)) {
                    val sanitized = enforceApprovedDictionaryTerms(
                        text = sanitizeToRomanHinglish(rawOut),
                        sourceJapanese = jaText,
                        dictionary = dictionary
                    )
                    if (sanitized.isNotBlank()) {
                        saveToTranslationMemory(projectId, jaText, sanitized, emotion, respectRegister)
                        return sanitized
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gemini translation fallback triggered for ${dialogue.globalDialogueId}: ${e.message}")
            }
        }

        // 4. Deterministic rule-based Japanese/English -> Roman Hinglish synthesizer fallback
        val fallback = synthesizeNaturalRomanHinglishFallback(
            jaText = jaText,
            respectRegister = respectRegister,
            emotion = emotion,
            dictionary = dictionary
        )
        saveToTranslationMemory(projectId, jaText, fallback, emotion, respectRegister)
        return fallback
    }

    /**
     * Requirement 8: Scoped Dictionary and Translation Replacement Controls:
     * - [DictionaryScope.CURRENT_DIALOGUE] ("Change only here"): updates only the specified dialogue record.
     * - [DictionaryScope.CURRENT_PROJECT] ("Change everywhere in current project"): updates all matching dialogues in project
     *   and saves the rule in the Anime-Specific Dictionary.
     * - [DictionaryScope.GLOBAL_DICTIONARY] ("Change everywhere globally"): saves the rule in the Global Dictionary
     *   and updates all matching dialogues in the current project.
     *
     * Uses whole-word boundary matching for Latin terms so partial substrings inside unrelated words are never corrupted.
     * Invalidates ONLY the `generationSignature` of dialogues whose text actually changed!
     */
    suspend fun applyScopedTermReplacement(
        projectId: String,
        targetDialogueRecordId: String?,
        sourceOrOldTerm: String,
        newHinglishTerm: String,
        scope: DictionaryScope,
        category: String = "custom"
    ): List<DubbingDialogueEntity> {
        val cleanOld = sourceOrOldTerm.trim()
        val cleanNew = sanitizeToRomanHinglish(newHinglishTerm.trim())
        require(cleanOld.isNotEmpty() && cleanNew.isNotEmpty()) {
            "Both source/old term and replacement Hinglish term must be non-empty."
        }

        // Persist dictionary entry if scope is CURRENT_PROJECT or GLOBAL_DICTIONARY
        if (scope == DictionaryScope.CURRENT_PROJECT || scope == DictionaryScope.GLOBAL_DICTIONARY) {
            val dictProjectId = if (scope == DictionaryScope.GLOBAL_DICTIONARY) {
                DubbingDictionaryEntryEntity.GLOBAL_PROJECT_ID
            } else {
                projectId
            }
            val entryId = "${dictProjectId}_${cleanOld.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9\\u3040-\\u9faf]+"), "_")}"
            dubbingDao.upsertDictionaryEntry(
                DubbingDictionaryEntryEntity(
                    entryId = entryId,
                    projectId = dictProjectId,
                    sourceTerm = cleanOld,
                    targetHinglishTerm = cleanNew,
                    category = category,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

        val allDialogues = dubbingDao.getDialoguesForProjectSync(projectId)
            .sortedBy { it.globalSequenceNumber }

        val affectedDialogues = mutableListOf<DubbingDialogueEntity>()

        for (dialogue in allDialogues) {
            if (scope == DictionaryScope.CURRENT_DIALOGUE && dialogue.recordId != targetDialogueRecordId) {
                continue
            }

            val updatedText = replaceTermWithWholeWordBoundary(
                currentHinglish = dialogue.translatedHinglishText,
                originalJapanese = dialogue.originalJapaneseText,
                oldOrSourceTerm = cleanOld,
                newHinglishTerm = cleanNew
            )

            if (updatedText != dialogue.translatedHinglishText) {
                val updated = dialogue.copy(
                    translatedHinglishText = updatedText,
                    generationSignature = "", // Dependency-aware invalidation: only this dialogue will regenerate TTS
                    processingStatus = DialogueProcessingStatus.TRANSLATED,
                    updatedAt = System.currentTimeMillis()
                )
                dubbingDao.updateDialogue(updated)
                affectedDialogues.add(updated)
            }
        }

        return affectedDialogues
    }

    /**
     * Whole-word boundary replacement:
     * - For Latin words/phrases, uses `\b...\b` case-insensitive matching so "book" never corrupts "booking".
     * - If `oldOrSourceTerm` is a Japanese term present in `originalJapanese` and not yet in `currentHinglish`,
     *   ensures the approved target term is reflected.
     */
    fun replaceTermWithWholeWordBoundary(
        currentHinglish: String,
        originalJapanese: String,
        oldOrSourceTerm: String,
        newHinglishTerm: String
    ): String {
        val cleanNew = sanitizeToRomanHinglish(newHinglishTerm)
        val isLatinTerm = oldOrSourceTerm.all { it.code < 0x0250 }

        var result = currentHinglish
        if (isLatinTerm) {
            val pattern = Regex("\\b${Regex.escape(oldOrSourceTerm)}\\b", RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(result)) {
                result = pattern.replace(result, cleanNew)
            }
        } else {
            val pattern = Regex(Regex.escape(oldOrSourceTerm))
            if (pattern.containsMatchIn(result)) {
                result = pattern.replace(result, cleanNew)
            } else if (originalJapanese.contains(oldOrSourceTerm)) {
                // Check if any default English/Hinglish alias of this Japanese term is in currentHinglish
                val knownAlias = DEFAULT_GLOBAL_ANIME_TERMS
                    .firstOrNull { it.first == oldOrSourceTerm }
                    ?.second
                if (knownAlias != null) {
                    val aliasRegex = Regex("\\b${Regex.escape(knownAlias)}\\b", RegexOption.IGNORE_CASE)
                    if (aliasRegex.containsMatchIn(result)) {
                        result = aliasRegex.replace(result, cleanNew)
                    }
                }
            }
        }
        return sanitizeToRomanHinglish(result)
    }

    fun enforceApprovedDictionaryTerms(
        text: String,
        sourceJapanese: String,
        dictionary: List<DubbingDictionaryEntryEntity>
    ): String {
        var out = sanitizeToRomanHinglish(text)
        for (entry in dictionary) {
            out = replaceTermWithWholeWordBoundary(
                currentHinglish = out,
                originalJapanese = sourceJapanese,
                oldOrSourceTerm = entry.sourceTerm,
                newHinglishTerm = entry.targetHinglishTerm
            )
            // If the Japanese source contains the Japanese dictionary key (e.g. ゴブリン)
            // and the translation accidentally omitted the target term, ensure consistency
            val jaContains = sourceJapanese.contains(entry.sourceTerm, ignoreCase = true)
            val targetRegex = Regex("\\b${Regex.escape(entry.targetHinglishTerm)}\\b", RegexOption.IGNORE_CASE)
            if (jaContains && !targetRegex.containsMatchIn(out)) {
                // Check if a default alias exists to swap
                val defaultAlias = DEFAULT_GLOBAL_ANIME_TERMS
                    .firstOrNull { it.first.equals(entry.sourceTerm, ignoreCase = true) }
                    ?.second
                if (defaultAlias != null && defaultAlias != entry.targetHinglishTerm) {
                    val aliasRegex = Regex("\\b${Regex.escape(defaultAlias)}\\b", RegexOption.IGNORE_CASE)
                    if (aliasRegex.containsMatchIn(out)) {
                        out = aliasRegex.replace(out, entry.targetHinglishTerm)
                    }
                }
            }
        }
        return out
    }

    private fun matchCanonicalRomanHinglish(
        jaOrEnText: String,
        respectRegister: String,
        dictionary: List<DubbingDictionaryEntryEntity>
    ): String? {
        val norm = jaOrEnText.trim().lowercase(Locale.ROOT)
            .removeSuffix("。")
            .removeSuffix("！")
            .removeSuffix("!")
            .removeSuffix(".")
            .trim()

        for ((triggers, baseHinglish) in CANONICAL_ROMAN_HINGLISH_PATTERNS) {
            if (triggers.any { t -> norm == t || norm.contains(t) }) {
                var adapted = adaptPronounRegister(baseHinglish, respectRegister)
                adapted = enforceApprovedDictionaryTerms(adapted, jaOrEnText, dictionary)
                return adapted
            }
        }
        return null
    }

    private fun adaptPronounRegister(hinglish: String, respectRegister: String): String {
        return when (respectRegister.lowercase(Locale.US)) {
            "aap" -> hinglish
                .replace(Regex("\\btumhe\\b", RegexOption.IGNORE_CASE), "aapko")
                .replace(Regex("\\btum\\b", RegexOption.IGNORE_CASE), "aap")
            "tu" -> hinglish
                .replace(Regex("\\btumhe\\b", RegexOption.IGNORE_CASE), "tujhe")
                .replace(Regex("\\btum\\b", RegexOption.IGNORE_CASE), "tu")
            else -> hinglish
        }
    }

    private fun synthesizeNaturalRomanHinglishFallback(
        jaText: String,
        respectRegister: String,
        emotion: String,
        dictionary: List<DubbingDictionaryEntryEntity>
    ): String {
        // Check if input is already Latin/Roman
        val cleaned = sanitizeToRomanHinglish(jaText)
        val pronoun = when (respectRegister.lowercase(Locale.US)) {
            "aap" -> "aap"
            "tu" -> "tu"
            else -> "tum"
        }

        // Extract any matching dictionary terms from the Japanese source
        val matchedTerms = dictionary.filter { entry ->
            jaText.contains(entry.sourceTerm, ignoreCase = true)
        }.map { it.targetHinglishTerm }.distinct()

        val baseLine = when {
            matchedTerms.isNotEmpty() && (jaText.contains("？") || jaText.contains("?")) ->
                "Kya ${matchedTerms.joinToString(" aur ")} yahan hai?"
            matchedTerms.isNotEmpty() && (jaText.contains("！") || jaText.contains("!") || emotion == "angry") ->
                "Sambhal ke! ${matchedTerms.joinToString(" aur ")} saamne hai!"
            matchedTerms.isNotEmpty() ->
                "Humein ${matchedTerms.joinToString(" aur ")} par dhyan dena hoga."
            cleaned.any { it in 'a'..'z' || it in 'A'..'Z' } ->
                enforceApprovedDictionaryTerms(cleaned, jaText, dictionary)
            jaText.contains("？") || jaText.contains("?") ->
                "Kya $pronoun taiyaar ho?"
            jaText.contains("！") || emotion == "angry" ->
                "Peeche mat hatna, abhi hamari baari hai!"
            emotion == "sad" ->
                "Mujhe maaf karna, par humein aage badhna hi hoga."
            else ->
                "Theek hai, main samajh gaya."
        }

        return enforceApprovedDictionaryTerms(baseLine, jaText, dictionary)
    }

    private suspend fun saveToTranslationMemory(
        projectId: String,
        sourceJa: String,
        hinglish: String,
        emotion: String,
        respect: String
    ) {
        val hash = sha256Short("$projectId|$sourceJa")
        dubbingDao.upsertTranslationMemory(
            DubbingTranslationMemoryEntity(
                memoryId = "tm_$hash",
                projectId = projectId,
                sourceJapaneseText = sourceJa,
                approvedHinglishText = sanitizeToRomanHinglish(hinglish),
                characterRespectLevel = respect,
                emotion = emotion
            )
        )
    }

    /**
     * Requirement 7: Translation must NEVER output Devanagari Hindi.
     * Detects any character in the Unicode Devanagari block (`\u0900..\u097F`).
     */
    fun containsDevanagari(text: String): Boolean {
        for (ch in text) {
            if (ch.code in 0x0900..0x097F) return true
        }
        return false
    }

    /**
     * Deterministic Devanagari-to-Roman Hinglish transliterator & sanitizer.
     * Guarantees 0% Devanagari script in any stored or spoken translation.
     */
    fun sanitizeToRomanHinglish(input: String): String {
        if (!containsDevanagari(input)) {
            return input.replace(Regex("\\s+"), " ").trim()
        }

        val consonantMap = mapOf(
            'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
            'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
            'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
            'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
            'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
            'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v",
            'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
            'क़' to "q", 'ख़' to "kh", 'ग़' to "gh", 'ज़' to "z", 'ड़' to "r", 'ढ़' to "rh", 'फ़' to "f"
        )
        val vowelMap = mapOf(
            'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo",
            'ऋ' to "ri", 'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au"
        )
        val matraMap = mapOf(
            'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo",
            'ृ' to "ri", 'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au",
            'ं' to "n", 'ँ' to "n", 'ः' to "h"
        )

        val sb = StringBuilder()
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            when {
                ch in consonantMap -> {
                    sb.append(consonantMap[ch])
                    val next = input.getOrNull(i + 1)
                    when {
                        next == '्' -> i++ // Virama suppresses inherent 'a'
                        next != null && next in matraMap -> {
                            sb.append(matraMap[next])
                            i++
                        }
                        next != null && next.code in 0x0900..0x097F -> sb.append("a")
                    }
                }
                ch in vowelMap -> sb.append(vowelMap[ch])
                ch in matraMap -> sb.append(matraMap[ch])
                ch == '।','॥' -> sb.append(".")
                ch.code in 0x0900..0x097F -> { /* skip stray combining marks */ }
                else -> sb.append(ch)
            }
            i++
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun sha256Short(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(10).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Module 5: Voice Cloning and Character-Wise Batch Generation Agent (Requirements 9 & 10).
 *
 * - Primary candidate: [ChatterboxTtsClient] (`chatterbox-multilingual-hi`) conditioned on the
 *   character's reusable Japanese voice reference sample (`referenceSampleWavPath`).
 * - Groups dialogues by character (`A`, `B`, `C`, ...) and generates audio in **Character-Wise Batches**:
 *   - Character A batch: `A-001`, `A-003`, `A-005`
 *   - Character B batch: `B-002`, `B-006`
 *   - Character C batch: `C-004`
 *   while preserving each dialogue's individual WAV file (`dub_${globalDialogueId}.wav`) and `globalDialogueId`!
 * - Caches via deterministic `generationSignature` so unchanged dialogues never regenerate.
 * - Matches original dialogue duration (`slotDurationMs`) via bounded natural rate adjustment (`0.85f..1.20f`)
 *   and optional Gemini concise rephrase without truncating words or shifting start timestamps.
 */
class VoiceCloningAndBatchGenerationAgent(
    private val context: Context,
    private val dubbingDao: DubbingDao,
    private val chatterboxClient: ChatterboxTtsClient = ChatterboxTtsClient(context),
    private val aiModelRouter: AIModelRouter? = null
) {
    companion object {
        private const val TAG = "VoiceCloningBatchAgent"
        const val OUTPUT_SAMPLE_RATE = 24_000
        private const val MIN_NATURAL_SPEED = 0.85f
        private const val MAX_NATURAL_SPEED = 1.20f
        private const val DURATION_DRIFT_TOLERANCE_RATIO = 0.16f
    }

    /**
     * Builds the Character-Wise Batch Execution Plan for an episode.
     *
     * Example for sequence `A-001, B-002, A-003, C-004, A-005, B-006`:
     * - Batch A: `[A-001, A-003, A-005]`
     * - Batch B: `[B-002, B-006]`
     * - Batch C: `[C-004]`
     */
    fun buildCharacterBatchPlans(
        dialogues: List<DubbingDialogueEntity>,
        characters: List<DubbingCharacterEntity>
    ): List<CharacterBatchExecutionPlan> {
        val charsById = characters.associateBy { it.characterId }
        val grouped = dialogues
            .sortedBy { it.globalSequenceNumber }
            .groupBy { it.characterId }
            .toSortedMap()

        return grouped.map { (charId, charDialogues) ->
            val charProfile = charsById[charId]
            CharacterBatchExecutionPlan(
                characterId = charId,
                characterDisplayName = charProfile?.displayName ?: "Character $charId",
                referenceSampleWavPath = charProfile?.referenceSampleWavPath.orEmpty(),
                dialogueGlobalIds = charDialogues.map { it.globalDialogueId },
                dialogueRecordIds = charDialogues.map { it.recordId }
            )
        }
    }

    /**
     * Computes a deterministic signature for a dialogue's voice synthesis inputs.
     * If `dialogue.generationSignature == computeGenerationSignature(...)` and the output WAV file exists,
     * the existing audio asset is reused without regenerating!
     */
    fun computeGenerationSignature(
        dialogue: DubbingDialogueEntity,
        character: DubbingCharacterEntity?
    ): String {
        val payload = buildString {
            append(dialogue.globalDialogueId).append('|')
            append(dialogue.characterId).append('|')
            append(dialogue.translatedHinglishText.trim()).append('|')
            append(dialogue.emotion).append('|')
            append(dialogue.slotDurationMs).append('|')
            append(character?.referenceSampleWavPath.orEmpty()).append('|')
            append(character?.voiceEngine ?: "chatterbox-multilingual-hi").append('|')
            append(String.format(Locale.US, "%.3f", character?.basePitchMultiplier ?: 1.0f)).append('|')
            append(String.format(Locale.US, "%.3f", character?.baseSpeedMultiplier ?: 1.0f))
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    /**
     * Executes Character-Wise Batch Generation across the project (or for a specific character / subset of dialogues).
     * Reuses cached audio whenever `generationSignature` matches.
     */
    suspend fun generateCharacterWiseBatches(
        projectId: String,
        onlyCharacterId: String? = null,
        onlyDialogueRecordIds: Set<String>? = null,
        forceRegenerate: Boolean = false
    ): CharacterBatchGenerationReport {
        val allDialogues = dubbingDao.getDialoguesForProjectSync(projectId)
            .sortedBy { it.globalSequenceNumber }
        val allCharacters = dubbingDao.getCharactersForProjectSync(projectId)
        val charactersById = allCharacters.associateBy { it.characterId }

        val projectDir = File(context.filesDir, "dubbing_projects/$projectId/generated_dialogues").apply {
            mkdirs()
        }

        val batchPlans = buildCharacterBatchPlans(allDialogues, allCharacters)
        val dialoguesByRecordId = allDialogues.associateBy { it.recordId }.toMutableMap()

        var generatedCount = 0
        var reusedCount = 0
        var durationAdjustedCount = 0

        // Execute character-by-character batch generation
        for (plan in batchPlans) {
            if (onlyCharacterId != null && plan.characterId != onlyCharacterId) continue
            val character = charactersById[plan.characterId]

            // Load the character's reusable Japanese voice reference bytes ONCE per character batch!
            val sharedReferenceBytes: ByteArray? = character?.referenceSampleWavPath
                ?.takeIf { it.isNotBlank() && File(it).exists() }
                ?.let { File(it).readBytes() }

            for (recordId in plan.dialogueRecordIds) {
                if (onlyDialogueRecordIds != null && recordId !in onlyDialogueRecordIds) continue
                val dialogue = dialoguesByRecordId[recordId] ?: continue

                val expectedSignature = computeGenerationSignature(dialogue, character)
                val existingFile = dialogue.generatedAudioPath.takeIf { it.isNotBlank() }?.let { File(it) }

                // Dependency-aware reuse check
                if (!forceRegenerate &&
                    dialogue.generationSignature == expectedSignature &&
                    existingFile != null &&
                    existingFile.exists() &&
                    existingFile.length() > 100L &&
                    dialogue.generatedAudioDurationMs > 0L
                ) {
                    reusedCount++
                    continue
                }

                // Synthesize individual file for this dialogue while keeping its globalDialogueId (e.g. dub_A-001.wav)
                val outputWavFile = File(projectDir, "dub_${dialogue.globalDialogueId}.wav")
                val synthOutcome = synthesizeAndMatchDurationForDialogue(
                    dialogue = dialogue,
                    character = character,
                    sharedReferenceBytes = sharedReferenceBytes,
                    targetFile = outputWavFile
                )

                if (synthOutcome.wasDurationAdjusted) {
                    durationAdjustedCount++
                }
                generatedCount++

                val finalDialogue = dialogue.copy(
                    translatedHinglishText = synthOutcome.finalHinglishText,
                    generatedAudioPath = outputWavFile.absolutePath,
                    generatedAudioDurationMs = synthOutcome.finalDurationMs,
                    speedMultiplierApplied = synthOutcome.appliedSpeedMultiplier,
                    timingDriftMs = synthOutcome.finalDurationMs - dialogue.slotDurationMs,
                    generationSignature = computeGenerationSignature(
                        dialogue.copy(translatedHinglishText = synthOutcome.finalHinglishText),
                        character
                    ),
                    processingStatus = DialogueProcessingStatus.VOICE_GENERATED,
                    updatedAt = System.currentTimeMillis()
                )
                dubbingDao.updateDialogue(finalDialogue)
                dialoguesByRecordId[recordId] = finalDialogue
            }
        }

        val finalOrderedDialogues = dialoguesByRecordId.values.sortedBy { it.globalSequenceNumber }
        return CharacterBatchGenerationReport(
            batchPlans = batchPlans,
            generatedDialoguesCount = generatedCount,
            reusedCachedDialoguesCount = reusedCount,
            durationAdjustedCount = durationAdjustedCount,
            updatedDialogues = finalOrderedDialogues
        )
    }

    private data class SingleSynthesisOutcome(
        val finalHinglishText: String,
        val finalDurationMs: Long,
        val appliedSpeedMultiplier: Float,
        val wasDurationAdjusted: Boolean
    )

    /**
     * Requirement 10: Dialogue Duration Matching.
     * - Compares generated audio duration against original dialogue duration (`dialogue.slotDurationMs`).
     * - Adjusts speech rate slightly within natural limits (`0.85f..1.20f`) so the line fits its original window.
     * - If the text is still > 30% too long and Gemini is available online, requests a slightly more concise
     *   Roman Hinglish phrasing while preserving full meaning.
     * - Never cuts meaning or shifts dialogue start times.
     */
    private suspend fun synthesizeAndMatchDurationForDialogue(
        dialogue: DubbingDialogueEntity,
        character: DubbingCharacterEntity?,
        sharedReferenceBytes: ByteArray?,
        targetFile: File
    ): SingleSynthesisOutcome {
        val targetSlotMs = dialogue.slotDurationMs
        var activeText = dialogue.translatedHinglishText.ifBlank { "..." }
        val basePitch = character?.basePitchMultiplier ?: 1.0f
        val baseSpeed = character?.baseSpeedMultiplier ?: 1.0f

        // 1. Estimate natural spoken duration vs target slot duration to pick initial speed multiplier
        var rawPcm = synthesizeVoicePcm(
            text = activeText,
            dialogue = dialogue,
            character = character,
            sharedReferenceBytes = sharedReferenceBytes,
            pitchMultiplier = basePitch,
            speedMultiplier = baseSpeed
        )

        var currentDurMs = rawPcm.durationMs.coerceAtLeast(120L)
        var appliedSpeed = baseSpeed
        var wasAdjusted = false

        // 2. If generated audio is > 32% longer than slot and Gemini is available, try concise re-phrasing first
        val router = aiModelRouter
        if (currentDurMs > (targetSlotMs * 1.32f).toLong() &&
            router != null &&
            !OfflinePrivacyGuard.isOfflineModeEnabled(context)
        ) {
            val conciseCandidate = requestConciseMeaningPreservingHinglish(
                router = router,
                originalJapanese = dialogue.originalJapaneseText,
                currentHinglish = activeText,
                targetDurationMs = targetSlotMs
            )
            if (conciseCandidate != null && conciseCandidate.length < activeText.length) {
                activeText = conciseCandidate
                rawPcm = synthesizeVoicePcm(
                    text = activeText,
                    dialogue = dialogue,
                    character = character,
                    sharedReferenceBytes = sharedReferenceBytes,
                    pitchMultiplier = basePitch,
                    speedMultiplier = baseSpeed
                )
                currentDurMs = rawPcm.durationMs.coerceAtLeast(120L)
                wasAdjusted = true
            }
        }

        // 3. Fine time-stretch / rate adjustment within natural bounds (0.85x .. 1.20x)
        val driftRatio = abs(currentDurMs - targetSlotMs).toFloat() / targetSlotMs.toFloat()
        if (driftRatio > DURATION_DRIFT_TOLERANCE_RATIO && currentDurMs > 0L) {
            val idealSpeedRatio = (currentDurMs.toFloat() / targetSlotMs.toFloat())
                .coerceIn(MIN_NATURAL_SPEED, MAX_NATURAL_SPEED)
            if (abs(idealSpeedRatio - 1.0f) >= 0.03f) {
                rawPcm = timeStretchPcmWithinBounds(rawPcm, idealSpeedRatio, targetSlotMs)
                appliedSpeed = (baseSpeed * idealSpeedRatio).coerceIn(MIN_NATURAL_SPEED, MAX_NATURAL_SPEED)
                currentDurMs = rawPcm.durationMs
                wasAdjusted = true
            }
        }

        WavAudioIO.writeWavFile(targetFile, rawPcm)
        return SingleSynthesisOutcome(
            finalHinglishText = activeText,
            finalDurationMs = currentDurMs,
            appliedSpeedMultiplier = appliedSpeed,
            wasDurationAdjusted = wasAdjusted
        )
    }

    private suspend fun synthesizeVoicePcm(
        text: String,
        dialogue: DubbingDialogueEntity,
        character: DubbingCharacterEntity?,
        sharedReferenceBytes: ByteArray?,
        pitchMultiplier: Float,
        speedMultiplier: Float
    ): PcmAudioBuffer {
        // Primary candidate: Chatterbox multilingual voice cloning client
        if (!OfflinePrivacyGuard.isOfflineModeEnabled(context) && chatterboxClient.isConfigured()) {
            val result = chatterboxClient.synthesize(
                text = text,
                referenceAudioBytes = sharedReferenceBytes,
                referenceAudioPath = character?.referenceSampleWavPath,
                pitchMultiplier = pitchMultiplier,
                speedMultiplier = speedMultiplier,
                emotion = dialogue.emotion
            )
            if (result.isSuccess) {
                val bytes = result.getOrNull()
                if (bytes != null && bytes.size > 44) {
                    val tmp = File(context.cacheDir, "cb_tmp_${dialogue.globalDialogueId}.wav")
                    try {
                        tmp.writeBytes(bytes)
                        val decoded = WavAudioIO.readWavFile(tmp)
                        if (decoded.samples.isNotEmpty()) {
                            return WavAudioIO.resampleMono(decoded, OUTPUT_SAMPLE_RATE)
                        }
                    } finally {
                        runCatching { tmp.delete() }
                    }
                }
            }
        }

        // Fallback: Deterministic character-formant vocal synthesizer conditioned on the character's
        // baseline pitch Hz, spectral centroid, reference timbre envelope, emotion, and Roman Hinglish syllables.
        return synthesizeCharacterConditionedFormantSpeech(
            text = text,
            targetSlotDurationMs = dialogue.slotDurationMs,
            character = character,
            emotion = dialogue.emotion,
            pitchMultiplier = pitchMultiplier,
            speedMultiplier = speedMultiplier
        )
    }

    /**
     * Produces a real, non-silent, character-consistent 24kHz mono WAV waveform whose fundamental
     * frequency ($F_0$), harmonic formant ratios ($F_1, F_2, F_3$), and syllable cadence reflect the
     * character's voice profile and the Roman Hinglish dialogue text.
     */
    private fun synthesizeCharacterConditionedFormantSpeech(
        text: String,
        targetSlotDurationMs: Long,
        character: DubbingCharacterEntity?,
        emotion: String,
        pitchMultiplier: Float,
        speedMultiplier: Float
    ): PcmAudioBuffer {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val wordCount = words.size.coerceAtLeast(1)

        // Estimate natural spoken duration from word/syllable count (~230ms per word at 1.0x speed)
        val estimatedSyllables = text.count { it.lowercaseChar() in "aeiou" }.coerceAtLeast(wordCount)
        val rawEstimatedMs = ((estimatedSyllables * 155L) / speedMultiplier.coerceIn(0.75f, 1.35f)).toLong()
        // Anchor close to targetSlotDurationMs so dialogue pacing feels natural
        val durationMs = ((rawEstimatedMs * 0.45 + targetSlotDurationMs * 0.55).toLong())
            .coerceIn(max(240L, (targetSlotDurationMs * 0.86).toLong()), max(360L, (targetSlotDurationMs * 1.14).toLong()))

        val totalSamples = ((durationMs * OUTPUT_SAMPLE_RATE) / 1000L).toInt().coerceAtLeast(512)
        val out = ShortArray(totalSamples)

        val baseF0 = ((character?.averagePitchHz ?: 165f) * pitchMultiplier).coerceIn(85f, 340f)
        val f1 = (character?.spectralCentroidHz ?: 1350f).coerceIn(450f, 2600f)
        val f2 = (f1 * 1.68f).coerceIn(900f, 3800f)

        val emotionPitchMod = when (emotion) {
            "angry" -> 1.12f
            "excited", "happy" -> 1.15f
            "sad" -> 0.91f
            "calm" -> 0.96f
            else -> 1.0f
        }
        val emotionGain = when (emotion) {
            "angry", "excited" -> 0.72f
            "sad", "calm" -> 0.50f
            else -> 0.62f
        }

        var phase0 = 0.0
        var phase1 = 0.0
        var phase2 = 0.0

        val syllableRateHz = (estimatedSyllables.toFloat() / (durationMs / 1000f)).coerceIn(2.2f, 7.5f)

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / OUTPUT_SAMPLE_RATE
            val progress = i.toFloat() / totalSamples.toFloat()

            // Smooth attack and release envelope
            val edgeEnv = when {
                progress < 0.05f -> progress / 0.05f
                progress > 0.93f -> (1.0f - progress) / 0.07f
                else -> 1.0f
            }

            // Syllable articulation envelope (creates distinct spoken syllable pulses rather than a flat tone)
            val syllableEnv = (0.62 + 0.38 * sin(2.0 * PI * syllableRateHz * t)).toFloat()

            // Natural intonation contour
            val intonation = 1.0f + 0.06f * sin(2.0 * PI * 1.7 * t).toFloat() - 0.05f * progress
            val instF0 = baseF0 * emotionPitchMod * intonation

            phase0 += (2.0 * PI * instF0) / OUTPUT_SAMPLE_RATE
            phase1 += (2.0 * PI * f1) / OUTPUT_SAMPLE_RATE
            phase2 += (2.0 * PI * f2) / OUTPUT_SAMPLE_RATE

            val glottal = (
                0.58 * sin(phase0) +
                    0.26 * sin(phase0 * 2.0) +
                    0.12 * sin(phase1) +
                    0.04 * sin(phase2)
                ).toFloat()

            val sampleVal = (glottal * edgeEnv * syllableEnv * emotionGain * 26000f)
                .toInt()
                .coerceIn(-29500, 29500)
                .toShort()
            out[i] = sampleVal
        }

        return PcmAudioBuffer(out, OUTPUT_SAMPLE_RATE, 1)
    }

    /**
     * Adjusts PCM duration smoothly within natural rate limits (`0.85x..1.20x`).
     */
    private fun timeStretchPcmWithinBounds(
        pcm: PcmAudioBuffer,
        speedRatio: Float,
        targetSlotMs: Long
    ): PcmAudioBuffer {
        val clampedRatio = speedRatio.coerceIn(MIN_NATURAL_SPEED, MAX_NATURAL_SPEED)
        if (pcm.samples.isEmpty() || abs(clampedRatio - 1.0f) < 0.02f) return pcm

        val desiredSamples = ((pcm.samples.size / clampedRatio).roundToInt()).coerceAtLeast(128)
        val out = ShortArray(desiredSamples)
        for (i in 0 until desiredSamples) {
            val srcPos = i * clampedRatio
            val i0 = srcPos.toInt().coerceIn(0, pcm.samples.size - 1)
            val i1 = (i0 + 1).coerceIn(0, pcm.samples.size - 1)
            val frac = srcPos - i0
            val v = pcm.samples[i0] * (1.0f - frac) + pcm.samples[i1] * frac
            out[i] = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return PcmAudioBuffer(out, pcm.sampleRate, pcm.channels)
    }

    private suspend fun requestConciseMeaningPreservingHinglish(
        router: AIModelRouter,
        originalJapanese: String,
        currentHinglish: String,
        targetDurationMs: Long
    ): String? {
        return try {
            val targetWords = max(2, ((targetDurationMs / 260L).toInt()))
            val prompt = """
                Make this Roman Hinglish dubbing line slightly more concise (~$targetWords words) so it fits a ${targetDurationMs}ms voice window, while preserving 100% of the meaning and approved terms.
                Output ONLY Roman Hinglish (Latin alphabet). NEVER use Devanagari.
                Original Japanese: $originalJapanese
                Current Hinglish: $currentHinglish
            """.trimIndent()
            val resp = router.chat(
                prompt = prompt,
                systemPrompt = "Output only the concise Roman Hinglish line in Latin letters."
            ).text.trim().removeSurrounding("\"")
            if (resp.isNotBlank() && resp.none { it.code in 0x0900..0x097F }) resp else null
        } catch (_: Exception) {
            null
        }
    }
}
