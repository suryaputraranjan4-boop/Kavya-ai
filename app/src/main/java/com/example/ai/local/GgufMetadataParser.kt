package com.example.ai.local

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets

/**
 * GGUF Binary Metadata & Tensor Map Parser for Qwen3-4B models on Android.
 *
 * Implements zero-copy memory-mapped file inspection (`FileChannel.map`), reading
 * GGUF v2/v3 header structures, key-value metadata dictionaries, vocabulary tokens,
 * and tensor tensor descriptors directly from local storage.
 */
class GgufMetadataParser(private val modelFile: File) {

    companion object {
        private const val TAG = "GgufMetadataParser"
        private const val GGUF_MAGIC = 0x46554747 // "GGUF" in little-endian

        // GGUF Value Types
        private const val GGUF_TYPE_UINT8 = 0
        private const val GGUF_TYPE_INT8 = 1
        private const val GGUF_TYPE_UINT16 = 2
        private const val GGUF_TYPE_INT16 = 3
        private const val GGUF_TYPE_UINT32 = 4
        private const val GGUF_TYPE_INT32 = 5
        private const val GGUF_TYPE_FLOAT32 = 6
        private const val GGUF_TYPE_BOOL = 7
        private const val GGUF_TYPE_STRING = 8
        private const val GGUF_TYPE_ARRAY = 9
        private const val GGUF_TYPE_UINT64 = 10
        private const val GGUF_TYPE_INT64 = 11
        private const val GGUF_TYPE_FLOAT64 = 12
    }

    data class TensorInfo(
        val name: String,
        val nDims: Int,
        val dimensions: LongArray,
        val type: Int,
        val offset: Long
    )

    data class ParsedGgufHeader(
        val version: Int,
        val tensorCount: Long,
        val metadataCount: Long,
        val architecture: String,
        val modelName: String,
        val contextLength: Int,
        val embeddingLength: Int,
        val blockCount: Int,
        val headCount: Int,
        val bosTokenId: Int,
        val eosTokenId: Int,
        val vocabularyTokens: List<String>,
        val metadataMap: Map<String, Any>,
        val tensors: Map<String, TensorInfo>,
        val tensorDataOffset: Long
    )

    private var fileChannel: FileChannel? = null
    private var mappedHeaderBuffer: ByteBuffer? = null

    /**
     * Memory-maps the GGUF header and parses model parameters and vocabulary.
     */
    fun parseHeader(): ParsedGgufHeader {
        require(modelFile.exists() && modelFile.canRead()) { "Model file does not exist or is not readable: ${modelFile.absolutePath}" }

        val raf = RandomAccessFile(modelFile, "r")
        fileChannel = raf.channel

        // Map initial 12MB chunk for header & metadata parsing
        val headerMapSize = Math.min(fileChannel!!.size(), 16 * 1024 * 1024L)
        val buffer = fileChannel!!.map(FileChannel.MapMode.READ_ONLY, 0, headerMapSize)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        mappedHeaderBuffer = buffer

        val magic = buffer.int
        check(magic == GGUF_MAGIC) { "File ${modelFile.name} is not a valid GGUF file. Magic mismatch: 0x${Integer.toHexString(magic)}" }

        val version = buffer.int
        check(version in 2..3) { "Unsupported GGUF version $version in ${modelFile.name}. Supported versions are 2 and 3." }

        val tensorCount = buffer.long
        val metadataCount = buffer.long

        Log.i(TAG, "Parsing GGUF v$version file '${modelFile.name}': Tensors=$tensorCount, MetadataKV=$metadataCount")

        val metadataMap = mutableMapOf<String, Any>()
        val vocabularyTokens = mutableListOf<String>()

        for (i in 0 until metadataCount) {
            val key = readGgufString(buffer)
            val valueType = buffer.int
            val value = readGgufValue(buffer, valueType)

            metadataMap[key] = value

            if (key == "tokenizer.ggml.tokens" && value is List<*>) {
                @Suppress("UNCHECKED_CAST")
                vocabularyTokens.addAll((value as List<String>))
            }
        }

        // Extract metadata keys with safe fallbacks for Qwen3 / Qwen2 architecture
        val arch = (metadataMap["general.architecture"] as? String) ?: "qwen2"
        val modelName = (metadataMap["general.name"] as? String) ?: "Qwen3-4B"
        val contextLength = (metadataMap["$arch.context_length"] as? Number)?.toInt()
            ?: (metadataMap["general.context_length"] as? Number)?.toInt()
            ?: 4096
        val embeddingLength = (metadataMap["$arch.embedding_length"] as? Number)?.toInt()
            ?: 2560
        val blockCount = (metadataMap["$arch.block_count"] as? Number)?.toInt()
            ?: 36
        val headCount = (metadataMap["$arch.attention.head_count"] as? Number)?.toInt()
            ?: 32
        val bosTokenId = (metadataMap["tokenizer.ggml.bos_token_id"] as? Number)?.toInt() ?: 151643
        val eosTokenId = (metadataMap["tokenizer.ggml.eos_token_id"] as? Number)?.toInt() ?: 151645

        // Parse Tensor Infos
        val tensorsMap = mutableMapOf<String, TensorInfo>()
        for (i in 0 until tensorCount) {
            val name = readGgufString(buffer)
            val nDims = buffer.int
            val dims = LongArray(nDims) { buffer.long }
            val type = buffer.int
            val offset = buffer.long

            tensorsMap[name] = TensorInfo(name, nDims, dims, type, offset)
        }

        // Calculate tensor binary payload alignment boundary (default 32 bytes)
        val alignment = (metadataMap["general.alignment"] as? Number)?.toInt() ?: 32
        val currentPos = buffer.position()
        val tensorDataOffset = (currentPos + alignment - 1) and (alignment - 1).inv()

        Log.i(TAG, "GGUF header successfully parsed: $modelName ($arch), VocabSize=${vocabularyTokens.size}, Context=$contextLength, TensorsCount=${tensorsMap.size}")

        return ParsedGgufHeader(
            version = version,
            tensorCount = tensorCount,
            metadataCount = metadataCount,
            architecture = arch,
            modelName = modelName,
            contextLength = contextLength,
            embeddingLength = embeddingLength,
            blockCount = blockCount,
            headCount = headCount,
            bosTokenId = bosTokenId,
            eosTokenId = eosTokenId,
            vocabularyTokens = vocabularyTokens,
            metadataMap = metadataMap,
            tensors = tensorsMap,
            tensorDataOffset = tensorDataOffset.toLong()
        )
    }

    private fun readGgufString(buffer: ByteBuffer): String {
        val len = buffer.long.toInt()
        val bytes = ByteArray(len)
        buffer.get(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun readGgufValue(buffer: ByteBuffer, type: Int): Any {
        return when (type) {
            GGUF_TYPE_UINT8 -> buffer.get().toInt() and 0xFF
            GGUF_TYPE_INT8 -> buffer.get().toInt()
            GGUF_TYPE_UINT16 -> buffer.short.toInt() and 0xFFFF
            GGUF_TYPE_INT16 -> buffer.short.toInt()
            GGUF_TYPE_UINT32 -> buffer.int.toLong() and 0xFFFFFFFFL
            GGUF_TYPE_INT32 -> buffer.int
            GGUF_TYPE_FLOAT32 -> buffer.float
            GGUF_TYPE_BOOL -> buffer.get() != 0.toByte()
            GGUF_TYPE_STRING -> readGgufString(buffer)
            GGUF_TYPE_UINT64, GGUF_TYPE_INT64 -> buffer.long
            GGUF_TYPE_FLOAT64 -> buffer.double
            GGUF_TYPE_ARRAY -> {
                val elemType = buffer.int
                val len = buffer.long.toInt()
                val list = ArrayList<Any>(Math.min(len, 100000))
                for (i in 0 until len) {
                    list.add(readGgufValue(buffer, elemType))
                }
                list
            }
            else -> throw IllegalArgumentException("Unknown GGUF value type: $type")
        }
    }

    fun close() {
        try {
            fileChannel?.close()
            fileChannel = null
            mappedHeaderBuffer = null
        } catch (_: Exception) {}
    }
}
