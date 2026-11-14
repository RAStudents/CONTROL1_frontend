package studio.acmenra.control.core.access.domain.usecase.impl

import kotlinx.coroutines.flow.StateFlow
import studio.acmenra.control.core.access.domain.TenantContextCoordinator
import studio.acmenra.control.core.access.domain.model.TenantContextState
import studio.acmenra.control.core.access.domain.usecase.ObserveTenantContextUseCase

class ObserveTenantContextUseCaseImpl(
    private val coordinator: TenantContextCoordinator,
) : ObserveTenantContextUseCase {
    override fun invoke(): StateFlow<TenantContextState> = coordinator.tenantState
}
