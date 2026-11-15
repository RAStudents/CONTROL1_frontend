package studio.acmenra.control.core.access.domain.usecase

import kotlinx.coroutines.flow.StateFlow
import studio.acmenra.control.core.access.domain.model.TenantContextState

interface ObserveTenantContextUseCase {
    operator fun invoke(): StateFlow<TenantContextState>
}
