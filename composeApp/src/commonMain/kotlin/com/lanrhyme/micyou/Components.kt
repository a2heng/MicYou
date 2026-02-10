package com.lanrhyme.micyou

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun WaterRippleEffect(
    trigger: Int,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    maxScale: Float = 2.0f,
    durationMillis: Int = 600
) {
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(1f) }

    LaunchedEffect(trigger) {
        if (trigger > 0) {
            alpha.snapTo(1f)
            scale.snapTo(1f)
            launch {
                scale.animateTo(maxScale, animationSpec = tween(durationMillis))
            }
            launch {
                alpha.animateTo(0f, animationSpec = tween(durationMillis))
            }
        }
    }

    if (alpha.value > 0f) {
        Box(
            modifier = modifier
                .scale(scale.value)
                .alpha(alpha.value)
                .border(2.dp, color, CircleShape)
        )
    }
}

