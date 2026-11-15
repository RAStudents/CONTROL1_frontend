package studio.acmenra.control.core.access.domain.parser

import studio.acmenra.control.core.access.domain.model.AccessDomainError
import studio.acmenra.control.core.network.model.NetworkError

interface AccessErrorParser {
    fun parse(error: NetworkError): AccessDomainError
}
