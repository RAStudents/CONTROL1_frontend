package studio.acmenra.control.navigation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import studio.acmenra.control.core.access.domain.model.AccessRestriction
import studio.acmenra.control.core.access.domain.model.RestrictionType
import studio.acmenra.control.core.network.model.NetworkError
import studio.acmenra.control.core.ui.components.AmberButton
import studio.acmenra.control.core.ui.components.AmberOutlinedButton
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.action_copy
import studio.acmenra.control.core.ui.generated.resources.action_logout
import studio.acmenra.control.core.ui.generated.resources.action_retry
import studio.acmenra.control.core.ui.generated.resources.action_switch_company
import studio.acmenra.control.core.ui.generated.resources.error_network_connection
import studio.acmenra.control.core.ui.generated.resources.error_server_unavailable
import studio.acmenra.control.core.ui.generated.resources.error_unknown_occurred
import studio.acmenra.control.core.ui.generated.resources.ic_content_copy
import studio.acmenra.control.core.ui.generated.resources.ic_error
import studio.acmenra.control.core.ui.generated.resources.ic_lock
import studio.acmenra.control.core.ui.generated.resources.ic_warning
import studio.acmenra.control.core.ui.generated.resources.notice_copied_to_clipboard
import studio.acmenra.control.core.ui.generated.resources.restricted_access_denied_desc
import studio.acmenra.control.core.ui.generated.resources.restricted_access_denied_title
import studio.acmenra.control.core.ui.generated.resources.restricted_account_frozen_desc
import studio.acmenra.control.core.ui.generated.resources.restricted_account_frozen_title
import studio.acmenra.control.core.ui.generated.resources.restricted_company_frozen_title
import studio.acmenra.control.core.ui.generated.resources.restricted_no_permissions_desc
import studio.acmenra.control.core.ui.generated.resources.restricted_no_permissions_title
import studio.acmenra.control.core.ui.generated.resources.restriction_admin_blocked
import studio.acmenra.control.core.ui.generated.resources.restriction_billing_debt
import studio.acmenra.control.core.ui.generated.resources.restriction_contact_label
import studio.acmenra.control.core.ui.generated.resources.restriction_maintenance
import studio.acmenra.control.core.ui.generated.resources.restriction_reason_label
import studio.acmenra.control.core.ui.generated.resources.restriction_terms_violation
import studio.acmenra.control.core.ui.generated.resources.restriction_unknown
import studio.acmenra.control.core.ui.generated.resources.restriction_until_label
import studio.acmenra.control.core.ui.generated.resources.tenant_error_title
import studio.acmenra.control.core.ui.theme.AmberCorners
import studio.acmenra.control.core.ui.theme.AmberDimens
import studio.acmenra.control.navigation.util.formatInstantHuman

@Composable
fun CompanyFrozenScreen(
    restriction: AccessRestriction?,
    companyName: String?,
    canSwitchCompany: Boolean,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    onSwitchCompany: (() -> Unit)? = null,
    isAccountLevel: Boolean = false,
) {
    val restrictionTypeText =
        when (restriction?.type) {
            RestrictionType.BILLING_DEBT -> stringResource(Res.string.restriction_billing_debt)
            RestrictionType.MAINTENANCE -> stringResource(Res.string.restriction_maintenance)
            RestrictionType.ADMIN_BLOCKED -> stringResource(Res.string.restriction_admin_blocked)
            RestrictionType.TERMS_VIOLATION -> stringResource(Res.string.restriction_terms_violation)
            RestrictionType.UNKNOWN, null -> stringResource(Res.string.restriction_unknown)
        }

    RestrictedScreenScaffold(
        iconRes = Res.drawable.ic_lock,
        iconTint = MaterialTheme.colorScheme.error,
        title =
            if (isAccountLevel) {
                stringResource(Res.string.restricted_account_frozen_title)
            } else {
                stringResource(Res.string.restricted_company_frozen_title)
            },
        subtitle = companyName,
        description =
            if (isAccountLevel) {
                stringResource(Res.string.restricted_account_frozen_desc)
            } else {
                null
            },
        canSwitchCompany = canSwitchCompany,
        onSwitchCompany = onSwitchCompany,
        onLogout = onLogout,
        modifier = modifier,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(AmberCorners.Medium),
                    ).padding(AmberDimens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall),
        ) {
            Text(
                text = restrictionTypeText,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )

            if (!restriction?.reason.isNullOrBlank()) {
                Text(
                    text = stringResource(Res.string.restriction_reason_label, restriction.reason.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            val untilAt = restriction?.untilAt
            if (untilAt != null) {
                Text(
                    text = stringResource(Res.string.restriction_until_label, formatInstantHuman(untilAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!restriction?.supportContact.isNullOrBlank()) {
                @Suppress("DEPRECATION")
                val clipboardManager = LocalClipboardManager.current
                var isCopied by remember { mutableStateOf(false) }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text =
                                stringResource(
                                    Res.string.restriction_contact_label,
                                    restriction.supportContact.orEmpty(),
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(restriction.supportContact.orEmpty()))
                                isCopied = true
                            },
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_content_copy),
                                contentDescription = stringResource(Res.string.action_copy),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(AmberDimens.ListIcon),
                            )
                        }
                    }

                    if (isCopied) {
                        Text(
                            text = stringResource(Res.string.notice_copied_to_clipboard),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = AmberDimens.StatusBadgePaddingVertical),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AccessDeniedScreen(
    companyName: String?,
    canSwitchCompany: Boolean,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(Res.string.restricted_access_denied_title),
    description: String = stringResource(Res.string.restricted_access_denied_desc),
    onSwitchCompany: (() -> Unit)? = null,
) {
    RestrictedScreenScaffold(
        iconRes = Res.drawable.ic_error,
        iconTint = MaterialTheme.colorScheme.error,
        title = title,
        subtitle = companyName,
        description = description,
        canSwitchCompany = canSwitchCompany,
        onSwitchCompany = onSwitchCompany,
        onLogout = onLogout,
        modifier = modifier,
    )
}

@Composable
fun NoPermissionsScreen(
    companyName: String?,
    canSwitchCompany: Boolean,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    onSwitchCompany: (() -> Unit)? = null,
) {
    RestrictedScreenScaffold(
        iconRes = Res.drawable.ic_warning,
        iconTint = MaterialTheme.colorScheme.primary,
        title = stringResource(Res.string.restricted_no_permissions_title),
        subtitle = companyName,
        description = stringResource(Res.string.restricted_no_permissions_desc),
        canSwitchCompany = canSwitchCompany,
        onSwitchCompany = onSwitchCompany,
        onLogout = onLogout,
        modifier = modifier,
    )
}

@Composable
fun TenantErrorScreen(
    error: NetworkError,
    canSwitchCompany: Boolean,
    onRetry: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    onSwitchCompany: (() -> Unit)? = null,
) {
    val errorMessage =
        when (error) {
            is NetworkError.Connectivity -> stringResource(Res.string.error_network_connection)
            is NetworkError.Http -> stringResource(Res.string.error_server_unavailable)
            is NetworkError.Serialization, is NetworkError.Unknown ->
                stringResource(Res.string.error_unknown_occurred)
        }

    RestrictedScreenScaffold(
        iconRes = Res.drawable.ic_error,
        iconTint = MaterialTheme.colorScheme.error,
        title = stringResource(Res.string.tenant_error_title),
        description = errorMessage,
        canSwitchCompany = canSwitchCompany,
        onSwitchCompany = onSwitchCompany,
        onLogout = onLogout,
        onRetry = onRetry,
        modifier = modifier,
    )
}

@Composable
private fun RestrictedScreenScaffold(
    iconRes: DrawableResource,
    iconTint: Color,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    description: String? = null,
    canSwitchCompany: Boolean = false,
    onSwitchCompany: (() -> Unit)? = null,
    onLogout: () -> Unit = {},
    onRetry: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .padding(AmberDimens.ScreenMargin),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = AmberDimens.RestrictedCardMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AmberDimens.GapMedium),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(AmberDimens.RestrictedIconSize),
            )

            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )

            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            if (content != null) {
                content()
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall),
            ) {
                if (onRetry != null) {
                    AmberButton(
                        text = stringResource(Res.string.action_retry),
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                if (canSwitchCompany && onSwitchCompany != null) {
                    AmberButton(
                        text = stringResource(Res.string.action_switch_company),
                        onClick = onSwitchCompany,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                AmberOutlinedButton(
                    text = stringResource(Res.string.action_logout),
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
