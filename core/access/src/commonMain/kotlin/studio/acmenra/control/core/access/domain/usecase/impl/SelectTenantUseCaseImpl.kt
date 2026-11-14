package studio.acmenra.control.core.access.domain.usecase.impl

import studio.acmenra.control.core.access.domain.TenantContextCoordinator
import studio.acmenra.control.core.access.domain.usecase.SelectTenantUseCase

class SelectTenantUseCaseImpl(
    private val coordinator: TenantContextCoordinator,
) : SelectTenantUseCase {
    override suspend fun invoke(companyId: String) {
        coordinator.selectTenant(companyId)
    }
}
