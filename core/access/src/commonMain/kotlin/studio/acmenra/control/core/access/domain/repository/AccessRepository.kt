package studio.acmenra.control.core.access.domain.repository

import studio.acmenra.control.core.access.domain.model.CompanyAccessDomain
import studio.acmenra.control.core.access.domain.model.GlobalUserContext
import studio.acmenra.control.core.network.model.NetworkResult

interface AccessRepository {
    suspend fun getGlobalContext(): NetworkResult<GlobalUserContext>

    suspend fun getCompanyAccess(
        userId: String,
        companyId: String,
        forceRefresh: Boolean = false,
    ): NetworkResult<CompanyAccessDomain>

    suspend fun getCachedCompanyAccess(
        userId: String,
        companyId: String,
    ): CompanyAccessDomain?

    suspend fun clearCache()
}
