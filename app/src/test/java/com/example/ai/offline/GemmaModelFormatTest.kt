package com.example.ai.offline

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GemmaModelFormatTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testNonExistentFileInspection() {
        val nonExistent = File(tempFolder.root, "does_not_exist.bin")
        val info = GemmaModelManager.inspectModel(nonExistent)

        assertFalse(info.isSupported)
        assertEquals(GemmaModelFormat.UNKNOWN, info.format)
        assertTrue(info.validationMessage.contains("does not exist"))
    }

    @Test
    fun testTinyFileInspection() {
        val tinyFile = tempFolder.newFile("tiny.bin")
        FileOutputStream(tinyFile).use { it.write(ByteArray(512)) } // 512 bytes

        val info = GemmaModelManager.inspectModel(tinyFile)
        assertFalse(info.isSupported)
        assertEquals(GemmaModelFormat.UNKNOWN, info.format)
        assertTrue(info.validationMessage.contains("too small"))
    }

    @Test
    fun testGgufFormatDetection() {
        val ggufFile = tempFolder.newFile("model.gguf")
        FileOutputStream(ggufFile).use { fos ->
            val data = ByteArray(2 * 1024 * 1024)
            // Write GGUF magic header: 0x47 0x47 0x55 0x46 ('G' 'G' 'U' 'F')
            data[0] = 'G'.toByte()
            data[1] = 'G'.toByte()
            data[2] = 'U'.toByte()
            data[3] = 'F'.toByte()
            // Version/tensor/metadata fields remain zero, so magic alone is rejected.
            fos.write(data)
        }

        val info = GemmaModelManager.inspectModel(ggufFile)
        assertEquals(GemmaModelFormat.GGUF, info.format)
        assertFalse("GGUF format must not be marked supported without a native GGUF engine", info.isSupported)
        assertTrue(info.validationMessage.contains("GGUF format detected"))
    }


    @Test
    fun testValidGgufHeaderIsSupported() {
        val ggufFile = tempFolder.newFile("valid.gguf")
        FileOutputStream(ggufFile).use { fos ->
            val data = ByteArray(2 * 1024 * 1024)
            data[0] = 'G'.code.toByte()
            data[1] = 'G'.code.toByte()
            data[2] = 'U'.code.toByte()
            data[3] = 'F'.code.toByte()
            data[4] = 3
            data[8] = 1
            data[16] = 1
            fos.write(data)
        }

        val info = GemmaModelManager.inspectModel(ggufFile)
        assertEquals(GemmaModelFormat.GGUF, info.format)
        assertTrue("A valid GGUF header should be supported by the bundled llama.cpp runtime", info.isSupported)
    }

    @Test
    fun testMediaPipeTaskBundleDetection() {
        val taskFile = tempFolder.newFile("gemma.task")
        ZipOutputStream(FileOutputStream(taskFile)).use { zip ->
            zip.putNextEntry(ZipEntry("model.bin"))
            val payload = ByteArray(2 * 1024 * 1024)
            Random(42).nextBytes(payload)
            zip.write(payload)
            zip.closeEntry()
        }

        val info = GemmaModelManager.inspectModel(taskFile)
        assertEquals(GemmaModelFormat.MEDIAPIPE_TASK, info.format)
        assertTrue("MediaPipe Task bundle must be marked supported", info.isSupported)
        assertTrue(info.validationMessage.contains("MediaPipe Task bundle"))
    }

    @Test
    fun testLiteRtFlatBufferDetection() {
        val tfliteFile = tempFolder.newFile("gemma.bin")
        FileOutputStream(tfliteFile).use { fos ->
            val data = ByteArray(2 * 1024 * 1024)
            data[4] = 'T'.toByte()
            data[5] = 'F'.toByte()
            data[6] = 'L'.toByte()
            data[7] = '3'.toByte()
            fos.write(data)
        }

        val info = GemmaModelManager.inspectModel(tfliteFile)
        assertEquals(GemmaModelFormat.UNKNOWN, info.format)
        assertFalse("A .tflite binary must not be mislabeled as a LiteRT-LM container", info.isSupported)
    }

    @Test
    fun testUnknownBinaryHeaderRejected() {
        val unknownFile = tempFolder.newFile("unknown.bin")
        FileOutputStream(unknownFile).use { fos ->
            val data = ByteArray(2 * 1024 * 1024)
            // Write arbitrary byte pattern
            data[0] = 0x12.toByte()
            data[1] = 0x34.toByte()
            data[2] = 0x56.toByte()
            data[3] = 0x78.toByte()
            fos.write(data)
        }

        val info = GemmaModelManager.inspectModel(unknownFile)
        assertEquals(GemmaModelFormat.UNKNOWN, info.format)
        assertFalse("Arbitrary unknown binary header must be rejected", info.isSupported)
        assertTrue(info.validationMessage.contains("Unsupported local model format"))
    }
}
