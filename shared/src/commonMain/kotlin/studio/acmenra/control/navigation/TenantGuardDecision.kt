package studio.acmenra.control.navigation

import studio.acmenra.control.core.access.domain.model.AccessRestriction
import studio.acmenra.control.core.access.domain.model.AuthorizedTenantContext
import studio.acmenra.control.core.access.domain.model.GlobalAccessState
import studio.acmenra.control.core.access.domain.model.RoutePermissionRequirement
import studio.acmenra.control.core.access.domain.model.TenantContextState
import studio.acmenra.control.core.network.model.NetworkError

sealed interface TenantGuardDecision {
    data object ShowLoading : TenantGuardDecision

    data class AccountFrozen(
        val restriction: AccessRestriction?,
    ) : TenantGuardDecision

    data object NoCompaniesAvailable : TenantGuardDecision

    data class GlobalError(
        val error: NetworkError,
    ) : TenantGuardDecision

    data class RedirectToCompany(
        val targetCompanyId: String,
    ) : TenantGuardDecision

    data object RedirectToVendorDashboard : TenantGuardDecision

    data object AccessDenied : TenantGuardDecision

    data class TenantError(
        val error: NetworkError,
        val companyId: String,
    ) : TenantGuardDecision

    data class CompanyFrozen(
        val companyId: String,
        val companyName: String?,
        val restriction: AccessRestriction?,
    ) : TenantGuardDecision

    data class NoPermissions(
        val companyId: String,
        val companyName: String?,
    ) : TenantGuardDecision

    data class TenantAccessDenied(
        val companyId: String,
        val companyName: String?,
    ) : TenantGuardDecision

    data class RenderContent(
        val context: AuthorizedTenantContext,
    ) : TenantGuardDecision
}

fun resolveTenantGuardDecision(
    globalState: GlobalAccessState,
    tenantState: TenantContextState,
    requestedCompanyId: String?,
    requirement: RoutePermissionRequirement? = null,
): TenantGuardDecision {
    when (globalState) {
        is GlobalAccessState.Idle, is GlobalAccessState.InitialLoading -> {
            return TenantGuardDecision.ShowLoading
        }

        is GlobalAccessState.Restricted.AccountFrozen -> {
            return TenantGuardDecision.AccountFrozen(globalState.restriction)
        }

        is GlobalAccessState.Restricted.NoCompaniesAvailable -> {
            return TenantGuardDecision.NoCompaniesAvailable
        }

        is GlobalAccessState.Error -> {
            return TenantGuardDecision.GlobalError(globalState.error)
        }

        is GlobalAccessState.Ready -> {
            // Evaluated below
        }
    }

    val userContext = globalState.context

    if (requestedCompanyId.isNullOrBlank()) {
        val resolvedId = TenantAccessResolver.resolveFallbackCompanyId(userContext)
        return when {
            resolvedId != null -> TenantGuardDecision.RedirectToCompany(resolvedId)
            TenantAccessResolver.canBrowseAll(userContext) -> TenantGuardDecision.RedirectToVendorDashboard
            else -> TenantGuardDecision.AccessDenied
        }
    }

    val hasAccess = TenantAccessResolver.hasAccess(userContext, requestedCompanyId)
    if (!hasAccess) {
        val previousAuthorizedCompanyId = (tenantState as? TenantContextState.Authorized)?.context?.companyId
        val fallbackCompanyId =
            TenantAccessResolver.resolveFallbackCompanyId(
                userContext = userContext,
                previousAuthorizedCompanyId = previousAuthorizedCompanyId,
                excludedCompanyIds = setOf(requestedCompanyId),
            )
        return when {
            fallbackCompanyId != null && fallbackCompanyId != requestedCompanyId ->
                TenantGuardDecision.RedirectToCompany(fallbackCompanyId)
            TenantAccessResolver.canBrowseAll(userContext) ->
                TenantGuardDecision.RedirectToVendorDashboard
            else ->
                TenantGuardDecision.AccessDenied
        }
    }

    return when (tenantState) {
        is TenantContextState.NotSelected, is TenantContextState.Loading -> {
            TenantGuardDecision.ShowLoading
        }

        is TenantContextState.Error -> {
            if (tenantState.companyId != requestedCompanyId) {
                TenantGuardDecision.ShowLoading
            } else {
                TenantGuardDecision.TenantError(tenantState.error, tenantState.companyId)
            }
        }

        is TenantContextState.Restricted.CompanyFrozen -> {
            if (tenantState.companyId != requestedCompanyId) {
                TenantGuardDecision.ShowLoading
            } else {
                TenantGuardDecision.CompanyFrozen(
                    companyId = tenantState.companyId,
                    companyName = tenantState.companyName,
                    restriction = tenantState.restriction,
                )
            }
        }

        is TenantContextState.Restricted.NoPermissions -> {
            if (tenantState.companyId != requestedCompanyId) {
                TenantGuardDecision.ShowLoading
            } else {
                TenantGuardDecision.NoPermissions(
                    companyId = tenantState.companyId,
                    companyName = tenantState.companyName,
                )
            }
        }

        is TenantContextState.Restricted.AccessDenied -> {
            if (tenantState.companyId != requestedCompanyId) {
                TenantGuardDecision.ShowLoading
            } else {
                TenantGuardDecision.TenantAccessDenied(
                    companyId = tenantState.companyId,
                    companyName = tenantState.companyName,
                )
            }
        }

        is TenantContextState.Authorized -> {
            if (tenantState.context.companyId != requestedCompanyId) {
                TenantGuardDecision.ShowLoading
            } else if (requirement != null && !requirement.isSatisfiedBy(tenantState.context)) {
                TenantGuardDecision.NoPermissions(
                    companyId = tenantState.context.companyId,
                    companyName = tenantState.context.companyName,
                )
            } else {
                TenantGuardDecision.RenderContent(tenantState.context)
            }
        }
    }
}
