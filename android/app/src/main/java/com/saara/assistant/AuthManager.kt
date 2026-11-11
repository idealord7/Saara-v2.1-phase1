package com.saara.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

/**
 * Manages Firebase Auth state for cloud sync.
 * Supports Google Sign-In and anonymous auth as a low-friction fallback.
 */
object AuthManager {
  private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

  val currentUser: FirebaseUser? get() = auth.currentUser
  val uid: String? get() = auth.currentUser?.uid
  val isLoggedIn: Boolean get() = auth.currentUser != null

  fun googleClient(context: Context, webClientId: String): GoogleSignInClient {
    val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
      .requestIdToken(webClientId)
      .requestEmail()
      .build()
    return GoogleSignIn.getClient(context, gso)
  }

  fun googleSignInIntent(context: Context, webClientId: String): Intent =
    googleClient(context, webClientId).signInIntent

  /** Call from Activity.onActivityResult with data from googleSignInIntent. */
  suspend fun handleGoogleResult(data: Intent?): Result<FirebaseUser> = runCatching {
    val task = GoogleSignIn.getSignedInAccountFromIntent(data)
    val account: GoogleSignInAccount = try {
      task.getResult(ApiException::class.java)
    } catch (e: ApiException) {
      throw IllegalStateException("Google sign-in failed: ${e.statusCode}", e)
    }
    val credential = GoogleAuthProvider.getCredential(account.idToken, null)
    auth.signInWithCredential(credential).await().user
      ?: error("Firebase sign-in returned no user")
  }

  suspend fun signInAnonymously(): Result<FirebaseUser> = runCatching {
    auth.signInAnonymously().await().user ?: error("Anonymous sign-in returned no user")
  }

  fun signOut(context: Context, webClientId: String? = null) {
    auth.signOut()
    if (!webClientId.isNullOrBlank()) {
      try { googleClient(context, webClientId).signOut() } catch (_: Exception) {}
    }
  }

  fun addAuthStateListener(listener: FirebaseAuth.AuthStateListener) = auth.addAuthStateListener(listener)
  fun removeAuthStateListener(listener: FirebaseAuth.AuthStateListener) = auth.removeAuthStateListener(listener)
}
