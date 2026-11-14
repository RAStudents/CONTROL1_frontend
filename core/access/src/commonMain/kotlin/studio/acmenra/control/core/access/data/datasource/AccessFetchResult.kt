package studio.acmenra.control.core.access.data.datasource

import studio.acmenra.control.core.access.data.model.CompanyAccessResponseDto

sealed interface AccessFetchResult {
    data class Modified(
        val dto: CompanyAccessResponseDto,
        val etag: String?,
    ) : AccessFetchResult

    data object NotModified : AccessFetchResult
}
