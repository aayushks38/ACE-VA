package com.ace.app.ui.model

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ace.app.ui.components.AceBackground
import com.ace.app.ui.components.GlowButton

private val Ink = Color(0xFFF8F7FF)
private val Muted = Color(0xFFB6B0C9)
private val Purple = Color(0xFF9D6BFF)
private val Surface = Color(0xFF1C1530)
private val DangerRed = Color(0xFFFF4D4D)
private val SuccessGreen = Color(0xFF4EEB99)

@Composable
fun ModelSetupScreen(
    onModelReady: () -> Unit,
    onSignOutClick: () -> Unit,
    viewModel: ModelDownloadViewModel = viewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.isReady) {
        if (state.isReady) {
            onModelReady()
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (ignored: Exception) {}

            viewModel.onFileSelected(context, uri)
        }
    }

    val isDiscoveredAvailable = remember {
        val discovery = com.ace.app.brain.model.ModelRepository.discoverModel(context)
        discovery.state == com.ace.app.brain.model.ModelDiscoveryState.MODEL_FOUND
    }

    AceBackground {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 440.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Surface.copy(alpha = 0.85f))
                    .border(1.dp, Purple.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "GEMMA LOCAL BRAIN SETUP",
                    color = Ink,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "ACE operates on an on-device Gemma AI model for private, fast local reasoning.",
                    color = Muted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )

                Spacer(modifier = Modifier.height(28.dp))

                when {
                    state.isLoading -> {
                        CircularProgressIndicator(color = Purple, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Initializing Gemma Local Brain...", color = Ink, fontSize = 14.sp)
                    }

                    state.isDownloading -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            LinearProgressIndicator(
                                progress = { state.downloadProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp)),
                                color = Purple,
                                trackColor = Surface
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            Text(
                                text = "${(state.downloadProgress * 100).toInt()}% Downloaded",
                                color = Ink,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = "${state.downloadedFormatted} / ${state.totalFormatted}",
                                color = Muted,
                                fontSize = 12.sp
                            )

                            Spacer(modifier = Modifier.height(20.dp))

                            Text(
                                text = "Cancel Download",
                                color = DangerRed,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(DangerRed.copy(alpha = 0.15f))
                                    .clickable { viewModel.cancelDownload() }
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }

                    else -> {
                        // Options Card
                        Column(
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isDiscoveredAvailable) {
                                GlowButton(
                                    text = "Initialize Detected Model (GGUF)",
                                    onClick = {
                                        viewModel.initializeDiscoveredModel(context)
                                    },
                                    glowIntensity = 0.8f,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Text(
                                    text = "Found existing Gemma model file in Downloads directory.",
                                    color = SuccessGreen,
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(4.dp))
                            }

                            OutlinedButton(
                                onClick = {
                                    try {
                                        documentPickerLauncher.launch(arrayOf("*/*"))
                                    } catch (_: Exception) {}
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp),
                                shape = RoundedCornerShape(14.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Purple)
                            ) {
                                Text("Select Other Model File", color = Ink, fontWeight = FontWeight.SemiBold)
                            }

                            Text(
                                text = "Choose a .gguf model file already downloaded to your device storage.",
                                color = Muted,
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            OutlinedButton(
                                onClick = { viewModel.startDownload(context) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp),
                                shape = RoundedCornerShape(14.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Purple.copy(alpha = 0.5f))
                            ) {
                                Text("Download Gemma Model (GGUF)", color = Muted, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                if (!state.errorMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = state.errorMessage!!,
                        color = DangerRed,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(DangerRed.copy(alpha = 0.12f))
                            .padding(12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Sign Out",
                    color = Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { onSignOutClick() }
                )
            }
        }
    }
}
