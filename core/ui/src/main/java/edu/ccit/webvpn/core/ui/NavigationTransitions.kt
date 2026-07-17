package edu.ccit.webvpn.core.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith

fun ccitForwardNavigationTransition(reduceMotion: Boolean): ContentTransform {
    val duration = if (reduceMotion) 120 else 220
    val enter = fadeIn(tween(duration, easing = FastOutSlowInEasing)) +
        scaleIn(
            initialScale = if (reduceMotion) 1f else 0.9f,
            animationSpec = tween(duration, easing = FastOutSlowInEasing),
        )
    val exit = fadeOut(tween(duration, easing = FastOutSlowInEasing)) +
        scaleOut(
            targetScale = if (reduceMotion) 1f else 1.1f,
            animationSpec = tween(duration, easing = FastOutSlowInEasing),
        )
    return enter togetherWith exit
}

fun ccitBackwardNavigationTransition(reduceMotion: Boolean): ContentTransform {
    val duration = if (reduceMotion) 120 else 220
    val enter = fadeIn(tween(duration, easing = FastOutSlowInEasing)) +
        scaleIn(
            initialScale = if (reduceMotion) 1f else 1.1f,
            animationSpec = tween(duration, easing = FastOutSlowInEasing),
        )
    val exit = fadeOut(tween(duration, easing = FastOutSlowInEasing)) +
        scaleOut(
            targetScale = if (reduceMotion) 1f else 0.9f,
            animationSpec = tween(duration, easing = FastOutSlowInEasing),
        )
    return enter togetherWith exit
}
