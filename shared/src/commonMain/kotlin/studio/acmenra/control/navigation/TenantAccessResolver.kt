package studio.acmenra.control.navigation

import studio.acmenra.control.core.access.domain.model.GlobalPermission
import studio.acmenra.control.core.access.domain.model.GlobalUserContext

object TenantAccessResolver {
    fun isVendorAdmin(userContext: GlobalUserContext): Boolean =
        userContext.globalPermissions.contains(GlobalPermission.COMPANIES_MANAGE)

    fun canBrowseAll(userContext: GlobalUserContext): Boolean = isVendorAdmin(userContext)

    fun hasAccess(
        userContext: GlobalUserContext,
        companyId: String?,
    ): Boolean {
        if (companyId.isNullOrBlank()) return false
        return isVendorAdmin(userContext) ||
            userContext.memberCompanies.any { it.id == companyId }
    }

    fun resolveFallbackCompanyId(
        userContext: GlobalUserContext,
        previousAuthorizedCompanyId: String? = null,
        excludedCompanyIds: Set<String> = emptySet(),
    ): String? {
        val isPreviousValid =
            previousAuthorizedCompanyId != null &&
                previousAuthorizedCompanyId !in excludedCompanyIds &&
                (
                    isVendorAdmin(userContext) ||
                        userContext.memberCompanies.any { it.id == previousAuthorizedCompanyId }
                )
        if (isPreviousValid) {
            return previousAuthorizedCompanyId
        }

        val defaultAccessible =
            userContext.defaultCompanyId?.takeIf { defId ->
                defId !in excludedCompanyIds &&
                    (isVendorAdmin(userContext) || userContext.memberCompanies.any { it.id == defId })
            }

        return defaultAccessible
            ?: userContext.memberCompanies.firstOrNull { it.id !in excludedCompanyIds }?.id
    }
}
