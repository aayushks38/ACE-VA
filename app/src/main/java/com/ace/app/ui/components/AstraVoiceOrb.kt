package com.ace.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ace.app.voice.VoiceState

private val PurpleCore = Color(0xFF9D6BFF)
private val CyanGlow = Color(0xFF00F0FF)
private val MagentaGlow = Color(0xFFFF007A)
private val AmberWorking = Color(0xFFFFB800)
private val DeepIndigo = Color(0xFF19102E)

@Composable
fun AstraVoiceOrb(
    voiceState: VoiceState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "AstraOrbPulse")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = when (voiceState) {
            VoiceState.LISTENING -> 1.18f
            VoiceState.SPEAKING -> 1.15f
            VoiceState.EXECUTING -> 1.12f
            VoiceState.THINKING -> 1.08f
            else -> 1.05f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (voiceState) {
                    VoiceState.THINKING -> 400
                    VoiceState.EXECUTING -> 500
                    VoiceState.SPEAKING -> 600
                    VoiceState.LISTENING -> 800
                    else -> 1500
                },
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "OrbScale"
    )

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing)
        ),
        label = "OrbRotate"
    )

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(180.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            val baseRadius = (size.minDimension / 2.6f) * pulseScale

            // Outer glowing aura rings
            val outerBrush = Brush.radialGradient(
                colors = listOf(
                    when (voiceState) {
                        VoiceState.LISTENING -> CyanGlow.copy(alpha = 0.5f)
                        VoiceState.SPEAKING -> MagentaGlow.copy(alpha = 0.5f)
                        VoiceState.THINKING -> PurpleCore.copy(alpha = 0.7f)
                        VoiceState.EXECUTING -> AmberWorking.copy(alpha = 0.6f)
                        else -> PurpleCore.copy(alpha = 0.3f)
                    },
                    Color.Transparent
                ),
                center = center,
                radius = baseRadius * 1.5f
            )
            drawCircle(brush = outerBrush, radius = baseRadius * 1.5f, center = center)

            // Dynamic Stroke Ring
            drawCircle(
                color = when (voiceState) {
                    VoiceState.LISTENING -> CyanGlow
                    VoiceState.SPEAKING -> MagentaGlow
                    VoiceState.THINKING -> PurpleCore
                    VoiceState.EXECUTING -> AmberWorking
                    else -> Color(0xFF6E47D5)
                }.copy(alpha = 0.8f),
                radius = baseRadius * 1.15f,
                center = center,
                style = Stroke(width = 4.dp.toPx())
            )

            // Inner Orb Gradient Core
            val coreBrush = Brush.sweepGradient(
                colors = listOf(
                    PurpleCore,
                    CyanGlow,
                    MagentaGlow,
                    PurpleCore
                ),
                center = center
            )
            drawCircle(brush = coreBrush, radius = baseRadius, center = center)
        }

        // Center Icon & Label
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = when (voiceState) {
                    VoiceState.LISTENING -> "🎙"
                    VoiceState.SPEAKING -> "🔊"
                    VoiceState.THINKING -> "⚡"
                    VoiceState.EXECUTING -> "⚙"
                    VoiceState.IDLE -> "✦"
                },
                fontSize = 36.sp,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = when (voiceState) {
                    VoiceState.LISTENING -> "Listening..."
                    VoiceState.SPEAKING -> "ACE speaks..."
                    VoiceState.THINKING -> "Thinking..."
                    VoiceState.EXECUTING -> "Working..."
                    VoiceState.IDLE -> "Tap to speak"
                },
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
