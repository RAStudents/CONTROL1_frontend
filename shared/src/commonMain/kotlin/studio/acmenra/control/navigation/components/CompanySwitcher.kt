package studio.acmenra.control.navigation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import studio.acmenra.control.core.access.domain.model.CompanyBrief
import studio.acmenra.control.core.access.domain.model.CompanyStatus
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.company_status_active
import studio.acmenra.control.core.ui.generated.resources.company_status_restricted
import studio.acmenra.control.core.ui.generated.resources.company_status_unknown
import studio.acmenra.control.core.ui.generated.resources.company_switcher_all_vendor
import studio.acmenra.control.core.ui.generated.resources.company_switcher_dialog_title
import studio.acmenra.control.core.ui.generated.resources.company_switcher_open
import studio.acmenra.control.core.ui.generated.resources.company_switcher_select
import studio.acmenra.control.core.ui.generated.resources.ic_arrow_drop_down
import studio.acmenra.control.core.ui.generated.resources.ic_check_circle
import studio.acmenra.control.core.ui.generated.resources.ic_company
import studio.acmenra.control.core.ui.theme.AmberCorners
import studio.acmenra.control.core.ui.theme.AmberDimens

@Composable
fun DesktopCompanySelector(
    currentCompanyName: String,
    isRestricted: Boolean,
    canSwitch: Boolean,
    companies: List<CompanyBrief>,
    selectedCompanyId: String?,
    onSelectCompany: (String) -> Unit,
    modifier: Modifier = Modifier,
    canBrowseAllCompanies: Boolean = false,
    onOpenFullDialog: (() -> Unit)? = null,
    onSelectAllCompanies: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val borderColor =
        if (isRestricted) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.outline
        }

    Box(modifier = modifier) {
        Surface(
            onClick = {
                if (canSwitch) {
                    expanded = true
                }
            },
            enabled = canSwitch,
            modifier =
                Modifier
                    .width(AmberDimens.CompanySwitcherWidth)
                    .height(AmberDimens.CompanySwitcherHeight)
                    .clip(RoundedCornerShape(AmberCorners.Small)),
            shape = RoundedCornerShape(AmberCorners.Small),
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border =
                BorderStroke(
                    width = AmberDimens.OutlinedBorderWidth,
                    color = borderColor,
                ),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = AmberDimens.GapMedium),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall),
            ) {
                Text(
                    text = currentCompanyName.ifBlank { stringResource(Res.string.company_switcher_select) },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isRestricted) {
                    CompanyStatusBadge(status = CompanyStatus.RESTRICTED)
                }
                if (canSwitch) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_arrow_drop_down),
                        contentDescription = stringResource(Res.string.company_switcher_open),
                        modifier = Modifier.size(AmberDimens.ListIcon),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(AmberCorners.ExtraSmall),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            if (canBrowseAllCompanies && onSelectAllCompanies != null) {
                val isAllSelected = selectedCompanyId == null
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(Res.string.company_switcher_all_vendor),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelectAllCompanies()
                    },
                    trailingIcon =
                        if (isAllSelected) {
                            {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_check_circle),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(AmberDimens.ListIcon),
                                )
                            }
                        } else {
                            null
                        },
                )
            }

            if (onOpenFullDialog != null && (companies.size > 5 || canBrowseAllCompanies)) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(Res.string.company_switcher_dialog_title),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    onClick = {
                        expanded = false
                        onOpenFullDialog()
                    },
                )
            }

            companies.forEach { company ->
                val isSelected = company.id == selectedCompanyId
                DropdownMenuItem(
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = company.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (company.status == CompanyStatus.RESTRICTED) {
                                CompanyStatusBadge(status = company.status)
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelectCompany(company.id)
                    },
                    trailingIcon =
                        if (isSelected) {
                            {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_check_circle),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(AmberDimens.ListIcon),
                                )
                            }
                        } else {
                            null
                        },
                )
            }
        }
    }
}

@Composable
fun MobileCompanyIconButton(
    isRestricted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_company),
            contentDescription = stringResource(Res.string.company_switcher_open),
            modifier = Modifier.size(AmberDimens.ListIcon),
            tint =
                if (isRestricted) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        )
    }
}

@Composable
fun CompanyStatusBadge(
    status: CompanyStatus,
    modifier: Modifier = Modifier,
) {
    val (textRes, bgColor, textColor) =
        when (status) {
            CompanyStatus.ACTIVE ->
                Triple(
                    Res.string.company_status_active,
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )

            CompanyStatus.RESTRICTED ->
                Triple(
                    Res.string.company_status_restricted,
                    MaterialTheme.colorScheme.errorContainer,
                    MaterialTheme.colorScheme.onErrorContainer,
                )

            CompanyStatus.UNKNOWN ->
                Triple(
                    Res.string.company_status_unknown,
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }

    Box(
        modifier =
            modifier
                .background(
                    color = bgColor,
                    shape = RoundedCornerShape(AmberCorners.ExtraSmall),
                ).padding(
                    horizontal = AmberDimens.StatusBadgePaddingHorizontal,
                    vertical = AmberDimens.StatusBadgePaddingVertical,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
        )
    }
}
