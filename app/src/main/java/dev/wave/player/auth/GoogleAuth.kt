package dev.wave.player.auth

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

class NeedsSignInException : Exception("Google sign-in needed")

/**
 * Google Drive authorization (no backend needed).
 * The token is kept in memory and refreshed silently ~10 min before it expires,
 * so stream requests prepared ahead of time never go out with a stale token.
 */
object GoogleAuth {
    // Full Drive scope: read songs you drop into the folder from any device,
    // and upload into any playlist folder. Fine for personal use in "Testing" mode.
    private val SCOPES = listOf(Scope("https://www.googleapis.com/auth/drive"))
    private const val LIFETIME_MS = 50 * 60 * 1000L

    @Volatile private var token: String? = null
    @Volatile private var expiresAt = 0L
    private val mutex = Mutex()

    private fun request() = AuthorizationRequest.builder()
        .setRequestedScopes(SCOPES)
        .build()

    /** UI path. If result.hasResolution(), launch result.pendingIntent for consent. */
    suspend fun authorize(context: Context): AuthorizationResult {
        val result = Identity.getAuthorizationClient(context).authorize(request()).await()
        accept(result)
        return result
    }

    fun acceptIntent(context: Context, data: Intent?) {
        accept(Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data))
    }

    private fun accept(result: AuthorizationResult) {
        val t = result.accessToken ?: return
        token = t
        expiresAt = System.currentTimeMillis() + LIFETIME_MS
    }

    val isSignedIn: Boolean get() = token != null

    /** Background path (players, workers). Never shows UI. */
    suspend fun token(context: Context): String = mutex.withLock {
        val cached = token
        if (cached != null && System.currentTimeMillis() < expiresAt) return cached
        val result = Identity.getAuthorizationClient(context.applicationContext)
            .authorize(request()).await()
        if (result.hasResolution()) throw NeedsSignInException()
        accept(result)
        token ?: throw NeedsSignInException()
    }

    /** For data-source threads that are already off the main thread. */
    fun tokenBlocking(context: Context): String = runBlocking { token(context) }

    /** Call after a 401 so the next request fetches a fresh token. */
    fun invalidate() { expiresAt = 0L }
}
