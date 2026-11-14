package studio.acmenra.control.core.access.data.datasource

import studio.acmenra.control.core.access.domain.model.CompanyAccessDomain
import kotlin.time.TimeMark

data class TenantCacheKey(
    val userId: String,
    val companyId: String,
)

data class CachedCompanyAccess(
    val domain: CompanyAccessDomain,
    val etag: String?,
    val cachedAtMark: TimeMark,
)
