package studio.acmenra.control.core.access.data.parser

import studio.acmenra.control.core.access.data.mapper.AccessMapper
import studio.acmenra.control.core.access.data.model.AccessErrorResponseDto
import studio.acmenra.control.core.access.domain.model.AccessDomainError
import studio.acmenra.control.core.access.domain.parser.AccessErrorParser
import studio.acmenra.control.core.network.model.NetworkError

class JsonAccessErrorParser(
    private val mapper: AccessMapper = AccessMapper(),
) : AccessErrorParser {
    override fun parse(error: NetworkError): AccessDomainError {
        if (error !is NetworkError.Http || error.statusCode != 403) {
            return AccessDomainError.Unknown
        }
        val dto = AccessErrorResponseDto.parse(error.errorBody) ?: return AccessDomainError.Unknown
        return when (dto.resolvedCode) {
            "ACCOUNT_FROZEN" ->
                AccessDomainError.AccountFrozen(
                    restriction = dto.restriction?.let(mapper::mapRestriction),
                )

            "COMPANY_RESTRICTED" ->
                AccessDomainError.CompanyRestricted(
                    companyId = dto.companyId,
                    restriction = dto.restriction?.let(mapper::mapRestriction),
                )

            "ACCESS_DENIED", "COMPANY_ACCESS_DENIED" ->
                AccessDomainError.AccessDenied(
                    companyId = dto.companyId,
                )

            else -> AccessDomainError.Unknown
        }
    }
}
