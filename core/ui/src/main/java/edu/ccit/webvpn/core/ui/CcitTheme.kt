package edu.ccit.webvpn.core.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.material.color.utilities.Variant
import edu.ccit.webvpn.core.ui.palette.colorscheme.BlueColorScheme
import edu.ccit.webvpn.core.ui.palette.colorscheme.GreenColorScheme
import edu.ccit.webvpn.core.ui.palette.colorscheme.OrangeColorScheme
import edu.ccit.webvpn.core.ui.palette.colorscheme.PinkColorScheme
import edu.ccit.webvpn.core.ui.palette.colorscheme.PurpleColorScheme
import edu.ccit.webvpn.core.ui.palette.colorscheme.dynamicColorScheme
import edu.ccit.webvpn.core.ui.palette.colorscheme.monetColorScheme

enum class CcitPalette { Blue, Green, Orange, Pink, Purple, Dynamic, Custom }

object CcitColors {
    val Shell: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background
    val Surface: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surface
    val Card: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceContainer
    val CardStrong: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primaryContainer
    val Ink: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onBackground
    val InkMuted: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val Stroke: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outlineVariant
    val Rose: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.secondary
    val Brown: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
    val Success: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.tertiary
}

@Composable
fun CcitAcademicTheme(
    palette: CcitPalette = CcitPalette.Dynamic,
    customSeedColor: Int = 0xFF705D61.toInt(),
    customVariant: Variant = Variant.CONTENT,
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoled: Boolean = false,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when (palette) {
        CcitPalette.Blue -> BlueColorScheme
        CcitPalette.Green -> GreenColorScheme
        CcitPalette.Orange -> OrangeColorScheme
        CcitPalette.Pink -> PinkColorScheme
        CcitPalette.Purple -> PurpleColorScheme
        CcitPalette.Custom -> dynamicColorScheme(customSeedColor, customVariant)
        CcitPalette.Dynamic -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            monetColorScheme(context)
        } else {
            dynamicColorScheme(0xFF705D61.toInt(), Variant.CONTENT)
        }
    }.getColorScheme(darkTheme, amoled)

    CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
        MaterialExpressiveTheme(
            colorScheme = colors,
            motionScheme = if (reduceMotion) MotionScheme.standard() else MotionScheme.expressive(),
            typography = CcitTypography,
            shapes = CcitShapes,
            content = content,
        )
    }
}

@Composable
fun Modifier.ccitBackground(): Modifier = background(CcitColors.Shell)

@Composable
fun CcitCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content,
    )
}

@Composable
fun CcitPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 52.dp),
        enabled = enabled,
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        content = content,
    )
}

@Composable
fun CcitOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 52.dp),
        enabled = enabled,
        shapes = ButtonDefaults.shapes(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        content = content,
    )
}

@Composable
fun ccitTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
    cursorColor = MaterialTheme.colorScheme.primary,
)
