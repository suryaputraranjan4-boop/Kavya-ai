package com.example.kucp.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.example.kucp.model.ConnectorDefinition
import com.example.kucp.security.SecretRedactor
import com.example.kucp.security.SsrfPolicyValidator
import com.example.kucp.storage.KucpCredentialVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class PkceSession(
    val connectorId: String,
    val state: String,
    val codeVerifier: String,
    val codeChallenge: String,
    val redirectUri: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

data class OAuthTokenResult(
    val success: Boolean,
    val expiresInSec: Long = 0L,
    val hasRefreshToken: Boolean = false,
    val grantedScopes: List<String> = emptyList(),
    val error: String? = null
)

/**
 * OAuth 2.0 Authorization Code + PKCE (RFC 7636) + OIDC Discovery + Refresh & Revocation Manager.
 * Tokens are stored strictly inside AES-256-GCM KucpCredentialVault (never Room DB or plaintext logs).
 */
class KucpOAuthManager(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    private val pendingSessions = ConcurrentHashMap<String, PkceSession>()
    private val secureRandom = SecureRandom()

    fun createPkceSession(connector: ConnectorDefinition): PkceSession {
        val verifierBytes = ByteArray(32).also { secureRandom.nextBytes(it) }
        val codeVerifier = Base64.encodeToString(verifierBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        val codeChallenge = Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        val stateBytes = ByteArray(18).also { secureRandom.nextBytes(it) }
        val state = Base64.encodeToString(stateBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val redirect = connector.authConfig.oauthRedirectUri.ifBlank { "kavya://oauth/callback" }

        val session = PkceSession(
            connectorId = connector.id,
            state = state,
            codeVerifier = codeVerifier,
            codeChallenge = codeChallenge,
            redirectUri = redirect
        )
        pendingSessions[state] = session
        return session
    }

    fun buildAuthorizationUri(connector: ConnectorDefinition, session: PkceSession): Result<Uri> {
        val auth = connector.authConfig
        if (auth.oauthAuthorizeUrl.isBlank()) {
            return Result.failure(IllegalArgumentException("Missing OAuth authorize URL for ${connector.name}."))
        }
        if (auth.oauthClientId.isBlank()) {
            return Result.failure(IllegalArgumentException("Missing OAuth Client ID for ${connector.name}. Configure your registered Client ID first."))
        }
        val urlCheck = SsrfPolicyValidator.validateUrl(auth.oauthAuthorizeUrl, connector.isLocalDevAllowed, emptyList())
        if (!urlCheck.allowed) return Result.failure(SecurityException(urlCheck.reason))

        val uri = Uri.parse(auth.oauthAuthorizeUrl).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", auth.oauthClientId)
            .appendQueryParameter("redirect_uri", session.redirectUri)
            .appendQueryParameter("state", session.state)
            .appendQueryParameter("code_challenge", session.codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .apply {
                if (auth.scopes.isNotEmpty()) {
                    appendQueryParameter("scope", auth.scopes.joinToString(" "))
                }
            }
            .build()
        return Result.success(uri)
    }

    fun launchAuthorizationInBrowser(context: Context, uri: Uri): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun handleCallbackAndExchangeCode(
        context: Context,
        callbackUri: Uri,
        connectorResolver: (String) -> ConnectorDefinition?
    ): OAuthTokenResult = withContext(Dispatchers.IO) {
        val err = callbackUri.getQueryParameter("error")
        if (!err.isNullOrBlank()) {
            return@withContext OAuthTokenResult(false, error = callbackUri.getQueryParameter("error_description") ?: err)
        }
        val state = callbackUri.getQueryParameter("state").orEmpty()
        val code = callbackUri.getQueryParameter("code").orEmpty()
        val session = pendingSessions.remove(state)
            ?: return@withContext OAuthTokenResult(false, error = "Invalid or expired OAuth state parameter (CSRF guard triggered).")

        if (code.isBlank()) {
            return@withContext OAuthTokenResult(false, error = "Authorization callback missing 'code' parameter.")
        }
        val connector = connectorResolver(session.connectorId)
            ?: return@withContext OAuthTokenResult(false, error = "Connector '${session.connectorId}' not found.")

        exchangeCode(context, connector, code, session.codeVerifier, session.redirectUri)
    }

    suspend fun exchangeCode(
        context: Context,
        connector: ConnectorDefinition,
        code: String,
        codeVerifier: String,
        redirectUri: String
    ): OAuthTokenResult = withContext(Dispatchers.IO) {
        val tokenUrl = connector.authConfig.oauthTokenUrl
        val check = SsrfPolicyValidator.validateUrl(tokenUrl, connector.isLocalDevAllowed, emptyList())
        if (!check.allowed) return@withContext OAuthTokenResult(false, error = check.reason)

        try {
            val formBuilder = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", redirectUri)
                .add("client_id", connector.authConfig.oauthClientId)
                .add("code_verifier", codeVerifier)

            val clientSecret = KucpCredentialVault.getCredential(context, connector.id, "oauth_client_secret")
            if (clientSecret.isNotBlank()) {
                formBuilder.add("client_secret", clientSecret)
            }

            val req = Request.Builder().url(tokenUrl)
                .header("Accept", "application/json")
                .post(formBuilder.build())
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext OAuthTokenResult(false, error = "Token exchange failed (${resp.code}): ${SecretRedactor.redact(raw.take(240))}")
                }
                val json = JSONObject(raw)
                val access = json.optString("access_token")
                if (access.isBlank()) {
                    return@withContext OAuthTokenResult(false, error = "Token response missing access_token")
                }
                val refresh = json.optString("refresh_token")
                val idToken = json.optString("id_token")
                val expiresIn = json.optLong("expires_in", 3600L)
                val scopes = json.optString("scope").split(Regex("\\s+")).filter { it.isNotBlank() }

                KucpCredentialVault.saveCredential(context, connector.id, "oauth_access", access)
                if (refresh.isNotBlank()) KucpCredentialVault.saveCredential(context, connector.id, "oauth_refresh", refresh)
                if (idToken.isNotBlank()) KucpCredentialVault.saveCredential(context, connector.id, "oidc_id_token", idToken)

                OAuthTokenResult(
                    success = true,
                    expiresInSec = expiresIn,
                    hasRefreshToken = refresh.isNotBlank(),
                    grantedScopes = scopes
                )
            }
        } catch (e: Exception) {
            OAuthTokenResult(false, error = SecretRedactor.redact(e.message))
        }
    }

    suspend fun refreshTokenIfPossible(context: Context, connector: ConnectorDefinition): OAuthTokenResult = withContext(Dispatchers.IO) {
        val refresh = KucpCredentialVault.getCredential(context, connector.id, "oauth_refresh")
        if (refresh.isBlank()) return@withContext OAuthTokenResult(false, error = "No refresh token stored.")
        val tokenUrl = connector.authConfig.oauthTokenUrl
        val check = SsrfPolicyValidator.validateUrl(tokenUrl, connector.isLocalDevAllowed, emptyList())
        if (!check.allowed) return@withContext OAuthTokenResult(false, error = check.reason)

        try {
            val form = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .add("client_id", connector.authConfig.oauthClientId)
                .apply {
                    val cs = KucpCredentialVault.getCredential(context, connector.id, "oauth_client_secret")
                    if (cs.isNotBlank()) add("client_secret", cs)
                }
                .build()
            val req = Request.Builder().url(tokenUrl).header("Accept", "application/json").post(form).build()
            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext OAuthTokenResult(false, error = "Refresh HTTP ${resp.code}: ${SecretRedactor.redact(raw.take(200))}")
                }
                val json = JSONObject(raw)
                val newAccess = json.optString("access_token")
                if (newAccess.isBlank()) return@withContext OAuthTokenResult(false, error = "Refresh response missing access_token")
                KucpCredentialVault.rotateCredential(context, connector.id, "oauth_access", newAccess)
                val newRefresh = json.optString("refresh_token")
                if (newRefresh.isNotBlank()) KucpCredentialVault.rotateCredential(context, connector.id, "oauth_refresh", newRefresh)
                OAuthTokenResult(
                    success = true,
                    expiresInSec = json.optLong("expires_in", 3600L),
                    hasRefreshToken = true
                )
            }
        } catch (e: Exception) {
            OAuthTokenResult(false, error = SecretRedactor.redact(e.message))
        }
    }

    suspend fun discoverOidcEndpoints(discoveryUrl: String): Result<Triple<String, String, String>> = withContext(Dispatchers.IO) {
        val check = SsrfPolicyValidator.validateUrl(discoveryUrl, allowLocalDev = false)
        if (!check.allowed) return@withContext Result.failure(SecurityException(check.reason))
        try {
            httpClient.newCall(Request.Builder().url(discoveryUrl).get().build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext Result.failure(IllegalStateException("OIDC discovery HTTP ${resp.code}"))
                val json = JSONObject(raw)
                Result.success(
                    Triple(
                        json.optString("authorization_endpoint"),
                        json.optString("token_endpoint"),
                        json.optString("revocation_endpoint")
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun revokeLocalTokens(context: Context, connectorId: String) {
        KucpCredentialVault.revokeCredential(context, connectorId, "oauth_access")
        KucpCredentialVault.revokeCredential(context, connectorId, "oauth_refresh")
        KucpCredentialVault.revokeCredential(context, connectorId, "oidc_id_token")
    }
}
