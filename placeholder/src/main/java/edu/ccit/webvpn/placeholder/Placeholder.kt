/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.ccit.webvpn.placeholder

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape

/**
 * Local skeleton placeholder adapted from the placeholder implementation used by the reference UI.
 * It deliberately owns no Material dependency so the app controls colors and motion policy.
 */
fun Modifier.placeholder(
    visible: Boolean,
    color: Color,
    highlightColor: Color,
    shape: Shape,
    reduceMotion: Boolean = false,
): Modifier = composed {
    val visibility = updateTransition(visible, label = "placeholder visibility")
    val overlayAlpha by visibility.animateFloat(
        transitionSpec = { tween(if (reduceMotion) 90 else 220, easing = FastOutSlowInEasing) },
        label = "placeholder alpha",
    ) { shown -> if (shown) 1f else 0f }
    val highlight = rememberInfiniteTransition(label = "placeholder highlight")
    val highlightAlpha by highlight.animateFloat(
        initialValue = 0.05f,
        targetValue = if (reduceMotion) 0.05f else 0.42f,
        animationSpec = infiniteRepeatable(
            animation = tween(650, delayMillis = 120, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "placeholder highlight alpha",
    )

    drawWithContent {
        drawContent()
        if (overlayAlpha > 0f) {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = when (outline) {
                is Outline.Generic -> outline.path
                is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
                is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
            }
            drawPath(path, color.copy(alpha = color.alpha * overlayAlpha))
            if (!reduceMotion) {
                drawPath(
                    path,
                    highlightColor.copy(alpha = highlightColor.alpha * highlightAlpha * overlayAlpha),
                )
            }
        }
    }
}
