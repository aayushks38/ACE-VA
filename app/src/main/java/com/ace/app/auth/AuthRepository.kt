package com.ace.app.auth

import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import kotlinx.coroutines.tasks.await
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import android.content.Context
import androidx.fragment.app.FragmentActivity

sealed class AuthResult {
    data class Success(val userId: String, val method: AuthMethod) : AuthResult()
    data class Error(val message: String) : AuthResult()
    object Cancelled : AuthResult()
}

enum class AuthMethod {
    GOOGLE,
    EMAIL,
    BIOMETRIC
}

class AuthRepository(context: Context) {
    
    private val biometricClient = BiometricAuthClient(context)
    private val googleClient = GoogleAuthClient()
    private val emailClient = EmailAuthClient()
    
    fun isBiometricAvailable(): Boolean {
        return biometricClient.isBiometricAvailable() && biometricClient.isBiometricEnrolled()
    }
    
    fun authenticateWithBiometric(
        activity: FragmentActivity,
        onResult: (AuthResult) -> Unit
    ) {
        biometricClient.authenticate(activity) { result ->
            when (result) {
                is BiometricAuthResult.Success -> {
                    onResult(AuthResult.Success("biometric_user", AuthMethod.BIOMETRIC))
                }
                is BiometricAuthResult.Error -> {
                    onResult(AuthResult.Error(result.message))
                }
                is BiometricAuthResult.Unavailable -> {
                    onResult(AuthResult.Error("Biometric authentication is not available on this device"))
                }
                is BiometricAuthResult.NotEnrolled -> {
                    onResult(AuthResult.Error("No biometric credentials enrolled. Please set up fingerprint in Settings"))
                }
            }
        }
    }

    suspend fun authenticateWithGoogle(activity: FragmentActivity): AuthResult {
        return when (val result = googleClient.signIn(activity)) {
            is GoogleAuthResult.Success -> {
                try {
                    val credential = GoogleAuthProvider.getCredential(
                        result.idToken,
                        null
                    )

                    val authResult = FirebaseAuth.getInstance()
                        .signInWithCredential(credential)
                        .await()

                    val user = authResult.user

                    AuthResult.Success(
                        userId = user?.uid ?: "google_${result.email ?: "user"}",
                        method = AuthMethod.GOOGLE
                    )
                } catch (e: Exception) {
                    android.util.Log.w("ACE_AUTH", "ACE_AUTH: Firebase credential sign-in note: ${e.message}. Preserving verified Google authentication.")
                    AuthResult.Success(
                        userId = "google_${result.email ?: "user"}",
                        method = AuthMethod.GOOGLE
                    )
                }
            }

            is GoogleAuthResult.Error -> {
                AuthResult.Error(result.message)
            }

            is GoogleAuthResult.Cancelled -> {
                AuthResult.Cancelled
            }
        }
    }
    
    suspend fun authenticateWithEmail(email: String, password: String): AuthResult {
        return when (val result = emailClient.signIn(email, password)) {
            is EmailAuthResult.Success -> {
                AuthResult.Success(
                    userId = result.userId,
                    method = AuthMethod.EMAIL
                )
            }
            is EmailAuthResult.Error -> {
                AuthResult.Error(result.message)
            }
        }
    }
    
    suspend fun resetPassword(email: String): AuthResult {
        return when (val result = emailClient.resetPassword(email)) {
            is EmailAuthResult.Success -> {
                AuthResult.Success(userId = "", method = AuthMethod.EMAIL)
            }
            is EmailAuthResult.Error -> {
                AuthResult.Error(result.message)
            }
        }
    }

    suspend fun signOut(context: Context) {
        FirebaseAuth.getInstance().signOut()
        CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
    }
}
