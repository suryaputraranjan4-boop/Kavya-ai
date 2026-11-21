package com.example.kucp.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.kucp.security.SecretRedactor
import com.example.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject

/**
 * AES-256-GCM Credential Vault wrapping SecureStorage.
 * Stores all API keys, Bearer tokens, OAuth2 access/refresh tokens, and webhook secrets
 * strictly encrypted outside Room DB, supporting slot listing, rotation, revocation, and masking.
 */
object KucpCredentialVault {
    private const val INDEX_KEY = "kucp_vault_slot_index"
    private const val PREFIX = "kucp_secret_"

    private fun fullKey(connectorId: String, slot: String): String {
        val safeId = connectorId.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val safeSlot = slot.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        return "$PREFIX${safeId}__$safeSlot"
    }

    @Synchronized
    fun saveCredential(context: Context, connectorId: String, slot: String, value: String): Boolean {
        val key = fullKey(connectorId, slot)
        val ok = SecureStorage.saveSecret(context, key, value)
        if (ok) {
            if (value.isNotBlank()) {
                SecretRedactor.registerSensitiveValue(value.trim())
                updateIndex(context, connectorId, slot, add = true)
            } else {
                updateIndex(context, connectorId, slot, add = false)
            }
        }
        return ok
    }

    fun getCredential(context: Context, connectorId: String, slot: String): String {
        val secret = SecureStorage.getSecret(context, fullKey(connectorId, slot))
        if (secret.isNotBlank()) SecretRedactor.registerSensitiveValue(secret)
        return secret
    }

    fun getMaskedCredential(context: Context, connectorId: String, slot: String): String {
        return SecureStorage.maskSecret(getCredential(context, connectorId, slot))
    }

    fun hasCredential(context: Context, connectorId: String, slot: String): Boolean =
        getCredential(context, connectorId, slot).isNotBlank()

    @Synchronized
    fun rotateCredential(context: Context, connectorId: String, slot: String, newValue: String): Boolean {
        val old = getCredential(context, connectorId, slot)
        if (old.isNotBlank()) SecretRedactor.unregisterSensitiveValue(old)
        return saveCredential(context, connectorId, slot, newValue)
    }

    @Synchronized
    fun revokeCredential(context: Context, connectorId: String, slot: String) {
        val old = getCredential(context, connectorId, slot)
        if (old.isNotBlank()) SecretRedactor.unregisterSensitiveValue(old)
        SecureStorage.clearSecret(context, fullKey(connectorId, slot))
        updateIndex(context, connectorId, slot, add = false)
    }

    @Synchronized
    fun revokeAllForConnector(context: Context, connectorId: String) {
        val slots = listSlotsForConnector(context, connectorId)
        for (slot in slots) {
            val old = getCredential(context, connectorId, slot)
            if (old.isNotBlank()) SecretRedactor.unregisterSensitiveValue(old)
            SecureStorage.clearSecret(context, fullKey(connectorId, slot))
        }
        val indexObj = loadIndexObj(context)
        indexObj.remove(connectorId)
        saveIndexObj(context, indexObj)
    }

    fun listSlotsForConnector(context: Context, connectorId: String): List<String> {
        val arr = loadIndexObj(context).optJSONArray(connectorId) ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotBlank()) add(s)
            }
        }
    }

    fun listAllVaultEntriesMasked(context: Context): List<Triple<String, String, String>> {
        val out = mutableListOf<Triple<String, String, String>>()
        val indexObj = loadIndexObj(context)
        val keys = indexObj.keys()
        while (keys.hasNext()) {
            val cid = keys.next()
            val arr = indexObj.optJSONArray(cid) ?: continue
            for (i in 0 until arr.length()) {
                val slot = arr.optString(i)
                if (slot.isNotBlank()) {
                    val masked = getMaskedCredential(context, cid, slot)
                    if (masked != "Not Configured") {
                        out.add(Triple(cid, slot, masked))
                    }
                }
            }
        }
        return out
    }

    private fun updateIndex(context: Context, connectorId: String, slot: String, add: Boolean) {
        val obj = loadIndexObj(context)
        val current = obj.optJSONArray(connectorId) ?: JSONArray()
        val set = LinkedHashSet<String>()
        for (i in 0 until current.length()) {
            val item = current.optString(i)
            if (item.isNotBlank()) set.add(item)
        }
        if (add) set.add(slot) else set.remove(slot)
        if (set.isEmpty()) obj.remove(connectorId) else obj.put(connectorId, JSONArray(set.toList()))
        saveIndexObj(context, obj)
    }

    private fun loadIndexObj(context: Context): JSONObject {
        val raw = SecureStorage.getSecret(context, INDEX_KEY)
        if (raw.isBlank()) return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun saveIndexObj(context: Context, obj: JSONObject) {
        val str = obj.toString()
        if (obj.length() == 0) SecureStorage.clearSecret(context, INDEX_KEY)
        else SecureStorage.saveSecret(context, INDEX_KEY, str)
    }
}

// =========================================================================
// ROOM METADATA ENTITIES (NEVER STORES RAW SECRETS)
// =========================================================================

@Entity(tableName = "kucp_connectors")
data class KucpConnectorEntity(
    @PrimaryKey val id: String,
    val name: String,
    val provider: String,
    val protocol: String,
    val category: String,
    val status: String,
    val version: String,
    val enabled: Boolean,
    val definitionJson: String,
    val updatedAtMs: Long
)

@Entity(tableName = "kucp_tools")
data class KucpToolEntity(
    @PrimaryKey val toolId: String,
    val connectorId: String,
    val name: String,
    val protocol: String,
    val riskLevel: String,
    val descriptorJson: String,
    val updatedAtMs: Long
)

@Entity(tableName = "kucp_plugins")
data class KucpPluginEntity(
    @PrimaryKey val pluginId: String,
    val name: String,
    val version: String,
    val sha256: String,
    val enabled: Boolean,
    val manifestJson: String,
    val previousManifestJson: String,
    val installedAtMs: Long
)

@Entity(tableName = "kucp_webhooks")
data class KucpWebhookEntity(
    @PrimaryKey val id: String,
    val connectorId: String,
    val name: String,
    val active: Boolean,
    val subscriptionJson: String,
    val updatedAtMs: Long
)

@Entity(tableName = "kucp_workflows")
data class KucpWorkflowEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean,
    val definitionJson: String,
    val updatedAtMs: Long
)

@Entity(tableName = "kucp_workflow_runs")
data class KucpWorkflowRunEntity(
    @PrimaryKey val runId: String,
    val workflowId: String,
    val status: String,
    val recordJson: String,
    val startedAtMs: Long,
    val finishedAtMs: Long
)

@Entity(tableName = "kucp_audit_events")
data class KucpAuditEventEntity(
    @PrimaryKey val id: String,
    val connectorId: String,
    val toolId: String,
    val action: String,
    val outcome: String,
    val riskLevel: String,
    val detailRedacted: String,
    val timestampMs: Long
)

@Dao
interface KucpDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConnector(entity: KucpConnectorEntity)

    @Query("SELECT * FROM kucp_connectors ORDER BY updatedAtMs DESC")
    suspend fun getAllConnectors(): List<KucpConnectorEntity>

    @Query("DELETE FROM kucp_connectors WHERE id = :connectorId")
    suspend fun deleteConnector(connectorId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTools(entities: List<KucpToolEntity>)

    @Query("SELECT * FROM kucp_tools")
    suspend fun getAllTools(): List<KucpToolEntity>

    @Query("DELETE FROM kucp_tools WHERE connectorId = :connectorId")
    suspend fun deleteToolsForConnector(connectorId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlugin(entity: KucpPluginEntity)

    @Query("SELECT * FROM kucp_plugins ORDER BY installedAtMs DESC")
    suspend fun getAllPlugins(): List<KucpPluginEntity>

    @Query("DELETE FROM kucp_plugins WHERE pluginId = :pluginId")
    suspend fun deletePlugin(pluginId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWebhook(entity: KucpWebhookEntity)

    @Query("SELECT * FROM kucp_webhooks ORDER BY updatedAtMs DESC")
    suspend fun getAllWebhooks(): List<KucpWebhookEntity>

    @Query("DELETE FROM kucp_webhooks WHERE id = :id")
    suspend fun deleteWebhook(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkflow(entity: KucpWorkflowEntity)

    @Query("SELECT * FROM kucp_workflows ORDER BY updatedAtMs DESC")
    suspend fun getAllWorkflows(): List<KucpWorkflowEntity>

    @Query("DELETE FROM kucp_workflows WHERE id = :id")
    suspend fun deleteWorkflow(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkflowRun(entity: KucpWorkflowRunEntity)

    @Query("SELECT * FROM kucp_workflow_runs ORDER BY startedAtMs DESC LIMIT 50")
    suspend fun getRecentWorkflowRuns(): List<KucpWorkflowRunEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuditEvent(entity: KucpAuditEventEntity)

    @Query("SELECT * FROM kucp_audit_events ORDER BY timestampMs DESC LIMIT 200")
    suspend fun getRecentAuditEvents(): List<KucpAuditEventEntity>

    @Query("DELETE FROM kucp_audit_events")
    suspend fun clearAuditEvents()
}
