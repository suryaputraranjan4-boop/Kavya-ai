package com.example.vision

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

data class ResolvedRemoteArtifact(
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256Hex: String = "",
    val sha512Sri: String = "",
    val sha1Hex: String = ""
)

data class ResolvedRegistryPackage(
    val packageName: String,
    val version: String,
    val license: String,
    val artifacts: List<ResolvedRemoteArtifact>,
    val totalSizeBytes: Long,
    val sourceRegistry: String
)

data class TarExtractionResult(
    val extractedFilesCount: Int,
    val extractedTotalBytes: Long,
    val licenseFilePath: String?,
    val packageJsonPath: String?
)

/**
 * Cryptographic verification, official registry JSON parsers (npm, PyPI, Hugging Face),
 * and path-traversal-safe selective GZIP+TAR extraction for visualization runtime packages.
 */
object VisionPackageArchiveUtils {

    private val ALLOWED_RUNTIME_EXTENSIONS = setOf(
        ".js", ".mjs", ".cjs", ".css", ".wasm", ".json", ".txt", ".md"
    )

    private val SKIPPED_PATH_SEGMENTS = setOf(
        "/test/", "/tests/", "/__tests__/", "/spec/", "/examples/", "/docs/", "/.github/", "/benchmark/"
    )

    fun isHtmlOrErrorPayload(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return true
        val headLen = minOf(file.length(), 160L).toInt()
        val head = ByteArray(headLen)
        FileInputStream(file).use { it.read(head) }
        val textHead = String(head, Charsets.UTF_8).trimStart().lowercase()
        return textHead.startsWith("<!doctype") ||
            textHead.startsWith("<html") ||
            textHead.startsWith("<?xml") ||
            textHead.startsWith("<error") ||
            textHead.startsWith("version https://git-lfs.github.com/spec/v1") ||
            textHead.contains("404 not found")
    }

    fun computeDigestHex(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        val buffer = ByteArray(32 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun computeSha512Base64(file: File): String {
        val digest = MessageDigest.getInstance("SHA-512")
        val buffer = ByteArray(32 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
    }

    fun verifyNpmSriSha512(file: File, expectedSri: String): Boolean {
        val trimmed = expectedSri.trim()
        if (!trimmed.startsWith("sha512-", ignoreCase = true)) return false
        val expectedB64 = trimmed.substringAfter("-")
        if (expectedB64.isBlank()) return false
        return computeSha512Base64(file) == expectedB64
    }

    fun verifySha256Hex(file: File, expectedHex: String): Boolean {
        if (expectedHex.isBlank()) return false
        return computeDigestHex(file, "SHA-256").equals(expectedHex.trim(), ignoreCase = true)
    }

    fun verifySha1Hex(file: File, expectedHex: String): Boolean {
        if (expectedHex.isBlank()) return false
        return computeDigestHex(file, "SHA-1").equals(expectedHex.trim(), ignoreCase = true)
    }

    fun verifyArtifactIntegrity(file: File, artifact: ResolvedRemoteArtifact): Pair<Boolean, String> {
        if (!file.isFile || file.length() <= 0L) {
            return false to "Downloaded file is missing or empty."
        }
        if (isHtmlOrErrorPayload(file)) {
            return false to "Server returned an HTML error page or LFS pointer instead of package binary."
        }
        if (artifact.sha512Sri.startsWith("sha512-", ignoreCase = true)) {
            return if (verifyNpmSriSha512(file, artifact.sha512Sri)) {
                true to "SHA-512"
            } else {
                false to "SHA-512 integrity check failed for ${artifact.fileName}."
            }
        }
        if (artifact.sha256Hex.length == 64) {
            return if (verifySha256Hex(file, artifact.sha256Hex)) {
                true to "SHA-256"
            } else {
                false to "SHA-256 integrity check failed for ${artifact.fileName}."
            }
        }
        if (artifact.sha1Hex.length == 40) {
            return if (verifySha1Hex(file, artifact.sha1Hex)) {
                true to "SHA-1"
            } else {
                false to "SHA-1 shasum check failed for ${artifact.fileName}."
            }
        }
        if (artifact.sizeBytes > 0L && file.length() != artifact.sizeBytes) {
            return false to "Size mismatch for ${artifact.fileName} (got ${file.length()}, expected ${artifact.sizeBytes})."
        }
        return true to "SIZE"
    }

    /**
     * Parses official npm registry metadata from `https://registry.npmjs.org/<package>/latest`.
     */
    fun parseNpmRegistryLatestJson(jsonStr: String, fallbackPackageName: String): ResolvedRegistryPackage? {
        return runCatching {
            val root = JSONObject(jsonStr)
            val name = root.optString("name", fallbackPackageName).ifBlank { fallbackPackageName }
            val version = root.optString("version", "").ifBlank { return null }
            val license = when (val licObj = root.opt("license")) {
                is String -> licObj
                is JSONObject -> licObj.optString("type", "See LICENSE")
                else -> "See LICENSE"
            }
            val dist = root.optJSONObject("dist") ?: return null
            val tarball = dist.optString("tarball", "")
            if (!tarball.startsWith("https://")) return null
            val integrity = dist.optString("integrity", "")
            val shasum = dist.optString("shasum", "")
            val unpackedSize = dist.optLong("unpackedSize", 0L)
            val fileName = tarball.substringAfterLast('/').ifBlank {
                "${name.replace("/", "_")}-$version.tgz"
            }
            val artifact = ResolvedRemoteArtifact(
                fileName = fileName,
                downloadUrl = tarball,
                sizeBytes = 0L, // Tarball compressed bytes come from HTTP Content-Length
                sha512Sri = integrity,
                sha1Hex = shasum
            )
            ResolvedRegistryPackage(
                packageName = name,
                version = version,
                license = license,
                artifacts = listOf(artifact),
                totalSizeBytes = unpackedSize,
                sourceRegistry = "registry.npmjs.org"
            )
        }.getOrNull()
    }

    /**
     * Parses official PyPI JSON metadata from `https://pypi.org/pypi/manim/json`.
     */
    fun parsePypiManimJson(jsonStr: String): ResolvedRegistryPackage? {
        return runCatching {
            val root = JSONObject(jsonStr)
            val info = root.optJSONObject("info") ?: return null
            val version = info.optString("version", "").ifBlank { return null }
            val license = info.optString("license", "MIT").ifBlank { "MIT" }
            val urls = root.optJSONArray("urls") ?: return null
            var bestWheel: ResolvedRemoteArtifact? = null
            var fallbackSdist: ResolvedRemoteArtifact? = null
            for (i in 0 until urls.length()) {
                val entry = urls.optJSONObject(i) ?: continue
                val url = entry.optString("url", "")
                if (!url.startsWith("https://")) continue
                val pkgType = entry.optString("packagetype", "")
                val fileName = entry.optString("filename", url.substringAfterLast('/'))
                val size = entry.optLong("size", 0L)
                val sha256 = entry.optJSONObject("digests")?.optString("sha256", "").orEmpty()
                val art = ResolvedRemoteArtifact(
                    fileName = fileName,
                    downloadUrl = url,
                    sizeBytes = size,
                    sha256Hex = sha256
                )
                if (pkgType == "bdist_wheel" && bestWheel == null) {
                    bestWheel = art
                } else if (pkgType == "sdist" && fallbackSdist == null) {
                    fallbackSdist = art
                }
            }
            val chosen = bestWheel ?: fallbackSdist ?: return null
            ResolvedRegistryPackage(
                packageName = "manim",
                version = version,
                license = license,
                artifacts = listOf(chosen),
                totalSizeBytes = chosen.sizeBytes,
                sourceRegistry = "pypi.org"
            )
        }.getOrNull()
    }

    /**
     * Parses official Hugging Face model tree for `stabilityai/TripoSR`
     * (`https://huggingface.co/api/models/stabilityai/TripoSR/tree/main`).
     */
    fun parseHuggingFaceTripoSrTree(jsonStr: String): ResolvedRegistryPackage? {
        return runCatching {
            val arr = JSONArray(jsonStr)
            val needed = listOf("config.yaml", "model.ckpt")
            val found = mutableListOf<ResolvedRemoteArtifact>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if (item.optString("type") != "file") continue
                val path = item.optString("path", "")
                if (path !in needed) continue
                val lfs = item.optJSONObject("lfs")
                val size = lfs?.optLong("size", 0L)?.takeIf { it > 0L } ?: item.optLong("size", 0L)
                val sha256 = lfs?.optString("oid", "").orEmpty()
                found.add(
                    ResolvedRemoteArtifact(
                        fileName = path,
                        downloadUrl = "https://huggingface.co/stabilityai/TripoSR/resolve/main/$path?download=true",
                        sizeBytes = size,
                        sha256Hex = sha256
                    )
                )
            }
            if (found.isEmpty()) return null
            // Ensure config.yaml downloads first, then model.ckpt
            val ordered = found.sortedBy { if (it.fileName == "config.yaml") 0 else 1 }
            ResolvedRegistryPackage(
                packageName = "stabilityai/TripoSR",
                version = "main",
                license = "MIT",
                artifacts = ordered,
                totalSizeBytes = ordered.sumOf { it.sizeBytes },
                sourceRegistry = "huggingface.co/stabilityai/TripoSR"
            )
        }.getOrNull()
    }

    /**
     * Selective, tar-slip-safe POSIX TAR.GZ extractor for official npm tarballs.
     * Extracts ONLY runtime distribution files, package.json, and LICENSE/NOTICE notices —
     * never extracts bloated test suites, source maps, or raw docs.
     */
    fun extractNpmTarballSelective(tgzFile: File, outputDir: File): TarExtractionResult {
        outputDir.mkdirs()
        val canonicalRoot = outputDir.canonicalFile
        var extractedCount = 0
        var extractedBytes = 0L
        var licensePath: String? = null
        var packageJsonPath: String? = null

        GZIPInputStream(BufferedInputStream(FileInputStream(tgzFile), 32 * 1024)).use { tarIn ->
            val header = ByteArray(512)
            val copyBuf = ByteArray(16 * 1024)
            var paxPathOverride: String? = null

            while (true) {
                val readHeader = readFully(tarIn, header, 0, 512)
                if (readHeader < 512 || header.all { it == 0.toByte() }) break

                val rawName = parseTarString(header, 0, 100)
                val prefix = parseTarString(header, 345, 155)
                val typeFlag = header[156].toInt().toChar()
                val size = parseTarOctal(header, 124, 12)
                val combinedName = paxPathOverride ?: if (prefix.isNotBlank()) "$prefix/$rawName" else rawName
                paxPathOverride = null

                // PAX extended header ('x') may carry long path entries
                if (typeFlag == 'x' || typeFlag == 'g') {
                    val paxBytes = ByteArray(size.toInt().coerceAtMost(65536))
                    if (size > 0L) {
                        readFully(tarIn, paxBytes, 0, paxBytes.size)
                        val remaining = size - paxBytes.size
                        if (remaining > 0L) skipFully(tarIn, remaining)
                        if (typeFlag == 'x') {
                            paxPathOverride = parsePaxPath(String(paxBytes, Charsets.UTF_8))
                        }
                    }
                    skipTarPadding(tarIn, size)
                    continue
                }

                val isRegularFile = typeFlag == '0' || typeFlag == '\u0000'
                val normalized = combinedName.removePrefix("./").removePrefix("package/")
                val shouldKeep = isRegularFile && size > 0L && shouldExtractNpmRuntimeEntry(normalized)

                if (shouldKeep) {
                    val destFile = File(outputDir, normalized).canonicalFile
                    if (!destFile.path.startsWith(canonicalRoot.path + File.separator)) {
                        throw SecurityException("Tar-slip path traversal blocked: $combinedName")
                    }
                    destFile.parentFile?.mkdirs()
                    FileOutputStream(destFile).use { out ->
                        var remaining = size
                        while (remaining > 0L) {
                            val chunk = minOf(remaining, copyBuf.size.toLong()).toInt()
                            val n = tarIn.read(copyBuf, 0, chunk)
                            if (n <= 0) break
                            out.write(copyBuf, 0, n)
                            remaining -= n
                        }
                    }
                    extractedCount++
                    extractedBytes += destFile.length()
                    val baseLower = destFile.name.lowercase()
                    if (licensePath == null && (baseLower.startsWith("license") || baseLower.startsWith("notice"))) {
                        licensePath = destFile.absolutePath
                    }
                    if (packageJsonPath == null && normalized == "package.json") {
                        packageJsonPath = destFile.absolutePath
                    }
                } else if (size > 0L) {
                    skipFully(tarIn, size)
                }

                skipTarPadding(tarIn, size)
            }
        }

        return TarExtractionResult(
            extractedFilesCount = extractedCount,
            extractedTotalBytes = extractedBytes,
            licenseFilePath = licensePath,
            packageJsonPath = packageJsonPath
        )
    }

    internal fun shouldExtractNpmRuntimeEntry(relativePath: String): Boolean {
        val clean = relativePath.trimStart('/')
        if (clean.isBlank() || clean.contains("..")) return false
        val lower = "/${clean.lowercase()}"
        if (SKIPPED_PATH_SEGMENTS.any { lower.contains(it) }) return false
        if (lower.endsWith(".map") || lower.endsWith(".d.ts") || lower.endsWith(".ts")) return false
        val fileNameLower = clean.substringAfterLast('/').lowercase()
        if (fileNameLower == "package.json" ||
            fileNameLower.startsWith("license") ||
            fileNameLower.startsWith("notice") ||
            fileNameLower == "copying"
        ) {
            return true
        }
        val hasAllowedExt = ALLOWED_RUNTIME_EXTENSIONS.any { fileNameLower.endsWith(it) }
        if (!hasAllowedExt) return false
        // Prefer dist/build/umd/esm/minified runtime outputs plus root entry files
        return !clean.contains('/') ||
            lower.contains("/dist/") ||
            lower.contains("/build/") ||
            lower.contains("/umd/") ||
            lower.contains("/esm/") ||
            lower.contains("/lib/") ||
            lower.contains("/widgets/") ||
            lower.contains("/assets/")
    }

    private fun parseTarString(buf: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = (offset + length).coerceAtMost(buf.size)
        while (end < limit && buf[end] != 0.toByte()) end++
        return String(buf, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun parseTarOctal(buf: ByteArray, offset: Int, length: Int): Long {
        val raw = parseTarString(buf, offset, length).trim()
        if (raw.isEmpty()) return 0L
        return raw.toLongOrNull(8) ?: 0L
    }

    private fun parsePaxPath(paxText: String): String? {
        for (line in paxText.lineSequence()) {
            val idx = line.indexOf(" path=")
            if (idx >= 0) {
                return line.substring(idx + 6).trim()
            }
        }
        return null
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray, off: Int, len: Int): Int {
        var total = 0
        while (total < len) {
            val n = input.read(buf, off + total, len - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun skipFully(input: java.io.InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val scratch = ByteArray(8192)
        while (remaining > 0L) {
            val n = input.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
            if (n <= 0) break
            remaining -= n
        }
    }

    private fun skipTarPadding(input: java.io.InputStream, entrySize: Long) {
        val mod = (entrySize % 512L)
        if (mod != 0L) {
            skipFully(input, 512L - mod)
        }
    }
}
