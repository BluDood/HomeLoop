package com.bludood.homeloop.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.bludood.homeloop.R
import com.bludood.homeloop.ui.theme.Space

@Composable
internal fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.md)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        Column(
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            content = content
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsSwitchRow(
    @DrawableRes icon: Int,
    title: String,
    supporting: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    index: Int = 0,
    count: Int = 1,
    enabled: Boolean = true,
    toggleFeedback: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
) {
    val feedback = LocalHapticFeedback.current
    val shapes = ListItemDefaults.segmentedShapes(index, count)
    val surface = containerColor
    val resting = ListItemDefaults.segmentedColors()
    val colors = ListItemDefaults.segmentedColors(
        containerColor = surface,
        selectedContainerColor = surface,
        selectedContentColor = resting.contentColor,
        selectedLeadingContentColor = resting.leadingContentColor,
        selectedSupportingContentColor = resting.supportingContentColor,
        selectedTrailingContentColor = resting.trailingContentColor,
    )
    val switch: @Composable () -> Unit =
        { Switch(checked = checked, onCheckedChange = null, enabled = enabled) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth / LocalDensity.current.fontScale < 240.dp
        SegmentedListItem(
            checked = checked,
            onCheckedChange = { value ->
                if (value != checked) {
                    onChecked(value)
                    if (toggleFeedback) {
                        feedback.performHapticFeedback(
                            if (value) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
                        )
                    }
                }
            },
            enabled = enabled,
            modifier = Modifier.semantics { role = Role.Switch },
            shapes = shapes.copy(selectedShape = shapes.shape),
            colors = colors,
            verticalAlignment = Alignment.CenterVertically,
            leadingContent = if (stacked) null else ({ Icon(painterResource(icon), null) }),
            content = {
                if (stacked) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.md)
                    ) {
                        Icon(painterResource(icon), null)
                        Text(title, Modifier.weight(1f))
                    }
                } else {
                    Text(title)
                }
            },
            supportingContent = {
                if (stacked) {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) { Text(supporting); switch() }
                } else {
                    Text(supporting)
                }
            },
            trailingContent = if (stacked) null else switch,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsActionRow(
    @DrawableRes icon: Int,
    title: String,
    supporting: String,
    onClick: () -> Unit,
    index: Int = 0,
    count: Int = 1,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = {
        Icon(
            painterResource(R.drawable.ms_keyboard_arrow_right_24),
            null
        )
    },
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        enabled = enabled,
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        verticalAlignment = Alignment.CenterVertically,
        leadingContent = { Icon(painterResource(icon), null) },
        supportingContent = { Text(supporting) },
        trailingContent = trailing,
        content = { Text(title) },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun InfoRow(
    title: String,
    value: String,
    index: Int = 0,
    count: Int = 1,
    @DrawableRes icon: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        verticalAlignment = Alignment.CenterVertically,
        leadingContent = icon?.let { { Icon(painterResource(it), null) } },
        supportingContent = { Text(value) },
        trailingContent = trailing,
        content = { Text(title) },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun WavyProgress(progress: Float, modifier: Modifier = Modifier) {
    LinearWavyProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun <T> ChoiceDialogRow(
    @DrawableRes icon: Int,
    title: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
    optionDescription: ((T) -> String)? = null,
    index: Int = 0,
    count: Int = 1,
    enabled: Boolean = true,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    SettingsActionRow(
        icon = icon,
        title = title,
        supporting = optionLabel(selected),
        onClick = { open = true },
        index = index,
        count = count,
        enabled = enabled,
        trailing = { Icon(painterResource(R.drawable.ms_arrow_drop_down_24), null) },
    )
    if (!open) return
    val haptics = LocalHapticFeedback.current
    var pending by rememberSaveable {
        mutableIntStateOf(
            options.indexOf(selected).coerceAtLeast(0)
        )
    }
    AlertDialog(
        onDismissRequest = { open = false },
        icon = { Icon(painterResource(icon), null, Modifier.size(AlertDialogDefaults.IconSize)) },
        title = { Text(title) },
        text = {
            Column(Modifier
                .selectableGroup()
                .verticalScroll(rememberScrollState())) {
                options.forEachIndexed { optionIndex, option ->
                    ListItem(
                        selected = pending == optionIndex,
                        onClick = {
                            if (pending != optionIndex) {
                                pending = optionIndex
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                        },
                        modifier = Modifier.semantics { role = Role.RadioButton },
                        colors = ListItemDefaults.colors(containerColor = AlertDialogDefaults.containerColor),
                        leadingContent = {
                            RadioButton(
                                selected = pending == optionIndex,
                                onClick = null
                            )
                        },
                        supportingContent = optionDescription?.let { describe ->
                            {
                                Text(
                                    describe(
                                        option
                                    )
                                )
                            }
                        },
                        content = { Text(optionLabel(option)) },
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                open = false
                if (options[pending] != selected) onSelected(options[pending])
            }, shapes = ButtonDefaults.shapes()) { Text("Apply") }
        },
        dismissButton = {
            TextButton(
                onClick = { open = false },
                shapes = ButtonDefaults.shapes()
            ) { Text("Cancel") }
        },
    )
}

@Composable
internal fun ChoiceSetting(
    @DrawableRes icon: Int,
    title: String,
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    index: Int = 0,
    count: Int = 1,
) {
    val colors = ListItemDefaults.segmentedColors()
    Surface(
        Modifier.fillMaxWidth(),
        shape = ListItemDefaults.segmentedShapes(index, count).shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = colors.contentColor,
    ) {
        Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.md)
            ) {
                Icon(painterResource(icon), null, tint = colors.leadingContentColor)
                Text(title, style = MaterialTheme.typography.bodyLarge)
            }
            ConnectedChoices(labels, selectedIndex, onSelected, groupLabel = title)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ConnectedChoices(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    groupLabel: String? = null,
) {
    val sources = remember(labels) { List(labels.size) { MutableInteractionSource() } }
    val haptics = LocalHapticFeedback.current
    val select: (Int) -> Unit = { index ->
        if (index != selectedIndex) {
            onSelected(index)
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val padding = ToggleButtonDefaults.contentPaddingFor(ToggleButtonDefaults.size, false)
    val widths = labels.map { label ->
        with(density) { measurer.measure(label, style).size.width.toDp() } +
                padding.calculateLeftPadding(direction) + padding.calculateRightPadding(direction)
    }
    val gap = ButtonGroupDefaults.ConnectedSpaceBetween
    fun named(label: String): Modifier =
        if (groupLabel != null) Modifier.semantics {
            contentDescription = "$label, $groupLabel"
        } else Modifier
    BoxWithConstraints(Modifier
        .fillMaxWidth()
        .selectableGroup()) {
        val itemWidth = (maxWidth - gap * (labels.size - 1)) / labels.size
        if (itemWidth < maxOf(80.dp, widths.max())) {
            Column {
                labels.forEachIndexed { index, label ->
                    ListItem(
                        selected = index == selectedIndex,
                        onClick = { select(index) },
                        modifier = named(label),
                        leadingContent = {
                            RadioButton(
                                selected = index == selectedIndex,
                                onClick = null
                            )
                        },
                        content = { Text(label) },
                    )
                }
            }
        } else {
            ButtonGroup(
                modifier = Modifier.fillMaxWidth(),
                expandedRatio = 0.15f,
                horizontalArrangement = Arrangement.spacedBy(gap),
                overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) },
            ) {
                labels.forEachIndexed { index, label ->
                    customItem(buttonGroupContent = {
                        val base = Modifier
                            .weight(1f)
                            .semantics { role = Role.RadioButton }
                            .then(named(label))
                        ToggleButton(
                            checked = index == selectedIndex,
                            onCheckedChange = { select(index) },
                            modifier = base.animateWidth(
                                sources[index],
                                compressionLimit = minOf(
                                    padding.calculateEndPadding(direction),
                                    (itemWidth - widths[index]).coerceAtLeast(0.dp),
                                ),
                            ),
                            interactionSource = sources[index],
                            contentPadding = padding,
                            shapes = when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                labels.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                        ) { Text(label, maxLines = 1, softWrap = false) }
                    }, menuContent = { menu ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { select(index); menu.dismiss() })
                    })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun NavigateUpButton(onUp: () -> Unit) {
    IconButton(onClick = onUp, shapes = IconButtonDefaults.shapes()) {
        Icon(painterResource(R.drawable.ms_arrow_back_24), "Navigate up")
    }
}

@Composable
internal fun ButtonLabel(@DrawableRes icon: Int, label: String) {
    Icon(
        painterResource(icon),
        null,
        Modifier.size(ButtonDefaults.iconSizeFor(ButtonDefaults.MinHeight))
    )
    Spacer(Modifier.size(ButtonDefaults.iconSpacingFor(ButtonDefaults.MinHeight)))
    Text(label)
}
