package com.ace.app.ui.welcome

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ace.app.R
import com.ace.app.ui.components.*
import com.ace.app.ui.theme.*

@Composable
fun WelcomeScreen(
    onNavigateToEmail: () -> Unit,
    onAuthSuccess: () -> Unit,
    viewModel: WelcomeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    LaunchedEffect(Unit) {
        android.util.Log.i("ACE_ONBOARDING", "ACE_ONBOARDING: entered login")
    }

    LaunchedEffect(uiState.isAuthenticated) {
        if (uiState.isAuthenticated) {
            android.util.Log.i("ACE_ONBOARDING", "ACE_ONBOARDING: login completed")
            onAuthSuccess()
        }
    }
    
    // Show error snackbar
    if (uiState.error != null) {
        LaunchedEffect(uiState.error) {
            // Error shown in UI, clear after display
            viewModel.clearError()
        }
    }
    
    AceBackground {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(80.dp))
                
                // ACE Logo with strong glow
                AceLogo(size = 220)
                
                Spacer(modifier = Modifier.height(68.dp))
                
                // Main heading
                Text(
                    text = "ACE",
                    style = MaterialTheme.typography.displayMedium,
                    color = AceTextWhite,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Subtitle
                Text(
                    text = "Your intelligent personal assistant",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = Color(0xFFD0D0DA),
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(48.dp))
                
                GlowButton(
                    text = "Continue with Google",
                // Google Sign-In Button
                    onClick = { activity?.let { viewModel.onGoogleSignIn(it) }},
                    icon = painterResource(id = R.drawable.ic_google),
                    glowIntensity = 0.5f,
                    enabled = !uiState.isLoading,
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(40.dp))
                
                // Email and Fingerprint options
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularAuthOption(
                        icon = painterResource(id = R.drawable.ic_email_lock),
                        label = "Email",
                        onClick = onNavigateToEmail,
                        enabled = !uiState.isLoading
                    )
                    
                    Spacer(modifier = Modifier.width(40.dp))
                    
                    CircularAuthOption(
                        icon = painterResource(id = R.drawable.ic_fingerprint),
                        label = "Fingerprint",
                        onClick = {
                            activity?.let { viewModel.onBiometricSignIn(it) }
                        },
                        enabled = !uiState.isLoading && uiState.isBiometricAvailable
                    )
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                // Terms and Privacy links
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Terms of Service",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = Color(0xFFA5A5B2),
                        modifier = Modifier.clickable { /* TODO: Open Terms */ }
                    )
                    
                    Spacer(modifier = Modifier.width(24.dp))
                    
                    Text(
                        text = "Privacy Policy",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = Color(0xFFA5A5B2),
                        modifier = Modifier.clickable { /* TODO: Open Privacy */ }
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // ACE branding
                Text(
                    text = "ACE",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = Color(0xFF8F8F9C),
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(32.dp))
            }
            
            // Loading overlay
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AcePurple)
                }
            }
            
            // Error message
            uiState.error?.let { error ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    containerColor = AceSurfaceDark,
                    contentColor = AceTextWhite
                ) {
                    Text(text = error)
                }
            }
        }
    }
}
