package studio.acmenra.control.core.access.domain.model

import studio.acmenra.control.core.network.model.NetworkError

sealed interface GlobalAccessState {
    data object Idle : GlobalAccessState

    data object InitialLoading : GlobalAccessState

    data class Ready(
        val context: GlobalUserContext,
        val isRefreshing: Boolean = false,
    ) : GlobalAccessState

    sealed interface Restricted : GlobalAccessState {
        data class AccountFrozen(
            val restriction: AccessRestriction?,
        ) : Restricted

        data object NoCompaniesAvailable : Restricted
    }

    data class Error(
        val error: NetworkError,
    ) : GlobalAccessState
}

sealed interface TenantContextState {
    data object NotSelected : TenantContextState

    data class Loading(
        val requestedCompanyId: String,
    ) : TenantContextState

    data class Authorized(
        val context: AuthorizedTenantContext,
        val isRefreshing: Boolean = false,
    ) : TenantContextState

    sealed interface Restricted : TenantContextState {
        val companyId: String
        val companyName: String?
        val canSwitchCompany: Boolean

        data class CompanyFrozen(
            override val companyId: String,
            override val companyName: String?,
            val restriction: AccessRestriction?,
            override val canSwitchCompany: Boolean,
        ) : Restricted

        data class NoPermissions(
            override val companyId: String,
            override val companyName: String?,
            override val canSwitchCompany: Boolean,
        ) : Restricted

        data class AccessDenied(
            override val companyId: String,
            override val companyName: String? = null,
            override val canSwitchCompany: Boolean,
        ) : Restricted
    }

    data class Error(
        val companyId: String,
        val error: NetworkError,
        val canSwitchCompany: Boolean,
    ) : TenantContextState
}

sealed interface RoutePermissionRequirement {
    data class Tenant(
        val permission: TenantPermission,
    ) : RoutePermissionRequirement

    data class Support(
        val permission: SupportPermission,
    ) : RoutePermissionRequirement

    data class AllOf(
        val tenant: Set<TenantPermission> = emptySet(),
        val support: Set<SupportPermission> = emptySet(),
    ) : RoutePermissionRequirement {
        init {
            require(tenant.isNotEmpty() || support.isNotEmpty()) { "AllOf requires at least one permission." }
        }
    }

    data class AnyOf(
        val tenant: Set<TenantPermission> = emptySet(),
        val support: Set<SupportPermission> = emptySet(),
    ) : RoutePermissionRequirement {
        init {
            require(tenant.isNotEmpty() || support.isNotEmpty()) { "AnyOf requires at least one permission." }
        }
    }

    fun isSatisfiedBy(context: AuthorizedTenantContext): Boolean {
        if (context.requiresImmediateRevalidation) return false
        val tenant = context.effectiveTenantPermissions
        val support = context.effectiveSupportPermissions
        return when (this) {
            is Tenant -> permission in tenant
            is Support -> permission in support
            is AllOf -> tenant.containsAll(this.tenant) && support.containsAll(this.support)
            is AnyOf -> this.tenant.any { it in tenant } || this.support.any { it in support }
        }
    }
}
