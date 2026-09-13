package com.ace.app.brain.model

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import java.io.File

object OnboardingManager {
    private const val PREFS_NAME = "ace_onboarding_prefs"
    private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
    private const val KEY_MODEL_SETUP_COMPLETE = "model_setup_complete"
    private const val TAG_STARTUP = "ACE_STARTUP"
    private const val TAG_ONBOARDING = "ACE_ONBOARDING"

    fun isOnboardingComplete(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_ONBOARDING_COMPLETE)) {
            return prefs.getBoolean(KEY_ONBOARDING_COMPLETE, true)
        }
        return true
    }

    fun setOnboardingComplete(context: Context, complete: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, complete).apply()
        Log.i(TAG_ONBOARDING, "ACE_ONBOARDING: onboarding_complete set to $complete")
    }

    fun isModelSetupComplete(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_MODEL_SETUP_COMPLETE)) {
            return prefs.getBoolean(KEY_MODEL_SETUP_COMPLETE, true)
        }
        return true
    }

    fun setModelSetupComplete(context: Context, complete: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_MODEL_SETUP_COMPLETE, complete).apply()
        Log.i(TAG_ONBOARDING, "ACE_ONBOARDING: model_setup_complete set to $complete")
    }

    fun clearAll(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        ModelRepository.clearRegisteredModel(context)
        Log.i(TAG_ONBOARDING, "ACE_ONBOARDING: Cleared all onboarding and model setup state")
    }
}
