package studio.acmenra.control.navigation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import studio.acmenra.control.core.access.domain.model.CompanyBrief
import studio.acmenra.control.core.access.domain.model.CompanyStatus
import studio.acmenra.control.core.access.domain.model.GlobalAccessState
import studio.acmenra.control.core.access.domain.usecase.ObserveGlobalAccessUseCase
import studio.acmenra.control.core.ui.components.ProvideTopBar
import studio.acmenra.control.core.ui.generated.resources.Res
import studio.acmenra.control.core.ui.generated.resources.company_search_no_results
import studio.acmenra.control.core.ui.generated.resources.vendor_dashboard_badge
import studio.acmenra.control.core.ui.generated.resources.vendor_dashboard_companies_header
import studio.acmenra.control.core.ui.generated.resources.vendor_dashboard_empty
import studio.acmenra.control.core.ui.generated.resources.vendor_dashboard_search_placeholder
import studio.acmenra.control.core.ui.generated.resources.vendor_dashboard_subtitle
import studio.acmenra.control.core.ui.generated.resources.vendor_dashboard_title
import studio.acmenra.control.core.ui.theme.AmberCorners
import studio.acmenra.control.core.ui.theme.AmberDimens
import studio.acmenra.control.navigation.components.CompanyStatusBadge

@Composable
fun CompaniesDashboardScreen(
    onSelectCompany: (companyId: String) -> Unit,
    modifier: Modifier = Modifier,
    observeGlobalAccessUseCase: ObserveGlobalAccessUseCase = koinInject(),
) {
    val globalState by observeGlobalAccessUseCase().collectAsState()
    val companies = (globalState as? GlobalAccessState.Ready)?.context?.memberCompanies.orEmpty()

    var searchQuery by remember { mutableStateOf("") }
    val filteredCompanies =
        remember(companies, searchQuery) {
            if (searchQuery.isBlank()) {
                companies
            } else {
                companies.filter { it.name.contains(searchQuery, ignoreCase = true) }
            }
        }

    ProvideTopBar(
        title = stringResource(Res.string.vendor_dashboard_title),
    )

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(AmberDimens.ScreenMargin),
    ) {
        // Vendor Header Banner
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(AmberCorners.Large),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.padding(AmberDimens.ScreenMargin),
            ) {
                Surface(
                    shape = RoundedCornerShape(AmberCorners.Small),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Text(
                        text = stringResource(Res.string.vendor_dashboard_badge),
                        style = MaterialTheme.typography.labelMedium,
                        modifier =
                            Modifier.padding(
                                horizontal = AmberDimens.StatusBadgePaddingHorizontal,
                                vertical = AmberDimens.StatusBadgePaddingVertical,
                            ),
                    )
                }

                Spacer(modifier = Modifier.height(AmberDimens.GapSmall))

                Text(
                    text = stringResource(Res.string.vendor_dashboard_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(AmberDimens.GapSmall))

                Text(
                    text = stringResource(Res.string.vendor_dashboard_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(AmberDimens.GapMedium))

        // Search Field
        if (companies.isNotEmpty()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(text = stringResource(Res.string.vendor_dashboard_search_placeholder))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(AmberCorners.Medium),
            )

            Spacer(modifier = Modifier.height(AmberDimens.GapMedium))
        }

        // Section header
        Text(
            text = stringResource(Res.string.vendor_dashboard_companies_header),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(AmberDimens.GapSmall))

        if (companies.isEmpty()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.vendor_dashboard_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (filteredCompanies.isEmpty()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.company_search_no_results),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(AmberDimens.GapSmall),
            ) {
                items(
                    items = filteredCompanies,
                    key = { it.id },
                ) { company ->
                    VendorCompanyCard(
                        company = company,
                        onClick = { onSelectCompany(company.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VendorCompanyCard(
    company: CompanyBrief,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AmberCorners.Medium)),
        shape = RoundedCornerShape(AmberCorners.Medium),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
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
                text = company.name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f, fill = false),
            )

            if (company.status == CompanyStatus.RESTRICTED) {
                CompanyStatusBadge(status = company.status)
            }
        }
    }
}
