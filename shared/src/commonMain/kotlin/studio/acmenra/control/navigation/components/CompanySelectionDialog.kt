package studio.acmenra.control.navigation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import studio.acmenra.control.core.access.domain.model.CompanyBrief
import studio.acmenra.control.core.access.domain.model.CompanyStatus
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.company_search_no_results
import studio.acmenra.control.core.ui.generated.resources.company_switcher_all_vendor
import studio.acmenra.control.core.ui.generated.resources.company_switcher_dialog_title
import studio.acmenra.control.core.ui.generated.resources.company_switcher_search_placeholder
import studio.acmenra.control.core.ui.generated.resources.dialog_close
import studio.acmenra.control.core.ui.generated.resources.ic_check_circle
import studio.acmenra.control.core.ui.theme.AmberCorners
import studio.acmenra.control.core.ui.theme.AmberDimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanySelectionDialog(
    companies: List<CompanyBrief>,
    onSelectCompany: (companyId: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    selectedCompanyId: String? = null,
    canBrowseAllCompanies: Boolean = false,
    onSelectAllCompanies: (() -> Unit)? = null,
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredCompanies =
        remember(searchQuery, companies) {
            if (searchQuery.isBlank()) {
                companies
            } else {
                companies.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }
        }

    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier =
            modifier
                .defaultMinSize(minWidth = AmberDimens.DialogWidth)
                .widthIn(max = AmberDimens.RestrictedCardMaxWidth),
    ) {
        Surface(
            shape = RoundedCornerShape(AmberCorners.ExtraLarge),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = AmberDimens.NoticeElevation,
        ) {
            Column(
                modifier = Modifier.padding(AmberDimens.CardPadding),
                verticalArrangement = Arrangement.spacedBy(AmberDimens.GapMedium),
            ) {
                Text(
                    text = stringResource(Res.string.company_switcher_dialog_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                if (companies.size > 3) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                text = stringResource(Res.string.company_switcher_search_placeholder),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(AmberCorners.Medium),
                    )
                }

                if (canBrowseAllCompanies && onSelectAllCompanies != null && searchQuery.isBlank()) {
                    val isAllSelected = selectedCompanyId == null
                    val vendorShape = RoundedCornerShape(AmberCorners.Small)
                    Surface(
                        onClick = {
                            onDismiss()
                            onSelectAllCompanies()
                        },
                        modifier = Modifier.fillMaxWidth().clip(vendorShape),
                        shape = vendorShape,
                        color =
                            if (isAllSelected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        contentColor =
                            if (isAllSelected) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = AmberDimens.ScreenMargin,
                                        vertical = AmberDimens.NoticeVerticalPadding,
                                    ),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(Res.string.company_switcher_all_vendor),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (isAllSelected) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_check_circle),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(AmberDimens.ListIcon),
                                )
                            }
                        }
                    }
                }

                if (filteredCompanies.isEmpty() && (!canBrowseAllCompanies || searchQuery.isNotBlank())) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = AmberDimens.GapMedium),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(Res.string.company_search_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    val total = filteredCompanies.size
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(AmberDimens.ListGap),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        itemsIndexed(filteredCompanies, key = { _, company -> company.id }) { index, company ->
                            val isSelected = company.id == selectedCompanyId
                            val shape =
                                if (total <= 1) {
                                    RoundedCornerShape(AmberCorners.ExtraLarge)
                                } else if (index == 0) {
                                    RoundedCornerShape(
                                        topStart = AmberCorners.ExtraLarge,
                                        topEnd = AmberCorners.ExtraLarge,
                                        bottomStart = AmberCorners.Small,
                                        bottomEnd = AmberCorners.Small,
                                    )
                                } else if (index == total - 1) {
                                    RoundedCornerShape(
                                        topStart = AmberCorners.Small,
                                        topEnd = AmberCorners.Small,
                                        bottomStart = AmberCorners.ExtraLarge,
                                        bottomEnd = AmberCorners.ExtraLarge,
                                    )
                                } else {
                                    RoundedCornerShape(AmberCorners.Small)
                                }

                            Surface(
                                onClick = {
                                    onDismiss()
                                    onSelectCompany(company.id)
                                },
                                modifier = Modifier.fillMaxWidth().clip(shape),
                                shape = shape,
                                color =
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                contentColor =
                                    if (isSelected) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                            ) {
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(
                                                horizontal = AmberDimens.ScreenMargin,
                                                vertical = AmberDimens.NoticeVerticalPadding,
                                            ),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall),
                                        modifier = Modifier.weight(1f, fill = false),
                                    ) {
                                        Text(
                                            text = company.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        if (company.status == CompanyStatus.RESTRICTED) {
                                            CompanyStatusBadge(status = company.status)
                                        }
                                    }

                                    if (isSelected) {
                                        Icon(
                                            painter = painterResource(Res.drawable.ic_check_circle),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(AmberDimens.ListIcon),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = stringResource(Res.string.dialog_close),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}
