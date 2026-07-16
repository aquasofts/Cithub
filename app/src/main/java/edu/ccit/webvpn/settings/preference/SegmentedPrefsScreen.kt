package edu.ccit.webvpn.settings.preference

import androidx.annotation.StringRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.ccit.webvpn.settings.Settings
import androidx.compose.material3.Switch
import kotlinx.coroutines.Dispatchers

interface SegmentedPrefsScope{

    /**
     * Adds a custom preference to the [SegmentedPrefsScreen].
     */
    fun customPreference(key: Any? = null, content: @Composable LazyItemScope.(shapes: ListItemShapes) -> Unit)

    /**
     * Adds a clickable preference to the [SegmentedPrefsScreen].
     *
     * @param onClick The action to perform when this preference is clicked.
     * @param title text which describes this preference.
     * @param summary used to give some more information about what this preference is for.
     * @param icon optional composable representing the preference's icon.
     * @param trailingContent the trailing content of this list item, such as a checkbox, switch, or
     *   icon.
     * @param enabled whether this preference is enabled.
     */
    fun preference(
        onClick: () -> Unit = {},
        title: @Composable () -> Unit,
        summary: @Composable (() -> Unit)? = null,
        icon: @Composable (() -> Unit)? = null,
        trailingContent: @Composable (() -> Unit)? = null,
        enabled: Boolean = true,
    )

    fun preference(
        onClick: () -> Unit = {},
        title: String,
        summary: String? = null,
        icon: ImageVector? = null,
        trailingIcon: ImageVector? = null,
        enabled: Boolean = true,
    )

    /**
     * Adds a list preference to the [SegmentedPrefsScreen].
     *
     * This preference will show a list of entries in a DropDownMenu where a single entry can be
     * selected at one time.
     *
     * @param value current selected option of this preference.
     * @param onValueChange Callback to be invoked when user selected new option in [options]
     * @param options all available option with its string description.
     * @param title text which describes this preference.
     * @param summary used to give some more information about what this preference is for
     * @param useSelectedAsSummary set true to use the current selected option description as [summary]
     * @param optionsIconSupplier leading icon to be drawn at the beginning of option menu item.
     * @param leadingIcon leading icon to be drawn at the beginning of the preference,
     *   typically [Icon].
     */
    fun <T> listPref(
        value: T,
        onValueChange: (T) -> Unit,
        options: StringLabelOptions<T>,
        title: String,
        summary: String? = null,
        useSelectedAsSummary: Boolean = summary == null,
        optionsIconSupplier: (@Composable (T) -> Unit)? = null,
        leadingIcon: ImageVector? = null,
    )

    /**
     * Adds a time preference to the [SegmentedPrefsScreen].
     *
     * This preference will show a picker that allows the user to select time.
     *
     * @param time the initial time for the time picker
     * @param onTimeChange called when user picked new time.
     * @param title text which describes this preference.
     * @param summary Used to give some more information about what this preference is for
     * @param dialogTitle optional title of the time picker dialog.
     * @param enabled controls the enabled state of this pref. When `false`, this component will
     *   not respond to user input, and it will appear visually disabled.
     *
     * @see [androidx.compose.material3.TimePicker]
     */
    fun timePreference(
        time: HmTime,
        onTimeChange: (HmTime) -> Unit,
        title: @Composable () -> Unit,
        summary: @Composable (() -> Unit)? = null,
        dialogTitle: @Composable (() -> Unit)? = null,
        leadingIcon: ImageVector? = null,
        enabled: Boolean = true,
    )

    /**
     * Adds grouped preferences to the [SegmentedPrefsScreen].
     *
     * @param title group title.
     * @param verticalPadding vertical padding to be applied on this group.
     * @param content a block which describes the child preferences.
     */
    fun group(
        @StringRes title: Int? = null,
        titleVerticalPadding: Dp = 16.dp,
        verticalPadding: Dp = Dp.Hairline,
        content: SegmentedPrefsScope.() -> Unit
    )
}

interface SettingsSegmentedPrefsScope<T>: SegmentedPrefsScope {

    val currentPreference: T

    fun update(transform: (T) -> T)

    /**
     * Adds a toggleable preference to the [SegmentedPrefsScreen].
     *
     * @param value current switch value.
     * @param title preference title.
     * @param summary used to give some more information about what this preference is for
     * @param leadingIcon an optional composable representing the item's icon.
     * @param enabled controls the enabled state of this pref. When `false`, this component will
     *   not respond to user input, and it will appear visually disabled.
     */
    fun toggleablePreference(
        value: Boolean,
        onValueChange: (Boolean) -> Unit,
        title: Int,
        summary: Int? = null,
        leadingIcon: ImageVector? = null,
        enabled: Boolean = true,
        onCheckedChange: ((Boolean) -> Unit)? = null,
    )

    /**
     * Adds a list preference to the [SegmentedPrefsScreen].
     *
     * This preference will show a list of entries in a Dialog where a single entry can be
     * selected at one time.
     *
     * @param value current selected value.
     * @param title text which describes this preference.
     * @param options all available options of this preference with description.
     * @param optionsIconSupplier leading icon to be drawn at the beginning of option menu item.
     * @param summary used to give some more information about what this preference is for
     * @param useSelectedAsSummary set true to use the current selected option description as [summary]
     * @param leadingIcon optional leading icon to be drawn at the beginning of the preference.
     * @param enabled controls the enabled state of this list pref. When `false`, this component will
     *   not respond to user input, and it will appear visually disabled.
     */
    fun <K> listPref(
        value: K,
        onValueChange: (K) -> Unit,
        @StringRes title: Int,
        options: Options<K>,
        optionsIconSupplier: (@Composable (K) -> Unit)? = null,
        @StringRes summary: Int? = null,
        useSelectedAsSummary: Boolean = summary == null,
        leadingIcon: ImageVector? = null,
        enabled: Boolean = true,
    )
}

private sealed interface ItemType {

    data object Custom: ItemType

    data object Clickable : ItemType

    data object Toggleable: ItemType

    data object GroupHeader: ItemType

    data object GroupEnd: ItemType
}

private open class SegmentedPrefsScopeImpl(
    lazyListScope: LazyListScope,
): SegmentedPrefsScope, LazyListScope by lazyListScope {

    /** For ListItemShapes type tracking, see [segmentedShapes] */
    protected val itemTypes: MutableList<ItemType> = mutableListOf()

    val itemsCount: Int
        get() = itemTypes.size

    protected inline fun prefsItem(
        key: Any? = null,
        contentType: ItemType,
        crossinline content: @Composable LazyItemScope.(shapes: ListItemShapes) -> Unit
    ) {
        val index = itemsCount
        item(key, contentType) {
            content(this@SegmentedPrefsScopeImpl.segmentedShapes(index))
        }
        itemTypes.add(contentType)
    }

    override fun customPreference(key: Any?, content: @Composable LazyItemScope.(shapes: ListItemShapes) -> Unit) {
        prefsItem(key, contentType = ItemType.Custom, content)
    }

    override fun preference(
        onClick: () -> Unit,
        title: @Composable () -> Unit,
        summary: @Composable (() -> Unit)?,
        icon: @Composable (() -> Unit)?,
        trailingContent: @Composable (() -> Unit)?,
        enabled: Boolean
    ) {
        prefsItem(contentType = ItemType.Clickable) { shapes ->
            SegmentedPreference(
                title = title,
                shapes = shapes,
                leadingIcon = icon,
                trailingContent = trailingContent,
                summary = summary,
                enabled = enabled,
                onClick = onClick,
            )
        }
    }

    override fun preference(
        onClick: () -> Unit,
        title: String,
        summary: String?,
        icon: ImageVector?,
        trailingIcon: ImageVector?,
        enabled: Boolean,
    ) {
        preference(
            onClick = onClick,
            title = { Text(text = title) },
            summary = summary?.let { { Text(text = it) } },
            icon = icon?.let { imageVector ->
                { Icon(imageVector, contentDescription = null) }
            },
            trailingContent = trailingIcon?.let { imageVector ->
                { Icon(imageVector, contentDescription = null) }
            },
            enabled = enabled,
        )
    }

    override fun <T> listPref(
        value: T,
        onValueChange: (T) -> Unit,
        options: StringLabelOptions<T>,
        title: String,
        summary: String?,
        useSelectedAsSummary: Boolean,
        optionsIconSupplier: (@Composable (T) -> Unit)?,
        leadingIcon: ImageVector?,
    ) {
        prefsItem(key = title, contentType = ItemType.Clickable) { shapes ->
            SegmentedListPreference(
                value = value,
                title = title,
                summary = summary,
                options = options,
                optionsIconSupplier = optionsIconSupplier,
                useSelectedAsSummary = useSelectedAsSummary,
                leadingIcon = leadingIcon,
                shapes = shapes,
                onValueChange = onValueChange
            )
        }
    }

    override fun timePreference(
        time: HmTime,
        onTimeChange: (HmTime) -> Unit,
        title: @Composable (() -> Unit),
        summary: @Composable (() -> Unit)?,
        dialogTitle: @Composable (() -> Unit)?,
        leadingIcon: ImageVector?,
        enabled: Boolean
    ) {
        prefsItem(contentType = ItemType.Clickable) { shapes ->
            val dialogState = rememberDialogState()

            SegmentedPreference(
                title = title,
                shapes = shapes,
                leadingIcon = leadingIcon?.let { { Icon(imageVector = it, contentDescription = null) } },
                summary = summary,
                enabled = enabled,
                onClick = dialogState::show
            )

            if (dialogState.show) {
                TimePickerDialog(
                    initialTime = time,
                    title = dialogTitle,
                    onConfirm = { state ->
                        val newTime = HmTime(hourOfDay = state.hour, minute = state.minute)
                        if (newTime != time) {
                            onTimeChange(newTime)
                        }
                    },
                    dialogState = dialogState,
                )
            }
        }
    }

    override fun group(
        @StringRes title: Int?,
        titleVerticalPadding: Dp,
        verticalPadding: Dp,
        content: SegmentedPrefsScope.() -> Unit
    ) {
        itemTypes.add(ItemType.GroupHeader)
        if (title != null) {
            item(key = title, contentType = ItemType.GroupHeader) {
                Text(
                    text = stringResource(title),
                    modifier = Modifier
                        .then(
                            if (verticalPadding > Dp.Hairline) {
                                Modifier.padding(top = verticalPadding)
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = 8.dp, vertical = titleVerticalPadding),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        } else if (verticalPadding > Dp.Hairline) {
            item {
                Spacer(modifier = Modifier.height(verticalPadding))
            }
        }
        content()
        itemTypes.add(ItemType.GroupEnd)
        if (verticalPadding > Dp.Hairline) {
            item(contentType = ItemType.GroupEnd) {
                Spacer(modifier = Modifier.height(verticalPadding))
            }
        }
    }

    @Stable
    @Composable
    protected fun segmentedShapes(index: Int): ListItemShapes {
        val count = itemTypes.size
        val isFirstItem = index == 0 ||
                itemTypes.getOrNull(index - 1)?.let { it is ItemType.GroupHeader || it is ItemType.GroupEnd } == true

        val isLastItem = index + 1 == count ||
                itemTypes.getOrNull(index + 1)?.let { it is ItemType.GroupHeader || it is ItemType.GroupEnd } == true

        return when {
            isFirstItem && isLastItem -> ListItemDefaults.shapes().run { copy(shape = selectedShape) }

            isFirstItem -> ListItemDefaults.segmentedShapes(index = 0, count = count)

            isLastItem -> ListItemDefaults.segmentedShapes(index = index, count = index + 1)

            else -> ListItemDefaults.segmentedShapes(index, count)
        }
    }
}

private class SettingsSegmentedPrefsScopeImpl<T>(
    lazyListScope: LazyListScope,
    private val state: State<T>,
    private val settings: Settings<T>,
): SettingsSegmentedPrefsScope<T>, SegmentedPrefsScopeImpl(lazyListScope) {

    override val currentPreference: T
        get() = state.value

    override fun update(transform: (T) -> T) = settings.save(transform)

    override fun toggleablePreference(
        value: Boolean,
        onValueChange: (Boolean) -> Unit,
        title: Int,
        summary: Int?,
        leadingIcon: ImageVector?,
        enabled: Boolean,
        onCheckedChange: ((Boolean) -> Unit)?,
    ) {
        prefsItem(key = title, contentType = ItemType.Toggleable) { shapes ->
            val interactionSource = remember { MutableInteractionSource() }
            SegmentedPreference(
                title = title,
                onClick = {
                    val newCheckedState = !value
                    onValueChange(newCheckedState)
                    onCheckedChange?.invoke(newCheckedState)
                },
                shapes = shapes,
                enabled = enabled,
                leadingIcon = leadingIcon,
                trailingContent = {
                    Switch(
                        checked = value,
                        enabled = enabled,
                        onCheckedChange = null,
                        interactionSource = interactionSource,
                    )
                },
                summary = summary,
                interactionSource = interactionSource,
            )
        }
    }

    override fun <K> listPref(
        value: K,
        onValueChange: (K) -> Unit,
        @StringRes title: Int,
        options: Options<K>,
        optionsIconSupplier: (@Composable (K) -> Unit)?,
        @StringRes summary: Int?,
        useSelectedAsSummary: Boolean,
        leadingIcon: ImageVector?,
        enabled: Boolean,
    ) {
        prefsItem(key = title, contentType = ItemType.Clickable) { shapes ->
            val stringOptions = mutableMapOf<K, String>()
            for ((option, optionSummary) in options) {
                stringOptions[option] = stringResource(optionSummary)
            }

            SegmentedListPreference(
                value = value,
                title = stringResource(title),
                summary = if (summary == null) null else stringResource(summary),
                options = stringOptions,
                optionsIconSupplier = optionsIconSupplier,
                useSelectedAsSummary = useSelectedAsSummary,
                leadingIcon = leadingIcon,
                shapes = shapes,
                enabled = enabled,
                onValueChange = onValueChange,
            )
        }
    }
}

fun SegmentedPrefsScope.preference(
    onClick: () -> Unit,
    @StringRes title: Int,
    @StringRes summary: Int? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    enabled: Boolean = true,
) =
    preference(
        onClick = onClick,
        title = { Text(text = stringResource(id = title)) },
        summary = summary?.let {
            { Text(text = stringResource(id = it)) }
        },
        icon = leadingIcon?.let { imageVector ->
            { Icon(imageVector, contentDescription = null) }
        },
        trailingContent = trailingIcon?.let { imageVector ->
            { Icon(imageVector, contentDescription = null) }
        },
        enabled = enabled,
    )

fun <T> SettingsSegmentedPrefsScope<T>.toggleablePreference(
    value: Boolean,
    onValueChange: (Boolean) -> Unit,
    title: Int,
    summaryOn: Int,
    summaryOff: Int,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
) =
    toggleablePreference(
        value = value,
        onValueChange = onValueChange,
        title = title,
        summary = if (value) summaryOn else summaryOff,
        leadingIcon = leadingIcon,
        enabled = enabled,
    )

@Composable
fun <T> SegmentedPrefsScreen(
    modifier: Modifier = Modifier,
    settings: Settings<T>,
    initialValue: T,
    contentPadding: PaddingValues = PaddingValues.Zero,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(2.dp),
    content: SettingsSegmentedPrefsScope<T>.() -> Unit
) {
    val latestContent by rememberUpdatedState(content)

    val settingsState = settings.collectAsStateWithLifecycle(
        initialValue = initialValue,
        minActiveState = Lifecycle.State.CREATED,
        context = Dispatchers.IO
    )

    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
    ) {
        val scope = SettingsSegmentedPrefsScopeImpl(
            lazyListScope = this,
            state = settingsState,
            settings = settings,
        )
        scope.latestContent()
    }
}

@Composable
fun SegmentedTextPrefsScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues.Zero,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(2.dp),
    content: SegmentedPrefsScope.() -> Unit
) {
    val latestContent by rememberUpdatedState(content)

    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
    ) {
        SegmentedPrefsScopeImpl(lazyListScope = this).latestContent()
    }
}

