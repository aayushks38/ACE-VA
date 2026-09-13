package com.ace.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ace.app.brain.model.ModelRepository
import com.ace.app.brain.model.ModelValidationResult
import com.ace.app.ui.email.EmailLoginScreen
import com.ace.app.ui.home.HomeScreen
import com.ace.app.ui.model.ModelSetupScreen
import com.ace.app.ui.profile.ProfileScreen
import com.ace.app.ui.theme.AceTheme
import com.ace.app.ui.welcome.WelcomeScreen

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        val permissionsList = mutableListOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissionsList.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val missingPermissions = permissionsList.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            androidx.core.app.ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 1001)
        }


        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
            try {
                val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }

        setContent {
            AceTheme {
                AceApp()
            }
        }
    }
}

@Composable
fun AceApp() {
    val navController = rememberNavController()
    val context = LocalContext.current

    val startDestination = remember {
        val isOnboarded = com.ace.app.brain.model.OnboardingManager.isOnboardingComplete(context)
        val isModelSetup = com.ace.app.brain.model.OnboardingManager.isModelSetupComplete(context)
        val isInstalled = com.ace.app.brain.GemmaBrainManager.isModelInstalled(context)
        val brainReady = com.ace.app.brain.GemmaBrainManager.getBrain(context).isReady()

        val dest = when {
            !isOnboarded -> "welcome"
            !isModelSetup -> "model_setup"
            else -> "home"
        }

        android.util.Log.i("ACE_STARTUP", "ACE_STARTUP: installation_state=${if (isInstalled) "INSTALLED" else "NOT_INSTALLED"}")
        android.util.Log.i("ACE_STARTUP", "ACE_STARTUP: authentication_state=${if (isOnboarded) "AUTHENTICATED" else "UNAUTHENTICATED"}")
        android.util.Log.i("ACE_STARTUP", "ACE_STARTUP: selected_model_state=${com.ace.app.brain.model.ModelRepository.getRegisteredModelPath(context) ?: "NONE"}")
        android.util.Log.i("ACE_STARTUP", "ACE_STARTUP: model_readiness_state=${if (brainReady) "READY" else "NOT_READY"}")
        android.util.Log.i("ACE_STARTUP", "ACE_STARTUP: chosen_navigation_destination=$dest")

        dest
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable("welcome") {
            WelcomeScreen(
                onNavigateToEmail = {
                    navController.navigate("email_login")
                },
                onAuthSuccess = {
                    com.ace.app.brain.model.OnboardingManager.setOnboardingComplete(context, true)
                    val isModelSetup = com.ace.app.brain.model.OnboardingManager.isModelSetupComplete(context)
                    val target = if (isModelSetup) "home" else "model_setup"
                    navController.navigate(target) {
                        popUpTo("welcome") {
                            inclusive = true
                        }
                    }
                }
            )
        }

        composable("email_login") {
            EmailLoginScreen(
                onNavigateBack = {
                    navController.navigateUp()
                },
                onAuthSuccess = {
                    com.ace.app.brain.model.OnboardingManager.setOnboardingComplete(context, true)
                    val isModelSetup = com.ace.app.brain.model.OnboardingManager.isModelSetupComplete(context)
                    val target = if (isModelSetup) "home" else "model_setup"
                    navController.navigate(target) {
                        popUpTo("welcome") {
                            inclusive = true
                        }
                    }
                }
            )
        }

        composable("model_setup") {
            ModelSetupScreen(
                onModelReady = {
                    com.ace.app.brain.model.OnboardingManager.setModelSetupComplete(context, true)
                    navController.navigate("home") {
                        popUpTo("model_setup") {
                            inclusive = true
                        }
                    }
                },
                onSignOutClick = {
                    com.ace.app.brain.model.OnboardingManager.clearAll(context)
                    navController.navigate("welcome") {
                        popUpTo(0) {
                            inclusive = true
                        }
                        launchSingleTop = true
                    }
                }
            )
        }

        composable("home") {
            HomeScreen(
                onProfileClick = {
                    navController.navigate("profile")
                }
            )
        }

        composable("profile") {
            ProfileScreen(
                onBackClick = {
                    navController.navigateUp()
                },
                onSignOutClick = {
                    navController.navigate("welcome") {
                        popUpTo(0) {
                            inclusive = true
                        }
                        launchSingleTop = true
                    }
                }
            )
        }
    }
}
