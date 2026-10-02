package com.example.ai.local

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Real Vocabulary Tokenizer for Qwen3-4B models on Android.
 *
 * Built directly from the vocabulary extracted from the GGUF model's `tokenizer.ggml.tokens` array.
 * Converts input text and ChatML prompts into model token IDs and decodes generated token streams
 * back into text subwords.
 */
class QwenTokenizer(
    private val vocabulary: List<String>,
    private val bosTokenId: Int = 151643,
    private val eosTokenId: Int = 151645
) {

    companion object {
        private const val TAG = "QwenTokenizer"
        private const val SPECIAL_IM_START = "<|im_start|>"
        private const val SPECIAL_IM_END = "<|im_end|>"
    }

    private val tokenToIdMap = HashMap<String, Int>(vocabulary.size * 2)
    private val specialTokens = mapOf(
        "<|im_start|>" to 151644,
        "<|im_end|>" to 151645,
        "<|endoftext|>" to 151643
    )

    init {
        for (i in vocabulary.indices) {
            val token = vocabulary[i]
            tokenToIdMap[token] = i
        }
        for ((sp, id) in specialTokens) {
            tokenToIdMap[sp] = id
        }
        Log.i(TAG, "Initialized QwenTokenizer with ${vocabulary.size} vocabulary tokens.")
    }

    /**
     * Encodes a prompt string into token IDs.
     */
    fun encode(text: String): IntArray {
        val tokens = mutableListOf<Int>()

        // Split text by special ChatML tokens and whitespace
        val regex = Regex("(<\\|im_start\\|>|<\\|im_end\\|>|<\\|endoftext\\|>|\\s+|\\w+|[^\\s\\w])")
        val matches = regex.findAll(text)

        for (match in matches) {
            val part = match.value
            val specialId = specialTokens[part]
            if (specialId != null) {
                tokens.add(specialId)
            } else if (part.isNotBlank()) {
                val directId = tokenToIdMap[part]
                if (directId != null) {
                    tokens.add(directId)
                } else {
                    // Fallback to byte/subword encoding
                    var found = false
                    for (len in part.length downTo 1) {
                        val sub = part.substring(0, len)
                        val subId = tokenToIdMap[sub]
                        if (subId != null) {
                            tokens.add(subId)
                            found = true
                            break
                        }
                    }
                    if (!found) {
                        tokens.add(bosTokenId)
                    }
                }
            }
        }

        return tokens.toIntArray()
    }

    /**
     * Decodes a single token ID into text subword.
     */
    fun decodeToken(tokenId: Int): String {
        if (tokenId == eosTokenId || tokenId == 151645 || tokenId == 151643) {
            return ""
        }
        if (tokenId in 0 until vocabulary.size) {
            val raw = vocabulary[tokenId]
            return raw.replace("Ġ", " ").replace(" ", " ")
        }
        return ""
    }

    /**
     * Decodes an array of token IDs back into string.
     */
    fun decode(tokenIds: IntArray): String {
        val sb = StringBuilder()
        for (id in tokenIds) {
            sb.append(decodeToken(id))
        }
        return sb.toString().trim()
    }

    fun getEosTokenId(): Int = eosTokenId
    fun getBosTokenId(): Int = bosTokenId
}
