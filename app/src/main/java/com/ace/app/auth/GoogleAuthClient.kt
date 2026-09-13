package com.ace.app.auth

import androidx.fragment.app.FragmentActivity
import android.content.Context
import android.content.IntentSender
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CancellationException

sealed class GoogleAuthResult {
    data class Success(val idToken: String, val displayName: String?, val email: String?) : GoogleAuthResult()
    data class Error(val message: String) : GoogleAuthResult()
    object Cancelled : GoogleAuthResult()
}

class GoogleAuthClient {
    
    /**
     * Initiates Google Sign-In flow using Credential Manager
     * 
     * To configure:
     * 1. Add your Web Client ID from Google Cloud Console
     * 2. Configure OAuth consent screen
     * 3. Add SHA-1 fingerprint in Firebase/Google Cloud Console
     */
    suspend fun signIn(activity: FragmentActivity): GoogleAuthResult {
        val credentialManager = CredentialManager.create(activity)
        // TODO: Replace with actual Web Client ID from Google Cloud Console
        // This should be your OAuth 2.0 Web Client ID, not Android Client ID
        val webClientId = "827519061853-ueo9krspmborj1jrnog5u9jdhop2808j.apps.googleusercontent.com"
        
        return try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(false)
                .build()
            
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            
            val result = credentialManager.getCredential(
                request = request,
                context = activity
            )
            
            try {

                val googleCredential = GoogleIdTokenCredential.createFrom(
                    result.credential.data
                )

                GoogleAuthResult.Success(
                    idToken = googleCredential.idToken,
                    displayName = googleCredential.displayName,
                    email = googleCredential.id
                )
            } catch (e: Exception) {
                GoogleAuthResult.Error(
                    "Failed to parse Google credential: ${e.message}"
                )
            }
        } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
            GoogleAuthResult.Cancelled
        } catch (e: GetCredentialException) {
            GoogleAuthResult.Error(e.message ?: "Google Sign-In failed")
        } catch (e: CancellationException) {
            GoogleAuthResult.Cancelled
        } catch (e: Exception) {
            GoogleAuthResult.Error(e.message ?: "Unknown error occurred")
        }
    }
    
    /**
     * Sign out (clear local session)
     * Note: Actual server-side sign out should be handled by your backend
     */
    fun signOut() {
        // Clear any local session data
        // In production, also revoke token on server
    }
}
