package com.example

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.ai.offline.ModelCatalog
import com.example.vision.ResolvedRemoteArtifact
import com.example.vision.TechnologyInstallStage
import com.example.vision.VisionDownloadKind
import com.example.vision.VisionPackageArchiveUtils
import com.example.vision.VisionPackageManager
import com.example.vision.VisionTechId
import com.example.vision.VisionTechRegistry
import com.example.vision.VisionTechnologyDownloadManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VisualizationTechnologyLibraryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        VisionPackageManager.setTripoSrEndpoint(context, "", "")
        VisionPackageManager.setManimEndpoint(context, "", "")
        val mgr = VisionTechnologyDownloadManager.getInstance(context)
        VisionTechRegistry.core12TechnologyIds.forEach { mgr.removeDownloadedAssets(it) }
    }

    @Test
    fun core12CatalogMatchesExactNamesOfficialUrlsAndPackagesAndLegacy12Removed() {
        // Legacy 12 lightweight ONNX models must be completely removed from catalog
        assertTrue("Old 12 lightweight ONNX models must be removed", ModelCatalog.LIGHTWEIGHT_VISION_DESCRIPTORS.isEmpty())

        val core12 = VisionTechRegistry.core12Technologies
        assertEquals(12, core12.size)

        data class ExpectedTech(
            val id: VisionTechId,
            val name: String,
            val docsUrl: String,
            val sourceUrl: String,
            val npmPkg: String?,
            val kind: VisionDownloadKind
        )

        val expected = listOf(
            ExpectedTech(VisionTechId.TRIPO_SR, "TripoSR", "https://github.com/VAST-AI-Research/TripoSR", "https://github.com/VAST-AI-Research/TripoSR", null, VisionDownloadKind.PYTHON_MODEL_WEIGHTS_AND_WORKER),
            ExpectedTech(VisionTechId.THREE_JS, "Three.js", "https://threejs.org/docs/", "https://threejs.org/manual/#en/installation", "three", VisionDownloadKind.NPM_WEBVIEW_PACKAGE),
            ExpectedTech(VisionTechId.BABYLON_JS, "Babylon.js", "https://doc.babylonjs.com/", "https://github.com/BabylonJS/Babylon.js", "@babylonjs/core", VisionDownloadKind.NPM_WEBVIEW_PACKAGE),
            ExpectedTech(VisionTechId.MODEL_VIEWER, "Model Viewer", "https://modelviewer.dev/", "https://github.com/google/model-viewer", "@google/model-viewer", VisionDownloadKind.NPM_WEBVIEW_PACKAGE),
            ExpectedTech(VisionTechId.MERMAID_JS, "Mermaid.js", "https://mermaid.js.org/", "https://github.com/mermaid-js/mermaid", "mermaid", VisionDownloadKind.NPM_WEBVIEW_PACKAGE),
            ExpectedTech(VisionTechId.D3_JS, "D3.js", "https://d3js.org/", "https://github.com/d3/d3", "d3", VisionDownloadKind.NPM_WEBVIEW_PACKAGE),
            ExpectedTech(VisionTechId.PLOTLY_JS, "Plotly.js", "https://plotly.com/javascript/", "https://github.com/plotly/plotly.js", "plotly.js", VisionDownloadKind.NPM_WEBVIEW_PACKAGE),
            ExpectedTech(VisionTechId.GSAP, "GSAP", "https://gsap.com/docs/v3/", "https://gsap.com/docs/v3/Installation/", "gsap", VisionDownloadKind.NPM_WEBVIEW_PACKAGE),
            ExpectedTech(VisionTechId.REACT_FLOW, "React Flow", "https://reactflow.dev/", "https://github.com/xyflow/xyflow", "@xyflow/react", VisionDownloadKind.NPM_WEBVIEW_PACKAGE),
            ExpectedTech(VisionTechId.EXCALIDRAW, "Excalidraw", "https://docs.excalidraw.com/", "https://github.com/excalidraw/excalidraw", "@excalidraw/excalidraw", VisionDownloadKind.NPM_WEBVIEW_PACKAGE),
            ExpectedTech(VisionTechId.CESIUM_JS, "CesiumJS", "https://cesium.com/learn/cesiumjs-learn/", "https://github.com/CesiumGS/cesium", "cesium", VisionDownloadKind.NPM_WEBVIEW_PACKAGE),
            ExpectedTech(VisionTechId.MANIM, "Manim Community", "https://docs.manim.community/", "https://github.com/ManimCommunity/manim", null, VisionDownloadKind.PYTHON_RENDER_PACKAGE_AND_WORKER)
        )

        assertEquals(expected.map { it.id }, core12.map { it.id })
        for (exp in expected) {
            val spec = VisionTechRegistry.getSpec(exp.id)
            assertEquals("Name mismatch for ${exp.id}", exp.name, spec.displayName)
            assertEquals("Docs URL mismatch for ${exp.id}", exp.docsUrl, spec.docsUrl)
            assertEquals("Official Source URL mismatch for ${exp.id}", exp.sourceUrl, spec.officialSourceUrl)
            assertEquals("DownloadKind mismatch for ${exp.id}", exp.kind, spec.downloadKind)
            assertTrue("Missing visualization role for ${exp.id}", spec.visualizationRole.isNotBlank())
            assertTrue("Missing registry URL for ${exp.id}", spec.registryMetadataUrl.startsWith("https://"))
            if (exp.npmPkg != null) {
                assertEquals("npm package mismatch for ${exp.id}", exp.npmPkg, spec.packageName)
                assertTrue(spec.registryMetadataUrl.startsWith("https://registry.npmjs.org/"))
            } else {
                // TripoSR and Manim Community must NOT be treated as ordinary npm JS packages
                assertNotEquals(VisionDownloadKind.NPM_WEBVIEW_PACKAGE, spec.downloadKind)
                assertTrue("Python runtime limitation note required for ${exp.id}", spec.androidLimitationNote.isNotBlank())
            }
        }
    }

    @Test
    fun officialRegistryParsersParseNpmPypiAndHuggingFaceTripoSrMetadata() {
        // 1. Official npm registry payload
        val npmJson = """
            {
              "name": "three",
              "version": "0.172.0",
              "license": "MIT",
              "dist": {
                "tarball": "https://registry.npmjs.org/three/-/three-0.172.0.tgz",
                "integrity": "sha512-abc123==",
                "shasum": "0123456789abcdef0123456789abcdef01234567",
                "unpackedSize": 32450120
              }
            }
        """.trimIndent()
        val npmParsed = VisionPackageArchiveUtils.parseNpmRegistryLatestJson(npmJson, "three")
        assertNotNull(npmParsed)
        assertEquals("three", npmParsed!!.packageName)
        assertEquals("0.172.0", npmParsed.version)
        assertEquals("MIT", npmParsed.license)
        assertEquals("https://registry.npmjs.org/three/-/three-0.172.0.tgz", npmParsed.artifacts.first().downloadUrl)
        assertEquals("sha512-abc123==", npmParsed.artifacts.first().sha512Sri)

        // 2. Official PyPI Manim payload
        val pypiJson = """
            {
              "info": { "version": "0.19.0", "license": "MIT" },
              "urls": [
                {
                  "packagetype": "bdist_wheel",
                  "filename": "manim-0.19.0-py3-none-any.whl",
                  "url": "https://files.pythonhosted.org/packages/manim-0.19.0-py3-none-any.whl",
                  "size": 650120,
                  "digests": { "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" }
                }
              ]
            }
        """.trimIndent()
        val manimParsed = VisionPackageArchiveUtils.parsePypiManimJson(pypiJson)
        assertNotNull(manimParsed)
        assertEquals("manim", manimParsed!!.packageName)
        assertEquals("0.19.0", manimParsed.version)
        assertEquals(650120L, manimParsed.totalSizeBytes)
        assertEquals("manim-0.19.0-py3-none-any.whl", manimParsed.artifacts.first().fileName)

        // 3. Official Hugging Face stabilityai/TripoSR tree payload
        val hfTreeJson = """
            [
              { "type": "file", "path": "README.md", "size": 2400 },
              { "type": "file", "path": "config.yaml", "size": 1024 },
              {
                "type": "file",
                "path": "model.ckpt",
                "size": 132,
                "lfs": {
                  "oid": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "size": 1680000000
                }
              }
            ]
        """.trimIndent()
        val tripoParsed = VisionPackageArchiveUtils.parseHuggingFaceTripoSrTree(hfTreeJson)
        assertNotNull(tripoParsed)
        assertEquals("stabilityai/TripoSR", tripoParsed!!.packageName)
        assertEquals(2, tripoParsed.artifacts.size)
        assertEquals("config.yaml", tripoParsed.artifacts[0].fileName)
        assertEquals("model.ckpt", tripoParsed.artifacts[1].fileName)
        assertEquals(1680000000L, tripoParsed.artifacts[1].sizeBytes)
        assertEquals(
            "https://huggingface.co/stabilityai/TripoSR/resolve/main/model.ckpt?download=true",
            tripoParsed.artifacts[1].downloadUrl
        )
    }

    @Test
    fun cryptographicIntegrityVerifiersAcceptValidHashesAndRejectCorruptionAndHtml() {
        val dir = tempFolder.newFolder("integrity")
        val payload = "export const version = '1.0.0';\n".repeat(16).toByteArray(Charsets.UTF_8)
        val validFile = File(dir, "pkg.tgz").apply { writeBytes(payload) }

        val sha512B64 = Base64.encodeToString(
            MessageDigest.getInstance("SHA-512").digest(payload),
            Base64.NO_WRAP
        )
        val sha256Hex = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }

        val validSriArt = ResolvedRemoteArtifact(
            fileName = "pkg.tgz",
            downloadUrl = "https://registry.npmjs.org/three/-/three.tgz",
            sizeBytes = payload.size.toLong(),
            sha512Sri = "sha512-$sha512B64"
        )
        val (sriOk, sriAlgo) = VisionPackageArchiveUtils.verifyArtifactIntegrity(validFile, validSriArt)
        assertTrue(sriOk)
        assertEquals("SHA-512", sriAlgo)

        val badSriArt = validSriArt.copy(sha512Sri = "sha512-WRONGHASH==")
        assertFalse(VisionPackageArchiveUtils.verifyArtifactIntegrity(validFile, badSriArt).first)

        val validSha256Art = ResolvedRemoteArtifact(
            fileName = "manim.whl",
            downloadUrl = "https://files.pythonhosted.org/manim.whl",
            sizeBytes = payload.size.toLong(),
            sha256Hex = sha256Hex
        )
        val (sha256Ok, sha256Algo) = VisionPackageArchiveUtils.verifyArtifactIntegrity(validFile, validSha256Art)
        assertTrue(sha256Ok)
        assertEquals("SHA-256", sha256Algo)

        // HTML 404 page must always fail integrity verification
        val htmlFile = File(dir, "error.tgz").apply {
            writeText("<!DOCTYPE html><html><body>404 Not Found</body></html>")
        }
        assertTrue(VisionPackageArchiveUtils.isHtmlOrErrorPayload(htmlFile))
        val (htmlOk, htmlMsg) = VisionPackageArchiveUtils.verifyArtifactIntegrity(
            htmlFile,
            ResolvedRemoteArtifact("error.tgz", "https://example.com/error.tgz", htmlFile.length())
        )
        assertFalse(htmlOk)
        assertTrue(htmlMsg.contains("HTML"))
    }

    @Test
    fun selectiveTarExtractorKeepsRuntimeAndLicenseSkipsTestsAndBlocksPathTraversal() {
        val dir = tempFolder.newFolder("tar_extract")
        val tgzFile = File(dir, "sample-pkg.tgz")
        buildGzipTar(
            tgzFile,
            listOf(
                "package/package.json" to """{"name":"three","version":"0.172.0","license":"MIT"}""",
                "package/LICENSE" to "MIT License Copyright (c) Authors",
                "package/build/three.module.min.js" to "export class Scene {}",
                "package/build/three.module.min.js.map" to """{"version":3}""",
                "package/tests/unit/scene.test.js" to "test('skip me', () => {})",
                "package/../../evil.js" to "console.log('traversal')"
            )
        )

        val outDir = File(dir, "out")
        val result = VisionPackageArchiveUtils.extractNpmTarballSelective(tgzFile, outDir)

        assertEquals(3, result.extractedFilesCount)
        assertNotNull(result.licenseFilePath)
        assertNotNull(result.packageJsonPath)
        assertTrue(File(outDir, "package.json").isFile)
        assertTrue(File(outDir, "LICENSE").isFile)
        assertTrue(File(outDir, "build/three.module.min.js").isFile)
        assertFalse("Source map must be skipped", File(outDir, "build/three.module.min.js.map").exists())
        assertFalse("Test files must be skipped", File(outDir, "tests/unit/scene.test.js").exists())
        assertFalse("Path traversal entry must never be extracted", File(dir, "evil.js").exists())
    }

    @Test
    fun downloadManagerTracksHonestStagesSeparatesInstalledFromFunctionalAndRemovesCleanly() {
        val mgr = VisionTechnologyDownloadManager.getInstance(context)
        mgr.refreshAllStates()

        // 1. Fresh state: WebView JS engine starts NOT_DOWNLOADED (never falsely marked FUNCTIONAL before verification)
        val initialThree = mgr.states.value[VisionTechId.THREE_JS]!!
        assertEquals(TechnologyInstallStage.NOT_DOWNLOADED, initialThree.stage)
        assertFalse(initialThree.integrityVerified)
        assertFalse(initialThree.runtimeVerified)

        // 2. Simulate verified downloaded & extracted npm package on disk -> INSTALLED (NOT yet FUNCTIONAL!)
        val threeDir = mgr.getTechnologyRootDir(VisionTechId.THREE_JS)
        File(threeDir, "build").mkdirs()
        File(threeDir, "build/three.module.min.js").writeText("export class WebGLRenderer {}")
        File(threeDir, "LICENSE_NOTICE.txt").writeText("MIT License")
        File(threeDir, "install_record.json").writeText(
            JSONObject().apply {
                put("techId", VisionTechId.THREE_JS.wireId)
                put("packageName", "three")
                put("version", "0.172.0")
                put("license", "MIT")
                put("integrityVerified", true)
                put("integrityAlgorithm", "SHA-512")
                put("extractedFilesCount", 2)
                put("packageSizeBytes", 1024L)
            }.toString()
        )
        mgr.refreshAllStates()
        val installedThree = mgr.states.value[VisionTechId.THREE_JS]!!
        assertEquals(TechnologyInstallStage.INSTALLED, installedThree.stage)
        assertTrue(installedThree.integrityVerified)
        assertFalse("Downloaded/installed package must NOT be marked FUNCTIONAL before live runtime check", installedThree.runtimeVerified)

        // Duplicate download prevention: already installed package must not start another download
        assertFalse("Duplicate download of already-installed package must be prevented", mgr.startOrResumeDownload(VisionTechId.THREE_JS))

        // 3. Live WebView runtime verification advances INSTALLED -> FUNCTIONAL
        mgr.recordLiveWebViewVerification(VisionTechId.THREE_JS, verified = true, detail = "Three.js WebGL2 frame rendered")
        val functionalThree = mgr.states.value[VisionTechId.THREE_JS]!!
        assertEquals(TechnologyInstallStage.FUNCTIONAL, functionalThree.stage)
        assertTrue(functionalThree.runtimeVerified)

        // 4. Safe removal deletes assets and resets state cleanly to NOT_DOWNLOADED
        val freed = mgr.removeDownloadedAssets(VisionTechId.THREE_JS)
        assertTrue(freed > 0L)
        val afterRemove = mgr.states.value[VisionTechId.THREE_JS]!!
        assertEquals(TechnologyInstallStage.NOT_DOWNLOADED, afterRemove.stage)
        assertFalse(afterRemove.hasDownloadedAssets)

        // 5. TripoSR & Manim Community truthfully separate NOT_DOWNLOADED -> DOWNLOADED -> CONFIGURED -> FUNCTIONAL
        val tripoInitial = mgr.states.value[VisionTechId.TRIPO_SR]!!
        assertEquals(TechnologyInstallStage.NOT_DOWNLOADED, tripoInitial.stage)

        val tripoDir = mgr.getTechnologyRootDir(VisionTechId.TRIPO_SR)
        File(tripoDir, "config.yaml").writeText("model: triposr\n")
        File(tripoDir, "install_record.json").writeText(
            JSONObject().apply {
                put("techId", VisionTechId.TRIPO_SR.wireId)
                put("version", "main")
                put("license", "MIT")
                put("integrityVerified", true)
                put("integrityAlgorithm", "SHA-256")
                put("extractedFilesCount", 1)
                put("packageSizeBytes", 1680000000L)
            }.toString()
        )
        mgr.refreshAllStates()
        assertEquals(
            "Downloaded TripoSR weights without worker URL must report DOWNLOADED, not FUNCTIONAL",
            TechnologyInstallStage.DOWNLOADED,
            mgr.states.value[VisionTechId.TRIPO_SR]!!.stage
        )

        VisionPackageManager.setTripoSrEndpoint(context, "http://10.0.2.2:8000", "")
        mgr.refreshAllStates()
        assertEquals(
            "Configured TripoSR endpoint before health check must report CONFIGURED",
            TechnologyInstallStage.CONFIGURED,
            mgr.states.value[VisionTechId.TRIPO_SR]!!.stage
        )

        VisionPackageManager.markWorkerReachability(VisionTechId.TRIPO_SR, true, "HTTP 200 OK")
        mgr.refreshAllStates()
        assertEquals(
            "Reachable TripoSR worker after live health check reports FUNCTIONAL",
            TechnologyInstallStage.FUNCTIONAL,
            mgr.states.value[VisionTechId.TRIPO_SR]!!.stage
        )

        // Storage check guard rejects absurdly oversized requests cleanly
        assertFalse(mgr.hasSufficientStorageFor(Long.MAX_VALUE / 4))

        // Cleanup
        mgr.removeDownloadedAssets(VisionTechId.TRIPO_SR)
        VisionPackageManager.setTripoSrEndpoint(context, "", "")
    }

    private fun buildGzipTar(target: File, entries: List<Pair<String, String>>) {
        val tarBytes = ByteArrayOutputStream()
        for ((name, content) in entries) {
            val data = content.toByteArray(Charsets.UTF_8)
            val header = ByteArray(512)
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))
            // mode "0000644\0"
            "0000644\u0000".toByteArray(Charsets.US_ASCII).copyInto(header, 100)
            // size in octal (11 digits + NUL)
            val octalSize = String.format("%011o\u0000", data.size)
            octalSize.toByteArray(Charsets.US_ASCII).copyInto(header, 124)
            // mtime
            "00000000000\u0000".toByteArray(Charsets.US_ASCII).copyInto(header, 136)
            // checksum placeholder spaces
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            // regular file typeflag '0'
            header[156] = '0'.code.toByte()
            // compute header checksum
            var sum = 0
            for (b in header) sum += (b.toInt() and 0xFF)
            String.format("%06o\u0000 ", sum).toByteArray(Charsets.US_ASCII).copyInto(header, 148)

            tarBytes.write(header)
            tarBytes.write(data)
            val rem = data.size % 512
            if (rem != 0) {
                tarBytes.write(ByteArray(512 - rem))
            }
        }
        // Two 512-byte zero blocks terminate tar archive
        tarBytes.write(ByteArray(1024))

        GZIPOutputStream(FileOutputStream(target)).use { gz ->
            gz.write(tarBytes.toByteArray())
        }
    }
}
