package studio.acmenra.control.core.access.domain.usecase.impl

import studio.acmenra.control.core.access.domain.TenantContextCoordinator
import studio.acmenra.control.core.access.domain.usecase.EnsureTenantContextUseCase

class EnsureTenantContextUseCaseImpl(
    private val coordinator: TenantContextCoordinator,
) : EnsureTenantContextUseCase {
    override suspend fun invoke(companyId: String) {
        coordinator.ensureTenantContext(companyId)
    }
}
