package com.ace.app.ui.email

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ace.app.ui.components.AceBackground
import com.ace.app.ui.components.GlowButton
import com.ace.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailLoginScreen(
    onNavigateBack: () -> Unit,
    onAuthSuccess: () -> Unit,
    viewModel: EmailLoginViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current
    
    // Navigate on successful authentication
    /*LaunchedEffect(uiState.isAuthenticated) {
        if (uiState.isAuthenticated) {
            onAuthSuccess()
        }
    }*/

    LaunchedEffect(uiState.isAuthenticated) {
        if (uiState.isAuthenticated) {
            android.util.Log.i("ACE_ONBOARDING", "ACE_ONBOARDING: login completed via Email")
            onAuthSuccess()
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
            ) {
                // Top bar
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.padding(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = AceTextWhite
                    )
                }
                
                Spacer(modifier = Modifier.height(40.dp))
                
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Title
                    Text(
                        text = "Sign in with Email",
                        style = MaterialTheme.typography.displayMedium,
                        color = AceTextWhite,
                        textAlign = TextAlign.Center
                    )
                    
                    Spacer(modifier = Modifier.height(48.dp))
                    
                    // Email/Password Card
                    GlowingFormCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp)
                        ) {
                            // Email field
                            GlowingTextField(
                                value = uiState.email,
                                onValueChange = { viewModel.onEmailChange(it) },
                                placeholder = "Email address",
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Email,
                                    imeAction = ImeAction.Next
                                ),
                                keyboardActions = KeyboardActions(
                                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                                ),
                                enabled = !uiState.isLoading
                            )
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // Password field
                            GlowingTextField(
                                value = uiState.password,
                                onValueChange = { viewModel.onPasswordChange(it) },
                                placeholder = "Password",
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        focusManager.clearFocus()
                                        viewModel.onSignIn()
                                    }
                                ),
                                enabled = !uiState.isLoading
                            )
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // Forgot password
                            TextButton(
                                onClick = { viewModel.onForgotPassword() },
                                enabled = !uiState.isLoading
                            ) {
                                Text(
                                    text = "Forgot password?",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AceLavender
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Sign In button
                            GlowButton(
                                text = "Sign In",
                                onClick = { viewModel.onSignIn() },
                                enabled = !uiState.isLoading &&
                                        uiState.email.isNotBlank() &&
                                        uiState.password.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
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
                    contentColor = AceTextWhite,
                    dismissAction = {
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("Dismiss", color = AceLavender)
                        }
                    }
                ) {
                    Text(text = error)
                }
            }
        }
    }
}

@Composable
fun GlowingFormCard(
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Outer glow
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .blur(20.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFFF9ACB).copy(alpha = 0.22f),
                            Color.Transparent
                        )
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
        )
        
        // Card surface
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFFF9ACB).copy(alpha = 0.85f),
                            Color(0xFFFFD6EA).copy(alpha = 0.95f),
                            Color(0xFFD9E9FF).copy(alpha = 0.85f),
                            Color(0xFFFF9ACB).copy(alpha = 0.75f)
                        )
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
                .background(
                    color = AceSurfaceDark.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(16.dp)
                )
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlowingTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    enabled: Boolean = true
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(
                text = placeholder,
                color = AceTextMuted
            )
        },
        modifier = modifier
            .fillMaxWidth(),
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        enabled = enabled,
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = AceBackgroundCharcoal,
            unfocusedContainerColor = AceBackgroundCharcoal,
            disabledContainerColor = AceBackgroundCharcoal,
            focusedTextColor = AceTextWhite,
            unfocusedTextColor = AceTextWhite,
            disabledTextColor = AceTextGray,
            cursorColor = AceLavender,
            focusedIndicatorColor = AceBorderGlow,
            unfocusedIndicatorColor = AceBorderDark,
            disabledIndicatorColor = AceBorderDark
        ),
        shape = RoundedCornerShape(12.dp)
    )
}
