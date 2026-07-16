package edu.ccit.webvpn.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import edu.ccit.webvpn.placeholder.placeholder

val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun Modifier.ccitPlaceholder(
    visible: Boolean = true,
    shape: Shape = MaterialTheme.shapes.small,
): Modifier = placeholder(
    visible = visible,
    color = MaterialTheme.colorScheme.surfaceContainerHighest,
    highlightColor = MaterialTheme.colorScheme.primaryContainer,
    shape = shape,
    reduceMotion = LocalReduceMotion.current,
)

@Composable
fun CcitSkeletonBlock(
    modifier: Modifier,
    shape: Shape = MaterialTheme.shapes.small,
) {
    Spacer(modifier.ccitPlaceholder(shape = shape))
}

@Composable
fun CcitSkeletonCard(
    modifier: Modifier = Modifier,
    lines: Int = 3,
) {
    CcitCard(modifier) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CcitSkeletonBlock(
                Modifier.fillMaxWidth(0.58f).height(22.dp),
                shape = CircleShape,
            )
            repeat(lines.coerceAtLeast(1)) { index ->
                CcitSkeletonBlock(
                    Modifier.fillMaxWidth(if (index == lines - 1) 0.72f else 1f).height(15.dp),
                    shape = CircleShape,
                )
            }
        }
    }
}
