package studio.acmenra.control.feature.events.presentation.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.compose.resources.painterResource
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.ic_calendar
import studio.acmenra.control.core.ui.generated.resources.ic_close
import studio.acmenra.control.core.ui.generated.resources.ic_expand_more
import studio.acmenra.control.core.ui.generated.resources.ic_refresh
import studio.acmenra.control.core.ui.generated.resources.ic_search
import studio.acmenra.control.core.ui.theme.AmberCorners
import studio.acmenra.control.core.ui.theme.AmberDimens

/**
 * Controlled Material 3 ID field.
 *
 * Keep debounce outside this composable (normally in the ViewModel). The field only emits raw
 * edits and explicit commits. `onClear` must synchronously cancel/flush any pending debounce and
 * commit an empty query, so an older delayed value cannot re-apply after clearing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EventIdOutlinedField(
    value: String,
    label: String,
    placeholder: String,
    clearTooltip: String,
    onValueChange: (String) -> Unit,
    onCommit: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusManager = LocalFocusManager.current
    val leadingTint by animateColorAsState(
        targetValue = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 180),
        label = "events-id-leading-icon-tint",
    )

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().height(AmberDimens.EventsFilterFieldHeight),
        interactionSource = interactionSource,
        textStyle = MaterialTheme.typography.bodyMedium,
        label = { Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        placeholder = { Text(text = placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            Icon(
                painter = painterResource(Res.drawable.ic_search),
                contentDescription = null,
                tint = leadingTint,
            )
        },
        trailingIcon = {
            // Keep a constant 48dp action slot. AnimatedContent changes only its contents, not
            // the field's measure result, so the text/cursor and neighbouring fields do not jump.
            Box(
                modifier = Modifier.size(AmberDimens.EventsFieldActionSlot),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = value.isNotEmpty(),
                    transitionSpec = {
                        (fadeIn(tween(140)) + scaleIn(initialScale = 0.92f, animationSpec = tween(140))) togetherWith
                            (fadeOut(tween(100)) + scaleOut(targetScale = 0.92f, animationSpec = tween(100)))
                    },
                    label = "events-id-clear-action",
                ) { visible ->
                    if (visible) {
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text(clearTooltip) } },
                            state = rememberTooltipState(),
                        ) {
                            IconButton(
                                onClick = {
                                    onValueChange("")
                                    onClear()
                                    focusManager.clearFocus(force = true)
                                },
                                modifier = Modifier.size(AmberDimens.EventsFieldActionSlot),
                            ) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_close),
                                    contentDescription = clearTooltip,
                                )
                            }
                        }
                    } else {
                        Spacer(Modifier.fillMaxSize())
                    }
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
        keyboardActions =
            KeyboardActions(
                onDone = {
                    onCommit(value.trim())
                    focusManager.clearFocus()
                },
            ),
        shape = RoundedCornerShape(AmberCorners.Medium),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedLabelColor = MaterialTheme.colorScheme.primary,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
    )
}

/**
 * Read-only date trigger using the actual M3 text-field decoration. The floating label, border
 * thickness and focus colours are animated by OutlinedTextField itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EventDateOutlinedField(
    value: String?,
    label: String,
    allDatesPlaceholder: String,
    resetTooltip: String,
    enabled: Boolean,
    onOpen: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val leadingTint by animateColorAsState(
        targetValue =
            when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                isFocused -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        animationSpec = tween(durationMillis = 180),
        label = "events-date-leading-icon-tint",
    )

    Box(modifier = modifier) {
        OutlinedTextField(
            value = value.orEmpty(),
            onValueChange = {},
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(AmberDimens.EventsFilterFieldHeight)
                    .focusRequester(focusRequester),
            interactionSource = interactionSource,
            enabled = enabled,
            readOnly = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            label = { Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            placeholder = {
                Text(text = allDatesPlaceholder, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(Res.drawable.ic_calendar),
                    contentDescription = null,
                    tint = leadingTint,
                )
            },
            trailingIcon = {
                // A fixed action slot avoids changing the text's available width when reset appears.
                Box(
                    modifier = Modifier.size(AmberDimens.EventsFieldActionSlot),
                    contentAlignment = Alignment.Center,
                ) {
                    AnimatedContent(
                        targetState = enabled && !value.isNullOrBlank(),
                        transitionSpec = {
                            (
                                fadeIn(
                                    tween(140),
                                ) + scaleIn(initialScale = 0.92f, animationSpec = tween(140))
                            ) togetherWith
                                (fadeOut(tween(100)) + scaleOut(targetScale = 0.92f, animationSpec = tween(100)))
                        },
                        label = "events-date-reset-action",
                    ) { visible ->
                        if (visible) {
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                tooltip = { PlainTooltip { Text(resetTooltip) } },
                                state = rememberTooltipState(),
                            ) {
                                IconButton(
                                    onClick = {
                                        onReset()
                                        focusManager.clearFocus(force = true)
                                    },
                                    modifier = Modifier.size(AmberDimens.EventsFieldActionSlot),
                                ) {
                                    Icon(
                                        painter = painterResource(Res.drawable.ic_refresh),
                                        contentDescription = resetTooltip,
                                    )
                                }
                            }
                        } else {
                            Spacer(Modifier.fillMaxSize())
                        }
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(AmberCorners.Medium),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.38f),
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                ),
        )

        // Overlay catching pointer taps across the entire field, excluding the trailing action slot
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .padding(end = AmberDimens.EventsFieldActionSlot)
                    .clickable(
                        enabled = enabled,
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = {
                            focusRequester.requestFocus()
                            onOpen()
                        },
                    ),
        )
    }
}

/**
 * Reusable M3 exposed multi-select field.
 *
 * M3 owns label placement, label/border colour and focus transitions. We animate only the
 * additional iconography. The draft is local until Apply; dismiss/Escape discards it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnimatedExposedFilterField(
    label: String,
    /** Empty when no IDs are selected; otherwise the compact selected-items summary. */
    summaryText: String,
    emptyPlaceholder: String,
    searchPlaceholder: String,
    applyLabel: String,
    clearSearchTooltip: String,
    resetTooltip: String,
    appliedIds: Set<String>,
    enabled: Boolean,
    isLoading: Boolean,
    loadingDescription: String,
    onApply: (Set<String>) -> Unit,
    onResetApplied: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable (Color) -> Unit)? = null,
    dropdownContent: @Composable (
        searchQuery: String,
        draftIds: Set<String>,
        onDraftIdsChange: (Set<String>) -> Unit,
    ) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var draftIds by remember(appliedIds) { mutableStateOf(appliedIds.toSet()) }
    var searchQuery by remember { mutableStateOf("") }

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val leadingTint by animateColorAsState(
        targetValue =
            when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                isFocused || expanded -> MaterialTheme.colorScheme.primary
                appliedIds.isNotEmpty() -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        animationSpec = tween(durationMillis = 180),
        label = "events-filter-leading-icon-tint",
    )
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "events-filter-arrow-rotation",
    )
    val arrowTint by animateColorAsState(
        targetValue = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 180),
        label = "events-filter-arrow-tint",
    )

    fun dismissAndDiscard() {
        expanded = false
        searchQuery = ""
        draftIds = appliedIds.toSet()
        focusManager.clearFocus(force = true)
    }

    LaunchedEffect(expanded) {
        if (expanded) {
            focusRequester.requestFocus()
        }
    }

    // Back/Forward or an external reset while the menu is open invalidates its draft. Close it
    // instead of silently showing a stale selection that could overwrite the route on Apply.
    LaunchedEffect(appliedIds) {
        draftIds = appliedIds.toSet()
        if (expanded) {
            expanded = false
            searchQuery = ""
            focusManager.clearFocus(force = true)
        }
    }

    LaunchedEffect(enabled) {
        if (!enabled && expanded) dismissAndDiscard()
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { shouldExpand ->
            if (!enabled) return@ExposedDropdownMenuBox
            if (shouldExpand) {
                if (!expanded) {
                    draftIds = appliedIds.toSet()
                    searchQuery = ""
                    expanded = true
                }
            }
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = if (expanded) searchQuery else summaryText,
            onValueChange = { newValue ->
                if (expanded) searchQuery = newValue
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(AmberDimens.EventsFilterFieldHeight)
                    .focusRequester(focusRequester)
                    .menuAnchor(
                        type =
                            if (expanded) {
                                ExposedDropdownMenuAnchorType.PrimaryEditable
                            } else {
                                ExposedDropdownMenuAnchorType.PrimaryNotEditable
                            },
                        enabled = enabled,
                    ),
            interactionSource = interactionSource,
            enabled = enabled,
            readOnly = !expanded,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            label = { Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            placeholder = {
                Text(
                    text = if (expanded) searchPlaceholder else emptyPlaceholder,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingIcon =
                leadingIcon?.let { content ->
                    { content(leadingTint) }
                },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { /* Search is applied locally as typed. */ }),
            trailingIcon = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    val trailingAction =
                        when {
                            expanded && searchQuery.isNotEmpty() -> FilterFieldTrailingAction.ClearSearch
                            expanded && draftIds.isNotEmpty() -> FilterFieldTrailingAction.ResetDraft
                            !expanded && appliedIds.isNotEmpty() -> FilterFieldTrailingAction.ResetApplied
                            else -> FilterFieldTrailingAction.None
                        }

                    Box(
                        modifier = Modifier.size(AmberDimens.EventsFieldActionSlot),
                        contentAlignment = Alignment.Center,
                    ) {
                        AnimatedContent(
                            targetState = trailingAction,
                            transitionSpec = {
                                val enter =
                                    fadeIn(tween(140)) +
                                        scaleIn(initialScale = 0.92f, animationSpec = tween(140))
                                val exit =
                                    fadeOut(tween(100)) +
                                        scaleOut(targetScale = 0.92f, animationSpec = tween(100))
                                enter togetherWith exit
                            },
                            label = "events-filter-trailing-action",
                        ) { action ->
                            when (action) {
                                FilterFieldTrailingAction.None -> Spacer(Modifier.fillMaxSize())

                                FilterFieldTrailingAction.ClearSearch ->
                                    FilterActionButton(
                                        tooltip = clearSearchTooltip,
                                        icon = Res.drawable.ic_close,
                                        onClick = {
                                            searchQuery = ""
                                            focusRequester.requestFocus()
                                        },
                                    )

                                FilterFieldTrailingAction.ResetDraft ->
                                    FilterActionButton(
                                        tooltip = resetTooltip,
                                        icon = Res.drawable.ic_refresh,
                                        onClick = { draftIds = emptySet() },
                                    )

                                FilterFieldTrailingAction.ResetApplied ->
                                    FilterActionButton(
                                        tooltip = resetTooltip,
                                        icon = Res.drawable.ic_refresh,
                                        onClick = {
                                            onResetApplied()
                                            focusManager.clearFocus(force = true)
                                        },
                                    )
                            }
                        }
                    }

                    Icon(
                        painter = painterResource(Res.drawable.ic_expand_more),
                        contentDescription = null,
                        modifier =
                            Modifier
                                .size(AmberDimens.ListIcon)
                                .rotate(arrowRotation)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    enabled = enabled,
                                ) {
                                    if (expanded) {
                                        dismissAndDiscard()
                                    } else {
                                        draftIds = appliedIds.toSet()
                                        searchQuery = ""
                                        expanded = true
                                    }
                                },
                        tint = arrowTint,
                    )
                }
            },
            shape = RoundedCornerShape(AmberCorners.Medium),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = ::dismissAndDiscard,
        ) {
            Column(
                modifier =
                    Modifier
                        .heightIn(max = AmberDimens.EventsDropdownMaxHeight)
                        .padding(AmberDimens.GapSmall),
            ) {
                Column(
                    modifier =
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                ) {
                    if (isLoading) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(AmberDimens.GapMedium),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier =
                                    Modifier
                                        .size(AmberDimens.ListIconCircle)
                                        .semantics { contentDescription = loadingDescription },
                            )
                        }
                    } else {
                        dropdownContent(
                            searchQuery,
                            draftIds,
                        ) { updatedIds: Set<String> ->
                            draftIds = updatedIds.toSet()
                            focusRequester.requestFocus()
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = AmberDimens.GapSmall),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = {
                            val result = draftIds.toSet()
                            expanded = false
                            searchQuery = ""
                            focusManager.clearFocus(force = true)
                            if (result != appliedIds) onApply(result)
                        },
                        enabled = enabled && !isLoading,
                        modifier = Modifier.height(AmberDimens.EventsCompactButtonHeight),
                    ) {
                        Text(text = applyLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterActionButton(
    tooltip: String,
    icon: org.jetbrains.compose.resources.DrawableResource,
    onClick: () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(AmberDimens.EventsFieldActionSlot),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = tooltip,
            )
        }
    }
}

private enum class FilterFieldTrailingAction {
    None,
    ClearSearch,
    ResetDraft,
    ResetApplied,
}
