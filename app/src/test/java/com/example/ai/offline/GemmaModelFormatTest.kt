package com.example.ai.offline

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
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
            fos.write(data)
        }

        val info = GemmaModelManager.inspectModel(ggufFile)
        assertEquals(GemmaModelFormat.GGUF, info.format)
        assertTrue("GGUF is supported by the bundled llama.cpp native runtime", info.isSupported)
        assertTrue(info.validationMessage.contains("GGUF format detected"))
    }

    @Test
    fun testMediaPipeTaskBundleDetection() {
        val taskFile = tempFolder.newFile("gemma.task")
        ZipOutputStream(FileOutputStream(taskFile)).use { zip ->
            // A real ZIP entry is required; a four-byte PK header plus zeroes is corrupt.
            zip.putNextEntry(ZipEntry("metadata.txt"))
            zip.write("valid test task bundle".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        val info = GemmaModelManager.inspectModel(taskFile)
        assertEquals(GemmaModelFormat.MEDIAPIPE_TASK, info.format)
        assertTrue("MediaPipe Task bundle must be marked supported", info.isSupported)
        assertTrue(info.validationMessage.contains("MediaPipe Task bundle"))
    }

    @Test
    fun testLiteRtFlatBufferDetection() {
        val tfliteFile = tempFolder.newFile("gemma.litertlm")
        FileOutputStream(tfliteFile).use { fos ->
            val data = ByteArray(2 * 1024 * 1024)
            // LiteRT-LM selection is based on its documented .litertlm container extension;
            // a TFL3 header alone is not sufficient to identify a LiteRT-LM model.
            fos.write(data)
        }

        val info = GemmaModelManager.inspectModel(tfliteFile)
        assertEquals(GemmaModelFormat.LITERT_LM, info.format)
        assertTrue("LiteRT FlatBuffer binary must be marked supported", info.isSupported)
        assertTrue(info.validationMessage.contains("LiteRT-LM"))
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
        assertTrue(info.validationMessage.contains("Unsupported or unknown"))
    }
}
