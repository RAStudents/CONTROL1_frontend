package studio.acmenra.control.core.access.domain.model

sealed interface AccessDomainError {
    data class AccountFrozen(
        val restriction: AccessRestriction?,
    ) : AccessDomainError

    data class CompanyRestricted(
        val companyId: String?,
        val restriction: AccessRestriction?,
    ) : AccessDomainError

    data class AccessDenied(
        val companyId: String?,
    ) : AccessDomainError

    data object Unknown : AccessDomainError
}
