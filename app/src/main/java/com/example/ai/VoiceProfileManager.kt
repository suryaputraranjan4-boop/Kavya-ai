package com.example.ai

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.example.utils.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

data class VoiceProfile(
    val id: String,
    val name: String,
    val audioFilePath: String?,
    val durationSeconds: Int,
    val isSystemPreset: Boolean = false,
    val description: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Manages custom voice profiles, audio sample recording, uploads, and persistence for Voice Studio.
 * Strict privacy rule: Only user's own recorded voice or explicitly uploaded samples with user consent are used.
 */
class VoiceProfileManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "VoiceProfileManager"
        private const val PREFS_NAME = "kavya_voice_profiles_store"
        private const val KEY_PROFILES_JSON = "key_profiles_json"
        private const val PROFILES_AUDIO_DIR = "voice_studio_samples"

        @Volatile
        private var INSTANCE: VoiceProfileManager? = null

        fun getInstance(context: Context): VoiceProfileManager {
            return INSTANCE ?: synchronized(this) {
                val inst = VoiceProfileManager(context.applicationContext)
                INSTANCE = inst
                inst
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _profiles = MutableStateFlow<List<VoiceProfile>>(emptyList())
    val profiles: StateFlow<List<VoiceProfile>> = _profiles.asStateFlow()

    private var activeRecorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null
    private var recordingStartTime = 0L

    init {
        loadProfiles()
    }

    fun getVoiceSamplesDir(): File {
        val dir = File(context.filesDir, PROFILES_AUDIO_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun loadProfiles() {
        val defaultList = mutableListOf(
            VoiceProfile(
                id = "default_kavya_hindi",
                name = "Kavya Sweet Hindi (Default)",
                audioFilePath = null,
                durationSeconds = 0,
                isSystemPreset = true,
                description = "Chatterbox Multilingual Hindi Default Acoustic Profile"
            ),
            VoiceProfile(
                id = "kavya_expressive",
                name = "Kavya Expressive Anime",
                audioFilePath = null,
                durationSeconds = 0,
                isSystemPreset = true,
                description = "High-expression conversational style"
            )
        )

        val rawJson = prefs.getString(KEY_PROFILES_JSON, null)
        if (!rawJson.isNullOrBlank()) {
            try {
                val array = JSONArray(rawJson)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    defaultList.add(
                        VoiceProfile(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            audioFilePath = obj.optString("audioFilePath").takeIf { it.isNotBlank() },
                            durationSeconds = obj.optInt("durationSeconds", 0),
                            isSystemPreset = obj.optBoolean("isSystemPreset", false),
                            description = obj.optString("description", ""),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing saved voice profiles: ${e.message}")
            }
        }
        _profiles.value = defaultList
    }

    private fun saveCustomProfiles() {
        val customList = _profiles.value.filter { !it.isSystemPreset }
        val array = JSONArray()
        for (p in customList) {
            val obj = JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("audioFilePath", p.audioFilePath ?: "")
                put("durationSeconds", p.durationSeconds)
                put("isSystemPreset", p.isSystemPreset)
                put("description", p.description)
                put("timestamp", p.timestamp)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_PROFILES_JSON, array.toString()).apply()
    }

    fun startRecording(): File {
        val file = File(getVoiceSamplesDir(), "sample_${System.currentTimeMillis()}.3gp")
        currentRecordingFile = file

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_WB)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            activeRecorder = recorder
            recordingStartTime = System.currentTimeMillis()
            return file
        } catch (error: Exception) {
            try {
                recorder.reset()
            } catch (_: Exception) {
            }
            try {
                recorder.release()
            } catch (_: Exception) {
            }
            activeRecorder = null
            currentRecordingFile = null
            file.delete()
            throw IllegalStateException("Unable to start voice recording. Check microphone permission and device recorder availability.", error)
        }
    }

    fun stopRecording(): File? {
        val recorder = activeRecorder ?: return null
        val file = currentRecordingFile
        var saved = false
        try {
            recorder.stop()
            saved = file?.isFile == true && file.length() > 0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed stopping recorder: ${e.message}")
        } finally {
            try { recorder.release() } catch (_: Exception) {}
            activeRecorder = null
            currentRecordingFile = null
        }
        if (!saved) {
            file?.delete()
            return null
        }
        return file
    }

    fun saveVoiceProfile(name: String, audioFile: File, durationSeconds: Int = 0): VoiceProfile {
        val profile = VoiceProfile(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "My Voice Profile" },
            audioFilePath = audioFile.absolutePath,
            durationSeconds = durationSeconds,
            isSystemPreset = false,
            description = "Custom voice profile conditioned on user reference sample"
        )
        _profiles.value = _profiles.value + profile
        saveCustomProfiles()
        AppPreferences.setActiveVoiceProfileId(context, profile.id)
        return profile
    }

    fun importVoiceSample(name: String, inputStream: InputStream): VoiceProfile? {
        return try {
            val file = File(getVoiceSamplesDir(), "imported_${System.currentTimeMillis()}.audio")
            FileOutputStream(file).use { out ->
                inputStream.copyTo(out)
            }
            saveVoiceProfile(name, file)
        } catch (e: Exception) {
            Log.e(TAG, "Failed importing voice sample: ${e.message}")
            null
        }
    }

    fun renameProfile(id: String, newName: String) {
        _profiles.value = _profiles.value.map {
            if (it.id == id && !it.isSystemPreset) it.copy(name = newName.trim()) else it
        }
        saveCustomProfiles()
    }

    fun deleteProfile(id: String) {
        val target = _profiles.value.find { it.id == id && !it.isSystemPreset } ?: return
        target.audioFilePath?.let { path ->
            try {
                val f = File(path)
                if (f.exists()) f.delete()
            } catch (_: Exception) {}
        }
        _profiles.value = _profiles.value.filter { it.id != id }
        saveCustomProfiles()

        if (AppPreferences.getActiveVoiceProfileId(context) == id) {
            AppPreferences.setActiveVoiceProfileId(context, "default_kavya_hindi")
        }
    }

    fun getActiveProfile(): VoiceProfile {
        val activeId = AppPreferences.getActiveVoiceProfileId(context)
        return _profiles.value.find { it.id == activeId } ?: _profiles.value.first()
    }
}
